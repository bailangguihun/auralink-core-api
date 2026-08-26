package com.auralink.provider;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;

import javax.imageio.ImageIO;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.config.properties.MediaAssetProperties;
import com.auralink.provider.artifact.AudioOutputValidator;
import com.auralink.provider.artifact.ProviderArtifactStagingService;
import com.auralink.service.media.ImageContentValidator;

public final class ProviderTestFixtures {

    private ProviderTestFixtures() {
    }

    public static CreationProviderProperties properties(Path stagingRoot) {
        CreationProviderProperties properties = new CreationProviderProperties();
        properties.setEnabled(true);
        properties.setStagingDir(stagingRoot);
        return properties;
    }

    public static ProviderArtifactStagingService staging(
            CreationProviderProperties properties) {
        MediaAssetProperties media = new MediaAssetProperties();
        media.setMaxImagePixels(1_000_000);
        return new ProviderArtifactStagingService(
                properties,
                new ImageContentValidator(media),
                new AudioOutputValidator());
    }

    /** Production-equivalent no-retry Apache client for loopback HTTP fixtures. */
    public static RestClient restClient(
            int timeoutMillis,
            Collection<CloseableHttpClient> ownedClients) {
        Timeout timeout = Timeout.of(Duration.ofMillis(timeoutMillis));
        ConnectionConfig connection = ConnectionConfig.custom()
                .setConnectTimeout(timeout)
                .setSocketTimeout(timeout)
                .build();
        var manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(connection)
                .setMaxConnTotal(1)
                .setMaxConnPerRoute(1)
                .build();
        RequestConfig request = RequestConfig.custom()
                .setRedirectsEnabled(false)
                .setAuthenticationEnabled(false)
                .setConnectionRequestTimeout(timeout)
                .setResponseTimeout(timeout)
                .setContentCompressionEnabled(false)
                .build();
        CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(manager)
                .setDefaultRequestConfig(request)
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableAuthCaching()
                .disableCookieManagement()
                .disableContentCompression()
                .build();
        ownedClients.add(client);
        return RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(client))
                .build();
    }

    public static byte[] png() {
        return image("PNG");
    }

    public static byte[] jpeg() {
        return image("JPEG");
    }

    public static byte[] wave() {
        byte[] bytes = new byte[48];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(bytes.length - 8);
        buffer.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(8_000);
        buffer.putInt(16_000);
        buffer.putShort((short) 2);
        buffer.putShort((short) 16);
        buffer.put("data".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(4);
        buffer.putInt(0);
        return bytes;
    }

    private static byte[] image(String format) {
        try {
            BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, Color.BLACK.getRGB());
            image.setRGB(1, 0, Color.WHITE.getRGB());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, output)) {
                throw new IllegalStateException("Synthetic image format unavailable");
            }
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Synthetic image could not be created", exception);
        }
    }
}
