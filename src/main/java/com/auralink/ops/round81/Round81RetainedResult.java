package com.auralink.ops.round81;

/** Safe metadata for one retained structurally validated output. */
record Round81RetainedResult(
        String resultFile,
        String mimeType,
        long byteLength,
        String sha256,
        Integer width,
        Integer height,
        String structuralState,
        String reviewState) {
}
