package com.auralink.service.painting;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.auralink.api.v1.painting.PaintingDetailResponse;
import com.auralink.entity.MediaAsset;
import com.auralink.entity.Painting;
import com.auralink.media.MediaAssetValues;

class PaintingResponseMapperTest {

    private final PaintingResponseMapper mapper = new PaintingResponseMapper();

    @Test
    void detailPreservesOfficialImageFieldButNeverProjectsUnsafeMediaAsset() {
        MediaAsset unavailableAsset = MediaAsset.builder()
                .publicId("00000000-0000-0000-0000-000000000002")
                .storageKey("catalog/internal-do-not-expose.jpg")
                .originalFilename("official.jpg")
                .assetType(MediaAssetValues.AssetType.IMAGE)
                .semanticType(MediaAssetValues.SemanticType.PAINTING)
                .sourceType(MediaAssetValues.SourceType.CATALOG_REFERENCE)
                .visibility(MediaAssetValues.Visibility.PRIVATE)
                .status(MediaAssetValues.Status.ACTIVE)
                .build();
        Painting painting = Painting.builder()
                .publicId("00000000-0000-0000-0000-000000000001")
                .sourceKey("painting-dataset:official")
                .sourceSequence("7")
                .imageStorageName("官方图像存储名")
                .title("山水图")
                .creationDynastyRaw("清朝")
                .creationDynastyNormalized("清代")
                .imageAsset(unavailableAsset)
                .imageAvailable(true)
                .visibleInGallery(false)
                .status("ACTIVE")
                .build();

        PaintingDetailResponse response = mapper.toDetail(painting, true);

        assertThat(response.imageStorageName()).isEqualTo("官方图像存储名");
        assertThat(response.creationDynastyNormalized()).isEqualTo("清代");
        assertThat(response.imageAvailable()).isTrue();
        assertThat(response.visibleInGallery()).isFalse();
        assertThat(response.image()).isNull();
        assertThat(response.favorited()).isTrue();
    }

    @Test
    void publicActiveMediaAssetBecomesLogicalUuidUrlsOnly() {
        MediaAsset asset = MediaAsset.builder()
                .publicId("00000000-0000-0000-0000-000000000002")
                .storageKey("catalog/internal-do-not-expose.jpg")
                .originalFilename("official.jpg")
                .mimeType("image/jpeg")
                .assetType(MediaAssetValues.AssetType.IMAGE)
                .semanticType(MediaAssetValues.SemanticType.PAINTING)
                .sourceType(MediaAssetValues.SourceType.CATALOG_REFERENCE)
                .visibility(MediaAssetValues.Visibility.PUBLIC)
                .status(MediaAssetValues.Status.ACTIVE)
                .build();
        Painting painting = Painting.builder()
                .publicId("00000000-0000-0000-0000-000000000001")
                .sourceKey("painting-dataset:official")
                .imageStorageName("official")
                .imageAsset(asset)
                .imageAvailable(true)
                .status("ACTIVE")
                .build();

        PaintingDetailResponse response = mapper.toDetail(painting, false);

        assertThat(response.image().assetId()).isEqualTo(asset.getPublicId());
        assertThat(response.image().contentUrl())
                .isEqualTo("/api/v1/assets/" + asset.getPublicId() + "/content");
        assertThat(response.image().toString()).doesNotContain("storageKey", "internal-do-not-expose");
    }
}
