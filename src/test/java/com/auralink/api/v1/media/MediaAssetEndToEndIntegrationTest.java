package com.auralink.api.v1.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.auralink.entity.MediaAsset;
import com.auralink.entity.User;
import com.auralink.media.MediaAssetValues;
import com.auralink.repository.MediaAssetRepository;
import com.auralink.repository.UserRepository;
import com.auralink.service.media.GeneratedAssetRequest;
import com.auralink.service.media.MediaAssetService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "spring.config.import=optional:file:/tmp/auralink-round4-test-no-env.properties",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.flyway.baseline-on-migrate=false",
        "spring.datasource.hikari.connection-init-sql=PRAGMA foreign_keys=ON",
        "auralink.paintings.import-enabled=false",
        "auralink.jwt.secret=round4-test-only-jwt-secret-that-is-never-used-outside-tests"
})
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MediaAssetEndToEndIntegrationTest {

    private static final Path ROOT = Path.of(
            "/tmp", "auralink-round4-api-" + UUID.randomUUID());
    private static final Path DATABASE = ROOT.resolve("round4.db");
    private static final Path MANAGED_ROOT = ROOT.resolve("managed");
    private static final Path CATALOG_ROOT = ROOT.resolve("catalog");
    private static final long TEST_UPLOAD_LIMIT = 8_192L;
    private static final long TEST_PIXEL_LIMIT = 10_000L;
    private static final AtomicInteger IDS = new AtomicInteger();

    @DynamicPropertySource
    static void isolatedRuntime(DynamicPropertyRegistry registry) {
        try {
            Files.createDirectories(ROOT);
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to create isolated Round 4 test directory", exception);
        }
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("auralink.media-assets.managed-dir", MANAGED_ROOT::toString);
        registry.add("auralink.media-assets.max-upload-bytes", () -> TEST_UPLOAD_LIMIT);
        registry.add("auralink.media-assets.max-generated-bytes", () -> 65_536L);
        registry.add("auralink.media-assets.max-image-pixels", () -> TEST_PIXEL_LIMIT);
        registry.add("auralink.media-assets.public-cache-seconds", () -> 60L);
        registry.add("auralink.paintings.picture-dir", CATALOG_ROOT::toString);
        registry.add("auralink.storage.upload-dir", () -> ROOT.resolve("legacy-uploads").toString());
        registry.add("auralink.storage.audio-dir", () -> ROOT.resolve("legacy-audio").toString());
        registry.add("auralink.storage.legacy-frontend-audio-dir",
                () -> ROOT.resolve("legacy-frontend-audio").toString());
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private MediaAssetRepository mediaAssets;
    @Autowired private MediaAssetService mediaAssetService;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void isolatedDatabaseIsMigratedAndApplicationConnectionsEnforceForeignKeys() {
        assertThat(DATABASE.toString()).startsWith("/tmp/");
        assertThat(jdbc.queryForObject("PRAGMA foreign_keys", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList(
                "SELECT version || ':' || type FROM flyway_schema_history "
                        + "WHERE success = 1 ORDER BY installed_rank",
                String.class)).containsExactly("1:SQL", "2:SQL", "3:SQL", "4:SQL");
    }

    @Test
    void authenticatedJpegAndPngUploadsUseAuthenticatedOwnerAndRemainDistinct() throws Exception {
        User owner = saveUser("upload-owner");
        User attemptedOwner = saveUser("attempted-owner");
        int legacyLogsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM generation_logs", Integer.class);
        byte[] jpeg = imageBytes("JPEG", 12, 8);

        MvcResult firstResult = mockMvc.perform(multipart("/api/v1/assets/uploads")
                        .file(new MockMultipartFile(
                                "file", "../../same-name.jpg", "image/jpeg", jpeg))
                        .param("semanticType", "PAINTING")
                        .param("ownerId", attemptedOwner.getId().toString())
                        .param("userId", attemptedOwner.getId().toString())
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assetType").value("IMAGE"))
                .andExpect(jsonPath("$.semanticType").value("PAINTING"))
                .andExpect(jsonPath("$.sourceType").value("USER_UPLOAD"))
                .andExpect(jsonPath("$.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.storageKey").doesNotExist())
                .andExpect(jsonPath("$.ownerUser").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn();

        String firstId = body(firstResult).path("assetId").asText();
        MediaAsset first = mediaAssets.findByPublicId(firstId).orElseThrow();
        assertThat(first.getOwnerUser().getId()).isEqualTo(owner.getId());
        assertThat(first.getOwnerUser().getId()).isNotEqualTo(attemptedOwner.getId());
        assertThat(first.getOriginalFilename()).isEqualTo("same-name.jpg");
        assertThat(first.getStorageKey())
                .startsWith("managed/private/" + owner.getId() + "/")
                .doesNotContain("..", "\\")
                .doesNotStartWith("/");

        MvcResult duplicateResult = mockMvc.perform(multipart("/api/v1/assets/uploads")
                        .file(new MockMultipartFile("file", "same-name.jpg", "image/jpeg", jpeg))
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isCreated())
                .andReturn();
        MediaAsset duplicate = mediaAssets.findByPublicId(
                body(duplicateResult).path("assetId").asText()).orElseThrow();
        assertThat(duplicate.getPublicId()).isNotEqualTo(first.getPublicId());
        assertThat(duplicate.getStorageKey()).isNotEqualTo(first.getStorageKey());
        assertThat(duplicate.getSha256()).isEqualTo(first.getSha256());

        mockMvc.perform(multipart("/api/v1/assets/uploads")
                        .file(new MockMultipartFile(
                                "file", "ink.png", "image/png", imageBytes("PNG", 7, 9)))
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mimeType").value("image/png"))
                .andExpect(jsonPath("$.width").value(7))
                .andExpect(jsonPath("$.height").value(9));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM generation_logs", Integer.class))
                .isEqualTo(legacyLogsBefore);
        assertThat(firstResult.getResponse().getContentAsString()).doesNotContain("/tmp/", "storageKey");
    }

    @Test
    void uploadRejectsFakeMismatchOversizedAndExcessivePixelImages() throws Exception {
        User owner = saveUser("invalid-owner");

        assertUploadError(owner,
                new MockMultipartFile("file", "fake.jpg", "image/jpeg", "not an image".getBytes()),
                400, "INVALID_IMAGE");

        byte[] jpeg = imageBytes("JPEG", 3, 3);
        assertUploadError(owner,
                new MockMultipartFile("file", "wrong.png", "image/jpeg", jpeg),
                400, "INVALID_IMAGE");
        assertUploadError(owner,
                new MockMultipartFile("file", "wrong.jpg", "image/png", jpeg),
                400, "INVALID_IMAGE");

        assertUploadError(owner,
                new MockMultipartFile(
                        "file", "oversized.jpg", "image/jpeg", new byte[(int) TEST_UPLOAD_LIMIT + 1]),
                413, "ASSET_TOO_LARGE");

        assertUploadError(owner,
                new MockMultipartFile(
                        "file", "pixels.png", "image/png", imageBytes("PNG", 101, 100)),
                400, "INVALID_IMAGE");
    }

    @Test
    void privateAssetsAreInvisibleToAnonymousAndOtherUsersButReadableByOwner() throws Exception {
        User owner = saveUser("private-owner");
        User other = saveUser("private-other");
        byte[] bytes = imageBytes("PNG", 4, 3);
        String assetId = upload(owner, "private.png", "image/png", bytes);

        mockMvc.perform(get("/api/v1/assets/{id}", assetId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ASSET_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/assets/{id}/content", assetId))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/assets/{id}/download", assetId))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/assets/{id}", assetId)
                        .with(user(other.getUsername()).roles("USER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ASSET_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/assets/{id}", assetId)
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(assetId));
        mockMvc.perform(get("/api/v1/assets/{id}/content", assetId)
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(bytes));
        mockMvc.perform(get("/api/v1/assets/{id}/download", assetId)
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("attachment")));

        MediaAsset deleted = mediaAssets.findByPublicId(assetId).orElseThrow();
        deleted.setStatus(MediaAssetValues.Status.DELETED);
        mediaAssets.saveAndFlush(deleted);
        mockMvc.perform(get("/api/v1/assets/{id}", assetId)
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/assets/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ASSET_ID"));
        mockMvc.perform(get("/api/v1/assets/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }

    @Test
    void catalogReferenceIsPublicIdempotentNoCopyAndSupportsRangeAndDownload() throws Exception {
        Files.createDirectories(CATALOG_ROOT.resolve("nested"));
        byte[] bytes = imageBytes("PNG", 5, 4);
        Path catalogFile = Files.write(CATALOG_ROOT.resolve("nested/official.png"), bytes);
        long managedBefore = regularFileCount(MANAGED_ROOT);

        MediaAsset first = mediaAssetService.registerCatalogReference("nested/official.png");
        MediaAsset second = mediaAssetService.registerCatalogReference("nested/official.png");
        String originalSha = first.getSha256();

        bytes = imageBytes("PNG", 6, 4);
        Files.write(catalogFile, bytes);
        MediaAsset refreshed = mediaAssetService.registerCatalogReference("nested/official.png");

        assertThat(second.getPublicId()).isEqualTo(first.getPublicId());
        assertThat(refreshed.getPublicId()).isEqualTo(first.getPublicId());
        assertThat(refreshed.getSha256()).isNotEqualTo(originalSha);
        assertThat(refreshed.getWidth()).isEqualTo(6);
        assertThat(first.getOwnerUser()).isNull();
        assertThat(first.getStorageKey()).isEqualTo("catalog/nested/official.png");
        assertThat(first.getSourceType()).isEqualTo(MediaAssetValues.SourceType.CATALOG_REFERENCE);
        assertThat(first.getVisibility()).isEqualTo(MediaAssetValues.Visibility.PUBLIC);
        assertThat(catalogFile).exists();
        assertThat(regularFileCount(MANAGED_ROOT)).isEqualTo(managedBefore);
        assertThat(mediaAssets.findByStorageKey("catalog/nested/official.png")).isPresent();

        mockMvc.perform(get("/api/v1/assets/{id}", first.getPublicId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(first.getPublicId()))
                .andExpect(jsonPath("$.storageKey").doesNotExist());
        mockMvc.perform(get("/api/v1/assets/{id}/content", first.getPublicId()))
                .andExpect(status().isOk())
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, bytes.length))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline"))
                .andExpect(header().string(HttpHeaders.ETAG, '"' + refreshed.getSha256() + '"'))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("max-age=60")))
                .andExpect(content().bytes(bytes));
        mockMvc.perform(get("/api/v1/assets/{id}/content", first.getPublicId())
                        .header(HttpHeaders.RANGE, "bytes=1-4"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE,
                        "bytes 1-4/" + bytes.length))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 4))
                .andExpect(content().bytes(java.util.Arrays.copyOfRange(bytes, 1, 5)));
        mockMvc.perform(get("/api/v1/assets/{id}/download", first.getPublicId()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("attachment")));
    }

    @Test
    void generatedImageAndAudioArePrivateOwnedStreamResourcesWithoutProviderCalls() throws Exception {
        User owner = saveUser("generated-owner");
        MediaAsset generatedImage = mediaAssetService.storeGeneratedAsset(new GeneratedAssetRequest(
                owner,
                new ByteArrayInputStream(imageBytes("PNG", 6, 5)),
                "generated-painting.png",
                "image/png",
                "IMAGE",
                "GENERATED_PAINTING",
                null));
        byte[] audio = "synthetic-wave-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MediaAsset generatedAudio = mediaAssetService.storeGeneratedAsset(new GeneratedAssetRequest(
                owner,
                new ByteArrayInputStream(audio),
                "generated.wav",
                "audio/wav",
                "AUDIO",
                "MUSIC",
                1.25));

        assertThat(generatedImage.getOwnerUser().getId()).isEqualTo(owner.getId());
        assertThat(generatedImage.getSourceType()).isEqualTo("GENERATED");
        assertThat(generatedImage.getVisibility()).isEqualTo("PRIVATE");
        assertThat(generatedImage.getStorageKey()).startsWith("managed/private/").doesNotStartWith("/");
        assertThat(generatedAudio.getAssetType()).isEqualTo("AUDIO");
        assertThat(generatedAudio.getSemanticType()).isEqualTo("MUSIC");
        assertThat(generatedAudio.getMimeType()).isEqualTo("audio/wav");
        assertThat(generatedAudio.getDurationSeconds()).isEqualTo(1.25);

        mockMvc.perform(get("/api/v1/assets/{id}/content", generatedAudio.getPublicId())
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("audio/wav"))
                .andExpect(content().bytes(audio));
        mockMvc.perform(get("/api/v1/assets/{id}", generatedAudio.getPublicId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void maliciousDatabaseStorageKeysCannotEscapeEitherStorageFamily() throws Exception {
        User owner = saveUser("malicious-key-owner");
        MediaAsset managed = mediaAssets.saveAndFlush(asset(
                owner, "managed/../outside.png", "USER_UPLOAD", "PRIVATE"));
        MediaAsset catalog = mediaAssets.saveAndFlush(asset(
                null, "catalog/../outside.png", "CATALOG_REFERENCE", "PUBLIC"));

        mockMvc.perform(get("/api/v1/assets/{id}/content", managed.getPublicId())
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ASSET_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/assets/{id}/content", catalog.getPublicId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ASSET_NOT_FOUND"));
    }

    @Test
    void outerTransactionRollbackRemovesBothDatabaseRowAndNewManagedFile() throws Exception {
        User owner = saveUser("rollback-owner");
        byte[] png = imageBytes("PNG", 3, 3);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        RolledBackAsset rolledBack = transaction.execute(status -> {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(owner, null, owner.getAuthorities()));
            try {
                MediaAsset asset = mediaAssetService.storeAuthenticatedImage(
                        new MockMultipartFile("file", "rollback.png", "image/png", png),
                        "IMAGE");
                status.setRollbackOnly();
                return new RolledBackAsset(asset.getPublicId(), asset.getStorageKey());
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        assertThat(rolledBack).isNotNull();
        assertThat(mediaAssets.findByPublicId(rolledBack.publicId())).isEmpty();
        Path physical = MANAGED_ROOT.resolve(
                rolledBack.storageKey().substring("managed/".length()));
        assertThat(physical).doesNotExist();
    }

    private void assertUploadError(
            User owner,
            MockMultipartFile file,
            int expectedStatus,
            String expectedCode) throws Exception {
        mockMvc.perform(multipart("/api/v1/assets/uploads")
                        .file(file)
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode));
    }

    private String upload(User owner, String filename, String mimeType, byte[] bytes) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/v1/assets/uploads")
                        .file(new MockMultipartFile("file", filename, mimeType, bytes))
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result).path("assetId").asText();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private User saveUser(String label) {
        int suffix = IDS.incrementAndGet();
        return users.saveAndFlush(User.builder()
                .username(label + "-" + suffix)
                .password("test-hash")
                .fullName("Round 4 Test User")
                .email(label + "-" + suffix + "@example.test")
                .role("ROLE_USER")
                .build());
    }

    private MediaAsset asset(User owner, String storageKey, String sourceType, String visibility) {
        return MediaAsset.builder()
                .ownerUser(owner)
                .storageKey(storageKey)
                .originalFilename("outside.png")
                .mimeType("image/png")
                .fileSize(1L)
                .sha256("a".repeat(64))
                .width(1)
                .height(1)
                .assetType("IMAGE")
                .semanticType("IMAGE")
                .sourceType(sourceType)
                .visibility(visibility)
                .status("ACTIVE")
                .build();
    }

    private byte[] imageBytes(String format, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.BLACK.getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, output)).isTrue();
        return output.toByteArray();
    }

    private long regularFileCount(Path root) throws Exception {
        if (!Files.exists(root)) {
            return 0;
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private record RolledBackAsset(String publicId, String storageKey) {
    }
}
