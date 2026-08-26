package com.auralink.provider.validation;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Narrow strict reader for untrusted provider JSON; it does not mutate the application mapper. */
public final class StrictProviderJson {

    private StrictProviderJson() {
    }

    public static JsonNode parse(ObjectMapper mapper, String json) throws IOException {
        return mapper.readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .readValue(json);
    }

    public static JsonNode parse(ObjectMapper mapper, byte[] json) throws IOException {
        return mapper.readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .readValue(json);
    }
}
