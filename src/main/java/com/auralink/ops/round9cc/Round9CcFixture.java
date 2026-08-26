package com.auralink.ops.round9cc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates the one private disposable root accepted by the ROUND 9C-C harness. */
public final class Round9CcFixture {

    static final String MARKER_NAME = ".round9cc-fixture";
    static final String MARKER_CONTENT = "ROUND9CC_FIXTURE\n";
    private static final Path TMP = Path.of("/tmp").toAbsolutePath().normalize();
    private static final Pattern ROOT_NAME = Pattern.compile("auralink-round9cc\\.[A-Za-z0-9_-]{8,64}");
    private static final Pattern LABEL = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    private static final Pattern INSTANCE = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,31}");
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> PRIVATE_FILE = PosixFilePermissions.fromString("rw-------");

    private final Path root;

    private Round9CcFixture(Path root) {
        this.root = root;
    }

    public static Round9CcFixture validate(Path proposedRoot) {
        try {
            if (proposedRoot == null) {
                throw invalid();
            }
            Path normalized = proposedRoot.toAbsolutePath().normalize();
            if (!ROOT_NAME.matcher(String.valueOf(normalized.getFileName())).matches()
                    || !normalized.getParent().equals(TMP)
                    || Files.isSymbolicLink(normalized)
                    || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid();
            }
            Path realTmp = TMP.toRealPath();
            Path realRoot = normalized.toRealPath();
            if (!realRoot.getParent().equals(realTmp) || Files.isSymbolicLink(realRoot)) {
                throw invalid();
            }
            requirePrivateDirectory(realRoot);
            requireOwner(realRoot);
            requireMarker(realRoot.resolve(MARKER_NAME));
            return new Round9CcFixture(realRoot);
        } catch (IOException | UnsupportedOperationException exception) {
            throw invalid();
        }
    }

    public Path root() {
        return root;
    }

    public Path requireDirectory(String name) {
        if (name == null || !INSTANCE.matcher(name).matches()) {
            throw invalid();
        }
        Path directory = root.resolve(name).normalize();
        if (!directory.getParent().equals(root)) {
            throw invalid();
        }
        try {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
                throw invalid();
            }
            requirePrivateDirectory(directory);
            return directory.toRealPath();
        } catch (IOException | UnsupportedOperationException exception) {
            throw invalid();
        }
    }

    public Path controlDirectory(String instance) {
        validateInstance(instance);
        Path control = requireDirectory("control");
        Path instanceDirectory = control.resolve(instance).normalize();
        if (!instanceDirectory.getParent().equals(control)) {
            throw invalid();
        }
        try {
            Files.createDirectory(instanceDirectory);
            setPrivateDirectory(instanceDirectory);
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            // A process role owns a stable per-instance control directory.
        } catch (IOException exception) {
            throw invalid();
        }
        try {
            if (!Files.isDirectory(instanceDirectory, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(instanceDirectory)) {
                throw invalid();
            }
            requirePrivateDirectory(instanceDirectory);
            return instanceDirectory.toRealPath();
        } catch (IOException | UnsupportedOperationException exception) {
            throw invalid();
        }
    }

    public Path journalFile(String instance) {
        validateInstance(instance);
        Path counters = requireDirectory("counters");
        return directChild(counters, instance + ".journal");
    }

    public Path runtimeFile(String instance, String suffix) {
        validateInstance(instance);
        if (suffix == null || !suffix.matches("[a-z][a-z0-9_-]{0,31}")) {
            throw invalid();
        }
        return directChild(requireDirectory("runtime"), instance + "." + suffix);
    }

    public Path propertiesFile() {
        return directChild(requireDirectory("env"), "fixture.properties");
    }

    public Path manifestFile() {
        return directChild(requireDirectory("manifest"), "scenario.properties");
    }

    static void validateLabel(String value) {
        if (value == null || !LABEL.matcher(value).matches()) {
            throw invalid();
        }
    }

    static void validateInstance(String value) {
        if (value == null || !INSTANCE.matcher(value).matches()) {
            throw invalid();
        }
    }

    static void setPrivateFile(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PRIVATE_FILE);
        } catch (UnsupportedOperationException exception) {
            throw invalid();
        }
    }

    static void setPrivateDirectory(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PRIVATE_DIRECTORY);
        } catch (UnsupportedOperationException exception) {
            throw invalid();
        }
    }

    private Path directChild(Path parent, String name) {
        Path child = parent.resolve(name).normalize();
        if (!child.getParent().equals(parent)) {
            throw invalid();
        }
        return child;
    }

    private static void requirePrivateDirectory(Path directory) throws IOException {
        if (!Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS).equals(PRIVATE_DIRECTORY)) {
            throw invalid();
        }
    }

    private static void requireOwner(Path path) throws IOException {
        FileOwnerAttributeView view = Files.getFileAttributeView(path, FileOwnerAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (view == null || !System.getProperty("user.name").equals(view.getOwner().getName())) {
            throw invalid();
        }
    }

    private static void requireMarker(Path marker) throws IOException {
        if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw invalid();
        }
        requireOwner(marker);
        if (!Files.getPosixFilePermissions(marker, LinkOption.NOFOLLOW_LINKS).equals(PRIVATE_FILE)) {
            throw invalid();
        }
        Object links = Files.getAttribute(marker, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        if (!(links instanceof Number count) || count.longValue() != 1L) {
            throw invalid();
        }
        if (!MARKER_CONTENT.equals(Files.readString(marker, StandardCharsets.UTF_8))) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("ROUND 9C-C fixture root is invalid");
    }
}
