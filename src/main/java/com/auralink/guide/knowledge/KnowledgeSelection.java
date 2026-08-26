package com.auralink.guide.knowledge;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record KnowledgeSelection(
        List<KnowledgeItem> items,
        Map<String, String> fingerprints
) {

    public KnowledgeSelection {
        items = items == null ? List.of() : List.copyOf(items);
        fingerprints = fingerprints == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new TreeMap<>(fingerprints));
    }
}
