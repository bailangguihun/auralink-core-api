package com.auralink.catalogread;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Optional disk cache of versioned derivatives only. Width and height describe the oriented source. */
final class CatalogThumbnailCache {
   private static final Logger log = LoggerFactory.getLogger(CatalogThumbnailCache.class);
   private final Path directory;

   CatalogThumbnailCache(String directory) {
      this.directory = directory == null || directory.isBlank() ? null : Path.of(directory).resolve("catalog-v2");
   }

   CatalogMediaService.ThumbnailDimensions dimensions(String key) {
      if (this.directory == null) return null;
      try (var input = open(key)) {
         return input == null ? null : readDimensions(input);
      } catch (IOException exception) {
         return null;
      }
   }

   CatalogThumbnail.Preview read(String key) {
      if (this.directory == null) return null;
      try (var input = open(key)) {
         if (input == null) return null;
         var dimensions = readDimensions(input);
         byte[] jpeg = input.readNBytes(CatalogThumbnail.MAX_THUMBNAIL_BYTES + 1);
         if (jpeg.length < 4 || jpeg.length > CatalogThumbnail.MAX_THUMBNAIL_BYTES
             || jpeg[0] != (byte) 0xff || jpeg[1] != (byte) 0xd8
             || jpeg[jpeg.length - 2] != (byte) 0xff || jpeg[jpeg.length - 1] != (byte) 0xd9) return null;
         return new CatalogThumbnail.Preview(jpeg, dimensions.width(), dimensions.height());
      } catch (IOException exception) {
         return null;
      }
   }

   void write(String key, CatalogThumbnail.Preview thumbnail) {
      if (this.directory == null) return;
      Path temporary = null;
      try {
         Files.createDirectories(this.directory);
         temporary = Files.createTempFile(this.directory, "preview-", ".tmp");
         try (var output = new DataOutputStream(Files.newOutputStream(temporary))) {
            output.writeInt(thumbnail.width());
            output.writeInt(thumbnail.height());
            output.write(thumbnail.bytes());
         }
         Files.move(temporary, file(key), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException exception) {
         log.debug("Unable to persist catalog thumbnail", exception);
      } finally {
         if (temporary != null) {
            try {
               Files.deleteIfExists(temporary);
            } catch (IOException exception) {
               log.debug("Unable to remove incomplete catalog thumbnail", exception);
            }
         }
      }
   }

   private Path file(String key) {
      return this.directory.resolve(key + ".preview");
   }

   private DataInputStream open(String key) throws IOException {
      Path file = file(key);
      return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
         ? new DataInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) : null;
   }

   private static CatalogMediaService.ThumbnailDimensions readDimensions(DataInputStream input) throws IOException {
      int width = input.readInt();
      int height = input.readInt();
      if (width < 1 || height < 1) throw new IOException("Invalid catalog preview dimensions");
      return new CatalogMediaService.ThumbnailDimensions(width, height);
   }
}
