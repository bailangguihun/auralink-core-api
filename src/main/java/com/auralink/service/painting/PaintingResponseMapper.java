package com.auralink.service.painting;

import org.springframework.stereotype.Component;

import com.auralink.api.v1.painting.PaintingDetailResponse;
import com.auralink.api.v1.painting.PaintingImageResponse;
import com.auralink.api.v1.painting.PaintingSummaryResponse;
import com.auralink.entity.MediaAsset;
import com.auralink.entity.Painting;
import com.auralink.media.MediaAssetValues;

/** Maps official entities to provider- and filesystem-independent v1 DTOs. */
@Component
public class PaintingResponseMapper {

    public PaintingSummaryResponse toSummary(Painting painting, boolean favorited) {
        PaintingImageResponse image = safeImage(painting);
        return new PaintingSummaryResponse(
                painting.getPublicId(),
                painting.getTitle(),
                painting.getAuthorName(),
                painting.getCreationDynastyRaw(),
                painting.getCreationDynastyNormalized(),
                painting.getCategory(),
                painting.getSubject(),
                painting.getPaintingSchool(),
                painting.getStyle(),
                painting.getArtisticConception(),
                painting.isImageAvailable(),
                image,
                favorited);
    }

    public PaintingDetailResponse toDetail(Painting painting, boolean favorited) {
        PaintingImageResponse image = safeImage(painting);
        return new PaintingDetailResponse(
                painting.getPublicId(),
                painting.getSourceSequence(),
                painting.getImageStorageName(),
                painting.getTitle(),
                painting.getAuthorName(),
                painting.getAuthorBirthYear(),
                painting.getAuthorBirthPlace(),
                painting.getAuthorSchool(),
                painting.getCreationYear(),
                painting.getCreationDynastyRaw(),
                painting.getCreationDynastyNormalized(),
                painting.getActualSize(),
                painting.getCollectionInstitution(),
                painting.getCategory(),
                painting.getSubject(),
                painting.getPaintingSchool(),
                painting.getStyle(),
                painting.getColor(),
                painting.getComposition(),
                painting.getArtisticConception(),
                painting.getBrushwork(),
                painting.getInkMethod(),
                painting.getPaintingMaterial(),
                painting.getPigment(),
                painting.getSeal(),
                painting.getCulturalSymbol(),
                painting.getGeneratedText(),
                painting.getMusicSceneDescription(),
                painting.getCollectionPlatform(),
                painting.isImageAvailable(),
                painting.isVisibleInGallery(),
                painting.getStatus(),
                image,
                favorited);
    }

    private PaintingImageResponse safeImage(Painting painting) {
        MediaAsset asset = painting.getImageAsset();
        if (!painting.isImageAvailable()
                || asset == null
                || asset.getPublicId() == null
                || asset.getPublicId().isBlank()
                || !MediaAssetValues.Status.ACTIVE.equals(asset.getStatus())
                || !MediaAssetValues.Visibility.PUBLIC.equals(asset.getVisibility())) {
            return null;
        }

        String baseUrl = "/api/v1/assets/" + asset.getPublicId();
        return new PaintingImageResponse(
                asset.getPublicId(),
                asset.getMimeType(),
                asset.getFileSize(),
                asset.getWidth(),
                asset.getHeight(),
                baseUrl + "/content",
                baseUrl + "/download");
    }
}
