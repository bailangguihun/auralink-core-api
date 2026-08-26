package com.auralink.ops.round81;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.creation.provider.ProviderSafeDiagnostic;

/** Packaged, non-web entry point for one controlled provider validation mode. */
public final class Round81ProviderValidationCommand {

    static final Path SERVER_LOCAL_ROOT = Path.of("/root/autodl-tmp/auralink");
    static final Path PRIVATE_RUN_ROOT = Path.of("/root/auralink_provider_validation_runs");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final String MOCK_TOKEN = Round81MockSupport.ENABLE_TOKEN;

    private Round81ProviderValidationCommand() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        ParsedArguments parsed;
        try {
            parsed = parseArguments(args);
            VerifiedRuntime runtime = parsed.mock()
                    ? verifyMockRuntime(parsed)
                    : verifyLiveRuntime(parsed);
            String[] controlledArguments = controlledArguments(runtime, parsed);
            try (ConfigurableApplicationContext context = startValidationContext(controlledArguments)) {
                Round81ProviderValidationCoordinator coordinator =
                        context.getBean(Round81ProviderValidationCoordinator.class);
                if (parsed.mode() == ValidationMode.DRY_RUN) {
                    coordinator.dryRun(parsed.operation());
                    System.out.println("DRY_RUN_ZERO_MUTATION");
                    System.out.println("DRY_RUN_OK");
                } else {
                    Round81RetainedResult retained = coordinator.validate(
                            parsed.operation(), runtime.runDirectory());
                    System.out.println(retained.structuralState());
                    System.out.println(retained.reviewState());
                    System.out.println("PROVIDER_CALL_COUNTS_VERIFIED");
                    System.out.println("PROVIDER_STAGING_CLEANED");
                }
            }
            return 0;
        } catch (Round81ValidationException exception) {
            reportSafeFailure(exception.code());
            return 2;
        } catch (ProviderExecutionException exception) {
            reportSafeFailure(exception.category().name());
            reportSafeDiagnostic(exception.safeDiagnostic());
            return 2;
        } catch (RuntimeException exception) {
            reportSafeFailure("VALIDATION_CONTEXT_FAILED");
            return 3;
        }
    }

    static ConfigurableApplicationContext startValidationContext(String... controlledArguments) {
        return new SpringApplicationBuilder(Round81ProviderValidationContextConfiguration.class)
                .web(WebApplicationType.NONE)
                .registerShutdownHook(false)
                .logStartupInfo(false)
                .run(controlledArguments);
    }

    private static ParsedArguments parseArguments(String[] args) {
        String mode = null;
        String operation = null;
        boolean mock = false;
        for (String argument : args) {
            if (argument.startsWith("--mode=")) {
                if (mode != null) {
                    throw invalidArguments();
                }
                mode = argument.substring("--mode=".length());
            } else if (argument.startsWith("--operation=")) {
                if (operation != null) {
                    throw invalidArguments();
                }
                operation = argument.substring("--operation=".length());
            } else if ("--mock".equals(argument)) {
                if (mock) {
                    throw invalidArguments();
                }
                mock = true;
            } else {
                throw invalidArguments();
            }
        }
        if (mode == null || operation == null) {
            throw invalidArguments();
        }
        ValidationMode parsedMode = switch (mode) {
            case "dry-run" -> ValidationMode.DRY_RUN;
            case "validate" -> ValidationMode.VALIDATE;
            default -> throw invalidArguments();
        };
        return new ParsedArguments(
                parsedMode, Round81ValidationOperation.fromToken(operation), mock);
    }

    private static VerifiedRuntime verifyLiveRuntime(ParsedArguments parsed) {
        try {
            Path cwd = Path.of("").toRealPath();
            if (Files.isSymbolicLink(SERVER_LOCAL_ROOT)
                    || !Files.isDirectory(SERVER_LOCAL_ROOT, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("SERVER_LOCAL_ROOT_REQUIRED");
            }
            Path realRoot = SERVER_LOCAL_ROOT.toRealPath();
            if (!realRoot.equals(SERVER_LOCAL_ROOT) || !cwd.equals(realRoot)) {
                throw failure("SERVER_LOCAL_ROOT_REQUIRED");
            }
            FileStore store = Files.getFileStore(realRoot);
            String filesystem = store.type().toLowerCase(Locale.ROOT);
            if (filesystem.contains("fuse") || filesystem.contains("sshfs")) {
                throw failure("SSHFS_EXECUTION_REFUSED");
            }

            Path environmentFile = requireRegularFile(realRoot.resolve("backend/.env"), "BACKEND_ENV_REQUIRED");
            Path jar = requireRegularFile(
                    realRoot.resolve("backend/target/auralink-backend-0.0.1-SNAPSHOT.jar"),
                    "PACKAGED_JAR_REQUIRED");
            String expected = requireCommit(System.getenv("AURALINK_ROUND81_EXPECTED_COMMIT"));
            String actual = runGit(realRoot, "rev-parse", "HEAD");
            if (!expected.equals(actual)) {
                throw failure("REVIEWED_COMMIT_MISMATCH");
            }
            if (!runGit(realRoot, "status", "--porcelain=v1", "--untracked-files=normal").isEmpty()) {
                throw failure("WORKTREE_NOT_CLEAN");
            }
            if (parsed.mode() == ValidationMode.VALIDATE) {
                requireConfirmation(parsed.operation());
            }
            System.out.println("SERVER_LOCAL_ROOT_VERIFIED");
            System.out.println("REVIEWED_COMMIT_VERIFIED=" + actual);
            System.out.println("PROVIDER_VALIDATION_WORKTREE_CLEAN");
            Path runDirectory = parsed.mode() == ValidationMode.VALIDATE
                    ? requiredEnvironmentPath("AURALINK_ROUND81_RUN_DIR")
                    : null;
            if (runDirectory != null
                    && (!runDirectory.startsWith(PRIVATE_RUN_ROOT)
                            || !PRIVATE_RUN_ROOT.equals(runDirectory.getParent()))) {
                throw failure("PRIVATE_RUN_DIRECTORY_INVALID");
            }
            return new VerifiedRuntime(environmentFile, jar, runDirectory, false);
        } catch (IOException exception) {
            throw new Round81ValidationException(
                    "SERVER_LOCAL_PREFLIGHT_FAILED", "Server-local validation preflight failed", exception);
        }
    }

    private static VerifiedRuntime verifyMockRuntime(ParsedArguments parsed) {
        if (!MOCK_TOKEN.equals(System.getenv("AURALINK_ROUND81_MOCK_MODE"))) {
            throw failure("MOCK_MODE_REFUSED");
        }
        String base = System.getenv("AURALINK_ROUND81_MOCK_BASE_URL");
        if (base == null || base.isBlank()) {
            throw failure("MOCK_ENDPOINT_INVALID");
        }
        if (parsed.mode() == ValidationMode.VALIDATE) {
            requireConfirmation(parsed.operation());
        }
        Path runDirectory = parsed.mode() == ValidationMode.VALIDATE
                ? requiredEnvironmentPath("AURALINK_ROUND81_RUN_DIR")
                : null;
        Path staging = requiredEnvironmentPath("AURALINK_PROVIDER_STAGING_DIR");
        Path temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if ((runDirectory != null && !runDirectory.normalize().startsWith(temporaryRoot))
                || !staging.normalize().startsWith(temporaryRoot)) {
            throw failure("MOCK_PATH_OUTSIDE_TEMP");
        }
        return new VerifiedRuntime(null, null, runDirectory, true);
    }

    private static String[] controlledArguments(VerifiedRuntime runtime, ParsedArguments parsed) {
        List<String> arguments = new ArrayList<>();
        arguments.add("--spring.main.web-application-type=none");
        arguments.add("--spring.main.banner-mode=off");
        arguments.add("--spring.jmx.enabled=false");
        arguments.add("--logging.level.root=OFF");
        arguments.add("--auralink.creation-providers.enabled=true");
        if (!runtime.mock()) {
            arguments.add("--spring.config.import=optional:file:"
                    + runtime.environmentFile() + "[.properties]");
        } else {
            String mockBase = System.getenv("AURALINK_ROUND81_MOCK_BASE_URL");
            arguments.add("--spring.config.location=optional:classpath:/round81-provider-validation-mock.properties");
            arguments.add("--auralink.round81.mock-mode=" + MOCK_TOKEN);
            arguments.add("--auralink.round81.mock-base-url=" + mockBase);
            arguments.add("--auralink.creation-providers.staging-dir="
                    + requiredEnvironmentPath("AURALINK_PROVIDER_STAGING_DIR"));
            arguments.add("--auralink.providers.seedream.api-key=round81-mock-seedream-key");
            arguments.add("--auralink.providers.seedream.base-url=https://ark.cn-beijing.volces.com/api/v3");
            arguments.add("--auralink.providers.seedream.model=round81-mock-seedream-model");
            arguments.add("--auralink.providers.qwen.api-key=round81-mock-qwen-key");
            arguments.add("--auralink.providers.qwen.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1");
            arguments.add("--auralink.providers.qwen.model=qwen3-vl-plus");
            arguments.add("--auralink.providers.painting-music.base-url=" + mockBase);
            arguments.add("--auralink.providers.painting-music.output-root="
                    + requiredEnvironmentPath("AURALINK_VMM_OUTPUT_DIR"));
        }
        return arguments.toArray(String[]::new);
    }

    private static void requireConfirmation(Round81ValidationOperation operation) {
        if (!operation.confirmation().equals(System.getenv("AURALINK_ROUND81_CONFIRM"))) {
            throw failure("OPERATION_CONFIRMATION_REQUIRED");
        }
    }

    private static Path requiredEnvironmentPath(String name) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            throw failure("VALIDATION_PATH_CONFIGURATION_MISSING");
        }
        Path configured = Path.of(raw);
        if (!configured.isAbsolute()) {
            throw failure("VALIDATION_PATH_CONFIGURATION_INVALID");
        }
        return configured.normalize();
    }

    private static Path requireRegularFile(Path path, String code) throws IOException {
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(code);
        }
        return path.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static String requireCommit(String value) {
        if (value == null || !COMMIT.matcher(value).matches()) {
            throw failure("EXPECTED_COMMIT_REQUIRED");
        }
        return value;
    }

    private static String runGit(Path root, String... arguments) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(root.toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        byte[] bytes;
        try {
            bytes = process.getInputStream().readNBytes(1024 * 1024);
            if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroy();
                throw failure("GIT_GUARD_FAILED");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Git guard was interrupted", exception);
        }
        if (process.exitValue() != 0 || process.getInputStream().read() != -1) {
            throw failure("GIT_GUARD_FAILED");
        }
        return new String(bytes, StandardCharsets.UTF_8).trim();
    }

    private static void reportSafeFailure(String code) {
        String safeCode = code != null && code.matches("[A-Z][A-Z0-9_]{0,95}")
                ? code
                : "UNEXPECTED_SAFE_FAILURE";
        System.err.println("ROUND81_VALIDATION_ERROR_CODE=" + safeCode);
    }

    private static void reportSafeDiagnostic(ProviderSafeDiagnostic<?, ?, ?> diagnostic) {
        if (diagnostic == null) {
            return;
        }
        String stage = diagnostic.validationStage().name();
        String code = diagnostic.validationCode().name();
        if (stage.matches("[A-Z][A-Z0-9_]{0,95}")
                && code.matches("[A-Z][A-Z0-9_]{0,95}")) {
            System.err.println("ROUND81_RESPONSE_VALIDATION_STAGE=" + stage);
            System.err.println("ROUND81_RESPONSE_VALIDATION_CODE=" + code);
        }
    }

    private static Round81ValidationException invalidArguments() {
        return failure("INVALID_ARGUMENTS");
    }

    private static Round81ValidationException failure(String code) {
        return new Round81ValidationException(code, "Controlled provider validation safety check failed");
    }

    private enum ValidationMode {
        DRY_RUN,
        VALIDATE
    }

    private record ParsedArguments(
            ValidationMode mode,
            Round81ValidationOperation operation,
            boolean mock) {
    }

    private record VerifiedRuntime(
            Path environmentFile,
            Path jar,
            Path runDirectory,
            boolean mock) {
    }
}
