package com.auralink.service.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.auralink.config.properties.MediaAssetProperties;
import com.auralink.config.properties.PaintingProperties;
import com.auralink.exception.InvalidStoragePathException;
import com.auralink.media.MediaAssetValues;

class MediaAssetStorageResolverTest {

    @TempDir
    Path temporaryDirectory;

    private Path managedRoot;
    private Path catalogRoot;
    private MediaAssetStorageResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        managedRoot = Files.createDirectories(temporaryDirectory.resolve("managed"));
        catalogRoot = Files.createDirectories(temporaryDirectory.resolve("catalog"));
        MediaAssetProperties assets = new MediaAssetProperties();
        assets.setManagedDir(managedRoot.toString());
        PaintingProperties paintings = new PaintingProperties();
        paintings.setPictureDir(catalogRoot.toString());
        resolver = new MediaAssetStorageResolver(assets, paintings);
    }

    @Test
    void resolvesOnlyTheNamespaceAssignedToTheSourceFamily() throws Exception {
        Path managed = managedRoot.resolve("user-upload/7/2026/08/a.jpg");
        Files.createDirectories(managed.getParent());
        Files.writeString(managed, "managed");
        Path catalog = catalogRoot.resolve("nested/painting.jpg");
        Files.createDirectories(catalog.getParent());
        Files.writeString(catalog, "catalog");

        assertThat(resolver.resolveForRead(
                MediaAssetValues.SourceType.USER_UPLOAD,
                "managed/user-upload/7/2026/08/a.jpg"))
                .isEqualTo(managed.toRealPath());
        assertThat(resolver.resolveForRead(
                MediaAssetValues.SourceType.CATALOG_REFERENCE,
                "catalog/nested/painting.jpg"))
                .isEqualTo(catalog.toRealPath());
        assertThat(resolver.toCatalogStorageKey("nested/painting.jpg"))
                .isEqualTo("catalog/nested/painting.jpg");

        assertThatThrownBy(() -> resolver.resolveForRead(
                MediaAssetValues.SourceType.CATALOG_REFERENCE,
                "managed/user-upload/7/2026/08/a.jpg"))
                .isInstanceOf(InvalidStoragePathException.class);
        assertThatThrownBy(() -> resolver.resolveForRead(
                MediaAssetValues.SourceType.GENERATED,
                "catalog/nested/painting.jpg"))
                .isInstanceOf(InvalidStoragePathException.class);
    }

    @Test
    void rejectsTraversalWindowsUncAbsoluteControlAndDotSegments() {
        String[] invalidManagedKeys = {
                "managed/../outside.jpg",
                "managed/nested/../../outside.jpg",
                "managed/C:/Windows/file.jpg",
                "managed/C:\\Windows\\file.jpg",
                "managed/\\\\server\\share\\file.jpg",
                "managed//absolute.jpg",
                "managed/./file.jpg",
                "managed/file\nname.jpg"
        };

        for (String key : invalidManagedKeys) {
            assertThatThrownBy(() -> resolver.resolveManagedForWrite(key))
                    .as(key)
                    .isInstanceOf(InvalidStoragePathException.class);
        }
        assertThatThrownBy(() -> resolver.toCatalogStorageKey("../outside.jpg"))
                .isInstanceOf(InvalidStoragePathException.class);
        assertThatThrownBy(() -> resolver.toCatalogStorageKey("C:\\outside.jpg"))
                .isInstanceOf(InvalidStoragePathException.class);
    }

    @Test
    void rejectsSymlinkEscapeFromBothRoots() throws Exception {
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        Path secret = Files.writeString(outside.resolve("secret.jpg"), "secret");
        assumeTrue(canCreateSymlink(managedRoot.resolve("escape"), outside));
        assumeTrue(canCreateSymlink(catalogRoot.resolve("escape"), outside));

        assertThatThrownBy(() -> resolver.resolveManagedForRead("managed/escape/secret.jpg"))
                .isInstanceOf(InvalidStoragePathException.class);
        assertThatThrownBy(() -> resolver.resolveCatalogForRead("catalog/escape/secret.jpg"))
                .isInstanceOf(InvalidStoragePathException.class);
        assertThat(secret).exists();
    }

    private boolean canCreateSymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            return false;
        }
    }
}
