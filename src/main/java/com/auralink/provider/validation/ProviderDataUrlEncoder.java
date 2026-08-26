package com.auralink.provider.validation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.stereotype.Component;

import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.provider.artifact.ProviderArtifact;

/** Encodes one already-bounded validated image without accepting URLs or paths. */
@Component
public class ProviderDataUrlEncoder {

    private static final int BUFFER_SIZE = 64 * 1024;

    public String encodeImage(ProviderArtifact artifact, long maxBytes) {
        if (artifact == null || !artifact.isAvailable() || artifact.byteLength() > maxBytes
                || !("image/jpeg".equals(artifact.mimeType()) || "image/png".equals(artifact.mimeType()))) {
            throw invalid("Provider image cannot be encoded");
        }
        long encodedLength = ((artifact.byteLength() + 2L) / 3L) * 4L;
        if (encodedLength > Integer.MAX_VALUE - 128L) {
            throw invalid("Provider image encoded payload is too large");
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream((int) encodedLength);
        try (InputStream input = artifact.openStream();
                OutputStream base64 = Base64.getEncoder().wrap(encoded)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                if (total > maxBytes - read) {
                    throw invalid("Provider image exceeds the configured byte limit");
                }
                base64.write(buffer, 0, read);
                total += read;
            }
            if (total != artifact.byteLength()) {
                throw invalid("Provider image changed during encoding");
            }
        } catch (ProviderExecutionException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_OUTPUT_INVALID,
                    "Provider image could not be encoded",
                    exception);
        }
        return "data:" + artifact.mimeType() + ";base64,"
                + encoded.toString(StandardCharsets.US_ASCII);
    }

    private ProviderExecutionException invalid(String message) {
        return new ProviderExecutionException(
                ProviderErrorCategory.PROVIDER_OUTPUT_INVALID,
                message);
    }
}
