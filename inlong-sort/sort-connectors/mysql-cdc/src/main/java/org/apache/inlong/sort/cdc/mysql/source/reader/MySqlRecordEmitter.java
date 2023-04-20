/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.sort.cdc.mysql.source.reader;

import com.ververica.cdc.connectors.mysql.source.utils.RecordUtils;
import io.debezium.connector.AbstractSourceInfo;
import io.debezium.data.Envelope;
import io.debezium.document.Array;
import io.debezium.relational.ColumnFilterMode;
import io.debezium.relational.Table;
import io.debezium.relational.TableId;
import io.debezium.relational.Tables;
import io.debezium.relational.history.HistoryRecord;
import io.debezium.relational.history.HistoryRecord.Fields;
import io.debezium.relational.history.TableChanges;
import io.debezium.relational.history.TableChanges.TableChange;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.RenameTableStatement;
import org.apache.flink.api.connector.source.SourceOutput;
import org.apache.flink.connector.base.source.reader.RecordEmitter;
import org.apache.flink.runtime.operators.shipping.OutputCollector;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.util.Collector;
import org.apache.inlong.sort.base.enums.ReadPhase;
import org.apache.inlong.sort.cdc.base.debezium.DebeziumDeserializationSchema;
import org.apache.inlong.sort.cdc.base.debezium.history.FlinkJsonTableChangeSerializer;
import org.apache.inlong.sort.cdc.base.util.ColumnFilterUtil;
import org.apache.inlong.sort.cdc.mysql.source.config.MySqlSourceConfig;
import org.apache.inlong.sort.cdc.mysql.source.config.MySqlSourceConfig;
import org.apache.inlong.sort.cdc.mysql.source.metrics.MySqlSourceReaderMetrics;
import org.apache.inlong.sort.cdc.mysql.source.offset.BinlogOffset;
import org.apache.inlong.sort.cdc.mysql.source.split.MySqlBinlogSplitState;
import org.apache.inlong.sort.cdc.mysql.source.split.MySqlSplitState;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.apache.inlong.sort.base.Constants.CARET;
import static org.apache.inlong.sort.base.Constants.DDL_FIELD_NAME;
import static org.apache.inlong.sort.base.Constants.DDL_OP_ALTER;
import static org.apache.inlong.sort.base.Constants.DOLLAR;
import static org.apache.inlong.sort.base.Constants.TABLE_NAME;
import static org.apache.inlong.sort.cdc.base.relational.JdbcSourceEventDispatcher.HISTORY_RECORD_FIELD;
import static org.apache.inlong.sort.cdc.mysql.source.utils.RecordUtils.getBinlogPosition;
import static org.apache.inlong.sort.cdc.mysql.source.utils.RecordUtils.getFetchTimestamp;
import static org.apache.inlong.sort.cdc.mysql.source.utils.RecordUtils.getHistoryRecord;
import static org.apache.inlong.sort.cdc.mysql.source.utils.RecordUtils.getMessageTimestamp;
import static org.apache.inlong.sort.cdc.mysql.source.utils.RecordUtils.getWatermark;
import static org.apache.inlong.sort.cdc.mysql.source.utils.RecordUtils.isDataChangeRecord;
import static org.apache.inlong.sort.cdc.mysql.source.utils.RecordUtils.isHeartbeatEvent;
import static org.apache.inlong.sort.cdc.mysql.source.utils.RecordUtils.isHighWatermarkEvent;
import static org.apache.inlong.sort.cdc.mysql.source.utils.RecordUtils.isSchemaChangeEvent;
import static org.apache.inlong.sort.cdc.mysql.source.utils.RecordUtils.isWatermarkEvent;
import static org.apache.inlong.sort.cdc.mysql.source.utils.RecordUtils.toSnapshotRecord;
import static org.apache.inlong.sort.protocol.ddl.Utils.ColumnUtils.reformatName;

/**
 * The {@link RecordEmitter} implementation for {@link MySqlSourceReader}.
 *
 * <p>The {@link RecordEmitter} buffers the snapshot records of split and call the binlog reader to
 * emit records rather than emit the records directly.</p>
 */
public final class MySqlRecordEmitter<T>
        implements
            RecordEmitter<SourceRecord, T, MySqlSplitState> {

    private static final Logger LOG = LoggerFactory.getLogger(MySqlRecordEmitter.class);
    private static final FlinkJsonTableChangeSerializer TABLE_CHANGE_SERIALIZER =
            new FlinkJsonTableChangeSerializer();

    private final DebeziumDeserializationSchema<T> debeziumDeserializationSchema;
    private final MySqlSourceReaderMetrics sourceReaderMetrics;
    private final boolean includeSchemaChanges;
    private final OutputCollector<T> outputCollector;
    private final Tables.ColumnNameFilter columnNameFilter;
    private volatile long binlogPos = 0L;
    private volatile long binlogFileNum = 0L;
    private volatile Boolean iSnapShot = false;
    private volatile long messageTimestamp = 0L;
    private volatile long fetchDelay = 0L;
    private volatile Long snapDuration;
    private volatile long snapEarliestTime = 0L;
    private volatile long snapProcessTime = 0L;

    private boolean includeIncremental;
    public final ObjectMapper objectMapper = new ObjectMapper();
    private boolean ghostDdlChange;
    private String ghostTableRegex;
    public final Map<TableId, String> tableDdls = new HashMap<>();

    public MySqlRecordEmitter(
            DebeziumDeserializationSchema<T> debeziumDeserializationSchema,
            MySqlSourceReaderMetrics sourceReaderMetrics,
            MySqlSourceConfig sourceConfig) {
        this.debeziumDeserializationSchema = debeziumDeserializationSchema;
        this.sourceReaderMetrics = sourceReaderMetrics;
        this.includeSchemaChanges = sourceConfig.isIncludeSchemaChanges();
        this.outputCollector = new OutputCollector<>();
        this.includeIncremental = sourceConfig.isIncludeIncremental();
        this.columnNameFilter = ColumnFilterUtil.createColumnFilter(
                sourceConfig.getDbzConfiguration(), ColumnFilterMode.CATALOG);
        this.ghostDdlChange = sourceConfig.isGhostDdlChange();
        this.ghostTableRegex = sourceConfig.getGhostTableRegex();
    }

    @Override
    public void emitRecord(SourceRecord element, SourceOutput<T> output, MySqlSplitState splitState)
            throws Exception {

        if (isWatermarkEvent(element)) {
            BinlogOffset watermark = getWatermark(element);
            if (isHighWatermarkEvent(element) && splitState.isSnapshotSplitState()) {
                splitState.asSnapshotSplitState().setHighWatermark(watermark);
            }
        } else if (isSchemaChangeEvent(element) && splitState.isBinlogSplitState()) {
            updateSnapshotRecord(element, splitState);
            HistoryRecord historyRecord = getHistoryRecord(element);
            Array tableChanges =
                    historyRecord.document().getArray(HistoryRecord.Fields.TABLE_CHANGES);
            TableChanges changes = TABLE_CHANGE_SERIALIZER.deserialize(tableChanges, true);
            for (TableChange tableChange : changes) {
                splitState.asBinlogSplitState().recordSchema(tableChange.getId(), tableChange);
                if (includeSchemaChanges) {
                    TableChange newTableChange = ColumnFilterUtil.createTableChange(tableChange, columnNameFilter);
                    if (ghostDdlChange) {
                        updateGhostDdlElement(element, splitState, historyRecord);
                    }
                    outputDdlElement(element, output, splitState, newTableChange);
                }
            }

            if (tableChanges.isEmpty()) {
                TableId tableId = RecordUtils.getTableId(element);
                // if this table is one of the captured tables, output the ddl element.
                if (splitState.getMySQLSplit().getTableSchemas().containsKey(tableId)
                        || shouldOutputRenameDdl(element, tableId)) {
                    outputDdlElement(element, output, splitState, null);
                }
                if (ghostDdlChange) {
                    collectGhostDdl(element, splitState, historyRecord);
                }
            }

        } else if (isDataChangeRecord(element)) {
            if (splitState.isBinlogSplitState()) {
                BinlogOffset position = getBinlogPosition(element);
                splitState.asBinlogSplitState().setStartingOffset(position);
                reportPos(position);
                iSnapShot = false;
                updateMessageTimestamp(element);
            } else {
                if (splitState.isSnapshotSplitState()) {
                    iSnapShot = true;
                }
                updateMessageTimestampSnap(element);
            }
            fetchDelay = System.currentTimeMillis() - messageTimestamp;
            reportMetrics(element);

            final Map<TableId, TableChange> tableSchemas =
                    splitState.getMySQLSplit().getTableSchemas();
            final TableChange tableSchema =
                    tableSchemas.getOrDefault(RecordUtils.getTableId(element), null);

            updateSnapshotRecord(element, splitState);

            TableChange newTableChange = ColumnFilterUtil.createTableChange(tableSchema, columnNameFilter);
            debeziumDeserializationSchema.deserialize(
                    element,
                    new Collector<T>() {

                        @Override
                        public void collect(final T t) {
                            Struct value = (Struct) element.value();
                            Struct source = value.getStruct(Envelope.FieldName.SOURCE);
                            String databaseName = source.getString(AbstractSourceInfo.DATABASE_NAME_KEY);
                            String tableName = source.getString(AbstractSourceInfo.TABLE_NAME_KEY);

                            sourceReaderMetrics.outputMetrics(databaseName, tableName, iSnapShot, t);
                            output.collect(t);
                        }

                        @Override
                        public void close() {
                            // do nothing
                        }
                    },
                    newTableChange);
        } else if (isHeartbeatEvent(element)) {
            updateStartingOffsetForSplit(splitState, element);
        } else {
            // unknown element
            LOG.info("Meet unknown element {}, just skip.", element);
        }
    }

    /**
     * if rename operation is "rename a to b" where a is the captured table
     * this method extract table names a and b, if any of table name is the captured table
     * we should output ddl element
     */
    private boolean shouldOutputRenameDdl(SourceRecord element, TableId tableId) {
        try {
            String ddl = objectMapper.readTree(((Struct) element.value()).get(HISTORY_RECORD_FIELD).toString())
                    .get(DDL_FIELD_NAME).asText();
            Statement statement = CCJSqlParserUtil.parse(ddl);
            if (statement instanceof RenameTableStatement) {
                RenameTableStatement renameTableStatement = (RenameTableStatement) statement;
                Set<Entry<Table, Table>> tableNames = renameTableStatement.getTableNames();
                for (Entry<Table, Table> entry : tableNames) {
                    Table oldTable = entry.getKey();
                    Table newTable = entry.getValue();
                    if (reformatName(oldTable.getName()).equals(tableId.table()) ||
                            reformatName(newTable.getName()).equals(tableId.table())) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("parse ddl error {}", element, e);
        }
        return false;
    }

    private void collectGhostDdl(SourceRecord element, MySqlSplitState splitState, HistoryRecord historyRecord) {
        String ddl = historyRecord.document().getString(Fields.DDL_STATEMENTS);
        String tableName = org.apache.inlong.sort.cdc.mysql.source.utils.RecordUtils.getTableName(element);
        Pattern compile = Pattern.compile(this.ghostTableRegex);
        Matcher matcher = compile.matcher(tableName);
        if (matcher.find()) {
            tableName = matcher.group(1);
            String dbName = org.apache.inlong.sort.cdc.mysql.source.utils.RecordUtils.getDbName(element);
            TableId tableId = org.apache.inlong.sort.cdc.mysql.source.utils.RecordUtils.getTabelId(dbName,
                    tableName);
            TableChange tableChange = splitState.getMySQLSplit().getTableSchemas().getOrDefault(
                    tableId,
                    null);
            if (null != tableChange) {
                if (ddl.toUpperCase().startsWith(DDL_OP_ALTER)) {
                    String matchTableInSqlRegex = ghostTableRegex;
                    if (matchTableInSqlRegex.startsWith(CARET) && matchTableInSqlRegex.endsWith(DOLLAR)) {
                        matchTableInSqlRegex = matchTableInSqlRegex.substring(1, matchTableInSqlRegex.length() - 1);
                    }
                    mySqlBinlogSplitState.recordTableDdl(tableId, ddl.replaceAll(matchTableInSqlRegex, tableName));
                }
            }
        }
    }

    private void updateGhostDdlElement(SourceRecord element, MySqlSplitState splitState, HistoryRecord historyRecord) {
        String tableNames = org.apache.inlong.sort.cdc.mysql.source.utils.RecordUtils.getTableName(element);
        for (String tableName : tableNames.split(",")) {
            Pattern compile = Pattern.compile(ghostTableRegex);
            Matcher matcher = compile.matcher(tableName);
            if (matcher.find()) {
                tableName = matcher.group(1);
                String dbName = org.apache.inlong.sort.cdc.mysql.source.utils.RecordUtils.getDbName(element);
                TableId tableId = org.apache.inlong.sort.cdc.mysql.source.utils.RecordUtils.getTableId(
                        dbName,
                        tableName);
                String ddl = splitState.asBinlogSplitState().getTableDdls().get(tableId);
                Struct value = (Struct) element.value();
                // Update source.table and historyRecord
                value.getStruct(Fields.SOURCE).put(TABLE_NAME, tableName);
                value.put(HISTORY_RECORD_FIELD, historyRecord.document().set(Fields.DDL_STATEMENTS, ddl).toString());
            }
        }
    }

    private void updateSnapshotRecord(SourceRecord element, MySqlSplitState splitState) {
        if (splitState.isSnapshotSplitState() && includeIncremental) {
            toSnapshotRecord(element);
        }
    }

    private void outputDdlElement(SourceRecord element, SourceOutput<T> output, MySqlSplitState splitState,
            TableChange tableChange) throws Exception {
        BinlogOffset position = getBinlogPosition(element);
        splitState.asBinlogSplitState().setStartingOffset(position);
        emitElement(element, output, tableChange);
    }

    private void updateStartingOffsetForSplit(MySqlSplitState splitState, SourceRecord element) {
        if (splitState.isBinlogSplitState()) {
            // record the time metric to enter the incremental phase
            if (sourceReaderMetrics != null) {
                sourceReaderMetrics.outputReadPhaseMetrics(ReadPhase.INCREASE_PHASE);
            }
            BinlogOffset position = getBinlogPosition(element);
            splitState.asBinlogSplitState().setStartingOffset(position);
        }
    }

    private void emitElement(SourceRecord element, SourceOutput<T> output,
            TableChange tableSchema) throws Exception {
        outputCollector.output = output;
        debeziumDeserializationSchema.deserialize(element, outputCollector, tableSchema);
    }

    private void reportMetrics(SourceRecord element) {
        long now = System.currentTimeMillis();
        // record the latest process time
        sourceReaderMetrics.recordProcessTime(now);
        Long messageTimestamp = getMessageTimestamp(element);

        if (messageTimestamp != null && messageTimestamp > 0L) {
            // report fetch delay
            Long fetchTimestamp = getFetchTimestamp(element);
            if (fetchTimestamp != null && fetchTimestamp >= messageTimestamp) {
                sourceReaderMetrics.recordFetchDelay(fetchTimestamp - messageTimestamp);
            }
            // report emit delay
            sourceReaderMetrics.recordEmitDelay(now - messageTimestamp);
        }
    }

    private void reportPos(BinlogOffset position) {
        try {
            binlogPos = position.getPosition();
            binlogFileNum = Long.parseLong(position.getFilename().replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Can not translate binlog pos or flile name.");
            }
        }
    }

    private void updateMessageTimestamp(SourceRecord record) {
        Schema schema = record.valueSchema();
        Struct value = (Struct) record.value();
        if (schema.field(Envelope.FieldName.SOURCE) == null) {
            return;
        }

        Struct source = value.getStruct(Envelope.FieldName.SOURCE);
        if (source.schema().field(Envelope.FieldName.TIMESTAMP) == null) {
            return;
        }

        Long tsMs = source.getInt64(Envelope.FieldName.TIMESTAMP);

        if (tsMs != null) {
            this.messageTimestamp = tsMs;
        }
    }

    private void updateMessageTimestampSnap(SourceRecord record) {
        Schema schema = record.valueSchema();
        Struct value = (Struct) record.value();
        if (schema.field(Envelope.FieldName.SOURCE) == null) {
            return;
        }

        Struct source = value.getStruct(Envelope.FieldName.SOURCE);
        if (source.schema().field(Envelope.FieldName.TIMESTAMP) == null) {
            return;
        }

        Long tsMs = source.getInt64(Envelope.FieldName.TIMESTAMP);

        if (tsMs != null) {
            this.messageTimestamp = tsMs;
            if (snapDuration == null) {
                this.snapEarliestTime = tsMs;
                this.snapDuration = System.currentTimeMillis() - tsMs;
            }
            if (this.snapEarliestTime > tsMs) {
                this.snapEarliestTime = tsMs;
            }
            this.snapProcessTime = tsMs - this.snapEarliestTime;
        }
    }

    private static class OutputCollector<T> implements Collector<T> {

        private SourceOutput<T> output;

        @Override
        public void collect(T record) {
            output.collect(record);
        }

        @Override
        public void close() {
            // do nothing
        }
    }
}
