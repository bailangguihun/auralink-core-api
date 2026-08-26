package com.auralink.creation;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.auralink.api.v1.creation.CreationSourceRequest;
import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.entity.MediaAsset;
import com.auralink.entity.Painting;
import com.auralink.entity.User;
import com.auralink.media.MediaAssetValues;
import com.auralink.repository.MediaAssetRepository;
import com.auralink.repository.PaintingRepository;
import com.auralink.workflow.WorkflowModality;

import lombok.RequiredArgsConstructor;

/** Resolves a Creation input to owned/private or official/catalog data only. */
@Service
@RequiredArgsConstructor
public class CreationSourceResolver {

    private static final String ACTIVE_PAINTING_STATUS = "ACTIVE";

    private final MediaAssetRepository mediaAssets;
    private final PaintingRepository paintings;
    private final CreationProviderProperties providerProperties;

    public ResolvedSource resolve(
            CreationSourceRequest request,
            WorkflowModality expectedModality,
            User owner) {
        if (request == null || !request.unknownFields().isEmpty()) {
            throw invalidSource();
        }
        WorkflowModality modality = parseModality(request.getModality());
        if (modality != expectedModality) {
            throw new ApiV1Exception(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    ApiErrorCode.CREATION_SOURCE_MISMATCH,
                    "创作源类型与工作流 SOURCE 不匹配");
        }
        return switch (modality) {
            case TEXT_DESCRIPTION, POEM -> resolveText(request, modality);
            case IMAGE -> resolveImage(request, modality, owner);
            case PAINTING -> resolvePainting(request, modality);
            default -> throw invalidSource();
        };
    }

    private ResolvedSource resolveText(CreationSourceRequest request, WorkflowModality modality) {
        if (!request.hasTextField() || request.hasAssetIdField() || request.hasPaintingIdField()) {
            throw invalidSource();
        }
        String text = request.getText();
        if (text == null || text.isBlank() || text.length() > providerProperties.getMaxTextChars()
                || containsUnsupportedControlCharacter(text)) {
            throw invalidSource();
        }
        return new ResolvedSource(modality, text, null, null);
    }

    private ResolvedSource resolveImage(
            CreationSourceRequest request,
            WorkflowModality modality,
            User owner) {
        if (!request.hasAssetIdField() || request.hasTextField() || request.hasPaintingIdField()) {
            throw invalidSource();
        }
        String assetId = canonicalUuidOrInvalid(request.getAssetId());
        MediaAsset asset = mediaAssets.findByPublicIdAndOwnerUser_IdAndStatusAndAssetType(
                        assetId,
                        owner.getId(),
                        MediaAssetValues.Status.ACTIVE,
                        MediaAssetValues.AssetType.IMAGE)
                .filter(candidate -> MediaAssetValues.Visibility.PRIVATE.equals(candidate.getVisibility()))
                .orElseThrow(CreationSourceResolver::invalidSource);
        return new ResolvedSource(modality, null, null, asset);
    }

    private ResolvedSource resolvePainting(CreationSourceRequest request, WorkflowModality modality) {
        if (!request.hasPaintingIdField() || request.hasTextField() || request.hasAssetIdField()) {
            throw invalidSource();
        }
        String paintingId = canonicalUuidOrInvalid(request.getPaintingId());
        Painting painting = paintings.findByPublicIdAndStatus(paintingId, ACTIVE_PAINTING_STATUS)
                .filter(this::hasUsableCatalogImage)
                .orElseThrow(CreationSourceResolver::invalidSource);
        return new ResolvedSource(modality, null, painting, null);
    }

    private boolean hasUsableCatalogImage(Painting painting) {
        MediaAsset image = painting.getImageAsset();
        return painting.isImageAvailable()
                && image != null
                && MediaAssetValues.Status.ACTIVE.equals(image.getStatus())
                && MediaAssetValues.AssetType.IMAGE.equals(image.getAssetType())
                && MediaAssetValues.SourceType.CATALOG_REFERENCE.equals(image.getSourceType())
                && MediaAssetValues.Visibility.PUBLIC.equals(image.getVisibility());
    }

    private WorkflowModality parseModality(String value) {
        try {
            return WorkflowModality.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw invalidSource();
        }
    }

    private String canonicalUuidOrInvalid(String value) {
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equals(value)) {
                throw invalidSource();
            }
            return canonical;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw invalidSource();
        }
    }

    private boolean containsUnsupportedControlCharacter(String text) {
        return text.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                && codePoint != '\n' && codePoint != '\r' && codePoint != '\t');
    }

    private static ApiV1Exception invalidSource() {
        return new ApiV1Exception(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.CREATION_SOURCE_INVALID,
                "创作源无效或不可访问");
    }

    public record ResolvedSource(
            WorkflowModality modality,
            String sourceText,
            Painting sourcePainting,
            MediaAsset sourceAsset) {
    }
}
