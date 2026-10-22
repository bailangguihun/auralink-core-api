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
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;

public class CatalogMediaService {
   private final CatalogReadStore store;
   private final CatalogReadProperties properties;

   CatalogMediaService(CatalogReadStore store, CatalogReadProperties properties) {
      this.store = store;
      this.properties = properties;
   }

   public Optional<CatalogMediaService.CatalogMediaContent> find(String assetId) {
      return this.store.findMedia(assetId).map(this::resolve);
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
