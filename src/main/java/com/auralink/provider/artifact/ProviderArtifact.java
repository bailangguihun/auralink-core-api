package com.auralink.provider.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;

/**
 * Single-owner transient binary artifact.
 *
 * <p>The contained path is intentionally not exposed. Consumers may reopen the
 * validated stream and must close the artifact after handoff.</p>
 */
public final class ProviderArtifact implements AutoCloseable {

    private final Path containedPath;
    private final Path stagingRoot;
    private final String mimeType;
    private final String fileExtension;
    private final long byteLength;
    private final String sha256;
    private final Integer width;
    private final Integer height;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    ProviderArtifact(
            Path containedPath,
            Path stagingRoot,
            String mimeType,
            String fileExtension,
            long byteLength,
            String sha256,
            Integer width,
            Integer height) {
        this.containedPath = containedPath;
        this.stagingRoot = stagingRoot;
        this.mimeType = mimeType;
        this.fileExtension = fileExtension;
        this.byteLength = byteLength;
        this.sha256 = sha256;
        this.width = width;
        this.height = height;
    }

    public String mimeType() {
        return mimeType;
    }

    public String fileExtension() {
        return fileExtension;
    }

    public long byteLength() {
        return byteLength;
    }

    public String sha256() {
        return sha256;
    }

    public Integer width() {
        return width;
    }

    public Integer height() {
        return height;
    }

    public boolean isAvailable() {
        return !closed.get()
                && Files.isRegularFile(containedPath, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(containedPath);
    }

    public InputStream openStream() {
        requireAvailable();
        try {
            return Files.newInputStream(containedPath);
        } catch (IOException exception) {
            throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_OUTPUT_INVALID,
                    "Provider artifact is unavailable",
                    exception);
        }
    }

    @Override
    public synchronized void close() {
        if (closed.get()) {
            return;
        }
        requireDirectContainedPath();
        try {
            // deleteIfExists removes a replaced symlink itself and never
            // follows it. This keeps cleanup contained even after tampering.
            Files.deleteIfExists(containedPath);
            closed.set(true);
        } catch (IOException exception) {
            throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_OUTPUT_INVALID,
                    "Provider artifact cleanup failed",
                    exception);
        }
    }

    private void requireAvailable() {
        requireDirectContainedPath();
        if (!isAvailable()) {
            throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_OUTPUT_INVALID,
                    "Provider artifact is unavailable");
        }
        try {
            if (Files.size(containedPath) != byteLength) {
                throw new ProviderExecutionException(
                        ProviderErrorCategory.PROVIDER_OUTPUT_INVALID,
                        "Provider artifact changed after validation");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(containedPath)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            if (!MessageDigest.isEqual(
                    digest.digest(), HexFormat.of().parseHex(sha256))) {
                throw new ProviderExecutionException(
                        ProviderErrorCategory.PROVIDER_OUTPUT_INVALID,
                        "Provider artifact changed after validation");
            }
        } catch (IOException exception) {
            throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_OUTPUT_INVALID,
                    "Provider artifact could not be verified",
                    exception);
        } catch (java.security.NoSuchAlgorithmException | IllegalArgumentException exception) {
            throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_INTERNAL_CONTRACT_ERROR,
                    "Provider artifact checksum could not be verified",
                    exception);
        }
    }

    private void requireDirectContainedPath() {
        Path normalized = containedPath.toAbsolutePath().normalize();
        if (!normalized.startsWith(stagingRoot)
                || normalized.getParent() == null
                || !normalized.getParent().equals(stagingRoot)) {
            throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_INTERNAL_CONTRACT_ERROR,
                    "Provider artifact containment check failed");
        }
    }
}
