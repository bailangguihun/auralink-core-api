package com.auralink.ops.round51;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Test-only Python interpreter resolution for portable Round 5.1 fixtures. */
final class Round51TestPython {

    static final String PROPERTY = "auralink.test.python3";
    static final String ENVIRONMENT_VARIABLE = "AURALINK_TEST_PYTHON3";
    private static final long LOOKUP_TIMEOUT_SECONDS = 5;

    private Round51TestPython() {
    }

    static Path resolve() {
        return resolve(
                System.getProperty(PROPERTY),
                System.getenv(ENVIRONMENT_VARIABLE),
                System.getenv("PATH"));
    }

    static Path resolve(String configuredProperty, String configuredEnvironment, String inheritedPath) {
        String configured = firstNonBlank(configuredProperty, configuredEnvironment);
        if (configured != null) {
            return requireAbsoluteExecutable(Path.of(configured), "configured test Python interpreter");
        }

        Path commandResolved = resolveWithCommand();
        if (commandResolved != null) {
            return commandResolved;
        }

        if (inheritedPath != null) {
            for (String entry : inheritedPath.split(Pattern.quote(File.pathSeparator))) {
                Path directory = entry.isBlank() ? Path.of(".") : Path.of(entry);
                Path candidate = directory.toAbsolutePath().normalize().resolve("python3");
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException("No absolute executable python3 was found for Round 5.1 tests");
    }

    static String shellQuote(Path executable) {
        return "'" + executable.toString().replace("'", "'\\''") + "'";
    }

    private static Path resolveWithCommand() {
        Process process = null;
        try {
            process = new ProcessBuilder("sh", "-c", "command -v python3")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                throw new IllegalStateException("Timed out resolving python3 for Round 5.1 tests");
            }
            if (process.exitValue() != 0) {
                return null;
            }
            String output;
            try (var input = process.getInputStream()) {
                output = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (output.isBlank() || output.contains("\n")) {
                return null;
            }
            Path candidate = Path.of(output);
            if (!candidate.isAbsolute()) {
                return null;
            }
            return requireAbsoluteExecutable(candidate, "python3 resolved from command -v");
        } catch (IOException exception) {
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted resolving python3 for Round 5.1 tests", exception);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static Path requireAbsoluteExecutable(Path candidate, String source) {
        if (!candidate.isAbsolute()) {
            throw new IllegalStateException(source + " must be an absolute path");
        }
        Path normalized = candidate.normalize();
        if (!Files.isRegularFile(normalized) || !Files.isExecutable(normalized)) {
            throw new IllegalStateException(source + " must be an executable regular file: " + normalized);
        }
        return normalized;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }
}
