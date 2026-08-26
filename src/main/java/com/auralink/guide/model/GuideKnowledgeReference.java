package com.auralink.guide.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Public-safe citation to one knowledge item supplied to the Guide provider. */
@JsonPropertyOrder({"sourceId", "sourceType", "title"})
public record GuideKnowledgeReference(
        String sourceId,
        String sourceType,
        String title) {
}
