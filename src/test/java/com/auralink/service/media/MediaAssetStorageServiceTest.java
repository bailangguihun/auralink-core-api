package com.auralink.service.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.auralink.config.properties.MediaAssetProperties;
import com.auralink.config.properties.PaintingProperties;
import com.auralink.entity.MediaAsset;
import com.auralink.exception.InvalidStoragePathException;
import com.auralink.media.MediaAssetValues;
import com.auralink.service.media.MediaAssetStorageService.CatalogMediaFile;
import com.auralink.service.media.MediaAssetStorageService.MediaAssetStoredResource;
import com.auralink.service.media.MediaAssetStorageService.StagedMediaFile;
import com.auralink.service.media.MediaAssetStorageService.StoredMediaFile;

class MediaAssetStorageServiceTest {

    @TempDir
    Path temporaryDirectory;

    private Path managedRoot;
    private Path catalogRoot;
    private MediaAssetProperties properties;
    private MediaAssetStorageService storage;

    @BeforeEach
    void setUp() throws Exception {
        managedRoot = temporaryDirectory.resolve("managed");
        catalogRoot = Files.createDirectories(temporaryDirectory.resolve("catalog"));
        properties = new MediaAssetProperties();
        properties.setManagedDir(managedRoot.toString());
        properties.setMaxUploadBytes(8);
        properties.setMaxGeneratedBytes(32);
        PaintingProperties paintings = new PaintingProperties();
        paintings.setPictureDir(catalogRoot.toString());
        MediaAssetStorageResolver resolver = new MediaAssetStorageResolver(properties, paintings);
        storage = new MediaAssetStorageService(properties, resolver);
    }

    @Test
    void stagesWithHardLimitAndDigestThenAtomicallyCommitsAndResolves() throws Exception {
        byte[] bytes = "content".getBytes(StandardCharsets.UTF_8);
        StagedMediaFile staged = storage.stageUserUpload(new ByteArrayInputStream(bytes));

        assertThat(staged.size()).isEqualTo(bytes.length);
        assertThat(staged.sha256()).isEqualTo(
                "ed7002b439e9ac845f22357d822bac1444730fbdb6016d3ec9432297b9ec9f73");
        assertThat(staged.path()).isRegularFile();

        String key = "managed/user-upload/7/2026/08/asset.jpg";
        StoredMediaFile stored = storage.commitManaged(staged, key);
        assertThat(stored.storageKey()).isEqualTo(key);
        assertThat(staged.path()).doesNotExist();

        MediaAsset entity = MediaAsset.builder()
                .storageKey(key)
                .originalFilename("original.jpg")
                .assetType(MediaAssetValues.AssetType.IMAGE)
                .semanticType(MediaAssetValues.SemanticType.IMAGE)
                .sourceType(MediaAssetValues.SourceType.USER_UPLOAD)
                .visibility(MediaAssetValues.Visibility.PRIVATE)
                .status(MediaAssetValues.Status.ACTIVE)
                .build();
        MediaAssetStoredResource resource = storage.resolve(entity);
        assertThat(resource.contentLength()).isEqualTo(bytes.length);
        assertThat(resource.resource().getFile().toPath().toRealPath())
                .startsWith(managedRoot.toRealPath());

        storage.deleteManaged(key);
        assertThat(resource.resource().getFile()).doesNotExist();
    }

    @Test
    void oversizedInputRemovesItsTemporaryFile() throws Exception {
        assertThatThrownBy(() -> storage.stageUserUpload(
                new ByteArrayInputStream("123456789".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(MediaAssetSizeLimitException.class);

        Path staging = managedRoot.resolve(".staging");
        assertThat(staging).isDirectory();
        try (var entries = Files.list(staging)) {
            assertThat(entries).isEmpty();
        }
    }

    @Test
    void userAndGeneratedLimitsAreIndependent() {
        byte[] twelveBytes = "123456789012".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> storage.stageUserUpload(new ByteArrayInputStream(twelveBytes)))
                .isInstanceOf(MediaAssetSizeLimitException.class);
        StagedMediaFile generated = storage.stageGenerated(new ByteArrayInputStream(twelveBytes));
        assertThat(generated.size()).isEqualTo(12);
        storage.discardStaged(generated);
    }

    @Test
    void uploadLimitMayBeConfiguredAboveGeneratedLimit() {
        properties.setMaxUploadBytes(64);
        properties.setMaxGeneratedBytes(8);
        byte[] twelveBytes = "123456789012".getBytes(StandardCharsets.UTF_8);

        StagedMediaFile upload = storage.stageUserUpload(new ByteArrayInputStream(twelveBytes));
        assertThat(upload.size()).isEqualTo(12);
        storage.discardStaged(upload);
        assertThatThrownBy(() -> storage.stageGenerated(new ByteArrayInputStream(twelveBytes)))
                .isInstanceOf(MediaAssetSizeLimitException.class);
    }

    @Test
    void catalogInspectionDoesNotCopyAndReturnsDeterministicLogicalKey() throws Exception {
        Path catalogFile = catalogRoot.resolve("nested/painting.jpg");
        Files.createDirectories(catalogFile.getParent());
        Files.writeString(catalogFile, "catalog-image");

        CatalogMediaFile inspected = storage.inspectCatalogReference("nested/painting.jpg");

        assertThat(inspected.storageKey()).isEqualTo("catalog/nested/painting.jpg");
        assertThat(inspected.path()).isEqualTo(catalogFile.toRealPath());
        assertThat(inspected.size()).isEqualTo(Files.size(catalogFile));
        assertThat(managedRoot).doesNotExist();
    }

    @Test
    void managedCommitNeverReplacesAndCleanupNeverAcceptsCatalogOrForgedStagePaths() {
        String key = "managed/generated/7/2026/08/output.wav";
        StagedMediaFile first = storage.stageGenerated(new ByteArrayInputStream(new byte[] {1}));
        storage.commitManaged(first, key);

        StagedMediaFile second = storage.stageGenerated(new ByteArrayInputStream(new byte[] {2}));
        assertThatThrownBy(() -> storage.commitManaged(second, key))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("already exists");
        storage.discardStaged(second);

        assertThatThrownBy(() -> storage.deleteManaged("catalog/painting.jpg"))
                .isInstanceOf(InvalidStoragePathException.class);
        StagedMediaFile forged = new StagedMediaFile(
                temporaryDirectory.resolve("unrelated.tmp"), 0, "0".repeat(64));
        assertThatThrownBy(() -> storage.discardStaged(forged))
                .isInstanceOf(InvalidStoragePathException.class);
    }
}
