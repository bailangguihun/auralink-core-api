package com.auralink.service.media;

import java.io.IOException;
import java.io.InputStream;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.config.properties.MediaAssetProperties;
import com.auralink.entity.MediaAsset;
import com.auralink.entity.User;
import com.auralink.exception.StorageException;
import com.auralink.media.MediaAssetValues;
import com.auralink.repository.MediaAssetRepository;
import com.auralink.repository.UserRepository;
import com.auralink.security.access.MediaAssetAccessPolicy;
import com.auralink.service.CurrentUserService;
import com.auralink.service.media.ImageContentValidator.ValidatedImage;
import com.auralink.service.media.MediaAssetStorageService.CatalogMediaFile;
import com.auralink.service.media.MediaAssetStorageService.MediaAssetStoredResource;
import com.auralink.service.media.MediaAssetStorageService.StagedMediaFile;
import com.auralink.service.media.MediaAssetStorageService.StoredMediaFile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Provider-independent business boundary for Auralink 2.0 file resources.
 *
 * <p>Legacy upload APIs intentionally do not call this service. New callers
 * exchange stable MediaAsset UUIDs and streams; no public method accepts or
 * returns an arbitrary filesystem path.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaAssetService {

    private static final DateTimeFormatter STORAGE_MONTH = DateTimeFormatter.ofPattern("yyyy/MM");
    private static final Pattern SAFE_MIME_TYPE = Pattern.compile(
            "[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+");
    private static final Pattern SAFE_EXTENSION = Pattern.compile("[a-z0-9]{1,10}");

    private static final Map<String, String> MIME_EXTENSIONS = Map.ofEntries(
            Map.entry("image/jpeg", "jpg"),
            Map.entry("image/png", "png"),
            Map.entry("audio/mpeg", "mp3"),
            Map.entry("audio/wav", "wav"),
            Map.entry("audio/x-wav", "wav"),
            Map.entry("audio/flac", "flac"),
            Map.entry("audio/ogg", "ogg"),
            Map.entry("audio/mp4", "m4a"),
            Map.entry("video/mp4", "mp4"),
            Map.entry("video/webm", "webm"),
            Map.entry("video/quicktime", "mov"),
            Map.entry("application/json", "json"),
            Map.entry("text/plain", "txt"),
            Map.entry("application/octet-stream", "bin"));

    private final MediaAssetRepository mediaAssetRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final MediaAssetAccessPolicy accessPolicy;
    private final MediaAssetStorageService storageService;
    private final ImageContentValidator imageValidator;
    private final MediaAssetProperties properties;

    /** Stores an authenticated JPEG/PNG upload as a private MediaAsset. */
    @Transactional
    public MediaAsset storeAuthenticatedImage(MultipartFile file, String semanticType) {
        User owner = currentUserService.requireCurrentUser();
        String normalizedSemantic = normalizeUploadSemanticType(semanticType);

        if (file == null || file.isEmpty()) {
            throw invalidImage("上传图片不能为空");
        }
        if (file.getSize() > properties.getMaxUploadBytes()) {
            throw assetTooLarge();
        }

        StagedMediaFile staged = null;
        String finalStorageKey = null;
        try (InputStream input = file.getInputStream()) {
            staged = storageService.stageUserUpload(input);
            ValidatedImage image = imageValidator.validateUpload(
                    staged.path(), file.getContentType(), file.getOriginalFilename());

            String publicId = UUID.randomUUID().toString();
            finalStorageKey = managedStorageKey(owner, publicId, image.fileExtension());
            StoredMediaFile stored = storageService.commitManaged(staged, finalStorageKey);
            staged = null;

            MediaAsset asset = MediaAsset.builder()
                    .publicId(publicId)
                    .ownerUser(owner)
                    .storageKey(stored.storageKey())
                    .originalFilename(safeOriginalFilename(
                            file.getOriginalFilename(), publicId, image.fileExtension()))
                    .mimeType(image.mimeType())
                    .fileSize(stored.size())
                    .sha256(stored.sha256())
                    .width(image.width())
                    .height(image.height())
                    .assetType(MediaAssetValues.AssetType.IMAGE)
                    .semanticType(normalizedSemantic)
                    .sourceType(MediaAssetValues.SourceType.USER_UPLOAD)
                    .visibility(MediaAssetValues.Visibility.PRIVATE)
                    .status(MediaAssetValues.Status.ACTIVE)
                    .build();

            return persistManagedAsset(asset, finalStorageKey);
        } catch (MediaAssetSizeLimitException exception) {
            throw assetTooLarge();
        } catch (InvalidImageContentException exception) {
            throw invalidImage("图片内容无效或格式不受支持");
        } catch (IOException exception) {
            throw assetStorageError(exception);
        } catch (StorageException exception) {
            throw assetStorageError(exception);
        } finally {
            if (staged != null) {
                storageService.discardStaged(staged);
            }
        }
    }

    /** Returns readable metadata without ever accepting an internal database ID. */
    @Transactional(readOnly = true)
    public MediaAsset getAccessibleAsset(String assetId) {
        String publicId = requireCanonicalUuid(assetId);
        MediaAsset asset = mediaAssetRepository.findByPublicId(publicId)
                .orElseThrow(MediaAssetAccessPolicy::assetNotFound);
        User currentUser = currentUserService.findCurrentUser().orElse(null);
        accessPolicy.requireReadable(asset, currentUser);
        return asset;
    }

    /** Returns a checked filesystem Resource descriptor, never a Path. */
    @Transactional(readOnly = true)
    public AccessibleMediaAssetContent getAccessibleContent(String assetId) {
        MediaAsset asset = getAccessibleAsset(assetId);
        try {
            MediaAssetStoredResource stored = storageService.resolve(asset);
            FileSystemResource resource = stored.resource();
            if (!resource.exists() || !resource.isReadable() || stored.contentLength() < 0) {
                throw MediaAssetAccessPolicy.assetNotFound();
            }
            return new AccessibleMediaAssetContent(asset, resource, stored.contentLength());
        } catch (ApiV1Exception exception) {
            throw exception;
        } catch (StorageException exception) {
            // Invalid database storage keys, missing files and inaccessible files all
            // use the same response as an unknown/private resource.
            throw MediaAssetAccessPolicy.assetNotFound();
        }
    }

    /**
     * Internal Round 5 contract: register an existing official image in place.
     * No bytes are copied into managed storage.
     */
    @Transactional
    public MediaAsset registerCatalogReference(String relativeName) {
        CatalogMediaFile catalogFile = storageService.inspectCatalogReference(relativeName);
        ValidatedImage image = imageValidator.validateTrustedImage(catalogFile.path());

        MediaAsset existing = mediaAssetRepository.findByStorageKey(catalogFile.storageKey()).orElse(null);
        if (existing != null) {
            if (!MediaAssetValues.SourceType.CATALOG_REFERENCE.equals(existing.getSourceType())) {
                throw new StorageException("Catalog storage key is already assigned to another source family");
            }
            existing.setOwnerUser(null);
            existing.setOriginalFilename(safeOriginalFilename(
                    relativeName, existing.getPublicId(), image.fileExtension()));
            existing.setMimeType(image.mimeType());
            existing.setFileSize(catalogFile.size());
            existing.setSha256(catalogFile.sha256());
            existing.setWidth(image.width());
            existing.setHeight(image.height());
            existing.setDurationSeconds(null);
            existing.setAssetType(MediaAssetValues.AssetType.IMAGE);
            existing.setSemanticType(MediaAssetValues.SemanticType.PAINTING);
            existing.setVisibility(MediaAssetValues.Visibility.PUBLIC);
            existing.setStatus(MediaAssetValues.Status.ACTIVE);
            return mediaAssetRepository.saveAndFlush(existing);
        }

        String publicId = UUID.randomUUID().toString();
        MediaAsset asset = MediaAsset.builder()
                .publicId(publicId)
                .ownerUser(null)
                .storageKey(catalogFile.storageKey())
                .originalFilename(safeOriginalFilename(
                        relativeName, publicId, image.fileExtension()))
                .mimeType(image.mimeType())
                .fileSize(catalogFile.size())
                .sha256(catalogFile.sha256())
                .width(image.width())
                .height(image.height())
                .assetType(MediaAssetValues.AssetType.IMAGE)
                .semanticType(MediaAssetValues.SemanticType.PAINTING)
                .sourceType(MediaAssetValues.SourceType.CATALOG_REFERENCE)
                .visibility(MediaAssetValues.Visibility.PUBLIC)
                .status(MediaAssetValues.Status.ACTIVE)
                .build();
        return mediaAssetRepository.saveAndFlush(asset);
    }

    /**
     * Internal Round 8/9 contract: consume provider output as a bounded stream.
     * The resource layer owns and closes the supplied stream.
     */
    @Transactional
    public MediaAsset storeGeneratedAsset(GeneratedAssetRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Generated asset request is required");
        }
        User owner = requirePersistedOwner(request.owner());
        String assetType = requireGeneratedAssetType(request.assetType());
        String semanticType = requireGeneratedSemanticType(request.semanticType());
        validateDuration(request.durationSeconds());

        StagedMediaFile staged = null;
        String finalStorageKey = null;
        try (InputStream input = request.content()) {
            staged = storageService.stageGenerated(input);
            if (staged.size() == 0) {
                throw new IllegalArgumentException("Generated asset content must not be empty");
            }

            String publicId = UUID.randomUUID().toString();
            ValidatedImage image = null;
            String mimeType;
            String extension;
            if (MediaAssetValues.AssetType.IMAGE.equals(assetType)) {
                image = imageValidator.validateTrustedImage(staged.path());
                mimeType = image.mimeType();
                requireMatchingGeneratedImageMime(request.mimeType(), mimeType);
                extension = image.fileExtension();
            } else {
                mimeType = requireSafeMimeType(request.mimeType(), assetType);
                extension = safeGeneratedExtension(request.originalFilename(), mimeType, assetType);
            }

            finalStorageKey = managedStorageKey(owner, publicId, extension);
            StoredMediaFile stored = storageService.commitManaged(staged, finalStorageKey);
            staged = null;

            MediaAsset asset = MediaAsset.builder()
                    .publicId(publicId)
                    .ownerUser(owner)
                    .storageKey(stored.storageKey())
                    .originalFilename(safeOriginalFilename(
                            request.originalFilename(), publicId, extension))
                    .mimeType(mimeType)
                    .fileSize(stored.size())
                    .sha256(stored.sha256())
                    .width(image == null ? null : image.width())
                    .height(image == null ? null : image.height())
                    .durationSeconds(request.durationSeconds())
                    .assetType(assetType)
                    .semanticType(semanticType)
                    .sourceType(MediaAssetValues.SourceType.GENERATED)
                    .visibility(MediaAssetValues.Visibility.PRIVATE)
                    .status(MediaAssetValues.Status.ACTIVE)
                    .build();
            return persistManagedAsset(asset, finalStorageKey);
        } catch (MediaAssetSizeLimitException exception) {
            throw assetTooLarge();
        } catch (InvalidImageContentException exception) {
            throw invalidImage("生成的图片内容无效或格式不受支持");
        } catch (IOException exception) {
            throw assetStorageError(exception);
        } catch (StorageException exception) {
            throw assetStorageError(exception);
        } finally {
            if (staged != null) {
                storageService.discardStaged(staged);
            }
        }
    }

    private MediaAsset persistManagedAsset(MediaAsset asset, String storageKey) {
        registerRollbackCleanup(storageKey);
        try {
            return mediaAssetRepository.saveAndFlush(asset);
        } catch (DataAccessException exception) {
            safeDeleteManaged(storageKey);
            throw assetStorageError(exception);
        } catch (RuntimeException exception) {
            safeDeleteManaged(storageKey);
            throw exception;
        }
    }

    private void registerRollbackCleanup(String storageKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    safeDeleteManaged(storageKey);
                }
            }
        });
    }

    private void safeDeleteManaged(String storageKey) {
        try {
            storageService.deleteManaged(storageKey);
        } catch (RuntimeException cleanupFailure) {
            log.warn("Unable to clean a newly stored MediaAsset after transaction rollback; type={}",
                    cleanupFailure.getClass().getSimpleName());
        }
    }

    private User requirePersistedOwner(User owner) {
        if (owner == null || owner.getId() == null) {
            throw new IllegalArgumentException("Generated asset owner must be a persisted user");
        }
        return userRepository.findById(owner.getId())
                .orElseThrow(() -> new IllegalArgumentException("Generated asset owner does not exist"));
    }

    private String normalizeUploadSemanticType(String value) {
        try {
            return MediaAssetValues.requireUploadSemanticType(
                    value == null || value.isBlank() ? MediaAssetValues.SemanticType.IMAGE : value);
        } catch (IllegalArgumentException exception) {
            throw new ApiV1Exception(
                    HttpStatus.BAD_REQUEST,
                    ApiErrorCode.UNSUPPORTED_ASSET_TYPE,
                    "上传图片语义类型不受支持");
        }
    }

    private String requireGeneratedAssetType(String value) {
        try {
            return MediaAssetValues.requireSupportedAssetType(value);
        } catch (IllegalArgumentException exception) {
            throw new ApiV1Exception(
                    HttpStatus.BAD_REQUEST,
                    ApiErrorCode.UNSUPPORTED_ASSET_TYPE,
                    "生成资源类型不受支持");
        }
    }

    private String requireGeneratedSemanticType(String value) {
        try {
            return MediaAssetValues.requireSupportedSemanticType(value);
        } catch (IllegalArgumentException exception) {
            throw new ApiV1Exception(
                    HttpStatus.BAD_REQUEST,
                    ApiErrorCode.UNSUPPORTED_ASSET_TYPE,
                    "生成资源语义类型不受支持");
        }
    }

    private String requireCanonicalUuid(String value) {
        if (value == null || value.isBlank()) {
            throw invalidAssetId();
        }
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equals(value.toLowerCase(Locale.ROOT))) {
                throw invalidAssetId();
            }
            return canonical;
        } catch (IllegalArgumentException exception) {
            throw invalidAssetId();
        }
    }

    private String managedStorageKey(User owner, String publicId, String extension) {
        return "managed/private/"
                + owner.getId() + "/"
                + YearMonth.now().format(STORAGE_MONTH) + "/"
                + publicId + "." + extension;
    }

    private String safeOriginalFilename(String supplied, String publicId, String extension) {
        if (supplied == null) {
            return publicId + "." + extension;
        }
        String basename = supplied.replace('\\', '/');
        basename = basename.substring(basename.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "")
                .trim();
        if (basename.isBlank() || ".".equals(basename) || "..".equals(basename)) {
            return publicId + "." + extension;
        }
        if (basename.length() > 512) {
            basename = basename.substring(0, 512);
        }
        return basename;
    }

    private String requireSafeMimeType(String supplied, String assetType) {
        String mimeType = supplied;
        if (mimeType == null || mimeType.isBlank()) {
            mimeType = switch (assetType) {
                case MediaAssetValues.AssetType.AUDIO -> "audio/mpeg";
                case MediaAssetValues.AssetType.VIDEO -> "video/mp4";
                default -> "application/octet-stream";
            };
        }
        mimeType = mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (mimeType.length() > 255 || !SAFE_MIME_TYPE.matcher(mimeType).matches()) {
            throw new ApiV1Exception(
                    HttpStatus.BAD_REQUEST,
                    ApiErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "生成资源媒体类型无效");
        }
        boolean typeMatches = switch (assetType) {
            case MediaAssetValues.AssetType.AUDIO -> mimeType.startsWith("audio/");
            case MediaAssetValues.AssetType.VIDEO -> mimeType.startsWith("video/");
            case MediaAssetValues.AssetType.FILE -> true;
            default -> false;
        };
        if (!typeMatches) {
            throw new ApiV1Exception(
                    HttpStatus.BAD_REQUEST,
                    ApiErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "生成资源媒体类型与资源类型不匹配");
        }
        return mimeType;
    }

    private void requireMatchingGeneratedImageMime(String supplied, String detected) {
        if (supplied == null || supplied.isBlank()) {
            return;
        }
        String normalized = supplied.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!detected.equals(normalized)) {
            throw new ApiV1Exception(
                    HttpStatus.BAD_REQUEST,
                    ApiErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "生成图片媒体类型与实际内容不匹配");
        }
    }

    private String safeGeneratedExtension(String originalFilename, String mimeType, String assetType) {
        String mapped = MIME_EXTENSIONS.get(mimeType);
        if (mapped != null) {
            return mapped;
        }
        if (originalFilename != null) {
            String basename = originalFilename.replace('\\', '/');
            basename = basename.substring(basename.lastIndexOf('/') + 1);
            int dot = basename.lastIndexOf('.');
            if (dot >= 0 && dot < basename.length() - 1) {
                String extension = basename.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (SAFE_EXTENSION.matcher(extension).matches()) {
                    return extension;
                }
            }
        }
        return switch (assetType) {
            case MediaAssetValues.AssetType.AUDIO -> "audio";
            case MediaAssetValues.AssetType.VIDEO -> "video";
            default -> "bin";
        };
    }

    private void validateDuration(Double durationSeconds) {
        if (durationSeconds != null
                && (!Double.isFinite(durationSeconds) || durationSeconds < 0)) {
            throw new IllegalArgumentException("Generated asset duration must be finite and non-negative");
        }
    }

    private ApiV1Exception invalidAssetId() {
        return new ApiV1Exception(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.INVALID_ASSET_ID,
                "资源标识格式无效");
    }

    private ApiV1Exception invalidImage(String message) {
        return new ApiV1Exception(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_IMAGE, message);
    }

    private ApiV1Exception assetTooLarge() {
        return new ApiV1Exception(
                HttpStatus.PAYLOAD_TOO_LARGE,
                ApiErrorCode.ASSET_TOO_LARGE,
                "资源大小超过允许上限");
    }

    private ApiV1Exception assetStorageError(Exception cause) {
        // The exception cause is intentionally not included in the public message.
        log.error("MediaAsset storage operation failed; type={}",
                cause.getClass().getSimpleName());
        return new ApiV1Exception(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ApiErrorCode.ASSET_STORAGE_ERROR,
                "资源存储失败");
    }

    public record AccessibleMediaAssetContent(
            MediaAsset asset,
            FileSystemResource resource,
            long contentLength) {
    }
}
