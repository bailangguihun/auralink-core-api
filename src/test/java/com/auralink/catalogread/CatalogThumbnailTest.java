package com.auralink.catalogread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.auralink.api.v1.media.MediaAssetController;
import com.auralink.catalogcritic.CatalogPrivateMediaService;
import com.auralink.catalogcritic.PostgresPrivateMediaUploadService;
import com.auralink.config.properties.MediaAssetProperties;
import com.auralink.service.CurrentUserService;
import com.auralink.service.media.MediaAssetService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CatalogThumbnailTest {
   private static final String PUBLIC_ID = "00000000-0000-0000-0000-000000000001";
   private static final String PRIVATE_ID = "00000000-0000-0000-0000-000000000002";
   private static final String BATCH_ID = "00000000-0000-0000-0000-000000000003";

   @TempDir Path root;

   @Test
   void servesOnlyActiveVersionedPublicCatalogThumbnails() throws Exception {
      Path original = Files.createDirectories(root.resolve("images/original")).resolve("catalog.jpg");
      BufferedImage image = new BufferedImage(1440, 900, BufferedImage.TYPE_INT_RGB);
      Random random = new Random(17);
      for (int y = 0; y < image.getHeight(); y++) {
         for (int x = 0; x < image.getWidth(); x++) {
            image.setRGB(x, y, random.nextInt(0x1000000));
         }
      }
      assertTrue(ImageIO.write(image, "jpeg", original.toFile()));
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(original)));
      var asset = new CatalogReadStore.CatalogMediaAsset(PUBLIC_ID, BATCH_ID, 1, "catalog.jpg", null, hash, "image/jpeg");
      var store = mock(CatalogReadStore.class);
      when(store.findMedia(PUBLIC_ID)).thenReturn(Optional.of(asset));
      var batch = new CatalogReadProperties.BatchMediaRoot();
      batch.setBatchId(BATCH_ID);
      batch.setRevision(1);
      batch.setRoot(root.toString());
      var properties = new CatalogReadProperties();
      properties.setBatchMediaRoots(List.of(batch));

      var legacy = mock(MediaAssetService.class);
      var privateMedia = mock(CatalogPrivateMediaService.class);
      var beans = new StaticListableBeanFactory();
      beans.addBean("catalogMedia", new CatalogMediaService(store, properties));
      beans.addBean("privateMedia", privateMedia);
      var mvc = MockMvcBuilders.standaloneSetup(new MediaAssetController(
         legacy, new MediaAssetProperties(), beans.getBeanProvider(CatalogMediaService.class),
         beans.getBeanProvider(CatalogPrivateMediaService.class), beans.getBeanProvider(PostgresPrivateMediaUploadService.class),
         mock(CurrentUserService.class)
      )).build();

      var response = mvc.perform(get(url(PUBLIC_ID, hash)))
         .andExpect(status().isOk())
         .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
         .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=3600, public, immutable"))
         .andReturn().getResponse();
      var bytes = response.getContentAsByteArray();
      var decoded = ImageIO.read(new ByteArrayInputStream(bytes));
      assertEquals(720, decoded.getWidth());
      assertEquals(450, decoded.getHeight());
      assertTrue(bytes.length < Files.size(original));
      mvc.perform(get(url(PUBLIC_ID, hash)).header(HttpHeaders.IF_NONE_MATCH, response.getHeader(HttpHeaders.ETAG)))
         .andExpect(status().isNotModified());
      mvc.perform(get(url(PUBLIC_ID, "0".repeat(64)))).andExpect(status().isNotFound())
         .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
      mvc.perform(get(url(PRIVATE_ID, hash))).andExpect(status().isNotFound())
         .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
      String smallUrl = "/api/v1/assets/public-catalog-thumbnails/v2/480/" + PUBLIC_ID + "/" + hash + ".jpg";
      var small = mvc.perform(get(smallUrl))
         .andExpect(status().isOk())
         .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
         .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=3600, public, immutable"))
         .andReturn().getResponse();
      var compact = ImageIO.read(new ByteArrayInputStream(small.getContentAsByteArray()));
      assertEquals(480, compact.getWidth());
      assertEquals(300, compact.getHeight());
      assertTrue(small.getContentAsByteArray().length < bytes.length);
      assertTrue(!response.getHeader(HttpHeaders.ETAG).equals(small.getHeader(HttpHeaders.ETAG)));
      mvc.perform(get(smallUrl).header(HttpHeaders.IF_NONE_MATCH, small.getHeader(HttpHeaders.ETAG)))
         .andExpect(status().isNotModified());
      mvc.perform(get(smallUrl.replace(hash, "0".repeat(64)))).andExpect(status().isNotFound())
         .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
      mvc.perform(get(smallUrl.replace(PUBLIC_ID, PRIVATE_ID))).andExpect(status().isNotFound())
         .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
      when(store.findMedia(PUBLIC_ID)).thenReturn(Optional.empty());
      mvc.perform(get(url(PUBLIC_ID, hash))).andExpect(status().isNotFound())
         .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
      mvc.perform(get(smallUrl)).andExpect(status().isNotFound());
      verifyNoInteractions(legacy, privateMedia);
   }

   @Test
   void rendersPngAndPersistsOrientedDimensionsAcrossServiceRestarts() throws Exception {
      Path images = Files.createDirectories(root.resolve("images/original"));
      Path png = images.resolve("landscape.png");
      assertTrue(ImageIO.write(new BufferedImage(1570, 390, BufferedImage.TYPE_INT_ARGB), "png", png.toFile()));
      String sourceHash = hash(png);
      var asset = new CatalogReadStore.CatalogMediaAsset(PUBLIC_ID, BATCH_ID, 1, "landscape.png", null, sourceHash, "image/png");
      var store = mock(CatalogReadStore.class);
      when(store.findMedia(PUBLIC_ID)).thenReturn(Optional.of(asset));
      var properties = cacheProperties();
      var service = new CatalogMediaService(store, properties);
      var generated = service.findThumbnail(PUBLIC_ID, sourceHash).orElseThrow();
      assertEquals(720, ImageIO.read(new ByteArrayInputStream(generated.bytes())).getWidth());
      assertEquals(new CatalogMediaService.ThumbnailDimensions(1570, 390), generated.dimensions());
      assertTrue(generated.bytes().length <= CatalogThumbnail.MAX_THUMBNAIL_BYTES);
      assertEquals("/api/v1/assets/public-catalog-thumbnails/v2/" + PUBLIC_ID + "/" + sourceHash + ".jpg",
         CatalogMediaService.previewPath(asset));
      var reopened = new CatalogMediaService(store, properties);
      assertEquals(generated.dimensions(), reopened.cachedThumbnailDimensions(asset));
      assertEquals(generated.sha256(), reopened.findThumbnail(PUBLIC_ID, sourceHash).orElseThrow().sha256());
      when(store.findMedia(PUBLIC_ID)).thenReturn(Optional.empty());
      assertTrue(reopened.findThumbnail(PUBLIC_ID, sourceHash).isEmpty());
   }

   @Test
   void rotatesJpegExifOrientationBeforeScalingLongScroll() throws Exception {
      Path images = Files.createDirectories(root.resolve("images/original"));
      Path original = images.resolve("scroll.jpg");
      var jpeg = new ByteArrayOutputStream();
      assertTrue(ImageIO.write(new BufferedImage(1800, 240, BufferedImage.TYPE_INT_RGB), "jpeg", jpeg));
      byte[] bytes = jpeg.toByteArray();
      byte[] exif = HexFormat.of().parseHex("ffe1002245786966000049492a0008000000010012010300010000000600000000000000");
      try (var output = Files.newOutputStream(original)) {
         int afterJfif = 4 + Short.toUnsignedInt(java.nio.ByteBuffer.wrap(bytes, 4, 2).getShort());
         output.write(Arrays.copyOf(bytes, afterJfif));
         output.write(exif);
         output.write(bytes, afterJfif, bytes.length - afterJfif);
      }
      var asset = new CatalogReadStore.CatalogMediaAsset(PUBLIC_ID, BATCH_ID, 1, "scroll.jpg", null, hash(original), "image/jpeg");
      var store = mock(CatalogReadStore.class);
      when(store.findMedia(PUBLIC_ID)).thenReturn(Optional.of(asset));
      var generated = new CatalogMediaService(store, cacheProperties()).findThumbnail(PUBLIC_ID, asset.contentSha256()).orElseThrow();
      var decoded = ImageIO.read(new ByteArrayInputStream(generated.bytes()));
      assertEquals(96, decoded.getWidth());
      assertEquals(720, decoded.getHeight());
      assertEquals(new CatalogMediaService.ThumbnailDimensions(240, 1800), generated.dimensions());
   }

   private CatalogReadProperties cacheProperties() {
      var batch = new CatalogReadProperties.BatchMediaRoot();
      batch.setBatchId(BATCH_ID);
      batch.setRevision(1);
      batch.setRoot(root.toString());
      var properties = new CatalogReadProperties();
      properties.setBatchMediaRoots(List.of(batch));
      properties.setThumbnailCacheDirectory(root.resolve("cache").toString());
      return properties;
   }

   private static String hash(Path image) throws Exception {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(image)));
   }

   private static String url(String assetId, String hash) {
      return "/api/v1/assets/public-catalog-thumbnails/v1/" + assetId + "/" + hash + ".jpg";
   }
}
