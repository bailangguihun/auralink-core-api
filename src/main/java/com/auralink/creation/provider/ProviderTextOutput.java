package com.auralink.creation.provider;

import java.util.List;

/** Strict four-line Painting-to-Poem output for future ROUND 9 persistence. */
public record ProviderTextOutput(
        String schemaVersion,
        String title,
        List<String> lines,
        String text) implements ProviderOutput {

    public ProviderTextOutput {
        lines = List.copyOf(lines);
    }
}
