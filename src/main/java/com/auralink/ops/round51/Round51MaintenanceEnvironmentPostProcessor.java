package com.auralink.ops.round51;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Set;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Refuses every ordinary backend startup while the server-local activation
 * lease exists. The activation script's owned Java processes receive the
 * matching ephemeral token; normal service-manager restarts do not.
 */
public final class Round51MaintenanceEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final Path SERVER_PROJECT_ROOT = Path.of("/root/autodl-tmp/auralink");
    static final Path SERVER_MARKER = Path.of(
            "/root/auralink_activation_backups/.round51-maintenance");
    static final Path SERVER_STARTUP_GATE = Path.of(
            "/root/auralink_activation_backups/.round51-startup-gate");
    static final String ORPHAN_FENCE_GLOB = ".round51-*-startup-gate-orphan-fence-*";
    static final String TOKEN_PROPERTY = "AURALINK_ROUND51_MAINTENANCE_TOKEN";
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_MARKER_BYTES = 129;

    private final Path marker;
    private final Path startupGate;
    private final Path serverProjectRoot;
    private final boolean serverRootClassificationRequired;
    private final boolean enforcementRequired;
    private static final List<GateLease> HELD_STARTUP_GATES = new CopyOnWriteArrayList<>();
    private static final ThreadLocal<MaintenancePaths> TEST_PATHS = new ThreadLocal<>();

    public Round51MaintenanceEnvironmentPostProcessor() {
        // The fixed marker root is absent in development. Always checking it
        // prevents copied-JAR/service-manager launches from bypassing fencing.
        this(defaultPathsForCurrentThread(), true);
    }

    Round51MaintenanceEnvironmentPostProcessor(Path marker) {
        this(marker, marker.resolveSibling(".round51-startup-gate"), null, false, true);
    }

    Round51MaintenanceEnvironmentPostProcessor(Path marker, Path startupGate) {
        this(marker, startupGate, null, false, true);
    }

    private Round51MaintenanceEnvironmentPostProcessor(
            MaintenancePaths paths,
            boolean serverRootClassificationRequired) {
        this(paths.marker(), paths.startupGate(), paths.serverProjectRoot(),
                serverRootClassificationRequired, true);
    }

    private Round51MaintenanceEnvironmentPostProcessor(
            Path marker,
            Path startupGate,
            Path serverProjectRoot,
            boolean serverRootClassificationRequired,
            boolean enforcementRequired) {
        this.marker = marker;
        this.startupGate = startupGate;
        this.serverProjectRoot = serverProjectRoot;
        this.serverRootClassificationRequired = serverRootClassificationRequired;
        this.enforcementRequired = enforcementRequired;
    }

    /**
     * Package-private test seam for Spring bootstraps that instantiate this
     * post-processor through spring.factories before a test can inject paths.
     * Normal application configuration cannot set this thread-local scope.
     */
    static TestPathScope isolatePathsForCurrentThread(
            Path marker,
            Path startupGate,
            Path offServerProjectRoot) {
        MaintenancePaths previous = TEST_PATHS.get();
        MaintenancePaths installed = new MaintenancePaths(
                marker, startupGate, offServerProjectRoot, false);
        TEST_PATHS.set(installed);
        return new TestPathScope(previous, installed);
    }

    static void installAutomaticPathsForCurrentThread(
            Path marker,
            Path startupGate,
            Path offServerProjectRoot) {
        MaintenancePaths current = TEST_PATHS.get();
        if (current == null || current.automatic()) {
            TEST_PATHS.set(new MaintenancePaths(marker, startupGate, offServerProjectRoot, true));
        }
    }

    static void releaseHeldStartupGatesForTests() {
        for (GateLease lease : List.copyOf(HELD_STARTUP_GATES)) {
            if (HELD_STARTUP_GATES.remove(lease)) {
                lease.close();
            }
        }
    }

    private static MaintenancePaths defaultPathsForCurrentThread() {
        MaintenancePaths testPaths = TEST_PATHS.get();
        return testPaths != null
                ? testPaths
                : new MaintenancePaths(SERVER_MARKER, SERVER_STARTUP_GATE, SERVER_PROJECT_ROOT, false);
    }

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment,
            SpringApplication application) {
        if (!enforcementRequired) {
            return;
        }
        // The fixed deployment root is the scope boundary. Development/test
        // hosts may coincidentally contain a private backup directory, but no
        // process outside the exact server-local checkout may be fenced by it.
        if (serverRootClassificationRequired && !isActualServerProjectPresent()) {
            return;
        }
        Path parent = marker.getParent();
        if (Files.notExists(parent, LinkOption.NOFOLLOW_LINKS)) {
            if (!serverRootClassificationRequired || !isActualServerProjectPresent()) {
                return;
            }
            provisionPrivateDirectory(parent);
        }
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || !hasPrivatePermissions(parent)) {
            refuse();
        }

        // The nonce-bearing marker is authoritative while activation or
        // recovery is in progress. Reviewed owner processes may pass it; an
        // ordinary backend is rejected before any datasource initialization.
        if (markerExists()) {
            requireOwnerToken(environment);
            return;
        }

        // A durable orphan fence survives host reset. Its presence means a
        // prior coordinator could not prove marker restoration or final
        // release, so ordinary startup remains blocked even if no lock holder
        // survived the reset. A matching live marker+nonce above is the only
        // reviewed-owner bypass during a healthy activation.
        if (hasDurableOrphanFence(parent)) {
            refuse();
        }

        provisionPrivateGate(startupGate);
        if (!Files.exists(startupGate, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(startupGate)
                || !Files.isRegularFile(startupGate, LinkOption.NOFOLLOW_LINKS)) {
            refuse();
        }

        GateLease lease = acquireSharedStartupGate();
        // Close the check/use gap: after acquiring the shared kernel lock,
        // inspect the marker again before retaining that lock for JVM life.
        if (markerExists()) {
            lease.close();
            requireOwnerToken(environment);
            return;
        }
        HELD_STARTUP_GATES.add(lease);
    }

    private boolean isActualServerProjectPresent() {
        try {
            return serverProjectRoot != null
                    && !Files.isSymbolicLink(serverProjectRoot)
                    && Files.isDirectory(serverProjectRoot, LinkOption.NOFOLLOW_LINKS)
                    && serverProjectRoot.equals(serverProjectRoot.toRealPath());
        } catch (IOException exception) {
            return false;
        }
    }

    private void provisionPrivateDirectory(Path directory) {
        try {
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
        } catch (FileAlreadyExistsException exception) {
            // A racing reviewed process created it; validation below is authoritative.
        } catch (IOException | UnsupportedOperationException exception) {
            refuse();
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)
                || !hasPrivatePermissions(directory)) {
            refuse();
        }
        // Every observer performs the parent barrier, not only the process
        // that won createDirectory(). This proves the directory entry is
        // durable even if that creator died before its own fsync completed.
        try (FileChannel parentDirectory = FileChannel.open(
                directory.getParent(), StandardOpenOption.READ)) {
            parentDirectory.force(true);
        } catch (IOException exception) {
            refuse();
        }
    }

    private void provisionPrivateGate(Path gate) {
        if (Files.notExists(gate, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createFile(gate, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")));
                try (FileChannel created = FileChannel.open(gate, StandardOpenOption.WRITE)) {
                    created.force(true);
                }
                try (FileChannel directory = FileChannel.open(gate.getParent(), StandardOpenOption.READ)) {
                    directory.force(true);
                }
            } catch (FileAlreadyExistsException exception) {
                // A racing reviewed process created it; validation below is authoritative.
            } catch (IOException | UnsupportedOperationException exception) {
                refuse();
            }
        }
        if (!Files.isRegularFile(gate, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(gate)
                || !hasPrivatePermissions(gate)) {
            refuse();
        }
    }

    private boolean hasPrivatePermissions(Path path) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                    path, LinkOption.NOFOLLOW_LINKS);
            return permissions.stream().noneMatch(permission -> switch (permission) {
                case GROUP_READ, GROUP_WRITE, GROUP_EXECUTE,
                        OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE -> true;
                default -> false;
            });
        } catch (IOException | UnsupportedOperationException exception) {
            return false;
        }
    }

    private boolean markerExists() {
        if (Files.notExists(marker, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(marker)
                || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            refuse();
        }
        return true;
    }

    private void requireOwnerToken(ConfigurableEnvironment environment) {
        String expected = readMarkerWithoutFollowingLinks();
        String supplied = environment.getProperty(TOKEN_PROPERTY, "");
        if (!TOKEN_PATTERN.matcher(expected).matches()
                || !TOKEN_PATTERN.matcher(supplied).matches()
                || !MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.US_ASCII),
                        supplied.getBytes(StandardCharsets.US_ASCII))) {
            refuse();
        }
    }

    private GateLease acquireSharedStartupGate() {
        Set<OpenOption> options = Set.of(
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        FileChannel channel = null;
        try {
            channel = FileChannel.open(startupGate, options);
            FileLock lock = channel.tryLock(0, Long.MAX_VALUE, true);
            if (lock == null || !lock.isShared()) {
                if (lock != null) {
                    lock.release();
                }
                channel.close();
                refuse();
            }
            return new GateLease(channel, lock);
        } catch (IOException | OverlappingFileLockException exception) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // Refusal remains authoritative.
                }
            }
            refuse();
            throw new IllegalStateException("unreachable");
        }
    }

    private boolean hasDurableOrphanFence(Path parent) {
        try (DirectoryStream<Path> candidates = Files.newDirectoryStream(
                parent, ORPHAN_FENCE_GLOB)) {
            for (Path candidate : candidates) {
                if (!Files.isSymbolicLink(candidate)
                        && Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    return true;
                }
                refuse();
            }
            return false;
        } catch (IOException exception) {
            refuse();
            return true;
        }
    }

    private String readMarkerWithoutFollowingLinks() {
        Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        ByteBuffer buffer = ByteBuffer.allocate(MAX_MARKER_BYTES);
        try (SeekableByteChannel channel = Files.newByteChannel(marker, options)) {
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // Consume at most MAX_MARKER_BYTES; an oversized value is invalid.
            }
            if (channel.read(ByteBuffer.allocate(1)) >= 0) {
                refuse();
            }
        } catch (IOException exception) {
            refuse();
        }
        buffer.flip();
        return StandardCharsets.US_ASCII.decode(buffer).toString().trim();
    }

    private static void refuse() {
        throw new IllegalStateException("AURALINK_ROUND51_MAINTENANCE_ACTIVE");
    }

    static final class TestPathScope implements AutoCloseable {
        private final MaintenancePaths previous;
        private final MaintenancePaths installed;

        private TestPathScope(MaintenancePaths previous, MaintenancePaths installed) {
            this.previous = previous;
            this.installed = installed;
        }

        @Override
        public void close() {
            if (TEST_PATHS.get() != installed) {
                throw new IllegalStateException("Round 5.1 test path scope closed out of order");
            }
            if (previous == null) {
                TEST_PATHS.remove();
            } else {
                TEST_PATHS.set(previous);
            }
        }
    }

    private record MaintenancePaths(
            Path marker,
            Path startupGate,
            Path serverProjectRoot,
            boolean automatic) {
    }

    private record GateLease(FileChannel channel, FileLock lock) {
        private void close() {
            try {
                lock.release();
            } catch (IOException ignored) {
                // Channel close below still releases the process lock.
            }
            try {
                channel.close();
            } catch (IOException ignored) {
                // The process is already failing closed on the marker check.
            }
        }
    }
}
