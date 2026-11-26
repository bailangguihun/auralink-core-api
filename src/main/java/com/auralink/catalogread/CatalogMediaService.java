package com.auralink.catalogread;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;

public class CatalogMediaService {
   public static final String THUMBNAIL_ROUTE = "/public-catalog-thumbnails/v1/{assetId}/{sourceHash}.jpg";
   private static final String THUMBNAIL_BASE = "/api/v1/assets/public-catalog-thumbnails/v1/";
   private static final Pattern ASSET_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
   private static final Pattern SOURCE_HASH = Pattern.compile("[0-9a-f]{64}");
   private static final int MAX_CACHED_THUMBNAILS = 96;
   private static final long THUMBNAIL_QUEUE_TIMEOUT_MS = 450;
   private final Map<String, ThumbnailContent> thumbnails = Collections.synchronizedMap(
      new LinkedHashMap<>(16, 0.75f, true) {
         @Override
         protected boolean removeEldestEntry(Map.Entry<String, ThumbnailContent> eldest) {
            return this.size() > MAX_CACHED_THUMBNAILS;
         }
      }
   );
   private final Semaphore thumbnailDecoders = new Semaphore(4, true);
   private final CatalogReadStore store;
   private final CatalogReadProperties properties;

   CatalogMediaService(CatalogReadStore store, CatalogReadProperties properties) {
      this.store = store;
      this.properties = properties;
   }

   public Optional<CatalogMediaService.CatalogMediaContent> find(String assetId) {
      return this.store.findMedia(assetId).map(this::resolve);
   }

   public static String thumbnailPath(CatalogReadStore.CatalogMediaAsset asset) {
      String hash = asset.contentSha256() == null ? "" : asset.contentSha256().toLowerCase(Locale.ROOT);
      if (!"image/jpeg".equalsIgnoreCase(asset.mimeType()) || !ASSET_ID.matcher(asset.publicId()).matches()
         || !SOURCE_HASH.matcher(hash).matches()) {
         return null;
      }
      return THUMBNAIL_BASE + asset.publicId() + "/" + hash + ".jpg";
   }

   public Optional<ThumbnailContent> findThumbnail(String assetId, String sourceHash) {
      if (!ASSET_ID.matcher(assetId).matches() || !SOURCE_HASH.matcher(sourceHash).matches()) {
         return Optional.empty();
      }
      // Check the active public registry on every miss at the edge, even if the
      // rendered bytes are already in this process. Private media is never consulted.
      var asset = this.store.findMedia(assetId).orElse(null);
      String path = THUMBNAIL_BASE + assetId + "/" + sourceHash + ".jpg";
      if (asset == null || !path.equals(thumbnailPath(asset))) {
         return Optional.empty();
      }
      ThumbnailContent cached = this.thumbnails.get(path);
      if (cached != null) {
         return Optional.of(cached);
      }

      try {
         if (!this.thumbnailDecoders.tryAcquire(THUMBNAIL_QUEUE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw unavailable();
         }
      } catch (InterruptedException exception) {
         Thread.currentThread().interrupt();
         throw unavailable();
      }
      try {
         cached = this.thumbnails.get(path);
         if (cached != null) {
            return Optional.of(cached);
         }
         byte[] bytes = CatalogThumbnail.render(this.resolve(asset));
         if (bytes == null) {
            return Optional.empty();
         }
         var thumbnail = new ThumbnailContent(bytes, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
         this.thumbnails.put(path, thumbnail);
         return Optional.of(thumbnail);
      } catch (IOException | IllegalArgumentException exception) {
         return Optional.empty();
      } catch (NoSuchAlgorithmException exception) {
         throw unavailable();
      } finally {
         this.thumbnailDecoders.release();
      }
   }

   public record ThumbnailContent(byte[] bytes, String sha256) {
   }

   private CatalogMediaService.CatalogMediaContent resolve(CatalogReadStore.CatalogMediaAsset asset) {
      CatalogReadProperties.BatchMediaRoot configuredRoot = this.properties
         .getBatchMediaRoots()
         .stream()
         .filter(root -> asset.sourceBatchId().equalsIgnoreCase(root.getBatchId()) && asset.sourceRevision() == root.getRevision())
         .findFirst()
         .orElseThrow(CatalogMediaService::unavailable);

      try {
         Path batchRoot = Path.of(configuredRoot.getRoot()).toRealPath();
         Path imageRoot = batchRoot.resolve("images").resolve("original");
         Path realImageRoot = imageRoot.toRealPath();
         if (!realImageRoot.startsWith(batchRoot)) {
            throw unavailable();
         }
         Path relative = Path.of(asset.relativePath());
         if (!relative.isAbsolute() && !relative.normalize().startsWith("..")) {
            Path candidate = imageRoot.resolve(relative).normalize();
            if (candidate.startsWith(imageRoot) && !Files.isSymbolicLink(candidate) && Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
               // Resolve intermediate links before containment checks and open
               // the canonical file, not the original path through those links.
               Path resolved = candidate.toRealPath();
               if (!resolved.startsWith(realImageRoot)) {
                  throw unavailable();
               } else {
                  long size = Files.size(resolved);
                  if (!asset.contentSha256().equalsIgnoreCase(sha256(resolved))) {
                     throw unavailable();
                  } else {
                     return new CatalogMediaService.CatalogMediaContent(asset, new FileSystemResource(resolved), size);
                  }
               }
            } else {
               throw unavailable();
            }
         } else {
            throw unavailable();
         }
      } catch (ApiV1Exception exception) {
         throw exception;
      } catch (IOException | NoSuchAlgorithmException | RuntimeException exception) {
         throw unavailable();
      }
   }

   private static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");

      try (InputStream input = Files.newInputStream(path)) {
         byte[] buffer = new byte[65536];

         int read;
         while ((read = input.read(buffer)) >= 0) {
            if (read > 0) {
               digest.update(buffer, 0, read);
            }
         }
      }

      return HexFormat.of().formatHex(digest.digest());
   }

   private static ApiV1Exception unavailable() {
      return new ApiV1Exception(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.CATALOG_READ_UNAVAILABLE, "画作目录媒体暂时不可用");
   }

   public record CatalogMediaContent(CatalogReadStore.CatalogMediaAsset asset, FileSystemResource resource, long size) {
      public String filename() {
         Path path = Path.of(this.asset.relativePath());
         Path name = path.getFileName();
         return name == null ? this.asset.publicId() : name.toString();
      }

      public String mimeType() {
         return this.asset.mimeType().toLowerCase(Locale.ROOT);
      }
   }
}
