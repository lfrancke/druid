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

package org.apache.druid.query.groupby;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.apache.druid.collections.CloseableDefaultBlockingPool;
import org.apache.druid.collections.CloseableStupidPool;
import org.apache.druid.collections.ReferenceCountingResourceHolder;
import org.apache.druid.java.util.common.concurrent.Execs;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.query.DruidProcessingConfig;
import org.apache.druid.query.QueryContexts;
import org.apache.druid.query.QueryDataSource;
import org.apache.druid.query.QueryRunner;
import org.apache.druid.query.QueryRunnerTestHelper;
import org.apache.druid.query.aggregation.LongSumAggregatorFactory;
import org.apache.druid.query.dimension.DefaultDimensionSpec;
import org.apache.druid.segment.TestHelper;
import org.apache.druid.testing.InitializedNullHandlingTest;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

@RunWith(Parameterized.class)
public class GroupByQueryMergeBufferTest extends InitializedNullHandlingTest
{
  private static final long TIMEOUT = 5000;

  private static class TestBlockingPool extends CloseableDefaultBlockingPool<ByteBuffer>
  {
    private int minRemainBufferNum;

    TestBlockingPool(Supplier<ByteBuffer> generator, int limit)
    {
      super(generator, limit);
      minRemainBufferNum = limit;
    }

    @Override
    public List<ReferenceCountingResourceHolder<ByteBuffer>> takeBatch(final int maxElements, final long timeout)
    {
      final List<ReferenceCountingResourceHolder<ByteBuffer>> holder = super.takeBatch(maxElements, timeout);
      final int poolSize = getPoolSize();
      if (minRemainBufferNum > poolSize) {
        minRemainBufferNum = poolSize;
      }
      return holder;
    }

    void resetMinRemainBufferNum()
    {
      minRemainBufferNum = PROCESSING_CONFIG.getNumMergeBuffers();
    }

    int getMinRemainBufferNum()
    {
      return minRemainBufferNum;
    }
  }

  private static final DruidProcessingConfig PROCESSING_CONFIG = new DruidProcessingConfig()
  {
    @Override
    public String getFormatString()
    {
      return null;
    }

    @Override
    public int intermediateComputeSizeBytes()
    {
      return 10 * 1024 * 1024;
    }

    @Override
    public int getNumMergeBuffers()
    {
      return 4;
    }

    @Override
    public int getNumThreads()
    {
      return 1;
    }
  };

  private static GroupByQueryRunnerFactory makeQueryRunnerFactory(
      final ObjectMapper mapper,
      final GroupByQueryConfig config
  )
  {
    final Supplier<GroupByQueryConfig> configSupplier = Suppliers.ofInstance(config);
    final GroupByStatsProvider groupByStatsProvider = new GroupByStatsProvider();
    final GroupByResourcesReservationPool groupByResourcesReservationPool =
        new GroupByResourcesReservationPool(MERGE_BUFFER_POOL, config);

    final GroupingEngine groupingEngine = new GroupingEngine(
        PROCESSING_CONFIG,
        configSupplier,
        groupByResourcesReservationPool,
        TestHelper.makeJsonMapper(),
        mapper,
        QueryRunnerTestHelper.NOOP_QUERYWATCHER,
        groupByStatsProvider
    );
    final GroupByQueryQueryToolChest toolChest = new GroupByQueryQueryToolChest(
        groupingEngine,
        groupByResourcesReservationPool
    );
    return new GroupByQueryRunnerFactory(groupingEngine, toolChest, BUFFER_POOL);
  }

  private static final CloseableStupidPool<ByteBuffer> BUFFER_POOL = new CloseableStupidPool<>(
      "GroupByQueryEngine-bufferPool",
      () -> ByteBuffer.allocate(PROCESSING_CONFIG.intermediateComputeSizeBytes())
  );

  private static final TestBlockingPool MERGE_BUFFER_POOL = new TestBlockingPool(
      () -> ByteBuffer.allocate(PROCESSING_CONFIG.intermediateComputeSizeBytes()),
      PROCESSING_CONFIG.getNumMergeBuffers()
  );

  private static final GroupByQueryRunnerFactory FACTORY = makeQueryRunnerFactory(
      GroupByQueryRunnerTest.DEFAULT_MAPPER,
      new GroupByQueryConfig()
      {
      }
  );

  private final QueryRunner<ResultRow> runner;

  @AfterClass
  public static void teardownClass()
  {
    BUFFER_POOL.close();
    MERGE_BUFFER_POOL.close();
  }

  @Parameters(name = "{0}")
  public static Collection<Object[]> constructorFeeder()
  {
    final List<Object[]> args = new ArrayList<>();
    for (QueryRunner<ResultRow> runner : QueryRunnerTestHelper.makeQueryRunners(FACTORY, true)) {
      args.add(new Object[]{runner});
    }
    return args;
  }

  public GroupByQueryMergeBufferTest(QueryRunner<ResultRow> runner)
  {
    this.runner = FACTORY.mergeRunners(Execs.directExecutor(), ImmutableList.of(runner));
  }

  @Before
  public void setup()
  {
    MERGE_BUFFER_POOL.resetMinRemainBufferNum();
  }

  @Test
  public void testSimpleGroupBy()
  {
    final GroupByQuery query = GroupByQuery
        .builder()
        .setDataSource(QueryRunnerTestHelper.DATA_SOURCE)
        .setGranularity(Granularities.ALL)
        .setInterval(QueryRunnerTestHelper.FIRST_TO_THIRD)
        .setAggregatorSpecs(new LongSumAggregatorFactory("rows", "rows"))
        .setContext(ImmutableMap.of(QueryContexts.TIMEOUT_KEY, TIMEOUT))
        .build();

    Assert.assertEquals(0, GroupByQueryResources.countRequiredMergeBufferNumForToolchestMerge(query));
    GroupByQueryRunnerTestHelper.runQuery(FACTORY, runner, query);

    Assert.assertEquals(3, MERGE_BUFFER_POOL.getMinRemainBufferNum());
    Assert.assertEquals(4, MERGE_BUFFER_POOL.getPoolSize());
  }

  @Test
  public void testNestedGroupBy()
  {
    final GroupByQuery query = GroupByQuery
        .builder()
        .setDataSource(
            new QueryDataSource(
                GroupByQuery.builder()
                            .setDataSource(QueryRunnerTestHelper.DATA_SOURCE)
                            .setInterval(QueryRunnerTestHelper.FIRST_TO_THIRD)
                            .setGranularity(Granularities.ALL)
                            .setDimensions(new DefaultDimensionSpec("quality", "alias"))
                            .setAggregatorSpecs(Collections.singletonList(QueryRunnerTestHelper.ROWS_COUNT))
                            .build()
            )
        )
        .setGranularity(Granularities.ALL)
        .setInterval(QueryRunnerTestHelper.FIRST_TO_THIRD)
        .setAggregatorSpecs(new LongSumAggregatorFactory("rows", "rows"))
        .setContext(ImmutableMap.of(QueryContexts.TIMEOUT_KEY, TIMEOUT))
        .build();

    Assert.assertEquals(1, GroupByQueryResources.countRequiredMergeBufferNumForToolchestMerge(query));
    GroupByQueryRunnerTestHelper.runQuery(FACTORY, runner, query);

    Assert.assertEquals(2, MERGE_BUFFER_POOL.getMinRemainBufferNum());
    Assert.assertEquals(4, MERGE_BUFFER_POOL.getPoolSize());
  }

  @Test
  public void testDoubleNestedGroupBy()
  {
    final GroupByQuery query = GroupByQuery
        .builder()
        .setDataSource(
            new QueryDataSource(
                GroupByQuery.builder()
                            .setDataSource(
                                GroupByQuery.builder()
                                            .setDataSource(QueryRunnerTestHelper.DATA_SOURCE)
                                            .setInterval(QueryRunnerTestHelper.FIRST_TO_THIRD)
                                            .setGranularity(Granularities.ALL)
                                            .setDimensions(
                                                new DefaultDimensionSpec("quality", "alias"),
                                                new DefaultDimensionSpec("market", null)
                                            )
                                            .setAggregatorSpecs(Collections.singletonList(QueryRunnerTestHelper.ROWS_COUNT))
                                            .build()
                            )
                            .setInterval(QueryRunnerTestHelper.FIRST_TO_THIRD)
                            .setGranularity(Granularities.ALL)
                            .setDimensions(new DefaultDimensionSpec("quality", "alias"))
                            .setAggregatorSpecs(Collections.singletonList(QueryRunnerTestHelper.ROWS_COUNT))
                            .build()
            )
        )
        .setGranularity(Granularities.ALL)
        .setInterval(QueryRunnerTestHelper.FIRST_TO_THIRD)
        .setAggregatorSpecs(new LongSumAggregatorFactory("rows", "rows"))
        .setContext(ImmutableMap.of(QueryContexts.TIMEOUT_KEY, TIMEOUT))
        .build();

    Assert.assertEquals(2, GroupByQueryResources.countRequiredMergeBufferNumForToolchestMerge(query));
    GroupByQueryRunnerTestHelper.runQuery(FACTORY, runner, query);

    // This should be 1 because the broker needs 2 buffers and the queryable node needs one.
    Assert.assertEquals(1, MERGE_BUFFER_POOL.getMinRemainBufferNum());
    Assert.assertEquals(4, MERGE_BUFFER_POOL.getPoolSize());
  }

  @Test
  public void testTripleNestedGroupBy()
  {
    final GroupByQuery query = GroupByQuery
        .builder()
        .setDataSource(
            new QueryDataSource(
                GroupByQuery.builder()
                            .setDataSource(
                                GroupByQuery.builder()
                                            .setDataSource(
                                                GroupByQuery.builder()
                                                            .setDataSource(QueryRunnerTestHelper.DATA_SOURCE)
                                                            .setInterval(QueryRunnerTestHelper.FIRST_TO_THIRD)
                                                            .setGranularity(Granularities.ALL)
                                                            .setDimensions(Lists.newArrayList(
                                                                new DefaultDimensionSpec("quality", "alias"),
                                                                new DefaultDimensionSpec("market", null),
                                                                new DefaultDimensionSpec("placement", null)
                                                            ))
                                                            .setAggregatorSpecs(Collections.singletonList(
                                                                QueryRunnerTestHelper.ROWS_COUNT))
                                                            .build()
                                            )
                                            .setInterval(QueryRunnerTestHelper.FIRST_TO_THIRD)
                                            .setGranularity(Granularities.ALL)
                                            .setDimensions(
                                                new DefaultDimensionSpec("quality", "alias"),
                                                new DefaultDimensionSpec("market", null)
                                            )
                                            .setAggregatorSpecs(Collections.singletonList(QueryRunnerTestHelper.ROWS_COUNT))
                                            .build()
                            )
                            .setInterval(QueryRunnerTestHelper.FIRST_TO_THIRD)
                            .setGranularity(Granularities.ALL)
                            .setDimensions(new DefaultDimensionSpec("quality", "alias"))
                            .setAggregatorSpecs(Collections.singletonList(QueryRunnerTestHelper.ROWS_COUNT))
                            .build()
            )
        )
        .setGranularity(Granularities.ALL)
        .setInterval(QueryRunnerTestHelper.FIRST_TO_THIRD)
        .setAggregatorSpecs(new LongSumAggregatorFactory("rows", "rows"))
        .setContext(ImmutableMap.of(QueryContexts.TIMEOUT_KEY, TIMEOUT))
        .build();

    Assert.assertEquals(2, GroupByQueryResources.countRequiredMergeBufferNumForToolchestMerge(query));
    GroupByQueryRunnerTestHelper.runQuery(FACTORY, runner, query);

    // This should be 1 because the broker needs 2 buffers and the queryable node needs one.
    Assert.assertEquals(1, MERGE_BUFFER_POOL.getMinRemainBufferNum());
    Assert.assertEquals(4, MERGE_BUFFER_POOL.getPoolSize());
  }

  @Test
  public void testSimpleGroupByWithSubtotals()
  {
    final GroupByQuery query = GroupByQuery
        .builder()
        .setDataSource(QueryRunnerTestHelper.DATA_SOURCE)
        .setDimensions(Arrays.asList(
            DefaultDimensionSpec.of(QueryRunnerTestHelper.MARKET_DIMENSION),
            DefaultDimensionSpec.of(QueryRunnerTestHelper.PLACEMENT_DIMENSION),
            DefaultDimensionSpec.of(QueryRunnerTestHelper.QUALITY_DIMENSION)
        ))
        .setGranularity(Granularities.ALL)
        .setInterval(QueryRunnerTestHelper.FIRST_TO_THIRD)
        .setAggregatorSpecs(new LongSumAggregatorFactory("rows", "rows"))
        .setSubtotalsSpec(Arrays.asList(
            Arrays.asList(QueryRunnerTestHelper.MARKET_DIMENSION, QueryRunnerTestHelper.PLACEMENT_DIMENSION),
            Arrays.asList(QueryRunnerTestHelper.MARKET_DIMENSION, QueryRunnerTestHelper.PLACEMENT_DIMENSION, QueryRunnerTestHelper.QUALITY_DIMENSION)
        ))
        .setContext(ImmutableMap.of(QueryContexts.TIMEOUT_KEY, TIMEOUT))
        .build();

    Assert.assertEquals(1, GroupByQueryResources.countRequiredMergeBufferNumForToolchestMerge(query));
    GroupByQueryRunnerTestHelper.runQuery(FACTORY, runner, query);

    // 1 for subtotal and 1 for GroupByQueryRunnerFactory#mergeRunners
    Assert.assertEquals(2, MERGE_BUFFER_POOL.getMinRemainBufferNum());
    Assert.assertEquals(4, MERGE_BUFFER_POOL.getPoolSize());
  }

  @Test
  public void testSimpleGroupByWithSubtotalsWithoutPrefixMatch()
  {
    final GroupByQuery query = GroupByQuery
        .builder()
        .setDataSource(QueryRunnerTestHelper.DATA_SOURCE)
        .setDimensions(Arrays.asList(
            DefaultDimensionSpec.of(QueryRunnerTestHelper.MARKET_DIMENSION),
            DefaultDimensionSpec.of(QueryRunnerTestHelper.PLACEMENT_DIMENSION),
            DefaultDimensionSpec.of(QueryRunnerTestHelper.QUALITY_DIMENSION)
        ))
        .setGranularity(Granularities.ALL)
        .setInterval(QueryRunnerTestHelper.FIRST_TO_THIRD)
        .setAggregatorSpecs(new LongSumAggregatorFactory("rows", "rows"))
        .setSubtotalsSpec(Arrays.asList(
            Arrays.asList(QueryRunnerTestHelper.MARKET_DIMENSION, QueryRunnerTestHelper.PLACEMENT_DIMENSION),
            Arrays.asList(QueryRunnerTestHelper.MARKET_DIMENSION, QueryRunnerTestHelper.QUALITY_DIMENSION)
        ))
        .setContext(ImmutableMap.of(QueryContexts.TIMEOUT_KEY, TIMEOUT))
        .build();

    Assert.assertEquals(2, GroupByQueryResources.countRequiredMergeBufferNumForToolchestMerge(query));
    GroupByQueryRunnerTestHelper.runQuery(FACTORY, runner, query);

    // 2 needed by subtotal and 1 for GroupByQueryRunnerFactory#mergeRunners
    Assert.assertEquals(1, MERGE_BUFFER_POOL.getMinRemainBufferNum());
    Assert.assertEquals(4, MERGE_BUFFER_POOL.getPoolSize());
  }

  @Test
  public void testNestedGroupByWithSubtotals()
  {
    final GroupByQuery query = GroupByQuery
        .builder()
        .setDataSource(
            new QueryDataSource(
                GroupByQuery.builder()
                            .setDataSource(QueryRunnerTestHelper.DATA_SOURCE)
                            .setInterval(QueryRunnerTestHelper.FIRST_TO_THIRD)
                            .setGranularity(Granularities.ALL)
                            .setDimensions(Arrays.asList(
                                DefaultDimensionSpec.of("quality"),
                                DefaultDimensionSpec.of("market"),
                                DefaultDimensionSpec.of("placement")
                            ))
                            .setAggregatorSpecs(Collections.singletonList(QueryRunnerTestHelper.ROWS_COUNT))
                            .build()
            )
        )
        .setGranularity(Granularities.ALL)
        .setInterval(QueryRunnerTestHelper.FIRST_TO_THIRD)
        .setDimensions(Arrays.asList(
            DefaultDimensionSpec.of("quality"),
            DefaultDimensionSpec.of("market")
        ))
        .setSubtotalsSpec(Arrays.asList(
            Collections.singletonList("quality"),
            Collections.singletonList("market")
        ))
        .setAggregatorSpecs(new LongSumAggregatorFactory("rows", "rows"))
        .setContext(ImmutableMap.of(QueryContexts.TIMEOUT_KEY, TIMEOUT))
        .build();

    Assert.assertEquals(3, GroupByQueryResources.countRequiredMergeBufferNumForToolchestMerge(query));
    GroupByQueryRunnerTestHelper.runQuery(FACTORY, runner, query);

    // 2 for subtotal, 1 for nested group by and 1 for GroupByQueryRunnerFactory#mergeRunners
    Assert.assertEquals(0, MERGE_BUFFER_POOL.getMinRemainBufferNum());
    Assert.assertEquals(4, MERGE_BUFFER_POOL.getPoolSize());
  }

  @Test
  public void testNestedGroupByWithNestedSubtotals()
  {
    final GroupByQuery query = GroupByQuery
        .builder()
        .setDataSource(
            new QueryDataSource(
                GroupByQuery.builder()
                            .setDataSource(QueryRunnerTestHelper.DATA_SOURCE)
                            .setInterval(QueryRunnerTestHelper.FIRST_TO_THIRD)
                            .setGranularity(Granularities.ALL)
                            .setDimensions(Arrays.asList(
                                DefaultDimensionSpec.of("quality"),
                                DefaultDimensionSpec.of("market"),
                                DefaultDimensionSpec.of("placement")
                            ))
                            .setSubtotalsSpec(Arrays.asList(
                                Collections.singletonList("quality"),
                                Collections.singletonList("market")
                            ))
                            .setAggregatorSpecs(Collections.singletonList(QueryRunnerTestHelper.ROWS_COUNT))
                            .build()
            )
        )
        .setGranularity(Granularities.ALL)
        .setInterval(QueryRunnerTestHelper.FIRST_TO_THIRD)
        .setDimensions(Arrays.asList(
            DefaultDimensionSpec.of("quality"),
            DefaultDimensionSpec.of("market")
        ))
        .setSubtotalsSpec(Arrays.asList(
            Collections.singletonList("quality"),
            Collections.singletonList("market")
        ))
        .setAggregatorSpecs(new LongSumAggregatorFactory("rows", "rows"))
        .setContext(ImmutableMap.of(QueryContexts.TIMEOUT_KEY, TIMEOUT))
        .build();

    Assert.assertEquals(3, GroupByQueryResources.countRequiredMergeBufferNumForToolchestMerge(query));
    GroupByQueryRunnerTestHelper.runQuery(FACTORY, runner, query);

    // 2 for subtotal, 1 for nested group by and 1 for GroupByQueryRunnerFactory#mergeRunners
    Assert.assertEquals(0, MERGE_BUFFER_POOL.getMinRemainBufferNum());
    Assert.assertEquals(4, MERGE_BUFFER_POOL.getPoolSize());
  }
}
