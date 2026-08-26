package com.auralink.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Local filesystem and safety limits for the Auralink 2.0 MediaAsset layer.
 *
 * <p>The managed directory is intentionally separate from the legacy upload
 * directory contract. Catalog references continue to use
 * {@link PaintingProperties#getPictureDir()} and are never copied into this
 * directory.</p>
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "auralink.media-assets")
public class MediaAssetProperties {

    public static final long DEFAULT_MAX_UPLOAD_BYTES = 10L * 1024L * 1024L;
    public static final long DEFAULT_MAX_GENERATED_BYTES = 256L * 1024L * 1024L;
    public static final long DEFAULT_MAX_IMAGE_PIXELS = 40_000_000L;
    public static final long DEFAULT_PUBLIC_CACHE_SECONDS = 86_400L;

    @NotBlank
    private String managedDir = "./temp_uploads/media-assets";

    @Min(1)
    private long maxUploadBytes = DEFAULT_MAX_UPLOAD_BYTES;

    @Min(1)
    private long maxGeneratedBytes = DEFAULT_MAX_GENERATED_BYTES;

    @Min(1)
    private long maxImagePixels = DEFAULT_MAX_IMAGE_PIXELS;

    @Min(0)
    private long publicCacheSeconds = DEFAULT_PUBLIC_CACHE_SECONDS;
}
