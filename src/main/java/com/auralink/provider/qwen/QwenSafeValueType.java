package com.auralink.provider.qwen;

import com.fasterxml.jackson.databind.JsonNode;

/** Small allowlist of value-shape tokens; it never includes a value or field name. */
public enum QwenSafeValueType {
    OBJECT,
    ARRAY,
    STRING,
    NUMBER,
    BOOLEAN,
    NULL,
    BINARY,
    OTHER;

    static QwenSafeValueType from(JsonNode value) {
        if (value == null || value.isNull()) {
            return NULL;
        }
        if (value.isObject()) {
            return OBJECT;
        }
        if (value.isArray()) {
            return ARRAY;
        }
        if (value.isTextual()) {
            return STRING;
        }
        if (value.isNumber()) {
            return NUMBER;
        }
        if (value.isBoolean()) {
            return BOOLEAN;
        }
        if (value.isBinary()) {
            return BINARY;
        }
        return OTHER;
    }
}
