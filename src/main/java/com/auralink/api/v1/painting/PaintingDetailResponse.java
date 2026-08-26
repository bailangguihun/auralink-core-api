package com.auralink.api.v1.painting;

/**
 * Complete official painting metadata. The 27 inherited source fields are
 * preserved, while the related MediaAsset is represented only by its public
 * UUID and logical API URLs.
 */
public record PaintingDetailResponse(
        String paintingId,
        String sourceSequence,
        String imageStorageName,
        String title,
        String authorName,
        String authorBirthYear,
        String authorBirthPlace,
        String authorSchool,
        String creationYear,
        String creationDynastyRaw,
        String creationDynastyNormalized,
        String actualSize,
        String collectionInstitution,
        String category,
        String subject,
        String paintingSchool,
        String style,
        String color,
        String composition,
        String artisticConception,
        String brushwork,
        String inkMethod,
        String paintingMaterial,
        String pigment,
        String seal,
        String culturalSymbol,
        String generatedText,
        String musicSceneDescription,
        String collectionPlatform,
        boolean imageAvailable,
        boolean visibleInGallery,
        String status,
        PaintingImageResponse image,
        boolean favorited) {
}
