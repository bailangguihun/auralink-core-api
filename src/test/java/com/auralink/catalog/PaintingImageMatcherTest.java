package com.auralink.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaintingImageMatcherTest {

    @TempDir
    Path temporaryDirectory;

    private final PaintingImageMatcher matcher = new PaintingImageMatcher();

    @Test
    void scansOnlyDirectRegularFilesIntoStableFilenameOrder() throws IOException {
        Files.writeString(temporaryDirectory.resolve("z.jpg"), "z");
        Files.writeString(temporaryDirectory.resolve("unsupported.png"), "png");
        Path first = Files.writeString(temporaryDirectory.resolve("a.jpg"), "abc");
        Files.setLastModifiedTime(first, FileTime.fromMillis(1_234L));
        Files.createDirectories(temporaryDirectory.resolve("nested"));
        Files.writeString(temporaryDirectory.resolve("nested/ignored.jpg"), "nested");
        Path symlink = temporaryDirectory.resolve("linked.jpg");
        try {
            Files.createSymbolicLink(symlink, first.getFileName());
        } catch (UnsupportedOperationException | SecurityException | IOException ignored) {
            // Filesystems without symlink support still exercise non-recursive scanning.
        }

        CatalogImageManifest manifest = matcher.scan(temporaryDirectory);

        assertThat(manifest.entries())
                .extracting(ImageManifestEntry::fileName)
                .containsExactly("a.jpg", "z.jpg");
        assertThat(manifest.fileCount()).isEqualTo(2);
        ImageManifestEntry entry = manifest.find("a.jpg").orElseThrow();
        assertThat(entry.size()).isEqualTo(3L);
        assertThat(entry.lastModifiedMillis()).isEqualTo(1_234L);
        assertThat(manifest.entries())
                .extracting(ImageManifestEntry::fileName)
                .doesNotContain("ignored.jpg", "linked.jpg");
    }

    @Test
    void matchesExactJpgBareNameAndExistingJpegExtension() {
        CatalogImageManifest manifest = manifest("exact.jpg", "bare.jpg", "photo.jpeg");

        assertThat(matcher.match("exact.jpg", manifest)).contains("exact.jpg");
        assertThat(matcher.match("bare", manifest)).contains("bare.jpg");
        assertThat(matcher.match("photo.jpeg", manifest)).contains("photo.jpeg");
        assertThat(matcher.match("EXACT.JPG", manifest)).contains("exact.jpg");
    }

    @Test
    void retainsLegacyParenthesisAndSpacingNormalization() {
        CatalogImageManifest manifest = manifest("1(1).jpg", "2 (3).jpg");

        assertThat(matcher.match("1（1）", manifest)).contains("1(1).jpg");
        assertThat(matcher.match("2(3)", manifest)).contains("2 (3).jpg");
        assertThat(matcher.candidatesFor("1（1）"))
                .containsExactly(
                        "1（1）.jpg", "1(1).jpg", "1 (1).jpg",
                        "1（1）.jpeg", "1(1).jpeg", "1 (1).jpeg");
    }

    @Test
    void returnsEmptyForMissingOrBlankNamesAndRejectsControlCharacters() {
        CatalogImageManifest manifest = manifest("present.jpg");

        assertThat(matcher.match("missing", manifest)).isEmpty();
        assertThat(matcher.match("  ", manifest)).isEmpty();
        assertThatThrownBy(() -> matcher.match("present\n.jpg", manifest))
                .isInstanceOf(CatalogSourceException.class)
                .hasMessageContaining("safe filename");
    }

    @Test
    void rejectsAmbiguousLegacyCandidatesInsteadOfChoosingByCandidateOrder() {
        CatalogImageManifest manifest = manifest("1（1）.jpg", "1(1).jpg");

        assertThatThrownBy(() -> matcher.match("1（1）", manifest))
                .isInstanceOf(CatalogSourceException.class)
                .hasMessageContaining("ambiguously");

        CatalogImageManifest extensionAmbiguity = manifest("same.jpg", "same.jpeg");
        assertThatThrownBy(() -> matcher.match("same", extensionAmbiguity))
                .isInstanceOf(CatalogSourceException.class)
                .hasMessageContaining("ambiguously");
    }

    @Test
    void manifestRejectsDuplicateOrPathBearingFileNames() {
        assertThatThrownBy(() -> new CatalogImageManifest(List.of(
                new ImageManifestEntry("same.jpg", 1, 1),
                new ImageManifestEntry("same.jpg", 2, 2))))
                .isInstanceOf(CatalogSourceException.class)
                .hasMessageContaining("duplicate filename");
        assertThatThrownBy(() -> new ImageManifestEntry("../escape.jpg", 1, 1))
                .isInstanceOf(CatalogSourceException.class)
                .hasMessageContaining("invalid filename");
        assertThatThrownBy(() -> new ImageManifestEntry("nested/escape.jpg", 1, 1))
                .isInstanceOf(CatalogSourceException.class)
                .hasMessageContaining("invalid filename");
        assertThatThrownBy(() -> new CatalogImageManifest(List.of(
                new ImageManifestEntry("CASE.jpg", 1, 1),
                new ImageManifestEntry("case.JPG", 2, 2))))
                .isInstanceOf(CatalogSourceException.class)
                .hasMessageContaining("case-insensitive");
    }

    @Test
    void rejectsTraversalAbsoluteAndWindowsStyleStorageNames() {
        CatalogImageManifest manifest = manifest("inside.jpg");

        assertUnsafeName("../inside.jpg", manifest);
        assertUnsafeName("nested/inside.jpg", manifest);
        assertUnsafeName("nested\\inside.jpg", manifest);
        assertUnsafeName("/inside.jpg", manifest);
        assertUnsafeName("C:\\inside.jpg", manifest);
        assertUnsafeName("..", manifest);
    }

    @Test
    void unavailableOrNullPictureDirectoryFailsWithoutDisclosingItsPath() {
        Path missing = temporaryDirectory.resolve("sensitive-server-path");
        assertThatThrownBy(() -> matcher.scan(missing))
                .isInstanceOf(CatalogSourceException.class)
                .hasMessage("Catalog picture directory is unavailable")
                .hasMessageNotContaining(missing.toString());
        assertThatThrownBy(() -> matcher.scan(null))
                .isInstanceOf(CatalogSourceException.class)
                .hasMessage("Catalog picture directory is required");
    }

    private CatalogImageManifest manifest(String... fileNames) {
        return new CatalogImageManifest(java.util.Arrays.stream(fileNames)
                .map(name -> new ImageManifestEntry(name, 1, 1))
                .toList());
    }

    private void assertUnsafeName(String value, CatalogImageManifest manifest) {
        assertThatThrownBy(() -> matcher.match(value, manifest))
                .isInstanceOf(CatalogSourceException.class)
                .hasMessageContaining("safe filename");
    }
}
