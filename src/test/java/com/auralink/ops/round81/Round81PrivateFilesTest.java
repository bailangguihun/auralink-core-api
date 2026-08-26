package com.auralink.ops.round81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

class Round81PrivateFilesTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesPrivateAtomicEvidenceAndBoundedArtifact() throws Exception {
        Path run = Files.createDirectory(
                temporaryDirectory.resolve("run"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        byte[] bytes = "validated-private-artifact".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));

        Path json = Round81PrivateFiles.writeJson(
                new ObjectMapper(), run, "safe.json", Map.of("status", "STRUCTURALLY_VALID"));
        Path artifact = Round81PrivateFiles.copyArtifact(
                run, "result.bin", new ByteArrayInputStream(bytes), bytes.length, sha256);

        assertThat(Files.getPosixFilePermissions(json))
                .containsExactlyInAnyOrderElementsOf(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(artifact))
                .containsExactlyInAnyOrderElementsOf(PosixFilePermissions.fromString("rw-------"));
        assertThat(Round81PrivateFiles.sha256(artifact)).isEqualTo(sha256);
        try (var files = Files.list(run)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .doesNotContain(".result.bin.part");
        }
    }

    @Test
    void rejectsTraversalSymlinkAndChecksumMismatch() throws Exception {
        Path run = Files.createDirectory(
                temporaryDirectory.resolve("run"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path outside = Files.writeString(temporaryDirectory.resolve("outside"), "outside");
        Files.createSymbolicLink(run.resolve("input-image.png"), outside);

        assertThatThrownBy(() -> Round81PrivateFiles.requireContainedRegularFile(
                run, "input-image.png"))
                .isInstanceOf(Round81ValidationException.class);
        assertThatThrownBy(() -> Round81PrivateFiles.copyArtifact(
                run,
                "../escape.bin",
                new ByteArrayInputStream(new byte[] {1}),
                1,
                "00".repeat(32)))
                .isInstanceOf(Round81ValidationException.class);
        assertThatThrownBy(() -> Round81PrivateFiles.copyArtifact(
                run,
                "bad.bin",
                new ByteArrayInputStream(new byte[] {1}),
                1,
                "00".repeat(32)))
                .isInstanceOf(Round81ValidationException.class);
        assertThat(run.resolve("bad.bin")).doesNotExist();
    }
}
