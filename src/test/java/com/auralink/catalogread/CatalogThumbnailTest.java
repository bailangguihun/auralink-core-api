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
      when(store.findMedia(PUBLIC_ID)).thenReturn(Optional.empty());
      mvc.perform(get(url(PUBLIC_ID, hash))).andExpect(status().isNotFound())
         .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
      verifyNoInteractions(legacy, privateMedia);
   }

   private static String url(String assetId, String hash) {
      return "/api/v1/assets/public-catalog-thumbnails/v1/" + assetId + "/" + hash + ".jpg";
   }
}
