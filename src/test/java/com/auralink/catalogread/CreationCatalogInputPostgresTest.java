package com.auralink.catalogread;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.config.properties.MediaAssetProperties;
import com.auralink.config.properties.PaintingProperties;
import com.auralink.creation.CreationExecutionTransactionService.ClaimedCreationData;
import com.auralink.creation.CreationExecutionTransactionService.StepData;
import com.auralink.creation.CreationInputMaterializer;
import com.auralink.creation.provider.ProviderImageInput;
import com.auralink.entity.MediaAsset;
import com.auralink.entity.Painting;
import com.auralink.exception.InvalidStoragePathException;
import com.auralink.provider.artifact.AudioOutputValidator;
import com.auralink.provider.artifact.ProviderArtifact;
import com.auralink.provider.artifact.ProviderArtifactStagingService;
import com.auralink.provider.qwen.PaintingPoemResultValidator;
import com.auralink.repository.CreationStepRepository;
import com.auralink.repository.MediaAssetRepository;
import com.auralink.repository.PaintingRepository;
import com.auralink.service.media.ImageContentValidator;
import com.auralink.service.media.MediaAssetStorageResolver;
import com.auralink.service.media.MediaAssetStorageService;
import com.auralink.workflow.WorkflowModality;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL media lookup, controlled file resolution and input staging.
 * Uses only disposable schemas in the explicit loopback test database.
 * JPA repositories remain synthetic; this is not a JPA mapping/transaction test.
 * No HTTP client, worker, scheduler or generation bean is started.
 */
@EnabledIfEnvironmentVariable(named = "ASSISTANT_TEST_JDBC_URL",
   matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/assistant_foundation_test")
class CreationCatalogInputPostgresTest {
   private static final String PAINTING_ID = "00000000-0000-4000-8000-000000000201";
   private static final String ASSET_ID = "00000000-0000-4000-8000-000000000202";
   private static final String BATCH_ID = "00000000-0000-4000-8000-000000000203";
   @TempDir Path temporary;
   private JdbcTemplate admin, jdbc;
   private String schema;
   private Path legacyRoot, managedRoot, batchRoot, stagingRoot;
   private PostgresCatalogReadStore catalog;
   private final PaintingRepository paintings = mock(PaintingRepository.class);
   private final MediaAssetRepository assets = mock(MediaAssetRepository.class);
   private final CreationStepRepository steps = mock(CreationStepRepository.class);

   @BeforeEach void setup() throws Exception {
      String url = System.getenv("ASSISTANT_TEST_JDBC_URL");
      String user = System.getenv("ASSISTANT_TEST_JDBC_USER");
      String password = System.getenv("ASSISTANT_TEST_JDBC_PASSWORD");
      admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
      assertThat(admin.queryForObject("SELECT current_database()", String.class)).isEqualTo("assistant_foundation_test");
      assertThat(admin.queryForObject("SELECT host(inet_server_addr())", String.class)).isEqualTo("127.0.0.1");
      schema = "creation_catalog_input_" + UUID.randomUUID().toString().replace("-", "");
      admin.execute("CREATE SCHEMA " + schema);
      jdbc = new JdbcTemplate(new DriverManagerDataSource(url + "?currentSchema=" + schema, user, password));
      jdbc.execute("CREATE TABLE catalog_media_assets(public_id uuid PRIMARY KEY, source_batch_id uuid, "
         + "source_revision int, relative_path text, storage_uri text, content_sha256 text, media_status text)");
      catalog = new PostgresCatalogReadStore(new NamedParameterJdbcTemplate(jdbc));
      legacyRoot = Files.createDirectories(temporary.resolve("legacy"));
      managedRoot = Files.createDirectories(temporary.resolve("managed"));
      batchRoot = Files.createDirectories(temporary.resolve("catalog-batch"));
      Files.createDirectories(batchRoot.resolve("images/original"));
      stagingRoot = temporary.resolve("provider-staging");
   }

   @AfterEach void removeOnlyOwnSchema() {
      if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
   }

   @ParameterizedTest @ValueSource(strings = {"jpeg", "png"})
   void activePostgresCatalogImageIsMaterializedFromBoundBatch(String format) throws Exception {
      byte[] bytes = image(format);
      MediaAsset asset = officialAsset(format, bytes);
      bindPainting(asset);
      insertCatalog(format, bytes, "ACTIVE");
      var row = catalog.findMedia(ASSET_ID).orElseThrow();
      assertThat(row.publicId()).isEqualTo(ASSET_ID);
      assertThat(row.sourceBatchId()).isEqualTo(BATCH_ID);
      assertThat(row.sourceRevision()).isEqualTo(2);
      assertThat(row.mimeType()).isEqualTo("image/" + format);

      try (var context = context()) {
         assertThatThrownBy(() -> context.getBean(MediaAssetStorageService.class).resolve(asset))
            .isInstanceOf(InvalidStoragePathException.class).hasRootCauseInstanceOf(NoSuchFileException.class);
         ProviderArtifact artifact;
         try (var input = context.getBean(CreationInputMaterializer.class).materialize(source(), step())) {
            var image = (ProviderImageInput) input.input();
            artifact = image.artifact();
            assertThat(image.modality()).isEqualTo(WorkflowModality.PAINTING);
            assertThat(image.paintingMetadata().paintingId()).isEqualTo(PAINTING_ID);
            assertThat(image.paintingMetadata().title()).isEqualTo("合成目录画作");
            assertThat(artifact.mimeType()).isEqualTo("image/" + format);
            assertThat(artifact.sha256()).isEqualTo(sha(bytes));
            assertThat(artifact.byteLength()).isEqualTo(bytes.length);
            try (var stream = artifact.openStream()) { assertThat(stream.readAllBytes()).isEqualTo(bytes); }
         }
         assertThat(artifact.isAvailable()).isFalse();
      }
      assertNoStagedFiles();
      assertThat(Files.readAllBytes(batchRoot.resolve("images/original/synthetic." + format))).isEqualTo(bytes);
   }

   @Test void inactivePostgresCatalogImageNeverFallsBackToReadableLegacyFile() throws Exception {
      byte[] bytes = image("png");
      MediaAsset asset = officialAsset("png", bytes);
      bindPainting(asset);
      insertCatalog("png", bytes, "INACTIVE");
      Path legacy = legacyRoot.resolve(asset.getStorageKey().substring("catalog/".length()));
      Files.createDirectories(legacy.getParent());
      Files.write(legacy, bytes);
      assertThat(catalog.findMedia(ASSET_ID)).isEmpty();
      try (var context = context()) {
         assertThat(context.getBean(MediaAssetStorageService.class).resolve(asset).resource().getContentAsByteArray()).isEqualTo(bytes);
         assertThatThrownBy(() -> context.getBean(CreationInputMaterializer.class).materialize(source(), step()))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Creation input is unavailable or invalid");
      }
      assertNoStagedFiles();
      assertThat(Files.readAllBytes(legacy)).isEqualTo(bytes);
   }

   private void insertCatalog(String format, byte[] bytes, String status) throws Exception {
      Files.write(batchRoot.resolve("images/original/synthetic." + format), bytes);
      jdbc.update("INSERT INTO catalog_media_assets VALUES (CAST(? AS uuid), CAST(? AS uuid), 2, ?, ?, ?, ?)",
         ASSET_ID, BATCH_ID, "synthetic." + format, "unused:synthetic", sha(bytes), status);
   }

   private void bindPainting(MediaAsset asset) {
      when(paintings.findById(201L)).thenReturn(Optional.of(Painting.builder().id(201L).publicId(PAINTING_ID)
         .status("ACTIVE").imageAvailable(true).imageAsset(asset).title("合成目录画作").authorName("合成作者").build()));
   }

   private AnnotationConfigApplicationContext context() {
      var media = new MediaAssetProperties(); media.setManagedDir(managedRoot.toString());
      var painting = new PaintingProperties(); painting.setPictureDir(legacyRoot.toString());
      var provider = new CreationProviderProperties(); provider.setStagingDir(stagingRoot);
      var catalogProperties = new CatalogReadProperties();
      var batch = new CatalogReadProperties.BatchMediaRoot();
      batch.setBatchId(BATCH_ID); batch.setRevision(2); batch.setRoot(batchRoot.toString());
      catalogProperties.setBatchMediaRoots(List.of(batch));
      var context = new AnnotationConfigApplicationContext();
      context.registerBean(MediaAssetProperties.class, () -> media);
      context.registerBean(PaintingProperties.class, () -> painting);
      context.registerBean(CreationProviderProperties.class, () -> provider);
      context.registerBean(MediaAssetRepository.class, () -> assets);
      context.registerBean(PaintingRepository.class, () -> paintings);
      context.registerBean(CreationStepRepository.class, () -> steps);
      context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
      context.registerBean(CatalogMediaService.class, () -> new CatalogMediaService(catalog, catalogProperties));
      context.register(MediaAssetStorageResolver.class, MediaAssetStorageService.class, ImageContentValidator.class,
         AudioOutputValidator.class, ProviderArtifactStagingService.class, PaintingPoemResultValidator.class, CreationInputMaterializer.class);
      context.refresh();
      return context;
   }

   private static MediaAsset officialAsset(String format, byte[] bytes) throws Exception {
      return MediaAsset.builder().id(202L).publicId(ASSET_ID).storageKey("catalog/" + ASSET_ID + "/synthetic." + format)
         .mimeType("image/" + format).sourceType("CATALOG_REFERENCE").assetType("IMAGE").semanticType("IMAGE")
         .fileSize((long) bytes.length).sha256(sha(bytes)).visibility("PUBLIC").status("ACTIVE").build();
   }

   private static ClaimedCreationData source() {
      return new ClaimedCreationData(1L, "synthetic-claim", 7L, "{}", "PAINTING", null, null, 201L, 0);
   }

   private static StepData step() {
      return new StepData(11L, 0, "step1", "PAINTING_TO_MUSIC", "auralink-vmm", "PAINTING", "AUDIO", "RUNNING", 1, "NOT_SENT");
   }

   private static byte[] image(String format) throws Exception {
      var output = new ByteArrayOutputStream();
      assertThat(ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), format, output)).isTrue();
      return output.toByteArray();
   }

   private static String sha(byte[] bytes) throws Exception {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
   }

   private void assertNoStagedFiles() throws Exception {
      if (Files.exists(stagingRoot)) try (var files = Files.list(stagingRoot)) { assertThat(files.count()).isZero(); }
   }
}
