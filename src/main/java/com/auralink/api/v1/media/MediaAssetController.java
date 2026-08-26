package com.auralink.api.v1.media;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.auralink.config.properties.MediaAssetProperties;
import com.auralink.entity.MediaAsset;
import com.auralink.media.MediaAssetValues;
import com.auralink.service.media.MediaAssetService;
import com.auralink.service.media.MediaAssetService.AccessibleMediaAssetContent;

import lombok.RequiredArgsConstructor;

/** Provider-independent public HTTP surface for Auralink 2.0 file resources. */
@RestController
@RequestMapping("/api/v1/assets")
@RequiredArgsConstructor
public class MediaAssetController {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final int MAX_DOWNLOAD_FILENAME_CHARS = 180;

    private final MediaAssetService mediaAssetService;
    private final MediaAssetProperties properties;

    @PostMapping(value = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MediaAssetResponse> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "semanticType", defaultValue = MediaAssetValues.SemanticType.IMAGE)
            String semanticType) {
        MediaAsset asset = mediaAssetService.storeAuthenticatedImage(file, semanticType);
        MediaAssetResponse response = MediaAssetResponse.from(asset);
        return ResponseEntity.created(URI.create("/api/v1/assets/" + response.assetId()))
                .body(response);
    }

    @GetMapping("/{assetId}")
    public MediaAssetResponse getMetadata(@PathVariable String assetId) {
        return MediaAssetResponse.from(mediaAssetService.getAccessibleAsset(assetId));
    }

    @GetMapping("/{assetId}/content")
    public ResponseEntity<Resource> getContent(@PathVariable String assetId) {
        return resourceResponse(mediaAssetService.getAccessibleContent(assetId), false);
    }

    @GetMapping("/{assetId}/download")
    public ResponseEntity<Resource> download(@PathVariable String assetId) {
        return resourceResponse(mediaAssetService.getAccessibleContent(assetId), true);
    }

    private ResponseEntity<Resource> resourceResponse(
            AccessibleMediaAssetContent content,
            boolean attachment) {
        MediaAsset asset = content.asset();
        boolean serveAsAttachment = attachment || !isSafeInlineType(asset);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(responseMediaType(asset, serveAsAttachment))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        contentDisposition(asset, serveAsAttachment).toString());

        String sha256 = asset.getSha256();
        if (sha256 != null && SHA256.matcher(sha256).matches()) {
            response.eTag('"' + sha256.toLowerCase(Locale.ROOT) + '"');
        }

        if (MediaAssetValues.Visibility.PUBLIC.equals(asset.getVisibility())) {
            response.cacheControl(CacheControl
                    .maxAge(Duration.ofSeconds(properties.getPublicCacheSeconds()))
                    .cachePublic());
        } else {
            response.cacheControl(CacheControl.noStore().cachePrivate())
                    .header(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION);
        }

        // A FileSystemResource is intentionally returned directly. Spring MVC's
        // Resource converters provide Accept-Ranges and correct 206/416 handling.
        return response.body(content.resource());
    }

    private ContentDisposition contentDisposition(MediaAsset asset, boolean attachment) {
        if (!attachment) {
            return ContentDisposition.inline().build();
        }
        String filename = safeDownloadFilename(asset.getOriginalFilename(), asset);
        return ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();
    }

    /**
     * Only formats whose bytes are validated or whose browser handling is passive
     * are eligible for same-origin inline rendering. Generic FILE assets (for
     * example HTML, SVG or XHTML) are always downloaded as opaque bytes.
     */
    private boolean isSafeInlineType(MediaAsset asset) {
        MediaType mediaType = safeMediaType(asset.getMimeType());
        return switch (asset.getAssetType()) {
            case MediaAssetValues.AssetType.IMAGE -> MediaType.IMAGE_JPEG.includes(mediaType)
                    || MediaType.IMAGE_PNG.includes(mediaType);
            case MediaAssetValues.AssetType.AUDIO -> "audio".equals(mediaType.getType());
            case MediaAssetValues.AssetType.VIDEO -> "video".equals(mediaType.getType());
            default -> false;
        };
    }

    private MediaType responseMediaType(MediaAsset asset, boolean attachment) {
        if (attachment && MediaAssetValues.AssetType.FILE.equals(asset.getAssetType())) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        return safeMediaType(asset.getMimeType());
    }

    private MediaType safeMediaType(String configuredType) {
        if (configuredType == null || configuredType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(configuredType);
            return mediaType.isWildcardType() || mediaType.isWildcardSubtype()
                    ? MediaType.APPLICATION_OCTET_STREAM
                    : mediaType;
        } catch (IllegalArgumentException exception) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private String safeDownloadFilename(String originalFilename, MediaAsset asset) {
        String candidate = originalFilename;
        if (candidate != null) {
            int lastSeparator = Math.max(candidate.lastIndexOf('/'), candidate.lastIndexOf('\\'));
            candidate = candidate.substring(lastSeparator + 1);
            StringBuilder sanitized = new StringBuilder(candidate.length());
            candidate.codePoints().forEach(codePoint -> {
                if (Character.isISOControl(codePoint)
                        || codePoint == '"'
                        || codePoint == ';'
                        || codePoint == '/'
                        || codePoint == '\\') {
                    sanitized.append('_');
                } else {
                    sanitized.appendCodePoint(codePoint);
                }
            });
            candidate = sanitized.toString().strip();
            while (candidate.startsWith(".")) {
                candidate = candidate.substring(1);
            }
            if (candidate.codePointCount(0, candidate.length()) > MAX_DOWNLOAD_FILENAME_CHARS) {
                candidate = candidate.substring(
                        0,
                        candidate.offsetByCodePoints(0, MAX_DOWNLOAD_FILENAME_CHARS));
            }
        }

        if (candidate == null || candidate.isBlank()) {
            candidate = asset.getPublicId() + extensionFor(asset.getMimeType());
        }
        return candidate;
    }

    private String extensionFor(String mimeType) {
        if (MediaType.IMAGE_JPEG_VALUE.equalsIgnoreCase(mimeType)) {
            return ".jpg";
        }
        if (MediaType.IMAGE_PNG_VALUE.equalsIgnoreCase(mimeType)) {
            return ".png";
        }
        if ("audio/mpeg".equalsIgnoreCase(mimeType)) {
            return ".mp3";
        }
        if ("audio/wav".equalsIgnoreCase(mimeType) || "audio/x-wav".equalsIgnoreCase(mimeType)) {
            return ".wav";
        }
        if ("video/mp4".equalsIgnoreCase(mimeType)) {
            return ".mp4";
        }
        return ".bin";
    }
}
