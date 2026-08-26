package com.auralink.provider.qwen;

import java.util.Objects;

/**
 * Ephemeral internal handoff from transport to the strict result validator.
 * Its content is never diagnostic data and its string representation is redacted.
 */
record QwenResponseContent(String content, QwenResponseShapeDiagnostic responseShape) {

    QwenResponseContent {
        content = Objects.requireNonNull(content, "content");
        responseShape = Objects.requireNonNull(responseShape, "responseShape");
    }

    @Override
    public String toString() {
        return "QwenResponseContent[content=REDACTED,responseShape=" + responseShape + "]";
    }
}
