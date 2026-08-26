package com.auralink.guide.provider;

import com.auralink.guide.model.GuideResult;

/** Validated provider result. Provider identity and trace data are deliberately absent. */
public record GuideGenerationResult(
        String requestId,
        GuideResult result) {
}
