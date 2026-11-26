package com.auralink.catalogread;

import com.auralink.catalogread.CatalogMediaService.CatalogMediaContent;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

final class CatalogThumbnail {
   private static final int MAX_SIDE = 720;
   private static final long MAX_SOURCE_PIXELS = 80_000_000;
   private static final long MAX_SOURCE_BYTES = 24L * 1024 * 1024;
   private static final int MAX_THUMBNAIL_BYTES = 192 * 1024;

   private CatalogThumbnail() {
   }

   static byte[] render(CatalogMediaContent original) throws IOException {
      if (original.size() > MAX_SOURCE_BYTES) {
         return null;
      }
      try (var input = new FileImageInputStream(original.resource().getFile())) {
         Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
         if (!readers.hasNext()) {
            return null;
         }
         ImageReader reader = readers.next();
         try {
            if (!"JPEG".equalsIgnoreCase(reader.getFormatName())) {
               return null;
            }
            reader.setInput(input);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if ((long) width * height > MAX_SOURCE_PIXELS || hasExif(reader)) {
               return null;
            }
            int longSide = Math.max(width, height);
            if (longSide <= MAX_SIDE) {
               return original.size() <= MAX_THUMBNAIL_BYTES ? Files.readAllBytes(original.resource().getFile().toPath()) : null;
            }
            var readParam = reader.getDefaultReadParam();
            int step = (longSide + 2 * MAX_SIDE - 1) / (2 * MAX_SIDE);
            readParam.setSourceSubsampling(step, step, 0, 0);
            BufferedImage decoded = reader.read(0, readParam);
            double scale = (double) MAX_SIDE / longSide;
            var thumbnail = new BufferedImage(
               Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale)), BufferedImage.TYPE_INT_RGB
            );
            Graphics2D graphics = thumbnail.createGraphics();
            try {
               graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
               graphics.drawImage(decoded, 0, 0, thumbnail.getWidth(), thumbnail.getHeight(), null);
            } finally {
               graphics.dispose();
            }
            byte[] encoded = jpeg(thumbnail);
            return encoded.length <= MAX_THUMBNAIL_BYTES && encoded.length < original.size() ? encoded : null;
         } finally {
            reader.dispose();
         }
      }
   }

   private static boolean hasExif(ImageReader reader) throws IOException {
      var metadata = reader.getImageMetadata(0);
      var tree = (IIOMetadataNode) metadata.getAsTree(metadata.getNativeMetadataFormatName());
      var markers = tree.getElementsByTagName("unknown");
      for (int i = 0; i < markers.getLength(); i++) {
         var marker = (IIOMetadataNode) markers.item(i);
         if ("225".equals(marker.getAttribute("MarkerTag")) && marker.getUserObject() instanceof byte[] bytes
            && bytes.length >= 6 && bytes[0] == 'E' && bytes[1] == 'x' && bytes[2] == 'i' && bytes[3] == 'f'
            && bytes[4] == 0 && bytes[5] == 0) {
            // Keep browser orientation handling for EXIF originals.
            return true;
         }
      }
      return false;
   }

   private static byte[] jpeg(BufferedImage image) throws IOException {
      var bytes = new ByteArrayOutputStream();
      ImageWriter writer = ImageIO.getImageWritersByFormatName("JPEG").next();
      try (var output = new MemoryCacheImageOutputStream(bytes)) {
         writer.setOutput(output);
         ImageWriteParam params = writer.getDefaultWriteParam();
         params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
         params.setCompressionQuality(0.82f);
         writer.write(null, new IIOImage(image, null, null), params);
         output.flush();
      } finally {
         writer.dispose();
      }
      return bytes.toByteArray();
   }
}
