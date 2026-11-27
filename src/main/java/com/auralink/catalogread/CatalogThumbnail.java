package com.auralink.catalogread;

import com.auralink.catalogread.CatalogMediaService.CatalogMediaContent;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
   private static final long MAX_SOURCE_PIXELS = 80_000_000;
   private static final long MAX_SOURCE_BYTES = 24L * 1024 * 1024;
   static final int MAX_THUMBNAIL_BYTES = 192 * 1024;

   private CatalogThumbnail() {
   }

   record Preview(byte[] bytes, int width, int height) {}

   static Preview render(CatalogMediaContent original, int maxSide) throws IOException {
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
            reader.setInput(input);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if ((long) width * height > MAX_SOURCE_PIXELS) {
               return null;
            }
            int orientation = "JPEG".equalsIgnoreCase(reader.getFormatName()) ? orientation(reader) : 1;
            boolean rotated = orientation >= 5;
            int longSide = Math.max(width, height);
            var readParam = reader.getDefaultReadParam();
            int step = Math.max(1, (longSide + 2 * maxSide - 1) / (2 * maxSide));
            readParam.setSourceSubsampling(step, step, 0, 0);
            BufferedImage decoded = reader.read(0, readParam);
            double scale = Math.min(1.0, (double) maxSide / longSide);
            int scaledWidth = Math.max(1, (int) Math.round(width * scale));
            int scaledHeight = Math.max(1, (int) Math.round(height * scale));
            var thumbnail = new BufferedImage(rotated ? scaledHeight : scaledWidth,
               rotated ? scaledWidth : scaledHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = thumbnail.createGraphics();
            try {
               graphics.setColor(Color.WHITE);
               graphics.fillRect(0, 0, thumbnail.getWidth(), thumbnail.getHeight());
               graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
               applyOrientation(graphics, orientation, scaledWidth, scaledHeight);
               graphics.drawImage(decoded, 0, 0, scaledWidth, scaledHeight, null);
            } finally {
               graphics.dispose();
            }
            byte[] encoded = jpeg(thumbnail, 0.76f);
            if (encoded.length > MAX_THUMBNAIL_BYTES) {
               encoded = jpeg(thumbnail, 0.52f);
            }
            return encoded.length <= MAX_THUMBNAIL_BYTES
               ? new Preview(encoded, rotated ? height : width, rotated ? width : height) : null;
         } finally {
            reader.dispose();
         }
      }
   }

   private static int orientation(ImageReader reader) throws IOException {
      var metadata = reader.getImageMetadata(0);
      var tree = (IIOMetadataNode) metadata.getAsTree(metadata.getNativeMetadataFormatName());
      var markers = tree.getElementsByTagName("unknown");
      for (int i = 0; i < markers.getLength(); i++) {
         var marker = (IIOMetadataNode) markers.item(i);
         if ("225".equals(marker.getAttribute("MarkerTag")) && marker.getUserObject() instanceof byte[] bytes
            && bytes.length >= 14 && bytes[0] == 'E' && bytes[1] == 'x' && bytes[2] == 'i' && bytes[3] == 'f'
            && bytes[4] == 0 && bytes[5] == 0) {
            return tiffOrientation(ByteBuffer.wrap(bytes, 6, bytes.length - 6).slice());
         }
      }
      return 1;
   }

   private static int tiffOrientation(ByteBuffer tiff) {
      int byteOrder = Short.toUnsignedInt(tiff.getShort(0));
      if (byteOrder == 0x4949) tiff.order(ByteOrder.LITTLE_ENDIAN);
      else if (byteOrder != 0x4d4d) return 1;
      if (tiff.getShort(2) != 42) return 1;
      int directory = tiff.getInt(4);
      if (directory < 8 || directory > tiff.limit() - 2) return 1;
      int entries = Short.toUnsignedInt(tiff.getShort(directory));
      for (int i = 0, offset = directory + 2; i < entries && offset <= tiff.limit() - 12; i++, offset += 12) {
         if (tiff.getShort(offset) == 0x0112 && tiff.getShort(offset + 2) == 3 && tiff.getInt(offset + 4) == 1) {
            int orientation = tiff.getShort(offset + 8);
            return orientation >= 1 && orientation <= 8 ? orientation : 1;
         }
      }
      return 1;
   }

   private static void applyOrientation(Graphics2D graphics, int orientation, int width, int height) {
      switch (orientation) {
         case 2 -> graphics.transform(new AffineTransform(-1, 0, 0, 1, width, 0));
         case 3 -> graphics.transform(new AffineTransform(-1, 0, 0, -1, width, height));
         case 4 -> graphics.transform(new AffineTransform(1, 0, 0, -1, 0, height));
         case 5 -> graphics.transform(new AffineTransform(0, 1, 1, 0, 0, 0));
         case 6 -> graphics.transform(new AffineTransform(0, 1, -1, 0, height, 0));
         case 7 -> graphics.transform(new AffineTransform(0, -1, -1, 0, height, width));
         case 8 -> graphics.transform(new AffineTransform(0, -1, 1, 0, 0, width));
         default -> { }
      }
   }

   private static byte[] jpeg(BufferedImage image, float quality) throws IOException {
      var bytes = new ByteArrayOutputStream();
      ImageWriter writer = ImageIO.getImageWritersByFormatName("JPEG").next();
      try (var output = new MemoryCacheImageOutputStream(bytes)) {
         writer.setOutput(output);
         ImageWriteParam params = writer.getDefaultWriteParam();
         params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
         params.setCompressionQuality(quality);
         writer.write(null, new IIOImage(image, null, null), params);
         output.flush();
      } finally {
         writer.dispose();
      }
      return bytes.toByteArray();
   }
}
