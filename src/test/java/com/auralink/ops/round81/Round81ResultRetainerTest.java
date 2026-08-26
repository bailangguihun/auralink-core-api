package com.auralink.ops.round81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.config.properties.MediaAssetProperties;
import com.auralink.creation.provider.ProviderBinaryOutput;
import com.auralink.creation.provider.ProviderExecutionResult;
import com.auralink.provider.ProviderTestFixtures;
import com.auralink.provider.artifact.AudioOutputValidator;
import com.auralink.provider.artifact.ProviderArtifact;
import com.auralink.provider.artifact.ProviderArtifactStagingService;
import com.auralink.service.media.ImageContentValidator;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;
import com.fasterxml.jackson.databind.ObjectMapper;

class Round81ResultRetainerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void retainsValidatedImageIndependentlyFromTransientArtifact() throws Exception {
        Fixture fixture = fixture();
        ProviderArtifact artifact = fixture.staging().stageOutputImage(
                new ByteArrayInputStream(ProviderTestFixtures.png()), "image/png");
        try {
            Round81RetainedResult retained = fixture.retainer().retain(
                    Round81ValidationOperation.TEXT_TO_PAINTING,
                    new ProviderExecutionResult(
                            "safe-request",
                            WorkflowOperation.TEXT_TO_PAINTING,
                            "seedream-5",
                            WorkflowModality.PAINTING,
                            new ProviderBinaryOutput(artifact)),
                    fixture.run());
            artifact.close();

            assertThat(retained.structuralState()).isEqualTo("STRUCTURALLY_VALID");
            assertThat(fixture.run().resolve(retained.resultFile())).isRegularFile();
            assertThat(Files.getPosixFilePermissions(fixture.run().resolve(retained.resultFile())))
                    .containsExactlyInAnyOrderElementsOf(PosixFilePermissions.fromString("rw-------"));
            try (var staged = Files.list(fixture.stagingRoot())) {
                assertThat(staged).isEmpty();
            }
        } finally {
            artifact.close();
        }
    }

    @Test
    void deletesRetainedCopyWhenOutputBytesDoNotMatchDeclaredModality() throws Exception {
        Fixture fixture = fixture();
        ProviderArtifact wave = fixture.staging().stageOutputWave(
                new ByteArrayInputStream(ProviderTestFixtures.wave()));
        try {
            assertThatThrownBy(() -> fixture.retainer().retain(
                    Round81ValidationOperation.TEXT_TO_PAINTING,
                    new ProviderExecutionResult(
                            "safe-request",
                            WorkflowOperation.TEXT_TO_PAINTING,
                            "seedream-5",
                            WorkflowModality.PAINTING,
                            new ProviderBinaryOutput(wave)),
                    fixture.run()))
                    .isInstanceOf(Round81ValidationException.class);
            assertThat(fixture.run().resolve("validated-result.wav")).doesNotExist();
            assertThat(fixture.run().resolve("result-metadata.json")).doesNotExist();
        } finally {
            wave.close();
        }
    }

    private Fixture fixture() throws Exception {
        Path run = Files.createDirectory(
                temporaryDirectory.resolve("run-" + java.util.UUID.randomUUID()),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path stagingRoot = temporaryDirectory.resolve("staging-" + java.util.UUID.randomUUID());
        CreationProviderProperties properties = ProviderTestFixtures.properties(stagingRoot);
        ProviderArtifactStagingService staging = ProviderTestFixtures.staging(properties);
        MediaAssetProperties mediaProperties = new MediaAssetProperties();
        Round81ResultRetainer retainer = new Round81ResultRetainer(
                new ObjectMapper(),
                properties,
                new ImageContentValidator(mediaProperties),
                new AudioOutputValidator());
        return new Fixture(run, stagingRoot, staging, retainer);
    }

    private record Fixture(
            Path run,
            Path stagingRoot,
            ProviderArtifactStagingService staging,
            Round81ResultRetainer retainer) {
    }
}
