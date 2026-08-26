package com.auralink.guide.provider;

import com.auralink.guide.context.PaintingGuideContext;

/** Provider-independent boundary for generating one standard Painting guide. */
public interface GuideProvider {

    GuideGenerationResult generate(String requestId, PaintingGuideContext context);
}
