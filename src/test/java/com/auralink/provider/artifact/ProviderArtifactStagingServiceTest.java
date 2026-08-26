package com.auralink.provider.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.provider.ProviderTestFixtures;

class ProviderArtifactStagingServiceTest {

    @TempDir
    Path temporaryDirectory;

    private CreationProviderProperties properties;
    private ProviderArtifactStagingService staging;

    @BeforeEach
    void setUp() {
        properties = ProviderTestFixtures.properties(temporaryDirectory.resolve("staging"));
        staging = ProviderTestFixtures.staging(properties);
    }

    @Test
    void stagesValidatedPngWithDigestDimensionsAndReplayableStream() throws Exception {
        byte[] png = ProviderTestFixtures.png();

        ProviderArtifact artifact = staging.stageInputImage(
                new ByteArrayInputStream(png), "image/png");

        assertThat(artifact.mimeType()).isEqualTo("image/png");
        assertThat(artifact.fileExtension()).isEqualTo("png");
        assertThat(artifact.byteLength()).isEqualTo(png.length);
        assertThat(artifact.sha256()).isEqualTo(sha256(png));
        assertThat(artifact.width()).isEqualTo(3);
        assertThat(artifact.height()).isEqualTo(2);
        assertThat(artifact.openStream().readAllBytes()).isEqualTo(png);
        assertThat(artifact.openStream().readAllBytes()).isEqualTo(png);
        assertThat(artifact.isAvailable()).isTrue();

        artifact.close();
        artifact.close();
        assertThat(artifact.isAvailable()).isFalse();
        assertStagingEmpty();
    }

    @Test
    void stagesValidatedJpegAndDerivesActualMime() {
        byte[] jpeg = ProviderTestFixtures.jpeg();

        try (ProviderArtifact artifact = staging.stageOutputImage(
                new ByteArrayInputStream(jpeg), "image/jpeg")) {
            assertThat(artifact.mimeType()).isEqualTo("image/jpeg");
            assertThat(artifact.fileExtension()).isEqualTo("jpg");
        }

        assertStagingEmpty();
    }

    @Test
    void rejectsDeclaredMimeMismatchAndAppendedPayloadWithoutRetainingFiles() throws Exception {
        byte[] jpeg = ProviderTestFixtures.jpeg();
        byte[] appended = java.util.Arrays.copyOf(jpeg, jpeg.length + 1);
        appended[appended.length - 1] = 0x41;

        assertThatThrownBy(() -> staging.stageInputImage(
                new ByteArrayInputStream(jpeg), "image/png"))
                .isInstanceOf(ProviderExecutionException.class)
                .extracting(exception -> ((ProviderExecutionException) exception).category())
                .isEqualTo(ProviderErrorCategory.PROVIDER_OUTPUT_INVALID);
        assertThatThrownBy(() -> staging.stageOutputImage(
                new ByteArrayInputStream(appended), "image/jpeg"))
                .isInstanceOf(ProviderExecutionException.class);

        assertStagingEmpty();
    }

    @Test
    void enforcesInputAndOutputByteLimitsDuringStreaming() {
        properties.setMaxImageInputBytes(10);
        properties.setMaxImageOutputBytes(10);

        assertThatThrownBy(() -> staging.stageInputImage(
                new ByteArrayInputStream(ProviderTestFixtures.png()), "image/png"))
                .isInstanceOf(ProviderExecutionException.class)
                .extracting(exception -> ((ProviderExecutionException) exception).category())
                .isEqualTo(ProviderErrorCategory.PROVIDER_OUTPUT_INVALID);
        assertThatThrownBy(() -> staging.stageOutputImage(
                target -> Files.write(target, new byte[11])))
                .isInstanceOf(ProviderExecutionException.class);

        assertStagingEmpty();
    }

    @Test
    void writerFailureCleansPartialTarget() {
        assertThatThrownBy(() -> staging.stageOutputImage(target -> {
            Files.write(target, new byte[] {1, 2, 3});
            throw new IllegalStateException("synthetic writer failure");
        })).isInstanceOf(ProviderExecutionException.class)
                .hasMessageNotContaining("synthetic writer failure");

        assertStagingEmpty();
    }

    @Test
    void rejectsWriterThatReplacesControlledTargetWithSymlink() throws Exception {
        Path outside = temporaryDirectory.resolve("outside.png");
        Files.write(outside, ProviderTestFixtures.png());

        assertThatThrownBy(() -> staging.stageOutputImage(target -> {
            Files.delete(target);
            Files.createSymbolicLink(target, outside);
        })).isInstanceOf(ProviderExecutionException.class);

        assertThat(Files.exists(outside)).isTrue();
        assertStagingEmpty();
    }

    @Test
    void stagesValidWaveWithDigestAndCleansIdempotently() {
        byte[] wave = ProviderTestFixtures.wave();

        ProviderArtifact artifact = staging.stageOutputWave(new ByteArrayInputStream(wave));

        assertThat(artifact.mimeType()).isEqualTo("audio/wav");
        assertThat(artifact.fileExtension()).isEqualTo("wav");
        assertThat(artifact.byteLength()).isEqualTo(wave.length);
        assertThat(artifact.sha256()).isEqualTo(sha256(wave));
        assertThat(artifact.width()).isNull();
        assertThat(artifact.height()).isNull();

        artifact.close();
        artifact.close();
        assertStagingEmpty();
    }

    @Test
    void rejectsInvalidRiffLengthSignatureAndOversizedAudio() {
        byte[] badSignature = ProviderTestFixtures.wave();
        badSignature[0] = 'X';
        byte[] badLength = ProviderTestFixtures.wave();
        badLength[4] = 0;
        properties.setMaxAudioOutputBytes(20);

        assertThatThrownBy(() -> staging.stageOutputWave(new ByteArrayInputStream(badSignature)))
                .isInstanceOf(ProviderExecutionException.class);
        assertThatThrownBy(() -> staging.stageOutputWave(new ByteArrayInputStream(badLength)))
                .isInstanceOf(ProviderExecutionException.class);
        assertThatThrownBy(() -> staging.stageOutputWave(
                new ByteArrayInputStream(ProviderTestFixtures.wave())))
                .isInstanceOf(ProviderExecutionException.class);

        assertStagingEmpty();
    }

    @Test
    void rejectsSymlinkStagingRoot() throws Exception {
        Path real = temporaryDirectory.resolve("real-root");
        Files.createDirectory(real);
        Path link = temporaryDirectory.resolve("linked-root");
        Files.createSymbolicLink(link, real);
        properties.setStagingDir(link);

        assertThatThrownBy(() -> staging.stageInputImage(
                new ByteArrayInputStream(ProviderTestFixtures.png()), "image/png"))
                .isInstanceOf(ProviderExecutionException.class);
    }

    @Test
    void rejectsRelativeStagingRootInsteadOfUsingProcessDirectory() {
        properties.setStagingDir(Path.of("relative-provider-staging"));

        assertThatThrownBy(() -> staging.stageInputImage(
                new ByteArrayInputStream(ProviderTestFixtures.png()), "image/png"))
                .isInstanceOf(ProviderExecutionException.class)
                .extracting(exception -> ((ProviderExecutionException) exception).category())
                .isEqualTo(ProviderErrorCategory.PROVIDER_OUTPUT_INVALID);
    }

    @Test
    void rejectsArtifactMutationBeforeReplay() throws Exception {
        ProviderArtifact artifact = staging.stageInputImage(
                new ByteArrayInputStream(ProviderTestFixtures.png()), "image/png");
        Path stagedFile;
        try (var files = Files.list(properties.getStagingDir())) {
            stagedFile = files.findFirst().orElseThrow();
        }
        Files.write(stagedFile, new byte[] {1}, StandardOpenOption.APPEND);

        assertThatThrownBy(artifact::openStream)
                .isInstanceOf(ProviderExecutionException.class)
                .extracting(exception -> ((ProviderExecutionException) exception).category())
                .isEqualTo(ProviderErrorCategory.PROVIDER_OUTPUT_INVALID);

        artifact.close();
        assertStagingEmpty();
    }

    @Test
    void rejectsSameLengthArtifactMutationBeforeReplay() throws Exception {
        ProviderArtifact artifact = staging.stageInputImage(
                new ByteArrayInputStream(ProviderTestFixtures.png()), "image/png");
        Path stagedFile;
        try (var files = Files.list(properties.getStagingDir())) {
            stagedFile = files.findFirst().orElseThrow();
        }
        byte[] changed = Files.readAllBytes(stagedFile);
        changed[changed.length / 2] ^= 1;
        Files.write(stagedFile, changed, StandardOpenOption.TRUNCATE_EXISTING);

        assertThatThrownBy(artifact::openStream)
                .isInstanceOf(ProviderExecutionException.class)
                .extracting(exception -> ((ProviderExecutionException) exception).category())
                .isEqualTo(ProviderErrorCategory.PROVIDER_OUTPUT_INVALID);

        artifact.close();
        assertStagingEmpty();
    }

    @Test
    void closeRemovesReplacedSymlinkWithoutFollowingItsTarget() throws Exception {
        ProviderArtifact artifact = staging.stageInputImage(
                new ByteArrayInputStream(ProviderTestFixtures.png()), "image/png");
        Path stagedFile;
        try (var files = Files.list(properties.getStagingDir())) {
            stagedFile = files.findFirst().orElseThrow();
        }
        Path outside = temporaryDirectory.resolve("outside-owned-by-someone-else.png");
        Files.write(outside, ProviderTestFixtures.png());
        Files.delete(stagedFile);
        Files.createSymbolicLink(stagedFile, outside);

        artifact.close();
        artifact.close();

        assertThat(Files.exists(stagedFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(Files.readAllBytes(outside)).isEqualTo(ProviderTestFixtures.png());
        assertStagingEmpty();
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private void assertStagingEmpty() {
        Path root = properties.getStagingDir();
        if (!Files.exists(root)) {
            return;
        }
        try (var files = Files.list(root)) {
            assertThat(files.toList()).isEmpty();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
