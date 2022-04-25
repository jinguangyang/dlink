package com.dlink.cdc;

import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.data.DecimalData;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.operations.ModifyOperation;
import org.apache.flink.table.types.logical.BigIntType;
import org.apache.flink.table.types.logical.BooleanType;
import org.apache.flink.table.types.logical.DecimalType;
import org.apache.flink.table.types.logical.DoubleType;
import org.apache.flink.table.types.logical.FloatType;
import org.apache.flink.table.types.logical.IntType;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.SmallIntType;
import org.apache.flink.table.types.logical.TinyIntType;
import org.apache.flink.table.types.logical.VarCharType;
import org.apache.flink.types.RowKind;
import org.apache.flink.util.Collector;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.dlink.assertion.Asserts;
import com.dlink.executor.CustomTableEnvironment;
import com.dlink.model.Column;
import com.dlink.model.ColumnType;
import com.dlink.model.FlinkCDCConfig;
import com.dlink.model.Schema;
import com.dlink.model.Table;

/**
 * AbstractCDCBuilder
 *
 * @author wenmo
 * @since 2022/4/12 21:28
 **/
public abstract class AbstractSinkBuilder {

    protected FlinkCDCConfig config;
    protected List<ModifyOperation> modifyOperations = new ArrayList();

    public AbstractSinkBuilder() {
    }

    public AbstractSinkBuilder(FlinkCDCConfig config) {
        this.config = config;
    }

    public FlinkCDCConfig getConfig() {
        return config;
    }

    public void setConfig(FlinkCDCConfig config) {
        this.config = config;
    }

    protected Properties getProperties() {
        Properties properties = new Properties();
        Map<String, String> sink = config.getSink();
        for (Map.Entry<String, String> entry : sink.entrySet()) {
            if (Asserts.isNotNullString(entry.getKey()) && Asserts.isNotNullString(entry.getValue())) {
                properties.setProperty(entry.getKey(), entry.getValue());
            }
        }
        return properties;
    }

    protected SingleOutputStreamOperator<Map> deserialize(DataStreamSource<String> dataStreamSource) {
        return dataStreamSource.map(new MapFunction<String, Map>() {
            @Override
            public Map map(String value) throws Exception {
                ObjectMapper objectMapper = new ObjectMapper();
                return objectMapper.readValue(value, Map.class);
            }
        });
    }

    protected SingleOutputStreamOperator<Map> shunt(
        SingleOutputStreamOperator<Map> mapOperator,
        Table table,
        String schemaFieldName) {
        final String tableName = table.getName();
        final String schemaName = table.getSchema();
        return mapOperator.filter(new FilterFunction<Map>() {
            @Override
            public boolean filter(Map value) throws Exception {
                LinkedHashMap source = (LinkedHashMap) value.get("source");
                return tableName.equals(source.get("table").toString())
                    && schemaName.equals(source.get(schemaFieldName).toString());
            }
        });
    }

    protected DataStream<RowData> buildRowData(
        SingleOutputStreamOperator<Map> filterOperator,
        List<String> columnNameList,
        List<LogicalType> columnTypeList) {
        return filterOperator
            .flatMap(new FlatMapFunction<Map, RowData>() {
                @Override
                public void flatMap(Map value, Collector<RowData> out) throws Exception {
                    switch (value.get("op").toString()) {
                        case "c":
                            GenericRowData igenericRowData = new GenericRowData(columnNameList.size());
                            igenericRowData.setRowKind(RowKind.INSERT);
                            Map idata = (Map) value.get("after");
                            for (int i = 0; i < columnNameList.size(); i++) {
                                igenericRowData.setField(i, convertValue(idata.get(columnNameList.get(i)), columnTypeList.get(i)));
                            }
                            out.collect(igenericRowData);
                            break;
                        case "d":
                            GenericRowData dgenericRowData = new GenericRowData(columnNameList.size());
                            dgenericRowData.setRowKind(RowKind.DELETE);
                            Map ddata = (Map) value.get("before");
                            for (int i = 0; i < columnNameList.size(); i++) {
                                dgenericRowData.setField(i, convertValue(ddata.get(columnNameList.get(i)), columnTypeList.get(i)));
                            }
                            out.collect(dgenericRowData);
                            break;
                        case "u":
                            GenericRowData ubgenericRowData = new GenericRowData(columnNameList.size());
                            ubgenericRowData.setRowKind(RowKind.UPDATE_BEFORE);
                            Map ubdata = (Map) value.get("before");
                            for (int i = 0; i < columnNameList.size(); i++) {
                                ubgenericRowData.setField(i, convertValue(ubdata.get(columnNameList.get(i)), columnTypeList.get(i)));
                            }
                            out.collect(ubgenericRowData);
                            GenericRowData uagenericRowData = new GenericRowData(columnNameList.size());
                            uagenericRowData.setRowKind(RowKind.UPDATE_AFTER);
                            Map uadata = (Map) value.get("after");
                            for (int i = 0; i < columnNameList.size(); i++) {
                                uagenericRowData.setField(i, convertValue(uadata.get(columnNameList.get(i)), columnTypeList.get(i)));
                            }
                            out.collect(uagenericRowData);
                            break;
                    }
                }
            });
    }

    public abstract void addSink(
        StreamExecutionEnvironment env,
        DataStream<RowData> rowDataDataStream,
        Table table,
        List<String> columnNameList,
        List<LogicalType> columnTypeList);

    public DataStreamSource build(
        CDCBuilder cdcBuilder,
        StreamExecutionEnvironment env,
        CustomTableEnvironment customTableEnvironment,
        DataStreamSource<String> dataStreamSource) {

        final List<Schema> schemaList = config.getSchemaList();
        final String schemaFieldName = config.getSchemaFieldName();

        if (Asserts.isNotNullCollection(schemaList)) {
            SingleOutputStreamOperator<Map> mapOperator = deserialize(dataStreamSource);
            for (Schema schema : schemaList) {
                for (Table table : schema.getTables()) {
                    SingleOutputStreamOperator<Map> filterOperator = shunt(mapOperator, table, schemaFieldName);

                    List<String> columnNameList = new ArrayList<>();
                    List<LogicalType> columnTypeList = new ArrayList<>();

                    buildColumn(columnNameList, columnTypeList, table.getColumns());

                    DataStream<RowData> rowDataDataStream = buildRowData(filterOperator, columnNameList, columnTypeList);

                    addSink(env, rowDataDataStream, table, columnNameList, columnTypeList);
                }
            }
        }
        return dataStreamSource;
    }

    protected void buildColumn(List<String> columnNameList, List<LogicalType> columnTypeList, List<Column> columns) {
        for (Column column : columns) {
            columnNameList.add(column.getName());
            columnTypeList.add(getLogicalType(column.getJavaType()));
        }
    }

    protected LogicalType getLogicalType(ColumnType columnType) {
        switch (columnType) {
            case STRING:
                return new VarCharType();
            case BOOLEAN:
            case JAVA_LANG_BOOLEAN:
                return new BooleanType();
            case BYTE:
            case JAVA_LANG_BYTE:
                return new TinyIntType();
            case SHORT:
            case JAVA_LANG_SHORT:
                return new SmallIntType();
            case LONG:
            case JAVA_LANG_LONG:
                return new BigIntType();
            case FLOAT:
            case JAVA_LANG_FLOAT:
                return new FloatType();
            case DOUBLE:
            case JAVA_LANG_DOUBLE:
                return new DoubleType();
            case DECIMAL:
                return new DecimalType();
            case INT:
            case INTEGER:
                return new IntType();
            default:
                return new VarCharType();
        }
    }

    protected Object convertValue(Object value, LogicalType logicalType) {
        if (value == null) {
            return null;
        }
        if (logicalType instanceof VarCharType) {
            return StringData.fromString((String) value);
        } else if (logicalType instanceof DecimalType) {
            final DecimalType decimalType = ((DecimalType) logicalType);
            final int precision = decimalType.getPrecision();
            final int scala = decimalType.getScale();
            return DecimalData.fromBigDecimal(new BigDecimal((String) value), precision, scala);
        } else {
            return value;
        }
    }

    protected String getSinkSchemaName(Table table) {
        String schemaName = table.getSchema();
        if (config.getSink().containsKey("sink.db")) {
            schemaName = config.getSink().get("sink.db");
        }
        return schemaName;
    }

    protected String getSinkTableName(Table table) {
        String tableName = table.getName();
        if (config.getSink().containsKey("table.prefix.schema")) {
            if (Boolean.valueOf(config.getSink().get("table.prefix.schema"))) {
                tableName = table.getSchema() + "_" + tableName;
            }
        }
        if (config.getSink().containsKey("table.prefix")) {
            tableName = config.getSink().get("table.prefix") + tableName;
        }
        if (config.getSink().containsKey("table.suffix")) {
            tableName = tableName + config.getSink().get("table.suffix");
        }
        if (config.getSink().containsKey("table.lower")) {
            if (Boolean.valueOf(config.getSink().get("table.lower"))) {
                tableName = tableName.toLowerCase();
            }
        }
        if (config.getSink().containsKey("table.upper")) {
            if (Boolean.valueOf(config.getSink().get("table.upper"))) {
                tableName = tableName.toUpperCase();
            }
        }
        return tableName;
    }
}