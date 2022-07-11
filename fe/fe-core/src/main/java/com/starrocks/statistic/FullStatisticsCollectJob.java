// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.
package com.starrocks.statistic;

import com.google.common.collect.Lists;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.cluster.ClusterNamespace;
import com.starrocks.common.Config;
import org.apache.velocity.VelocityContext;

import java.util.List;
import java.util.Map;

public class FullStatisticsCollectJob extends StatisticsCollectJob {

    private static final String COLLECT_FULL_STATISTIC_TEMPLATE =
            " SELECT $tableId, $partitionId, '$columnName', $dbId," +
                    " '$dbName.$tableName', '$partitionName'," +
                    " COUNT(1), $dataSize, $countDistinctFunction, $countNullFunction, $maxFunction, $minFunction, NOW() "
                    + "FROM $dbName.$tableName partition $partitionName";

    private final List<Long> partitionIdList;

    public FullStatisticsCollectJob(Database db, OlapTable table, List<Long> partitionIdList, List<String> columns,
                                    StatsConstants.AnalyzeType type, StatsConstants.ScheduleType scheduleType,
                                    Map<String, String> properties) {
        super(db, table, columns, type, scheduleType, properties);
        this.partitionIdList = partitionIdList;
    }

    public List<Long> getPartitionIdList() {
        return partitionIdList;
    }

    @Override
    public void collect() throws Exception {
        for (Long partitionId : partitionIdList) {
            Partition partition = table.getPartition(partitionId);

            List<List<String>> splitColumns = Lists.partition(columns,
                    (int) (partition.getRowCount() * columns.size() / Config.statistics_collect_max_row_count_per_query + 1));
            for (List<String> splitColItem : splitColumns) {
                for (String columnName : splitColItem) {
                    String sql = buildCollectFullStatisticSQL(db, table, partition, Lists.newArrayList(columnName));
                    collectStatisticSync(sql);
                }
            }
        }
    }

    public String buildCollectFullStatisticSQL(Database database, OlapTable table, Partition partition,
                                               List<String> columnNames) {
        StringBuilder builder = new StringBuilder("INSERT INTO column_statistics").append(" ");

        for (String name : columnNames) {
            VelocityContext context = new VelocityContext();
            Column column = table.getColumn(name);

            context.put("dbId", database.getId());
            context.put("tableId", table.getId());
            context.put("partitionId", partition.getId());
            context.put("columnName", name);
            context.put("dbName", ClusterNamespace.getNameFromFullName(database.getFullName()));
            context.put("tableName", table.getName());
            context.put("partitionName", partition.getName());
            context.put("dataSize", getDataSize(column, false));

            if (!column.getType().canStatistic()) {
                context.put("countDistinctFunction", "hll_empty()");
                context.put("countNullFunction", "0");
                context.put("maxFunction", "''");
                context.put("minFunction", "''");
            } else {
                context.put("countDistinctFunction", "IFNULL(hll_union(hll_hash(`" + name + "`)), hll_empty())");
                context.put("countNullFunction", "COUNT(1) - COUNT(`" + name + "`)");
                context.put("maxFunction", "IFNULL(MAX(`" + name + "`), '')");
                context.put("minFunction", "IFNULL(MIN(`" + name + "`), '')");
            }

            builder.append(build(context, COLLECT_FULL_STATISTIC_TEMPLATE));
            builder.append(" UNION ALL ");
        }

        return builder.substring(0, builder.length() - "UNION ALL ".length());
    }
}
