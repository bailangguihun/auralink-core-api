package com.auralink.guide.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Versioned, provider-independent structured result persisted in painting_guides. */
@JsonPropertyOrder({"schemaVersion", "summary", "sections", "highlights", "knowledgeReferences"})
public record GuideResult(
        String schemaVersion,
        String summary,
        GuideSections sections,
        List<String> highlights,
        List<GuideKnowledgeReference> knowledgeReferences) {
}
