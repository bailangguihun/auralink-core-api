package com.auralink.ops.round81;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Private, atomic validation-run file helpers. */
final class Round81PrivateFiles {

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private Round81PrivateFiles() {
    }

    static Path requirePrivateRunDirectory(Path raw) {
        try {
            if (raw == null || !raw.isAbsolute() || Files.isSymbolicLink(raw)
                    || !Files.isDirectory(raw, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("PRIVATE_RUN_DIRECTORY_INVALID");
            }
            Path normalized = raw.toAbsolutePath().normalize();
            if (!normalized.equals(raw) || !normalized.equals(raw.toRealPath())) {
                throw failure("PRIVATE_RUN_DIRECTORY_INVALID");
            }
            requirePermissions(raw, DIRECTORY_PERMISSIONS);
            return normalized;
        } catch (IOException exception) {
            throw new Round81ValidationException(
                    "PRIVATE_RUN_DIRECTORY_INVALID", "Private validation directory is invalid", exception);
        }
    }

    static Path requireContainedRegularFile(Path runDirectory, String fileName) {
        if (fileName == null || !fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                || fileName.contains("..")) {
            throw failure("PRIVATE_RUN_FILE_INVALID");
        }
        Path candidate = runDirectory.resolve(fileName).normalize();
        try {
            if (!candidate.getParent().equals(runDirectory)
                    || Files.isSymbolicLink(candidate)
                    || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    || !candidate.toRealPath().getParent().equals(runDirectory)) {
                throw failure("PRIVATE_RUN_FILE_INVALID");
            }
            requirePermissions(candidate, FILE_PERMISSIONS);
            return candidate;
        } catch (IOException exception) {
            throw new Round81ValidationException(
                    "PRIVATE_RUN_FILE_INVALID", "Private validation input file is invalid", exception);
        }
    }

    static Path writeJson(ObjectMapper mapper, Path runDirectory, String fileName, Object value) {
        try {
            byte[] bytes = mapper.writer().writeValueAsBytes(value);
            return writeBytes(runDirectory, fileName, bytes);
        } catch (IOException exception) {
            throw new Round81ValidationException(
                    "PRIVATE_RESULT_WRITE_FAILED", "Sanitized validation evidence could not be written", exception);
        }
    }

    static Path copyArtifact(
            Path runDirectory,
            String fileName,
            InputStream input,
            long maximumBytes,
            String expectedSha256) {
        Path target = directTarget(runDirectory, fileName);
        Path partial = runDirectory.resolve("." + fileName + ".part");
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(partial, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("PRIVATE_RESULT_ALREADY_EXISTS");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream source = input;
                    OutputStream destination = Files.newOutputStream(
                            partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                setFilePermissions(partial);
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = source.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    if (total > maximumBytes - read) {
                        throw failure("PRIVATE_RESULT_TOO_LARGE");
                    }
                    destination.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    total += read;
                }
            }
            if (total < 1 || !MessageDigest.isEqual(
                    digest.digest(), HexFormat.of().parseHex(expectedSha256))) {
                throw failure("PRIVATE_RESULT_CHECKSUM_MISMATCH");
            }
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
            setFilePermissions(target);
            return target;
        } catch (Round81ValidationException exception) {
            deletePartial(partial);
            throw exception;
        } catch (IOException | NoSuchAlgorithmException | IllegalArgumentException exception) {
            deletePartial(partial);
            throw new Round81ValidationException(
                    "PRIVATE_RESULT_WRITE_FAILED", "Validated provider output could not be retained", exception);
        }
    }

    static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new Round81ValidationException(
                    "PRIVATE_RESULT_CHECKSUM_FAILED", "Private result checksum could not be calculated", exception);
        }
    }

    static void deleteDirectFile(Path runDirectory, String fileName) {
        Path target = directTarget(runDirectory, fileName);
        try {
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new Round81ValidationException(
                    "PRIVATE_RESULT_CLEANUP_FAILED", "Incomplete private result could not be removed", exception);
        }
    }

    private static Path writeBytes(Path runDirectory, String fileName, byte[] bytes) throws IOException {
        Path target = directTarget(runDirectory, fileName);
        Path partial = runDirectory.resolve("." + fileName + ".part");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(partial, LinkOption.NOFOLLOW_LINKS)) {
            throw failure("PRIVATE_RESULT_ALREADY_EXISTS");
        }
        try {
            Files.write(partial, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            setFilePermissions(partial);
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
            setFilePermissions(target);
            return target;
        } finally {
            deletePartial(partial);
        }
    }

    private static Path directTarget(Path runDirectory, String fileName) {
        if (fileName == null || !fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                || fileName.contains("..")) {
            throw failure("PRIVATE_RUN_FILE_INVALID");
        }
        Path target = runDirectory.resolve(fileName).normalize();
        if (!target.getParent().equals(runDirectory)) {
            throw failure("PRIVATE_RUN_FILE_INVALID");
        }
        return target;
    }

    private static void requirePermissions(Path path, Set<PosixFilePermission> expected) throws IOException {
        try {
            if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(expected)) {
                throw failure("PRIVATE_RUN_PERMISSIONS_INVALID");
            }
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX development fixtures are still protected by containment.
        }
    }

    private static void setFilePermissions(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, FILE_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // The production root is POSIX; this permits portable unit fixtures.
        }
    }

    private static void deletePartial(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A later coordinator cleanup verifies that no partial remains.
        }
    }

    private static Round81ValidationException failure(String code) {
        return new Round81ValidationException(code, "Private validation file policy was not satisfied");
    }
}
