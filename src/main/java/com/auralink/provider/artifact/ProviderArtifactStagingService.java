package com.auralink.provider.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.service.media.ImageContentValidator;
import com.auralink.service.media.ImageContentValidator.ValidatedImage;
import com.auralink.service.media.InvalidImageContentException;

import lombok.RequiredArgsConstructor;

/** Controlled, private, bounded staging for provider input and output binaries. */
@Service
@RequiredArgsConstructor
public class ProviderArtifactStagingService {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private final CreationProviderProperties properties;
    private final ImageContentValidator imageContentValidator;
    private final AudioOutputValidator audioOutputValidator;

    /** Consumes and closes the supplied stream. */
    public ProviderArtifact stageInputImage(InputStream input, String declaredMimeType) {
        return stageImage(input, declaredMimeType, properties.getMaxImageInputBytes());
    }

    /** Consumes and closes the supplied stream. */
    public ProviderArtifact stageOutputImage(InputStream input, String declaredMimeType) {
        return stageImage(input, declaredMimeType, properties.getMaxImageOutputBytes());
    }

    /** Allows a trusted downloader to populate only a random contained target. */
    public ProviderArtifact stageOutputImage(ProviderArtifactWriter writer) {
        return stageWrittenImage(writer, null, properties.getMaxImageOutputBytes());
    }

    /** Consumes and closes the supplied stream. */
    public ProviderArtifact stageOutputWave(InputStream input) {
        return stageWave(input, properties.getMaxAudioOutputBytes());
    }

    public ProviderArtifact stageOutputWave(ProviderArtifactWriter writer) {
        return stageWrittenWave(writer, properties.getMaxAudioOutputBytes());
    }

    /** Creates and validates the private root before a provider request is submitted. */
    public void prepare() {
        try {
            ensureStagingRoot();
        } catch (IOException exception) {
            throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_CONFIGURATION_INVALID,
                    "Provider staging root is unavailable or unsafe",
                    exception);
        }
    }

    private ProviderArtifact stageImage(InputStream input, String declaredMimeType, long maxBytes) {
        if (input == null) {
            throw invalidContract("Provider image stream is required");
        }
        return stageWrittenImage(target -> {
            try (InputStream source = input;
                    OutputStream destination = Files.newOutputStream(
                            target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                copyWithLimit(source, destination, maxBytes);
            }
        }, declaredMimeType, maxBytes);
    }

    private ProviderArtifact stageWave(InputStream input, long maxBytes) {
        if (input == null) {
            throw invalidContract("Provider audio stream is required");
        }
        return stageWrittenWave(target -> {
            try (InputStream source = input;
                    OutputStream destination = Files.newOutputStream(
                            target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                copyWithLimit(source, destination, maxBytes);
            }
        }, maxBytes);
    }

    private ProviderArtifact stageWrittenImage(
            ProviderArtifactWriter writer,
            String declaredMimeType,
            long maxBytes) {
        return stage(writer, maxBytes, partial -> {
            final ValidatedImage image;
            try {
                image = imageContentValidator.validateTrustedImage(partial);
            } catch (InvalidImageContentException exception) {
                throw new ProviderExecutionException(
                        ProviderErrorCategory.PROVIDER_OUTPUT_INVALID,
                        "Provider image failed content validation",
                        exception);
            }
            if (declaredMimeType != null
                    && !normalizeMime(declaredMimeType).equals(image.mimeType())) {
                throw new ProviderExecutionException(
                        ProviderErrorCategory.PROVIDER_OUTPUT_INVALID,
                        "Provider image MIME does not match its bytes");
            }
            return new ValidatedBinary(
                    image.mimeType(), image.fileExtension(), image.width(), image.height());
        });
    }

    private ProviderArtifact stageWrittenWave(ProviderArtifactWriter writer, long maxBytes) {
        return stage(writer, maxBytes, partial -> {
            audioOutputValidator.validateWave(partial, "audio/wav", maxBytes);
            return new ValidatedBinary("audio/wav", "wav", null, null);
        });
    }

    private ProviderArtifact stage(
            ProviderArtifactWriter writer,
            long maxBytes,
            BinaryValidator validator) {
        if (writer == null || maxBytes < 1) {
            throw invalidContract("Provider staging writer and byte limit are required");
        }

        Path partial = null;
        Path completed = null;
        try {
            Path root = ensureStagingRoot();
            partial = Files.createTempFile(root, ".provider-incoming-", ".part");
            setFilePermissions(partial);
            writer.write(partial);
            requireContainedRegularFile(root, partial);

            DigestMetadata digest = digestWithLimit(partial, maxBytes);
            if (digest.byteLength() < 1) {
                throw new ProviderExecutionException(
                        ProviderErrorCategory.PROVIDER_OUTPUT_INVALID,
                        "Provider artifact is empty");
            }
            ValidatedBinary binary = validator.validate(partial);

            completed = root.resolve(UUID.randomUUID() + "." + binary.extension()).normalize();
            requireDirectChild(root, completed);
            Files.move(partial, completed, StandardCopyOption.ATOMIC_MOVE);
            partial = null;
            setFilePermissions(completed);
            requireContainedRegularFile(root, completed);

            return new ProviderArtifact(
                    completed,
                    root,
                    binary.mimeType(),
                    binary.extension(),
                    digest.byteLength(),
                    digest.sha256(),
                    binary.width(),
                    binary.height());
        } catch (ProviderExecutionException exception) {
            deleteQuietly(partial);
            deleteQuietly(completed);
            throw exception;
        } catch (Exception exception) {
            deleteQuietly(partial);
            deleteQuietly(completed);
            throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_OUTPUT_INVALID,
                    "Provider artifact staging failed",
                    exception);
        }
    }

    private Path ensureStagingRoot() throws IOException {
        Path configured = properties.getStagingDir();
        if (configured == null) {
            throw new IOException("Provider staging root is unavailable");
        }
        if (!configured.isAbsolute()) {
            throw new IOException("Provider staging root must be absolute");
        }
        Path normalized = configured.normalize();
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(normalized)) {
            throw new IOException("Provider staging root is unsafe");
        }
        Files.createDirectories(normalized);
        if (Files.isSymbolicLink(normalized)) {
            throw new IOException("Provider staging root is unsafe");
        }
        Path real = normalized.toRealPath();
        if (!real.equals(normalized) || !Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Provider staging root is unsafe");
        }
        setDirectoryPermissions(real);
        return real;
    }

    private void requireContainedRegularFile(Path root, Path candidate) throws IOException {
        requireDirectChild(root, candidate);
        if (Files.isSymbolicLink(candidate)
                || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Provider staging target is unsafe");
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(root) || !real.getParent().equals(root)) {
            throw new IOException("Provider staging target escaped its root");
        }
    }

    private void requireDirectChild(Path root, Path candidate) throws IOException {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)
                || normalized.getParent() == null
                || !normalized.getParent().equals(root)) {
            throw new IOException("Provider staging target escaped its root");
        }
    }

    private DigestMetadata digestWithLimit(Path path, long maxBytes)
            throws IOException, NoSuchAlgorithmException {
        long declaredSize = Files.size(path);
        if (declaredSize > maxBytes) {
            throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_OUTPUT_INVALID,
                    "Provider artifact exceeds the configured byte limit");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total;
        try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
            total = copyWithLimit(input, OutputStream.nullOutputStream(), maxBytes);
        }
        if (total != declaredSize) {
            throw new IOException("Provider artifact size changed during validation");
        }
        return new DigestMetadata(total, HexFormat.of().formatHex(digest.digest()));
    }

    static long copyWithLimit(InputStream input, OutputStream output, long maxBytes) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        while (true) {
            int read = input.read(buffer);
            if (read < 0) {
                return total;
            }
            if (read == 0) {
                continue;
            }
            if (total > maxBytes - read) {
                throw new ProviderExecutionException(
                        ProviderErrorCategory.PROVIDER_OUTPUT_INVALID,
                        "Provider artifact exceeds the configured byte limit");
            }
            output.write(buffer, 0, read);
            total += read;
        }
    }

    private String normalizeMime(String mimeType) {
        return mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private void setDirectoryPermissions(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, DIRECTORY_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // Platform does not expose POSIX permissions; containment still applies.
        }
    }

    private void setFilePermissions(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, FILE_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // Platform does not expose POSIX permissions; containment still applies.
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Random contained staging target; retain the primary safe failure.
        }
    }

    private ProviderExecutionException invalidContract(String message) {
        return new ProviderExecutionException(
                ProviderErrorCategory.PROVIDER_INTERNAL_CONTRACT_ERROR,
                message);
    }

    @FunctionalInterface
    private interface BinaryValidator {
        ValidatedBinary validate(Path partial) throws Exception;
    }

    private record ValidatedBinary(
            String mimeType,
            String extension,
            Integer width,
            Integer height) {
    }

    private record DigestMetadata(long byteLength, String sha256) {
    }
}
