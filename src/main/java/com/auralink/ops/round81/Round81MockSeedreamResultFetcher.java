package com.auralink.ops.round81;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.provider.artifact.ProviderArtifact;
import com.auralink.provider.artifact.ProviderArtifactStagingService;
import com.auralink.provider.seedream.SeedreamResultFetcher;

/** Exact-loopback result fetch used only when the packaged Mock token is active. */
final class Round81MockSeedreamResultFetcher implements SeedreamResultFetcher {

    private static final int BUFFER_SIZE = 16 * 1024;

    private final ProviderArtifactStagingService stagingService;
    private final CreationProviderProperties properties;
    private final Round81MockSupport mockSupport;

    Round81MockSeedreamResultFetcher(
            ProviderArtifactStagingService stagingService,
            CreationProviderProperties properties,
            Round81MockSupport mockSupport) {
        this.stagingService = stagingService;
        this.properties = properties;
        this.mockSupport = mockSupport;
    }

    @Override
    public void prepare() {
        mockSupport.requireEnabled();
        stagingService.prepare();
    }

    @Override
    public ProviderArtifact fetch(String resultUrl) {
        URI expected = mockSupport.generatedImage();
        final URI actual;
        try {
            actual = URI.create(resultUrl);
        } catch (IllegalArgumentException exception) {
            throw invalid("Mock Seedream result URL is invalid", exception);
        }
        if (!expected.equals(actual)) {
            throw invalid("Mock Seedream result URL is outside the fixed loopback fixture", null);
        }
        return stagingService.stageOutputImage(target -> download(expected, target));
    }

    private void download(URI source, java.nio.file.Path target) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) source.toURL().openConnection(Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(toMillis(properties.getConnectTimeout()));
        connection.setReadTimeout(toMillis(properties.getSeedreamReadTimeout()));
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "image/png,image/jpeg");
        try {
            if (connection.getResponseCode() != 200) {
                throw new IOException("Mock image fixture returned a non-success status");
            }
            long declared = connection.getContentLengthLong();
            if (declared > properties.getMaxImageOutputBytes()) {
                throw new IOException("Mock image fixture exceeds the byte limit");
            }
            try (InputStream input = connection.getInputStream();
                    OutputStream output = Files.newOutputStream(
                            target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                copyBounded(input, output, properties.getMaxImageOutputBytes());
            }
        } finally {
            connection.disconnect();
        }
    }

    private void copyBounded(InputStream input, OutputStream output, long maximum) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            if (total > maximum - read) {
                throw new IOException("Mock image fixture exceeds the byte limit");
            }
            output.write(buffer, 0, read);
            total += read;
        }
    }

    private int toMillis(java.time.Duration duration) {
        long millis = duration.toMillis();
        if (millis < 1 || millis > Integer.MAX_VALUE) {
            throw invalid("Mock transport timeout configuration is invalid", null);
        }
        return (int) millis;
    }

    private ProviderExecutionException invalid(String message, Throwable cause) {
        return cause == null
                ? new ProviderExecutionException(ProviderErrorCategory.PROVIDER_OUTPUT_INVALID, message)
                : new ProviderExecutionException(ProviderErrorCategory.PROVIDER_OUTPUT_INVALID, message, cause);
    }
}
