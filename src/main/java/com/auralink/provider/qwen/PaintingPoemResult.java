package com.auralink.provider.qwen;

import java.util.List;

/** Strict versioned result from Painting-to-Poem generation. */
public record PaintingPoemResult(
        String schemaVersion,
        String title,
        List<String> lines,
        String text) {

    public PaintingPoemResult {
        lines = List.copyOf(lines);
    }
}
