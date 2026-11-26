package com.auralink.api.v1.media;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.catalogcritic.CatalogPrivateMediaAsset;
import com.auralink.catalogcritic.CatalogPrivateMediaService;
import com.auralink.catalogcritic.PostgresPrivateMediaUploadService;
import com.auralink.catalogread.CatalogMediaService;
import com.auralink.config.properties.MediaAssetProperties;
import com.auralink.entity.MediaAsset;
import com.auralink.service.CurrentUserService;
import com.auralink.service.media.MediaAssetService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseEntity.BodyBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/assets")
public class MediaAssetController {
   private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
   private static final int MAX_DOWNLOAD_FILENAME_CHARS = 180;
   private final MediaAssetService mediaAssetService;
   private final MediaAssetProperties properties;
   private final ObjectProvider<CatalogMediaService> catalogMediaServiceProvider;
   private final ObjectProvider<CatalogPrivateMediaService> catalogPrivateMediaServiceProvider;
   private final ObjectProvider<PostgresPrivateMediaUploadService> privateMediaUploadServiceProvider;
   private final CurrentUserService currentUserService;
   private final Environment environment;

   @Autowired
   public MediaAssetController(
      MediaAssetService mediaAssetService,
      MediaAssetProperties properties,
      ObjectProvider<CatalogMediaService> catalogMediaServiceProvider,
      ObjectProvider<CatalogPrivateMediaService> catalogPrivateMediaServiceProvider,
      ObjectProvider<PostgresPrivateMediaUploadService> privateMediaUploadServiceProvider,
      CurrentUserService currentUserService,
      Environment environment
   ) {
      this.mediaAssetService = mediaAssetService;
      this.properties = properties;
      this.catalogMediaServiceProvider = catalogMediaServiceProvider;
      this.catalogPrivateMediaServiceProvider = catalogPrivateMediaServiceProvider;
      this.privateMediaUploadServiceProvider = privateMediaUploadServiceProvider;
      this.currentUserService = currentUserService;
      this.environment = environment;
   }

   public MediaAssetController(
      MediaAssetService mediaAssetService,
      MediaAssetProperties properties,
      ObjectProvider<CatalogMediaService> catalogMediaServiceProvider,
      ObjectProvider<CatalogPrivateMediaService> catalogPrivateMediaServiceProvider,
      ObjectProvider<PostgresPrivateMediaUploadService> privateMediaUploadServiceProvider,
      CurrentUserService currentUserService
   ) {
      this(
         mediaAssetService,
         properties,
         catalogMediaServiceProvider,
         catalogPrivateMediaServiceProvider,
         privateMediaUploadServiceProvider,
         currentUserService,
         new StandardEnvironment()
      );
   }

   @PostMapping(value = "/uploads", consumes = "multipart/form-data")
   public ResponseEntity<MediaAssetResponse> uploadImage(
      @RequestParam("file") MultipartFile file, @RequestParam(name = "semanticType", defaultValue = "IMAGE") String semanticType
   ) {
      if (this.catalogPrivateMediaServiceProvider.getIfAvailable() != null) {
         PostgresPrivateMediaUploadService upload = (PostgresPrivateMediaUploadService)this.privateMediaUploadServiceProvider.getIfAvailable();
         if (upload != null) {
            CatalogPrivateMediaAsset asset = upload.storeAuthenticatedImage(file, semanticType);
            MediaAssetResponse response = MediaAssetResponse.fromCatalogPrivate(asset);
            return ResponseEntity.created(URI.create("/api/v1/assets/" + response.assetId())).body(response);
         }

         if (!Arrays.asList(this.environment.getActiveProfiles()).contains("catalog-postgres-creation-integration")) {
            throw new ApiV1Exception(HttpStatus.CONFLICT, ApiErrorCode.CATALOG_READ_WRITE_UNSUPPORTED, "当前隔离 Critic 配置未启用媒体上传");
         }
      }

      MediaAsset asset = this.mediaAssetService.storeAuthenticatedImage(file, semanticType);
      MediaAssetResponse response = MediaAssetResponse.from(asset);
      return ResponseEntity.created(URI.create("/api/v1/assets/" + response.assetId())).body(response);
   }

   @GetMapping("/{assetId}")
   public MediaAssetResponse getMetadata(@PathVariable String assetId) {
      CatalogMediaService.CatalogMediaContent catalogContent = this.catalogContent(assetId);
      if (catalogContent != null) {
         return MediaAssetResponse.fromCatalog(catalogContent);
      }

      CatalogPrivateMediaService privateMedia = (CatalogPrivateMediaService)this.catalogPrivateMediaServiceProvider.getIfAvailable();
      return privateMedia != null && privateMedia.owns(assetId)
         ? MediaAssetResponse.fromCatalogPrivate(privateMedia.accessible(assetId, this.currentUserService.findCurrentUser().orElse(null)))
         : MediaAssetResponse.from(this.mediaAssetService.getAccessibleAsset(assetId));
   }

   @GetMapping(CatalogMediaService.THUMBNAIL_ROUTE)
   public ResponseEntity<byte[]> getPublicCatalogThumbnail(@PathVariable String assetId, @PathVariable String sourceHash) {
      CatalogMediaService service = this.catalogMediaServiceProvider.getIfAvailable();
      try {
         var thumbnail = service == null ? null : service.findThumbnail(assetId, sourceHash).orElse(null);
         if (thumbnail != null) {
            return ResponseEntity.ok()
               .contentType(MediaType.IMAGE_JPEG)
               .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic().immutable())
               .eTag(thumbnail.sha256())
               .body(thumbnail.bytes());
         }
      } catch (ApiV1Exception exception) {
         return ResponseEntity.status(exception.getStatus()).cacheControl(CacheControl.noStore()).build();
      }
      return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore()).build();
   }

   @GetMapping("/{assetId}/content")
   public ResponseEntity<Resource> getContent(@PathVariable String assetId) {
      CatalogMediaService.CatalogMediaContent catalogContent = this.catalogContent(assetId);
      if (catalogContent != null) {
         return this.catalogResourceResponse(catalogContent, false);
      }

      CatalogPrivateMediaService privateMedia = (CatalogPrivateMediaService)this.catalogPrivateMediaServiceProvider.getIfAvailable();
      return privateMedia != null && privateMedia.owns(assetId)
         ? this.privateResourceResponse(privateMedia.content(assetId, this.currentUserService.findCurrentUser().orElse(null)), false)
         : this.resourceResponse(this.mediaAssetService.getAccessibleContent(assetId), false);
   }

   @GetMapping("/{assetId}/download")
   public ResponseEntity<Resource> download(@PathVariable String assetId) {
      CatalogMediaService.CatalogMediaContent catalogContent = this.catalogContent(assetId);
      if (catalogContent != null) {
         return this.catalogResourceResponse(catalogContent, true);
      }

      CatalogPrivateMediaService privateMedia = (CatalogPrivateMediaService)this.catalogPrivateMediaServiceProvider.getIfAvailable();
      return privateMedia != null && privateMedia.owns(assetId)
         ? this.privateResourceResponse(privateMedia.content(assetId, this.currentUserService.findCurrentUser().orElse(null)), true)
         : this.resourceResponse(this.mediaAssetService.getAccessibleContent(assetId), true);
   }

   private CatalogMediaService.CatalogMediaContent catalogContent(String assetId) {
      CatalogMediaService service = (CatalogMediaService)this.catalogMediaServiceProvider.getIfAvailable();
      return service == null ? null : service.find(assetId).orElse(null);
   }

   private ResponseEntity<Resource> catalogResourceResponse(CatalogMediaService.CatalogMediaContent content, boolean attachment) {
      MediaType type = this.safeMediaType(content.mimeType());
      BodyBuilder response = (BodyBuilder)((BodyBuilder)ResponseEntity.ok()
            .contentType(type)
            .header(
               "Content-Disposition",
               new String[]{
                  (attachment ? ContentDisposition.attachment() : ContentDisposition.inline())
                     .filename(content.filename(), StandardCharsets.UTF_8)
                     .build()
                     .toString()
               }
            ))
         .cacheControl(CacheControl.maxAge(Duration.ofSeconds(this.properties.getPublicCacheSeconds())).cachePublic());
      String sha256 = content.asset().contentSha256();
      if (sha256 != null && SHA256.matcher(sha256).matches()) {
         response.eTag("\"" + sha256.toLowerCase(Locale.ROOT) + "\"");
      }

      return response.body(content.resource());
   }

   private ResponseEntity<Resource> resourceResponse(MediaAssetService.AccessibleMediaAssetContent content, boolean attachment) {
      MediaAsset asset = content.asset();
      boolean serveAsAttachment = attachment || !this.isSafeInlineType(asset);
      BodyBuilder response = (BodyBuilder)ResponseEntity.ok()
         .contentType(this.responseMediaType(asset, serveAsAttachment))
         .header("Content-Disposition", new String[]{this.contentDisposition(asset, serveAsAttachment).toString()});
      String sha256 = asset.getSha256();
      if (sha256 != null && SHA256.matcher(sha256).matches()) {
         response.eTag("\"" + sha256.toLowerCase(Locale.ROOT) + "\"");
      }

      if ("PUBLIC".equals(asset.getVisibility())) {
         response.cacheControl(CacheControl.maxAge(Duration.ofSeconds(this.properties.getPublicCacheSeconds())).cachePublic());
      } else {
         ((BodyBuilder)response.cacheControl(CacheControl.noStore().cachePrivate())).header("Vary", new String[]{"Authorization"});
      }

      return response.body(content.resource());
   }

   private ResponseEntity<Resource> privateResourceResponse(CatalogPrivateMediaService.AccessibleContent content, boolean attachment) {
      MediaAsset asset = this.asEntity(content.asset());
      boolean serveAsAttachment = attachment || !this.isSafeInlineType(asset);
      BodyBuilder response = (BodyBuilder)((BodyBuilder)((BodyBuilder)ResponseEntity.ok()
               .contentType(this.responseMediaType(asset, serveAsAttachment))
               .header("Content-Disposition", new String[]{this.contentDisposition(asset, serveAsAttachment).toString()}))
            .cacheControl(CacheControl.noStore().cachePrivate()))
         .header("Vary", new String[]{"Authorization"});
      String sha256 = content.asset().contentSha256();
      if (sha256 != null && SHA256.matcher(sha256).matches()) {
         response.eTag("\"" + sha256.toLowerCase(Locale.ROOT) + "\"");
      }

      return response.body(content.resource());
   }

   private MediaAsset asEntity(CatalogPrivateMediaAsset asset) {
      return MediaAsset.builder()
         .publicId(asset.publicId())
         .storageKey(asset.storageKey())
         .originalFilename(asset.originalFilename())
         .mimeType(asset.mimeType())
         .fileSize(asset.fileSize())
         .sha256(asset.contentSha256())
         .width(asset.width())
         .height(asset.height())
         .durationSeconds(asset.durationSeconds())
         .assetType(asset.assetType())
         .semanticType(asset.semanticType())
         .sourceType(asset.sourceType())
         .visibility(asset.visibility())
         .status(asset.status())
         .createdAt(asset.createdAt())
         .updatedAt(asset.updatedAt())
         .build();
   }

   private ContentDisposition contentDisposition(MediaAsset asset, boolean attachment) {
      if (!attachment) {
         return ContentDisposition.inline().build();
      }

      String filename = this.safeDownloadFilename(asset.getOriginalFilename(), asset);
      return ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build();
   }

   private boolean isSafeInlineType(MediaAsset asset) {
      MediaType mediaType = this.safeMediaType(asset.getMimeType());

      return switch (asset.getAssetType()) {
         case "IMAGE" -> MediaType.IMAGE_JPEG.includes(mediaType) || MediaType.IMAGE_PNG.includes(mediaType);
         case "AUDIO" -> "audio".equals(mediaType.getType());
         case "VIDEO" -> "video".equals(mediaType.getType());
         default -> false;
      };
   }

   private MediaType responseMediaType(MediaAsset asset, boolean attachment) {
      return attachment && "FILE".equals(asset.getAssetType()) ? MediaType.APPLICATION_OCTET_STREAM : this.safeMediaType(asset.getMimeType());
   }

   private MediaType safeMediaType(String configuredType) {
      if (configuredType != null && !configuredType.isBlank()) {
         try {
            MediaType mediaType = MediaType.parseMediaType(configuredType);
            return !mediaType.isWildcardType() && !mediaType.isWildcardSubtype() ? mediaType : MediaType.APPLICATION_OCTET_STREAM;
         } catch (IllegalArgumentException exception) {
            return MediaType.APPLICATION_OCTET_STREAM;
         }
      } else {
         return MediaType.APPLICATION_OCTET_STREAM;
      }
   }

   private String safeDownloadFilename(String originalFilename, MediaAsset asset) {
      String candidate = originalFilename;
      if (candidate != null) {
         int lastSeparator = Math.max(candidate.lastIndexOf(47), candidate.lastIndexOf(92));
         candidate = candidate.substring(lastSeparator + 1);
         StringBuilder sanitized = new StringBuilder(candidate.length());
         candidate.codePoints().forEach(codePoint -> {
            if (!Character.isISOControl(codePoint) && codePoint != 34 && codePoint != 59 && codePoint != 47 && codePoint != 92) {
               sanitized.appendCodePoint(codePoint);
            } else {
               sanitized.append('_');
            }
         });
         candidate = sanitized.toString().strip();

         while (candidate.startsWith(".")) {
            candidate = candidate.substring(1);
         }

         if (candidate.codePointCount(0, candidate.length()) > 180) {
            candidate = candidate.substring(0, candidate.offsetByCodePoints(0, 180));
         }
      }

      if (candidate == null || candidate.isBlank()) {
         candidate = asset.getPublicId() + this.extensionFor(asset.getMimeType());
      }

      return candidate;
   }

   private String extensionFor(String mimeType) {
      if ("image/jpeg".equalsIgnoreCase(mimeType)) {
         return ".jpg";
      } else if ("image/png".equalsIgnoreCase(mimeType)) {
         return ".png";
      } else if ("audio/mpeg".equalsIgnoreCase(mimeType)) {
         return ".mp3";
      } else if ("audio/wav".equalsIgnoreCase(mimeType) || "audio/x-wav".equalsIgnoreCase(mimeType)) {
         return ".wav";
      } else {
         return "video/mp4".equalsIgnoreCase(mimeType) ? ".mp4" : ".bin";
      }
   }
}
