package com.auralink.datafoundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.auralink.entity.CatalogImportRun;
import com.auralink.entity.Creation;
import com.auralink.entity.CreationFavorite;
import com.auralink.entity.CreationStep;
import com.auralink.entity.MediaAsset;
import com.auralink.entity.Painting;
import com.auralink.entity.PaintingFavorite;
import com.auralink.entity.PaintingGuide;
import com.auralink.entity.User;
import com.auralink.entity.UserWorkflow;
import com.auralink.config.properties.StorageProperties;
import com.auralink.repository.CatalogImportRunRepository;
import com.auralink.repository.CreationFavoriteRepository;
import com.auralink.repository.CreationRepository;
import com.auralink.repository.CreationStepRepository;
import com.auralink.repository.MediaAssetRepository;
import com.auralink.repository.PaintingFavoriteRepository;
import com.auralink.repository.PaintingGuideRepository;
import com.auralink.repository.PaintingRepository;
import com.auralink.repository.UserRepository;
import com.auralink.repository.UserWorkflowRepository;

import jakarta.persistence.EntityManager;

/**
 * Proves that the Auralink 2.0 JPA mappings operate on the schema produced by
 * the real Flyway migrations. The database path is unique and always lives
 * under /tmp; this test can never resolve to the inherited live database.
 */
@DataJpaTest(showSql = false, properties = {
        "spring.config.import=optional:file:/tmp/auralink-round31-test-no-env.properties",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.flyway.baseline-on-migrate=false",
        "spring.datasource.hikari.connection-init-sql=PRAGMA foreign_keys=ON"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableConfigurationProperties(StorageProperties.class)
class DataFoundationJpaIntegrationTest {

    private static final Path DATABASE = Path.of(
            "/tmp", "auralink-round31-jpa-" + UUID.randomUUID() + ".db");
    private static final Path RUNTIME_STORAGE = Path.of(
            "/tmp", "auralink-round31-jpa-storage-" + UUID.randomUUID());

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("auralink.storage.upload-dir", () -> RUNTIME_STORAGE.resolve("uploads").toString());
        registry.add("auralink.storage.audio-dir", () -> RUNTIME_STORAGE.resolve("audios").toString());
        registry.add("auralink.storage.legacy-frontend-audio-dir",
                () -> RUNTIME_STORAGE.resolve("legacy-audios").toString());
    }

    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private MediaAssetRepository mediaAssets;
    @Autowired private PaintingRepository paintings;
    @Autowired private CatalogImportRunRepository importRuns;
    @Autowired private PaintingGuideRepository guides;
    @Autowired private PaintingFavoriteRepository paintingFavorites;
    @Autowired private UserWorkflowRepository workflows;
    @Autowired private CreationRepository creations;
    @Autowired private CreationStepRepository steps;
    @Autowired private CreationFavoriteRepository creationFavorites;

    @Test
    void flywayMigratesTheIsolatedDatabaseAndApplicationConnectionsEnforceForeignKeys() {
        assertThat(DATABASE.toString()).startsWith("/tmp/");
        assertThat(jdbc.queryForObject("PRAGMA foreign_keys", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList(
                "SELECT version || ':' || type FROM flyway_schema_history "
                        + "WHERE success = 1 ORDER BY installed_rank",
                String.class)).containsExactly("1:SQL", "2:SQL", "3:SQL", "4:SQL");
    }

    @Test
    void persistsAllNineEntitiesFourSourceModalitiesAndFoundationRepositoryQueries() {
        User user = saveUser("all");
        MediaAsset systemAsset = mediaAssets.saveAndFlush(asset("catalog/all.jpg", null));
        MediaAsset ownedAsset = mediaAssets.saveAndFlush(asset("uploads/all.png", user));

        Painting missingImage = paintings.saveAndFlush(painting("painting:test:missing", null));
        Painting linkedPainting = paintings.saveAndFlush(painting("painting:test:linked", systemAsset));
        CatalogImportRun importRun = importRuns.saveAndFlush(CatalogImportRun.builder()
                .sourceName("synthetic-test.csv")
                .sourceSha256("a".repeat(64))
                .totalRows(2)
                .matchedImages(1)
                .missingImages(1)
                .status("COMPLETED")
                .build());
        PaintingGuide guide = guides.saveAndFlush(PaintingGuide.builder()
                .painting(linkedPainting)
                .resultJson("{\"title\":\"standard guide\"}")
                .sourceHash("b".repeat(64))
                .status("READY")
                .generatedAt(LocalDateTime.now())
                .build());
        PaintingFavorite paintingFavorite = paintingFavorites.saveAndFlush(
                PaintingFavorite.builder().user(user).painting(linkedPainting).build());
        UserWorkflow workflow = workflows.saveAndFlush(UserWorkflow.builder()
                .user(user)
                .name("Private workflow")
                .description("synthetic integration fixture")
                .graphJson("{\"nodes\":[]}")
                .schemaVersion(1)
                .status("ACTIVE")
                .build());

        Creation text = creations.saveAndFlush(creation(user, workflow, "TEXT_DESCRIPTION")
                .sourceText("mist over mountains")
                .finalModality("POEM")
                .finalOutputJson("{\"text\":\"mountain poem\"}")
                .build());
        Creation poem = creations.saveAndFlush(creation(user, workflow, "POEM")
                .sourceText("江流天地外")
                .build());
        Creation paintingStart = creations.saveAndFlush(creation(user, workflow, "PAINTING")
                .sourcePainting(linkedPainting)
                .build());
        Creation image = creations.saveAndFlush(creation(user, workflow, "IMAGE")
                .sourceAsset(ownedAsset)
                .finalModality("IMAGE")
                .finalAsset(ownedAsset)
                .build());

        CreationStep second = steps.saveAndFlush(step(text, 1, "node-b", ownedAsset, null));
        CreationStep first = steps.saveAndFlush(step(text, 0, "node-a", null, ownedAsset));
        CreationFavorite creationFavorite = creationFavorites.saveAndFlush(
                CreationFavorite.builder().user(user).creation(text).build());

        entityManager.clear();

        assertThat(missingImage.getImageAsset()).isNull();
        assertThat(mediaAssets.findByPublicId(systemAsset.getPublicId())).isPresent();
        assertThat(mediaAssets.findByStorageKey("catalog/all.jpg")).isPresent();
        assertThat(paintings.findByPublicId(linkedPainting.getPublicId())).isPresent();
        assertThat(paintings.findBySourceKey("painting:test:linked")).isPresent();
        assertThat(importRuns.findByPublicId(importRun.getPublicId())).isPresent();
        assertThat(guides.findByPaintingId(linkedPainting.getId())).isPresent();
        assertThat(paintingFavorites.existsByUserIdAndPaintingId(user.getId(), linkedPainting.getId())).isTrue();
        assertThat(workflows.findByPublicIdAndUserId(workflow.getPublicId(), user.getId())).isPresent();
        assertThat(creations.findByPublicIdAndUserId(text.getPublicId(), user.getId())).isPresent();
        assertThat(steps.findByCreationIdAndStepIndex(text.getId(), 0)).isPresent();
        assertThat(steps.findByCreationIdOrderByStepIndexAsc(text.getId()))
                .extracting(CreationStep::getPublicId)
                .containsExactly(first.getPublicId(), second.getPublicId());
        assertThat(creationFavorites.existsByUserIdAndCreationId(user.getId(), text.getId())).isTrue();
        assertThat(guide.getPublicId()).isNotBlank();
        assertThat(paintingFavorite.getPublicId()).isNotBlank();
        assertThat(creationFavorite.getPublicId()).isNotBlank();
        assertThat(List.of(text, poem, paintingStart, image))
                .extracting(Creation::getSourceModality)
                .containsExactly("TEXT_DESCRIPTION", "POEM", "PAINTING", "IMAGE");
    }

    @Test
    void generatedPublicUuidIsCanonicalUniqueAndStableAcrossUpdate() {
        MediaAsset generated = mediaAssets.saveAndFlush(asset("uuid/generated.png", null));
        String original = generated.getPublicId();
        assertThat(UUID.fromString(original).toString()).isEqualTo(original);

        generated.setStatus("ARCHIVED");
        mediaAssets.saveAndFlush(generated);
        entityManager.clear();
        assertThat(mediaAssets.findById(generated.getId()).orElseThrow().getPublicId()).isEqualTo(original);

        MediaAsset duplicate = asset("uuid/duplicate.png", null);
        duplicate.setPublicId(original);
        assertThatThrownBy(() -> mediaAssets.saveAndFlush(duplicate))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void paintingSourceKeyIsUnique() {
        paintings.saveAndFlush(painting("painting:test:unique", null));
        assertThatThrownBy(() -> paintings.saveAndFlush(painting("painting:test:unique", null)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void onlyOneGuideMayExistForAPainting() {
        Painting painting = paintings.saveAndFlush(painting("painting:test:guide", null));
        guides.saveAndFlush(guide(painting, "first"));
        assertThatThrownBy(() -> guides.saveAndFlush(guide(painting, "second")))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void paintingFavoriteIsUniquePerUserAndPainting() {
        User user = saveUser("painting-favorite");
        Painting painting = paintings.saveAndFlush(painting("painting:test:favorite", null));
        paintingFavorites.saveAndFlush(PaintingFavorite.builder().user(user).painting(painting).build());
        assertThatThrownBy(() -> paintingFavorites.saveAndFlush(
                PaintingFavorite.builder().user(user).painting(painting).build()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void creationFavoriteIsUniquePerUserAndCreation() {
        User user = saveUser("creation-favorite");
        Creation creation = creations.saveAndFlush(creation(user, null, "TEXT_DESCRIPTION")
                .sourceText("source")
                .build());
        creationFavorites.saveAndFlush(CreationFavorite.builder().user(user).creation(creation).build());
        assertThatThrownBy(() -> creationFavorites.saveAndFlush(
                CreationFavorite.builder().user(user).creation(creation).build()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void creationStepIndexIsUniqueAndRepositoryReturnsExecutionOrder() {
        User user = saveUser("step-order");
        Creation creation = creations.saveAndFlush(creation(user, null, "POEM")
                .sourceText("poem")
                .build());
        steps.saveAndFlush(step(creation, 1, "second", null, null));
        steps.saveAndFlush(step(creation, 0, "first", null, null));
        assertThat(steps.findByCreationIdOrderByStepIndexAsc(creation.getId()))
                .extracting(CreationStep::getStepIndex)
                .containsExactly(0, 1);
        assertThatThrownBy(() -> steps.saveAndFlush(step(creation, 1, "duplicate", null, null)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void workflowSnapshotIsImmutableHistoryAndWorkflowDeletionSetsReferenceNull() {
        User user = saveUser("snapshot");
        UserWorkflow workflow = workflows.saveAndFlush(UserWorkflow.builder()
                .user(user).name("Editable").graphJson("{\"version\":1}")
                .schemaVersion(1).status("ACTIVE").build());
        Creation creation = creations.saveAndFlush(creation(user, workflow, "TEXT_DESCRIPTION")
                .sourceText("source")
                .workflowSnapshot("{\"version\":1}")
                .build());

        workflow.setGraphJson("{\"version\":2}");
        workflows.saveAndFlush(workflow);
        entityManager.clear();
        assertThat(creations.findById(creation.getId()).orElseThrow().getWorkflowSnapshot())
                .isEqualTo("{\"version\":1}");

        workflows.deleteById(workflow.getId());
        workflows.flush();
        entityManager.clear();
        Creation historical = creations.findById(creation.getId()).orElseThrow();
        assertThat(historical.getWorkflow()).isNull();
        assertThat(historical.getWorkflowSnapshot()).isEqualTo("{\"version\":1}");
    }

    @Test
    void invalidParentForeignKeyIsRejectedOnAnApplicationManagedConnection() {
        assertThat(jdbc.queryForObject("PRAGMA foreign_keys", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO media_assets (
                    public_id, owner_user_id, storage_key, original_filename,
                    asset_type, semantic_type, source_type, visibility, status,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID().toString(), Long.MAX_VALUE,
                "invalid/owner.png", "owner.png", "IMAGE", "UPLOAD",
                "USER_UPLOAD", "PRIVATE", "READY"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void deletingPaintingImageAssetSetsReferenceNull() {
        MediaAsset asset = mediaAssets.saveAndFlush(asset("delete/image.jpg", null));
        Painting painting = paintings.saveAndFlush(painting("painting:test:set-null", asset));
        jdbc.update("DELETE FROM media_assets WHERE id = ?", asset.getId());
        entityManager.clear();
        assertThat(paintings.findById(painting.getId()).orElseThrow().getImageAsset()).isNull();
    }

    @Test
    void guideAndCreationAssetReferencesPreventUnsafeParentDeletion() {
        Painting painting = paintings.saveAndFlush(painting("painting:test:restrict-guide", null));
        guides.saveAndFlush(guide(painting, "guide"));
        assertThatThrownBy(() -> jdbc.update("DELETE FROM paintings WHERE id = ?", painting.getId()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void creationAssetReferencesPreventSilentAssetDeletion() {
        User user = saveUser("asset-restrict");
        MediaAsset asset = mediaAssets.saveAndFlush(asset("delete/restrict.png", user));
        creations.saveAndFlush(creation(user, null, "IMAGE")
                .sourceAsset(asset).finalAsset(asset).finalModality("IMAGE").build());
        assertThatThrownBy(() -> jdbc.update("DELETE FROM media_assets WHERE id = ?", asset.getId()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void deletingUserCascadesPaintingFavoriteButUserHistoryIsRestricted() {
        User favoriteOnlyUser = saveUser("favorite-cascade");
        Painting painting = paintings.saveAndFlush(painting("painting:test:user-cascade", null));
        PaintingFavorite favorite = paintingFavorites.saveAndFlush(
                PaintingFavorite.builder().user(favoriteOnlyUser).painting(painting).build());
        jdbc.update("DELETE FROM users WHERE id = ?", favoriteOnlyUser.getId());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM painting_favorites WHERE id = ?", Integer.class, favorite.getId()))
                .isZero();
        entityManager.clear();

        User historyOwner = saveUser("history-restrict");
        workflows.saveAndFlush(UserWorkflow.builder().user(historyOwner).name("history")
                .graphJson("{}").schemaVersion(1).status("ACTIVE").build());
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", historyOwner.getId()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void deletingCreationCascadesStepsAndCreationFavoritesWithoutDeletingAssets() {
        User user = saveUser("creation-cascade");
        MediaAsset asset = mediaAssets.saveAndFlush(asset("delete/preserved.png", user));
        Creation creation = creations.saveAndFlush(creation(user, null, "IMAGE")
                .sourceAsset(asset).build());
        CreationStep step = steps.saveAndFlush(step(creation, 0, "node", asset, null));
        CreationFavorite favorite = creationFavorites.saveAndFlush(
                CreationFavorite.builder().user(user).creation(creation).build());

        jdbc.update("DELETE FROM creations WHERE id = ?", creation.getId());

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM creation_steps WHERE id = ?", Integer.class, step.getId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM creation_favorites WHERE id = ?", Integer.class, favorite.getId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM media_assets WHERE id = ?", Integer.class, asset.getId())).isOne();
    }

    private User saveUser(String suffix) {
        return users.saveAndFlush(User.builder()
                .username("round31-" + suffix)
                .password("test-hash-not-a-secret")
                .fullName("Round 3.1 Test")
                .email("round31-" + suffix + "@example.invalid")
                .build());
    }

    private MediaAsset asset(String storageKey, User owner) {
        return MediaAsset.builder()
                .ownerUser(owner)
                .storageKey(storageKey)
                .originalFilename(Path.of(storageKey).getFileName().toString())
                .mimeType("image/png")
                .fileSize(128L)
                .sha256("c".repeat(64))
                .width(64)
                .height(64)
                .assetType("IMAGE")
                .semanticType("UPLOAD")
                .sourceType(owner == null ? "CATALOG_REFERENCE" : "USER_UPLOAD")
                .visibility(owner == null ? "PUBLIC" : "PRIVATE")
                .status("READY")
                .build();
    }

    private Painting painting(String sourceKey, MediaAsset imageAsset) {
        return Painting.builder()
                .sourceKey(sourceKey)
                .imageStorageName(sourceKey.substring(sourceKey.lastIndexOf(':') + 1) + ".jpg")
                .title("Synthetic painting")
                .generatedText("official annotation")
                .musicSceneDescription("official music annotation")
                .imageAsset(imageAsset)
                .imageAvailable(imageAsset != null)
                .visibleInGallery(true)
                .status("ACTIVE")
                .build();
    }

    private PaintingGuide guide(Painting painting, String marker) {
        return PaintingGuide.builder()
                .painting(painting)
                .resultJson("{\"guide\":\"" + marker + "\"}")
                .sourceHash("d".repeat(64))
                .status("READY")
                .build();
    }

    private Creation.CreationBuilder<?, ?> creation(User user, UserWorkflow workflow, String sourceModality) {
        return Creation.builder()
                .user(user)
                .workflow(workflow)
                .workflowSnapshot(workflow == null ? "{\"nodes\":[]}" : workflow.getGraphJson())
                .sourceModality(sourceModality)
                .status("COMPLETED");
    }

    private CreationStep step(
            Creation creation,
            int index,
            String nodeId,
            MediaAsset inputAsset,
            MediaAsset outputAsset) {
        return CreationStep.builder()
                .creation(creation)
                .stepIndex(index)
                .nodeId(nodeId)
                .operationCode("TEST_OPERATION")
                .providerCode("test-provider")
                .inputModality(inputAsset == null ? "TEXT_DESCRIPTION" : "IMAGE")
                .outputModality(outputAsset == null ? "POEM" : "IMAGE")
                .inputJson("{}")
                .parametersJson("{}")
                .outputJson("{}")
                .inputAsset(inputAsset)
                .outputAsset(outputAsset)
                .status("SUCCEEDED")
                .attemptCount(1)
                .build();
    }
}
