/*
 * Copyright 2019 Aiven Oy
 * Copyright 2016 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.aiven.kafka.connect.opensearch;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.data.ConnectSchema;
import org.apache.kafka.connect.data.Date;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.data.Time;
import org.apache.kafka.connect.data.Timestamp;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.storage.Converter;

import org.opensearch.action.DocWriteRequest;
import org.opensearch.action.delete.DeleteRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.index.VersionType;

public class RecordConverter {

    private static final Converter JSON_CONVERTER;

    static {
        JSON_CONVERTER = new JsonConverter();
        JSON_CONVERTER.configure(Collections.singletonMap("schemas.enable", "false"), false);
    }

    private final OpensearchSinkConnectorConfig config;

    private final DocumentIDStrategy globalDocIdGenerator;
    private final DocumentIDStrategy perTopicDocIdGenerator; // Used on topics present in TOPIC_KEY_IGNORE_CONFIG

    public RecordConverter(final Boolean useCompactMapEntries,
                           final RecordConverter.BehaviorOnNullValues behaviorOnNullValues) {
        this(null);
    }

    public RecordConverter(final OpensearchSinkConnectorConfig config) {
        this.config = config;
        globalDocIdGenerator = (config != null) ? config.globalDocIdStrategy() : DocumentIDStrategy.RECORD_KEY;
        perTopicDocIdGenerator = (config != null) ? config.docIdStrategy() : DocumentIDStrategy.TOPIC_PARTITION_OFFSET;
    }

    private DocumentIDStrategy getDocIdStrategy(final SinkRecord record) {
        return config.topicIgnoreKey().contains(record.topic()) ? perTopicDocIdGenerator : globalDocIdGenerator;
    }

    private static String convertKey(final Schema keySchema, final Object key) {
        if (key == null) {
            throw new DataException("Key is used as document id and can not be null.");
        }

        final Schema.Type schemaType;
        if (keySchema == null) {
            schemaType = ConnectSchema.schemaType(key.getClass());
            if (schemaType == null) {
                throw new DataException(
                        String.format("Java class %s does not have corresponding schema type.", key.getClass())
                );
            }
        } else {
            schemaType = keySchema.type();
        }

        switch (schemaType) {
            case INT8:
            case INT16:
            case INT32:
            case INT64:
            case STRING:
                return String.valueOf(key);
            default:
                throw new DataException(schemaType.name() + " is not supported as the document id.");
        }
    }

    public DocWriteRequest<?> convert(final SinkRecord record, final String index) {
        if (record.value() == null) {
            switch (config.behaviorOnNullValues()) {
                case IGNORE:
                    return null;
                case DELETE:
                    if (record.key() == null) {
                        // Since the record key is used as the ID of the index to delete and we don't have a key
                        // for this record, we can't delete anything anyways, so we ignore the record.
                        // We can also disregard the value of the ignoreKey parameter, since even if it's true
                        // the resulting index we'd try to delete would be based solely off topic/partition/
                        // offset information for the SinkRecord. Since that information is guaranteed to be
                        // unique per message, we can be confident that there wouldn't be any corresponding
                        // index present in ES to delete anyways.
                        return null;
                    }
                    // Will proceed as normal, ultimately creating an with a null payload
                    break;
                case FAIL:
                default:
                    throw new DataException(String.format(
                            "Sink record with key of %s and null value encountered for topic/partition/offset "
                                    + "%s/%s/%s (to ignore future records like this change the configuration property "
                                    + "'%s' from '%s' to '%s')",
                            record.key(),
                            record.topic(),
                            record.kafkaPartition(),
                            record.kafkaOffset(),
                            OpensearchSinkConnectorConfig.BEHAVIOR_ON_NULL_VALUES_CONFIG,
                            BehaviorOnNullValues.FAIL,
                            BehaviorOnNullValues.IGNORE
                    ));
            }
        }

        final DocumentIDStrategy docIdStrategy = getDocIdStrategy(record);
        final var id = docIdStrategy.generate(record);

        if (Objects.isNull(record.value())) {
            return (docIdStrategy == DocumentIDStrategy.NONE) ? null 
                : addExternalVersionIfNeeded(new DeleteRequest(index).id(id), record, docIdStrategy);
        }

        final String payload = getPayload(record);
        final IndexRequest indexRequest = new IndexRequest(index)
            .source(payload, XContentType.JSON)
            .opType(DocWriteRequest.OpType.INDEX);

        // VersionType.EXTERNAL can't be used without a Document ID.
        return (docIdStrategy == DocumentIDStrategy.NONE) ? indexRequest
            : addExternalVersionIfNeeded(indexRequest.id(id), record, docIdStrategy);
    }

    private DocWriteRequest<?> addExternalVersionIfNeeded(final DocWriteRequest<?> request, final SinkRecord record, 
                                                          final DocumentIDStrategy docIdStrategy) {
        if (docIdStrategy == DocumentIDStrategy.RECORD_KEY) {
            request.versionType(VersionType.EXTERNAL);
            request.version(record.kafkaOffset());
        }
        return request;
    }

    private String getPayload(final SinkRecord record) {
        if (record.value() == null) {
            return null;
        }

        final Schema schema = config.ignoreSchemaFor(record.topic())
                ? record.valueSchema()
                : preProcessSchema(record.valueSchema());

        final Object value = config.ignoreSchemaFor(record.topic())
                ? record.value()
                : preProcessValue(record.value(), record.valueSchema(), schema);

        final byte[] rawJsonPayload = JSON_CONVERTER.fromConnectData(record.topic(), schema, value);
        return new String(rawJsonPayload, StandardCharsets.UTF_8);
    }

    // We need to pre process the Kafka Connect schema before converting to JSON as Elasticsearch
    // expects a different JSON format from the current JSON converter provides. Rather than
    // completely rewrite a converter for Elasticsearch, we will refactor the JSON converter to
    // support customized translation. The pre process is no longer needed once we have the JSON
    // converter refactored.
    // visible for testing
    Schema preProcessSchema(final Schema schema) {
        if (schema == null) {
            return null;
        }
        // Handle logical types
        final String schemaName = schema.name();
        if (schemaName != null) {
            switch (schemaName) {
                case Decimal.LOGICAL_NAME:
                    return copySchemaBasics(schema, SchemaBuilder.float64()).build();
                case Date.LOGICAL_NAME:
                case Time.LOGICAL_NAME:
                case Timestamp.LOGICAL_NAME:
                    return schema;
                default:
                    // User type or unknown logical type
                    break;
            }
        }

        final Schema.Type schemaType = schema.type();
        switch (schemaType) {
            case ARRAY:
                return preProcessArraySchema(schema);
            case MAP:
                return preProcessMapSchema(schema);
            case STRUCT:
                return preProcessStructSchema(schema);
            default:
                return schema;
        }
    }

    private Schema preProcessArraySchema(final Schema schema) {
        final Schema valSchema = preProcessSchema(schema.valueSchema());
        return copySchemaBasics(schema, SchemaBuilder.array(valSchema)).build();
    }

    private Schema preProcessMapSchema(final Schema schema) {
        final Schema keySchema = schema.keySchema();
        final Schema valueSchema = schema.valueSchema();
        final String keyName = keySchema.name() == null ? keySchema.type().name() : keySchema.name();
        final String valueName = valueSchema.name() == null ? valueSchema.type().name() : valueSchema.name();
        final Schema preprocessedKeySchema = preProcessSchema(keySchema);
        final Schema preprocessedValueSchema = preProcessSchema(valueSchema);
        if (config.useCompactMapEntries() && keySchema.type() == Schema.Type.STRING) {
            final SchemaBuilder result = SchemaBuilder.map(preprocessedKeySchema, preprocessedValueSchema);
            return copySchemaBasics(schema, result).build();
        }
        final Schema elementSchema = SchemaBuilder.struct().name(keyName + "-" + valueName)
                .field(Mapping.KEY_FIELD, preprocessedKeySchema)
                .field(Mapping.VALUE_FIELD, preprocessedValueSchema)
                .build();
        return copySchemaBasics(schema, SchemaBuilder.array(elementSchema)).build();
    }

    private Schema preProcessStructSchema(final Schema schema) {
        final SchemaBuilder builder = copySchemaBasics(schema, SchemaBuilder.struct().name(schema.name()));
        for (final Field field : schema.fields()) {
            builder.field(field.name(), preProcessSchema(field.schema()));
        }
        return builder.build();
    }

    private SchemaBuilder copySchemaBasics(final Schema source, final SchemaBuilder target) {
        if (source.isOptional()) {
            target.optional();
        }
        if (source.defaultValue() != null && source.type() != Schema.Type.STRUCT) {
            final Object defaultVal = preProcessValue(source.defaultValue(), source, target);
            target.defaultValue(defaultVal);
        }
        return target;
    }

    // visible for testing
    Object preProcessValue(final Object value, final Schema schema, final Schema newSchema) {
        // Handle missing schemas and acceptable null values
        if (schema == null) {
            return value;
        }

        if (value == null) {
            return preProcessNullValue(schema);
        }

        // Handle logical types
        final String schemaName = schema.name();
        if (schemaName != null) {
            final Object result = preProcessLogicalValue(schemaName, value);
            if (result != null) {
                return result;
            }
        }

        final Schema.Type schemaType = schema.type();
        switch (schemaType) {
            case ARRAY:
                return preProcessArrayValue(value, schema, newSchema);
            case MAP:
                return preProcessMapValue(value, schema, newSchema);
            case STRUCT:
                return preProcessStructValue(value, schema, newSchema);
            default:
                return value;
        }
    }

    private Object preProcessNullValue(final Schema schema) {
        if (schema.defaultValue() != null) {
            return schema.defaultValue();
        }
        if (schema.isOptional()) {
            return null;
        }
        throw new DataException("null value for field that is required and has no default value");
    }

    // @returns the decoded logical value or null if this isn't a known logical type
    private Object preProcessLogicalValue(final String schemaName, final Object value) {
        switch (schemaName) {
            case Decimal.LOGICAL_NAME:
                return ((BigDecimal) value).doubleValue();
            case Date.LOGICAL_NAME:
            case Time.LOGICAL_NAME:
            case Timestamp.LOGICAL_NAME:
                return value;
            default:
                // User-defined type or unknown built-in
                return null;
        }
    }

    private Object preProcessArrayValue(final Object value, final Schema schema, final Schema newSchema) {
        final Collection<?> collection = (Collection<?>) value;
        final List<Object> result = new ArrayList<>();
        for (final Object element : collection) {
            result.add(preProcessValue(element, schema.valueSchema(), newSchema.valueSchema()));
        }
        return result;
    }

    private Object preProcessMapValue(final Object value, final Schema schema, final Schema newSchema) {
        final Schema keySchema = schema.keySchema();
        final Schema valueSchema = schema.valueSchema();
        final Schema newValueSchema = newSchema.valueSchema();
        final Map<?, ?> map = (Map<?, ?>) value;
        if (config.useCompactMapEntries() && keySchema.type() == Schema.Type.STRING) {
            final Map<Object, Object> processedMap = new HashMap<>();
            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                processedMap.put(
                        preProcessValue(entry.getKey(), keySchema, newSchema.keySchema()),
                        preProcessValue(entry.getValue(), valueSchema, newValueSchema)
                );
            }
            return processedMap;
        }
        final List<Struct> mapStructs = new ArrayList<>();
        for (final Map.Entry<?, ?> entry : map.entrySet()) {
            final Struct mapStruct = new Struct(newValueSchema);
            final Schema mapKeySchema = newValueSchema.field(Mapping.KEY_FIELD).schema();
            final Schema mapValueSchema = newValueSchema.field(Mapping.VALUE_FIELD).schema();
            mapStruct.put(
                    Mapping.KEY_FIELD,
                    preProcessValue(entry.getKey(), keySchema, mapKeySchema));
            mapStruct.put(
                    Mapping.VALUE_FIELD,
                    preProcessValue(entry.getValue(), valueSchema, mapValueSchema));
            mapStructs.add(mapStruct);
        }
        return mapStructs;
    }

    private Object preProcessStructValue(final Object value, final Schema schema, final Schema newSchema) {
        final Struct struct = (Struct) value;
        final Struct newStruct = new Struct(newSchema);
        for (final Field field : schema.fields()) {
            final Schema newFieldSchema = newSchema.field(field.name()).schema();
            final Object converted = preProcessValue(struct.get(field), field.schema(), newFieldSchema);
            newStruct.put(field.name(), converted);
        }
        return newStruct;
    }

    public enum BehaviorOnNullValues {
        IGNORE,
        DELETE,
        FAIL;

        public static final BehaviorOnNullValues DEFAULT = IGNORE;

        // Want values for "behavior.on.null.values" property to be case-insensitive
        public static final ConfigDef.Validator VALIDATOR = new ConfigDef.Validator() {
            private final ConfigDef.ValidString validator = ConfigDef.ValidString.in(names());

            @Override
            public void ensureValid(final String name, final Object value) {
                if (value instanceof String) {
                    final String lowerStringValue = ((String) value).toLowerCase(Locale.ROOT);
                    validator.ensureValid(name, lowerStringValue);
                } else {
                    validator.ensureValid(name, value);
                }
            }

            // Overridden here so that ConfigDef.toEnrichedRst shows possible values correctly
            @Override
            public String toString() {
                return validator.toString();
            }

        };

        public static String[] names() {
            final BehaviorOnNullValues[] behaviors = values();
            final String[] result = new String[behaviors.length];

            for (int i = 0; i < behaviors.length; i++) {
                result[i] = behaviors[i].toString();
            }

            return result;
        }

        public static BehaviorOnNullValues forValue(final String value) {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum DocumentIDStrategy {
        NONE("none", "No Doc ID is added", record -> null),
        RECORD_KEY("record.key", "Generated from the record's key",
            record -> convertKey(record.keySchema(), record.key())),
        TOPIC_PARTITION_OFFSET("topic.partition.offset", "Generated as record's ``topic+partition+offset``",
            record ->  record.topic() + "+" + record.kafkaPartition() + "+" + record.kafkaOffset());
                
        private final String name;

        private final String description;

        private final Function<SinkRecord, String> docIdGenerator;

        private DocumentIDStrategy(final String name, final String description, 
                                   final Function<SinkRecord, String> docIdGenerator) {
            this.name = name.toLowerCase(Locale.ROOT);
            this.description = description;
            this.docIdGenerator = docIdGenerator;
        }

        @Override
        public String toString() {
            return name;
        }

        public static DocumentIDStrategy fromString(final String name) {
            for (final DocumentIDStrategy strategy : DocumentIDStrategy.values()) {
                if (strategy.nameEquals(name)) {
                    return strategy;
                }
            }
            throw new IllegalArgumentException("Unknown Document ID Strategy " + name);
        }

        public static String[] names() {
            return Arrays.stream(values()).map(v -> v.toString()).toArray(String[]::new);
        }

        public static String describe() {
            return Arrays.stream(values()).map(v -> v.toString() + " : " + v.description)
                    .collect(Collectors.joining(", ", "{", "}"));
        }

        public Boolean nameEquals(final String name) {
            return name.equalsIgnoreCase(this.name);
        }

        public String generate(final SinkRecord record) {
            return docIdGenerator.apply(record);
        }

        public static final ConfigDef.Validator VALIDATOR = new ConfigDef.Validator() {
            private final ConfigDef.ValidString validator = ConfigDef.ValidString.in(names());

            @Override
            public void ensureValid(final String name, final Object value) {
                if (value instanceof String) {
                    final String lowerStringValue = ((String) value).toLowerCase(Locale.ROOT);
                    validator.ensureValid(name, lowerStringValue);
                } else {
                    validator.ensureValid(name, value);
                }
            }

            // Overridden here so that ConfigDef.toEnrichedRst shows possible values correctly
            @Override
            public String toString() {
                return validator.toString();
            }
        };
    }
}
