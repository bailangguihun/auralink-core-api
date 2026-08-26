package com.auralink.provider.qwen;

/** Versioned validated Qwen plan consumed only by the composite adapter. */
public record PaintingPromptPlan(
        String schemaVersion,
        String subject,
        String scene,
        String composition,
        String colorPalette,
        String brushwork,
        String artisticConception,
        String finalPrompt) {
}
