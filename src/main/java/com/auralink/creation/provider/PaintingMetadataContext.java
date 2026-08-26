package com.auralink.creation.provider;

/** Safe optional official Painting context without internal IDs or storage data. */
public record PaintingMetadataContext(
        String paintingId,
        String title,
        String author,
        String dynasty,
        String category,
        String subject,
        String paintingSchool,
        String style,
        String composition,
        String artisticConception,
        String generatedText,
        String musicSceneDescription) {
}
