package com.creatorcrm.llm;

import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON schemas for Claude's structured outputs, and parsing of the replies.
 *
 * <p>We don't use the Anthropic SDK's {@code StructuredOutputConfig.format(Class)}: it needs jsonschema-generator 4.x
 * (Jackson 2), while Spring AI needs 5.x (Jackson 3), and only one can be on the classpath. Building the schema
 * here with 5.x and passing it as plain JSON avoids the clash.
 */
final class OutputSchemas {

    private static final SchemaGenerator GENERATOR;
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private static final Map<Class<?>, Map<String, Object>> CACHE = new ConcurrentHashMap<>();

    static {
        SchemaGeneratorConfigBuilder config = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                .with(new JacksonModule())                          // @JsonPropertyDescription -> "description"
                .with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT) // structured outputs require it
                .without(Option.SCHEMA_VERSION_INDICATOR);
        config.forFields().withRequiredCheck(field -> true);       // every field always present
        GENERATOR = new SchemaGenerator(config.build());
    }

    private OutputSchemas() {}

    /** The JSON schema for {@code type}, as plain maps/lists ready to hand to the SDK. */
    static Map<String, Object> of(Class<?> type) {
        return CACHE.computeIfAbsent(type, t ->
                JSON.convertValue(GENERATOR.generateSchema(t), new TypeReference<Map<String, Object>>() {}));
    }

    static <T> T parse(String json, Class<T> type) {
        return JSON.readValue(json, type);
    }
}
