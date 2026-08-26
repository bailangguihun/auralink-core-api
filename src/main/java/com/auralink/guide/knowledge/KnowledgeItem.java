package com.auralink.guide.knowledge;

public record KnowledgeItem(
        String sourceId,
        String sourceType,
        String title,
        String content
) {
}
