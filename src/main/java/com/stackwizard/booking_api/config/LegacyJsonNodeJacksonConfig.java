package com.stackwizard.booking_api.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * Entities keep Jackson 2 {@link JsonNode} for Hibernate JSON mapping, while Spring MVC runs on Jackson 3,
 * which would otherwise serialize those trees as plain beans.
 */
@Configuration
public class LegacyJsonNodeJacksonConfig {

    @Bean
    public SimpleModule legacyJsonNodeModule() {
        ObjectMapper legacyMapper = new ObjectMapper();
        SimpleModule module = new SimpleModule("legacy-json-node");
        module.addSerializer(JsonNode.class, new ValueSerializer<JsonNode>() {
            @Override
            public void serialize(JsonNode value, JsonGenerator gen, SerializationContext ctxt) {
                gen.writeRawValue(value.toString());
            }
        });
        module.addDeserializer(JsonNode.class, new ValueDeserializer<JsonNode>() {
            @Override
            public JsonNode deserialize(JsonParser p, DeserializationContext ctxt) {
                tools.jackson.databind.JsonNode tree = ctxt.readTree(p);
                try {
                    return legacyMapper.readTree(tree.toString());
                } catch (JsonProcessingException e) {
                    throw new IllegalArgumentException("Invalid JSON value", e);
                }
            }
        });
        return module;
    }
}
