package com.auralink.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.auralink.config.properties.MediaAssetProperties;

class MediaAssetValuesTest {

    @Test
    void propertiesHaveConservativeStandaloneDefaults() {
        MediaAssetProperties properties = new MediaAssetProperties();

        assertThat(properties.getManagedDir()).isEqualTo("./temp_uploads/media-assets");
        assertThat(properties.getMaxUploadBytes()).isEqualTo(10L * 1024L * 1024L);
        assertThat(properties.getMaxGeneratedBytes()).isEqualTo(256L * 1024L * 1024L);
        assertThat(properties.getMaxImagePixels()).isEqualTo(40_000_000L);
        assertThat(properties.getPublicCacheSeconds()).isEqualTo(86_400L);
    }

    @Test
    void currentVocabularyIsNormalizedWithoutUsingClosedPersistenceEnums() {
        assertThat(MediaAssetValues.requireSupportedAssetType(" image "))
                .isEqualTo(MediaAssetValues.AssetType.IMAGE);
        assertThat(MediaAssetValues.requireSupportedSemanticType("generated_painting"))
                .isEqualTo(MediaAssetValues.SemanticType.GENERATED_PAINTING);
        assertThat(MediaAssetValues.requireSupportedSourceType("catalog_reference"))
                .isEqualTo(MediaAssetValues.SourceType.CATALOG_REFERENCE);
        assertThat(MediaAssetValues.requireSupportedVisibility("private"))
                .isEqualTo(MediaAssetValues.Visibility.PRIVATE);
        assertThat(MediaAssetValues.requireSupportedStatus("active"))
                .isEqualTo(MediaAssetValues.Status.ACTIVE);
    }

    @Test
    void publicUploadSemanticTypesRemainNarrowerThanInternalTypes() {
        assertThat(MediaAssetValues.requireUploadSemanticType("painting"))
                .isEqualTo(MediaAssetValues.SemanticType.PAINTING);
        assertThat(MediaAssetValues.requireUploadSemanticType("image"))
                .isEqualTo(MediaAssetValues.SemanticType.IMAGE);

        assertThatThrownBy(() -> MediaAssetValues.requireUploadSemanticType("music"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("MUSIC");
        assertThatThrownBy(() -> MediaAssetValues.requireSupportedAssetType("FUTURE_TYPE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaAssetValues.normalize("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
