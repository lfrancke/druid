/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.query.groupby.epinephelinae;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Predicates;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.apache.druid.collections.ReferenceCountingResourceHolder;
import org.apache.druid.collections.ResourceHolder;
import org.apache.druid.common.guava.GuavaUtils;
import org.apache.druid.error.DruidException;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.guava.Accumulator;
import org.apache.druid.java.util.common.guava.BaseSequence;
import org.apache.druid.java.util.common.guava.Sequence;
import org.apache.druid.java.util.common.io.Closer;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.query.AbstractPrioritizedQueryRunnerCallable;
import org.apache.druid.query.ChainedExecutionQueryRunner;
import org.apache.druid.query.DruidProcessingConfig;
import org.apache.druid.query.QueryContext;
import org.apache.druid.query.QueryInterruptedException;
import org.apache.druid.query.QueryPlus;
import org.apache.druid.query.QueryProcessingPool;
import org.apache.druid.query.QueryResourceId;
import org.apache.druid.query.QueryRunner;
import org.apache.druid.query.QueryTimeoutException;
import org.apache.druid.query.QueryWatcher;
import org.apache.druid.query.ResourceLimitExceededException;
import org.apache.druid.query.context.ResponseContext;
import org.apache.druid.query.groupby.GroupByQuery;
import org.apache.druid.query.groupby.GroupByQueryConfig;
import org.apache.druid.query.groupby.GroupByQueryResources;
import org.apache.druid.query.groupby.GroupByResourcesReservationPool;
import org.apache.druid.query.groupby.GroupByStatsProvider;
import org.apache.druid.query.groupby.ResultRow;
import org.apache.druid.query.groupby.epinephelinae.RowBasedGrouperHelper.RowBasedKey;

import java.io.Closeable;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Class that knows how to merge a collection of groupBy {@link QueryRunner} objects, called {@code queryables},
 * using a buffer provided by {@code mergeBufferPool} and a parallel executor provided by {@code exec}. Outputs a
 * fully aggregated stream of {@link ResultRow} objects. Does not apply post-aggregators.
 *
 * The input {@code queryables} are expected to come from a {@link GroupByQueryEngine}. This code primarily runs on data
 * servers like Historicals and Realtime Tasks. This can also run on Brokers, if the query is operating on local
 * data sources, like inlined data, where the broker itself acts like a data server
 *
 * This class has some resemblance to {@link GroupByRowProcessor}. See the javadoc of that class for a discussion of
 * similarities and differences.
 *
 * Used by
 * {@link org.apache.druid.query.groupby.GroupingEngine#mergeRunners(QueryProcessingPool, Iterable)}
 */
public class GroupByMergingQueryRunner implements QueryRunner<ResultRow>
{
  private static final Logger log = new Logger(GroupByMergingQueryRunner.class);
  private static final String CTX_KEY_MERGE_RUNNERS_USING_CHAINED_EXECUTION = "mergeRunnersUsingChainedExecution";

  private final GroupByQueryConfig config;
  private final DruidProcessingConfig processingConfig;
  private final Iterable<QueryRunner<ResultRow>> queryables;
  private final GroupByResourcesReservationPool groupByResourcesReservationPool;
  private final QueryProcessingPool queryProcessingPool;
  private final QueryWatcher queryWatcher;
  private final int concurrencyHint;
  private final ObjectMapper spillMapper;
  private final String processingTmpDir;
  private final int mergeBufferSize;
  private final GroupByStatsProvider groupByStatsProvider;

  public GroupByMergingQueryRunner(
      GroupByQueryConfig config,
      DruidProcessingConfig processingConfig,
      QueryProcessingPool queryProcessingPool,
      QueryWatcher queryWatcher,
      Iterable<QueryRunner<ResultRow>> queryables,
      GroupByResourcesReservationPool groupByResourcesReservationPool,
      int concurrencyHint,
      int mergeBufferSize,
      ObjectMapper spillMapper,
      String processingTmpDir,
      GroupByStatsProvider groupByStatsProvider
  )
  {
    this.config = config;
    this.processingConfig = processingConfig;
    this.queryProcessingPool = queryProcessingPool;
    this.queryWatcher = queryWatcher;
    this.queryables = Iterables.unmodifiableIterable(Iterables.filter(queryables, Predicates.notNull()));
    this.groupByResourcesReservationPool = groupByResourcesReservationPool;
    this.concurrencyHint = concurrencyHint;
    this.spillMapper = spillMapper;
    this.processingTmpDir = processingTmpDir;
    this.mergeBufferSize = mergeBufferSize;
    this.groupByStatsProvider = groupByStatsProvider;
  }

  @Override
  public Sequence<ResultRow> run(final QueryPlus<ResultRow> queryPlus, final ResponseContext responseContext)
  {
    final GroupByQuery query = (GroupByQuery) queryPlus.getQuery();
    final GroupByQueryConfig querySpecificConfig = config.withOverrides(query);

    // CTX_KEY_MERGE_RUNNERS_USING_CHAINED_EXECUTION is here because realtime servers use nested mergeRunners calls
    // (one for the entire query and one for each sink). We only want the outer call to actually do merging with a
    // merge buffer, otherwise the query will allocate too many merge buffers. This is potentially sub-optimal as it
    // will involve materializing the results for each sink before starting to feed them into the outer merge buffer.
    // I'm not sure of a better way to do this without tweaking how realtime servers do queries.
    final boolean forceChainedExecution = query.context().getBoolean(
        CTX_KEY_MERGE_RUNNERS_USING_CHAINED_EXECUTION,
        false
    );
    final QueryPlus<ResultRow> queryPlusForRunners = queryPlus
        .withQuery(
            query.withOverriddenContext(ImmutableMap.of(CTX_KEY_MERGE_RUNNERS_USING_CHAINED_EXECUTION, true))
        )
        .withoutThreadUnsafeState();

    final QueryContext queryContext = query.context();
    if (queryContext.isBySegment() || forceChainedExecution) {
      ChainedExecutionQueryRunner<ResultRow> runner = new ChainedExecutionQueryRunner<>(queryProcessingPool, queryWatcher, queryables);
      return runner.run(queryPlusForRunners, responseContext);
    }

    final boolean isSingleThreaded = querySpecificConfig.isSingleThreaded();

    final File temporaryStorageDirectory = new File(
        processingTmpDir,
        StringUtils.format("druid-groupBy-%s_%s", UUID.randomUUID(), query.getId())
    );

    GroupByStatsProvider.PerQueryStats perQueryStats =
        groupByStatsProvider.getPerQueryStatsContainer(query.context().getQueryResourceId());

    final int priority = queryContext.getPriority();

    // Figure out timeoutAt time now, so we can apply the timeout to both the mergeBufferPool.take and the actual
    // query processing together.
    final long queryTimeout = queryContext.getTimeout();
    final boolean hasTimeout = queryContext.hasTimeout();
    final long timeoutAt = System.currentTimeMillis() + queryTimeout;

    return new BaseSequence<>(
        new BaseSequence.IteratorMaker<ResultRow, CloseableGrouperIterator<RowBasedKey, ResultRow>>()
        {
          @Override
          public CloseableGrouperIterator<RowBasedKey, ResultRow> make()
          {
            final Closer resources = Closer.create();

            try {
              final LimitedTemporaryStorage temporaryStorage = new LimitedTemporaryStorage(
                  temporaryStorageDirectory,
                  querySpecificConfig.getMaxOnDiskStorage().getBytes(),
                  perQueryStats
              );

              final ReferenceCountingResourceHolder<LimitedTemporaryStorage> temporaryStorageHolder =
                  ReferenceCountingResourceHolder.fromCloseable(temporaryStorage);
              resources.register(temporaryStorageHolder);

              // If parallelCombine is enabled, we need two merge buffers for parallel aggregating and parallel combining
              final int numMergeBuffers = querySpecificConfig.getNumParallelCombineThreads() > 1 ? 2 : 1;

              final List<ReferenceCountingResourceHolder<ByteBuffer>> mergeBufferHolders =
                  getMergeBuffersHolder(query, numMergeBuffers);
              resources.registerAll(mergeBufferHolders);

              final ReferenceCountingResourceHolder<ByteBuffer> mergeBufferHolder = mergeBufferHolders.get(0);
              final ReferenceCountingResourceHolder<ByteBuffer> combineBufferHolder = numMergeBuffers == 2 ?
                                                                                      mergeBufferHolders.get(1) :
                                                                                      null;

              Pair<Grouper<RowBasedKey>, Accumulator<AggregateResult, ResultRow>> pair =
                  RowBasedGrouperHelper.createGrouperAccumulatorPair(
                      query,
                      null,
                      config,
                      processingConfig,
                      Suppliers.ofInstance(mergeBufferHolder.get()),
                      combineBufferHolder,
                      concurrencyHint,
                      temporaryStorage,
                      spillMapper,
                      queryProcessingPool, // Passed as executor service
                      priority,
                      hasTimeout,
                      timeoutAt,
                      mergeBufferSize,
                      perQueryStats
                  );
              final Grouper<RowBasedKey> grouper = pair.lhs;
              final Accumulator<AggregateResult, ResultRow> accumulator = pair.rhs;
              grouper.init();

              final ReferenceCountingResourceHolder<Grouper<RowBasedKey>> grouperHolder =
                  ReferenceCountingResourceHolder.fromCloseable(grouper);
              resources.register(grouperHolder);

              List<ListenableFuture<AggregateResult>> futures = Lists.newArrayList(
                      Iterables.transform(
                          queryables,
                          new Function<QueryRunner<ResultRow>, ListenableFuture<AggregateResult>>()
                          {
                            @Override
                            public ListenableFuture<AggregateResult> apply(final QueryRunner<ResultRow> input)
                            {
                              if (input == null) {
                                throw new ISE("Null queryRunner! Looks to be some segment unmapping action happening");
                              }

                              ListenableFuture<AggregateResult> future = queryProcessingPool.submitRunnerTask(
                                  new AbstractPrioritizedQueryRunnerCallable<AggregateResult, ResultRow>(priority, input)
                                  {
                                    @Override
                                    public AggregateResult call()
                                    {
                                      try (
                                          // These variables are used to close releasers automatically.
                                          @SuppressWarnings("unused")
                                          Closeable bufferReleaser = mergeBufferHolder.increment();
                                          @SuppressWarnings("unused")
                                          Closeable grouperReleaser = grouperHolder.increment()
                                      ) {
                                        // Return true if OK, false if resources were exhausted.
                                        return input.run(queryPlusForRunners, responseContext)
                                            .accumulate(AggregateResult.ok(), accumulator);
                                      }
                                      catch (QueryInterruptedException | QueryTimeoutException e) {
                                        throw e;
                                      }
                                      catch (Exception e) {
                                        log.error(e, "Exception with one of the sequences!");
                                        Throwables.propagateIfPossible(e);
                                        throw new RuntimeException(e);
                                      }
                                    }
                                  }
                              );

                              if (isSingleThreaded) {
                                waitForFutureCompletion(
                                    query,
                                    ImmutableList.of(future),
                                    hasTimeout,
                                    timeoutAt - System.currentTimeMillis()
                                );
                              }

                              return future;
                            }
                          }
                      )
                  );

              if (!isSingleThreaded) {
                waitForFutureCompletion(query, futures, hasTimeout, timeoutAt - System.currentTimeMillis());
              }

              return RowBasedGrouperHelper.makeGrouperIterator(
                  grouper,
                  query,
                  resources
              );
            }
            catch (Throwable t) {
              // Exception caught while setting up the iterator; release resources.
              try {
                resources.close();
              }
              catch (Exception ex) {
                t.addSuppressed(ex);
              }
              throw t;
            }
          }

          @Override
          public void cleanup(CloseableGrouperIterator<RowBasedKey, ResultRow> iterFromMake)
          {
            iterFromMake.close();
          }
        }
    );
  }

  private List<ReferenceCountingResourceHolder<ByteBuffer>> getMergeBuffersHolder(GroupByQuery query, int numBuffers)
  {
    QueryResourceId queryResourceId = query.context().getQueryResourceId();
    GroupByQueryResources resource = groupByResourcesReservationPool.fetch(queryResourceId);
    if (resource == null) {
      throw DruidException.defensive(
          "Expected merge buffers to be reserved in the reservation pool for the query resource id [%s] however while executing "
          + "the GroupByMergingQueryRunner none were provided.",
          queryResourceId
      );
    }
    if (numBuffers > resource.getNumMergingQueryRunnerMergeBuffers()) {
      // Defensive exception, because we should have acquired the correct number of merge buffers beforehand, or
      // thrown an RLE in the caller of the runner
      throw DruidException.defensive(
          "Query needs [%d] merge buffers for GroupByMergingQueryRunner, however only [%d] were provided.",
          numBuffers,
          resource.getNumMergingQueryRunnerMergeBuffers()
      );
    }
    final List<ReferenceCountingResourceHolder<ByteBuffer>> mergeBufferHolders = new ArrayList<>();
    for (int i = 0; i < numBuffers; ++i) {
      ResourceHolder<ByteBuffer> mergeBufferHolder = resource.getMergingQueryRunnerMergeBuffer();
      mergeBufferHolders.add(new ReferenceCountingResourceHolder<>(mergeBufferHolder.get(), mergeBufferHolder));
    }
    return mergeBufferHolders;
  }

  private void waitForFutureCompletion(
      GroupByQuery query,
      List<ListenableFuture<AggregateResult>> futures,
      boolean hasTimeout,
      long timeout
  )
  {
    ListenableFuture<List<AggregateResult>> future = Futures.allAsList(futures);
    try {
      if (queryWatcher != null) {
        queryWatcher.registerQueryFuture(query, future);
      }

      if (hasTimeout && timeout <= 0) {
        throw new QueryTimeoutException();
      }

      final List<AggregateResult> results = hasTimeout ? future.get(timeout, TimeUnit.MILLISECONDS) : future.get();

      for (AggregateResult result : results) {
        if (!result.isOk()) {
          GuavaUtils.cancelAll(true, future, futures);
          throw new ResourceLimitExceededException(result.getReason());
        }
      }
    }
    catch (InterruptedException e) {
      log.warn(e, "Query interrupted, cancelling pending results, query id [%s]", query.getId());
      GuavaUtils.cancelAll(true, future, futures);
      throw new QueryInterruptedException(e);
    }
    catch (CancellationException e) {
      GuavaUtils.cancelAll(true, future, futures);
      throw new QueryInterruptedException(e);
    }
    catch (QueryTimeoutException | TimeoutException e) {
      log.info("Query timeout, cancelling pending results for query id [%s]", query.getId());
      GuavaUtils.cancelAll(true, future, futures);
      throw new QueryTimeoutException();
    }
    catch (ExecutionException e) {
      GuavaUtils.cancelAll(true, future, futures);
      throw new RuntimeException(e);
    }
  }
}
