package com.auralink.catalog;

import java.util.Map;

import org.springframework.stereotype.Component;

/** Conservative dynasty aliases for stable filtering while retaining raw text. */
@Component
public class DynastyNormalizer {

    private static final Map<String, String> EXACT_ALIASES = Map.ofEntries(
            Map.entry("唐", "唐代"),
            Map.entry("唐朝", "唐代"),
            Map.entry("宋", "宋代"),
            Map.entry("宋朝", "宋代"),
            Map.entry("元", "元代"),
            Map.entry("元朝", "元代"),
            Map.entry("明", "明代"),
            Map.entry("明朝", "明代"),
            Map.entry("清", "清代"),
            Map.entry("清朝", "清代"),
            Map.entry("民国时期", "民国"),
            Map.entry("民国年间", "民国"));

    public String normalize(String rawDynasty) {
        String normalized = rawDynasty == null
                ? ""
                : rawDynasty.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            return null;
        }
        return EXACT_ALIASES.getOrDefault(normalized, normalized);
    }
}
