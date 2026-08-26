package com.auralink.provider.seedream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.file.Files;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.provider.ProviderTestFixtures;
import com.auralink.provider.artifact.ProviderArtifact;
import com.auralink.provider.artifact.ProviderArtifactStagingService;
import com.auralink.service.SafeRemoteResourceFetcher;

class SafeSeedreamResultFetcherTest {

    @TempDir
    java.nio.file.Path temporaryDirectory;

    private CreationProviderProperties properties;
    private SafeRemoteResourceFetcher remote;
    private SafeSeedreamResultFetcher fetcher;

    @BeforeEach
    void setUp() {
        properties = ProviderTestFixtures.properties(temporaryDirectory.resolve("staging"));
        ProviderArtifactStagingService staging = ProviderTestFixtures.staging(properties);
        remote = mock(SafeRemoteResourceFetcher.class);
        fetcher = new SafeSeedreamResultFetcher(remote, staging);
    }

    @Test
    void stagesAndValidatesDownloadedPngWithoutForwardingProviderHeaders() throws Exception {
        doAnswer(invocation -> {
            Files.write(invocation.getArgument(1), ProviderTestFixtures.png());
            return null;
        }).when(remote).fetchTo(eq("https://signed.example/result.png"), any());

        try (ProviderArtifact artifact = fetcher.fetch("https://signed.example/result.png")) {
            assertThat(artifact.mimeType()).isEqualTo("image/png");
            assertThat(artifact.width()).isEqualTo(3);
            assertThat(artifact.height()).isEqualTo(2);
            assertThat(artifact.sha256()).matches("[0-9a-f]{64}");
        }
        verify(remote).fetchTo(eq("https://signed.example/result.png"), any());
        assertStagingEmpty();
    }

    @Test
    void stagesAndValidatesDownloadedJpeg() throws Exception {
        doAnswer(invocation -> {
            Files.write(invocation.getArgument(1), ProviderTestFixtures.jpeg());
            return null;
        }).when(remote).fetchTo(eq("https://signed.example/result.jpg"), any());

        try (ProviderArtifact artifact = fetcher.fetch("https://signed.example/result.jpg")) {
            assertThat(artifact.mimeType()).isEqualTo("image/jpeg");
        }
        assertStagingEmpty();
    }

    @Test
    void rejectsUnsafeOrFailedRemoteFetchAndLeavesNoArtifact() throws Exception {
        doAnswer(invocation -> {
            throw new java.io.IOException("private destination rejected");
        }).when(remote).fetchTo(eq("http://127.0.0.1/result.png"), any());

        assertThatThrownBy(() -> fetcher.fetch("http://127.0.0.1/result.png"))
                .isInstanceOf(ProviderExecutionException.class)
                .extracting(exception -> ((ProviderExecutionException) exception).category())
                .isEqualTo(ProviderErrorCategory.PROVIDER_OUTPUT_INVALID);
        assertStagingEmpty();
    }

    @Test
    void rejectsDownloadedPolyglotAndLeavesNoArtifact() throws Exception {
        byte[] png = ProviderTestFixtures.png();
        byte[] active = java.util.Arrays.copyOf(png, png.length + 16);
        System.arraycopy("<script>x</script>".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                0, active, png.length, 16);
        doAnswer(invocation -> {
            Files.write(invocation.getArgument(1), active);
            return null;
        }).when(remote).fetchTo(eq("https://signed.example/polyglot.png"), any());

        assertThatThrownBy(() -> fetcher.fetch("https://signed.example/polyglot.png"))
                .isInstanceOf(ProviderExecutionException.class)
                .extracting(exception -> ((ProviderExecutionException) exception).category())
                .isEqualTo(ProviderErrorCategory.PROVIDER_OUTPUT_INVALID);
        assertStagingEmpty();
    }

    @Test
    void invalidStagingConfigurationFailsDuringPreflightBeforeDownload() {
        properties.setStagingDir(java.nio.file.Path.of("relative-staging"));

        assertThatThrownBy(fetcher::prepare)
                .isInstanceOf(ProviderExecutionException.class)
                .extracting(exception -> ((ProviderExecutionException) exception).category())
                .isEqualTo(ProviderErrorCategory.PROVIDER_CONFIGURATION_INVALID);
        verifyNoInteractions(remote);
    }

    private void assertStagingEmpty() throws Exception {
        if (!Files.exists(properties.getStagingDir())) {
            return;
        }
        try (var files = Files.list(properties.getStagingDir())) {
            assertThat(files.toList()).isEmpty();
        }
    }
}
