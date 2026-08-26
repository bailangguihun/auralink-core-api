package com.auralink.provider.artifact;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.springframework.stereotype.Component;

import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;

/** Strict current VMM RIFF/WAVE output validation without transcoding. */
@Component
public class AudioOutputValidator {

    public void validateWave(Path path, String declaredMimeType, long maxBytes) {
        if (!"audio/wav".equals(declaredMimeType)) {
            throw invalid("Provider audio MIME type is invalid");
        }
        if (path == null || Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw invalid("Provider audio is not a regular file");
        }
        try {
            long size = Files.size(path);
            if (size < 12 || size > maxBytes) {
                throw invalid("Provider audio size is invalid");
            }
            ByteBuffer header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
                while (header.hasRemaining()) {
                    if (channel.read(header) < 0) {
                        throw invalid("Provider audio header is incomplete");
                    }
                }
            }
            byte[] bytes = header.array();
            if (!matches(bytes, 0, "RIFF") || !matches(bytes, 8, "WAVE")) {
                throw invalid("Provider audio is not RIFF/WAVE");
            }
            long declaredSize = Integer.toUnsignedLong(header.getInt(4));
            if (declaredSize != size - 8) {
                throw invalid("Provider audio RIFF length is inconsistent");
            }
        } catch (ProviderExecutionException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_OUTPUT_INVALID,
                    "Provider audio could not be validated",
                    exception);
        }
    }

    private boolean matches(byte[] bytes, int offset, String value) {
        for (int index = 0; index < value.length(); index++) {
            if (bytes[offset + index] != (byte) value.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private ProviderExecutionException invalid(String message) {
        return new ProviderExecutionException(
                ProviderErrorCategory.PROVIDER_OUTPUT_INVALID,
                message);
    }
}
