package com.auralink.ops.round81;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class Round81ScriptContractTest {

    @Test
    void coordinatorExposesOnlyReviewedPerOperationModesAndPackagedEntryPoint() throws Exception {
        String script = Files.readString(Path.of("scripts/validate-round8-live-providers.sh"));
        String stateTool = Files.readString(Path.of("scripts/round81_provider_state.py"));

        assertThat(script).contains(
                "--preflight-all",
                "--dry-run --operation=<operation>",
                "--validate --operation=<operation>",
                "/root/autodl-tmp/auralink",
                "org.springframework.boot.loader.launch.PropertiesLauncher",
                "AURALINK_ROUND81_EXPECTED_COMMIT",
                "PROVIDER_VALIDATION_WORKTREE_CLEAN",
                "CONFLICTING_VALIDATION_PROCESS",
                "VMM_OWNED_FAILURE_CLEANUP_COMPLETE");
        assertThat(stateTool).contains("ALREADY_VALIDATED_AND_HEALTHY");
        assertThat(script).doesNotContain(
                "--validate-all",
                "--prompt",
                "--poem",
                "--image-path",
                "--api-key",
                "source backend/.env");
        assertThat(script.split("--validate --operation=<operation>", -1)).hasSize(2);
    }

    @Test
    void confirmationsAndCallBudgetsAreExactAndNoBatchPaidModeExists() throws Exception {
        String operations = Files.readString(Path.of(
                "src/main/java/com/auralink/ops/round81/Round81ValidationOperation.java"));

        assertThat(operations).contains(
                "VALIDATE_ONE_LIVE_TEXT_TO_PAINTING",
                "VALIDATE_ONE_LIVE_IMAGE_TO_PAINTING",
                "VALIDATE_ONE_LIVE_POEM_TO_PAINTING",
                "VALIDATE_ONE_LIVE_PAINTING_TO_POEM",
                "VALIDATE_ONE_LIVE_PAINTING_TO_MUSIC");
        assertThat(operations).doesNotContain("VALIDATE_ALL", "YES", "CONFIRM_ALL");
    }

    @Test
    void qwenFailureTerminalAndPackagedHarnessUseOnlySafeTypedDiagnostics() throws Exception {
        String command = Files.readString(Path.of(
                "src/main/java/com/auralink/ops/round81/Round81ProviderValidationCommand.java"));
        String harness = Files.readString(Path.of(
                "src/test/scripts/round81-packaged-mock-harness.sh"));

        assertThat(command).contains(
                "ROUND81_VALIDATION_ERROR_CODE=",
                "ROUND81_RESPONSE_VALIDATION_STAGE=",
                "ROUND81_RESPONSE_VALIDATION_CODE=");
        assertThat(command).doesNotContain(
                "ROUND81_VALIDATION_ERROR_SUMMARY",
                "responseShape=",
                "getMessage()",
                "printStackTrace");
        assertThat(harness).contains(
                "qwen-poem-numeric-schema",
                "qwen-poem-five-lines",
                "qwen-poem-fenced",
                "qwen-poem-text-mismatch",
                "PACKAGED_MOCK_QWEN_INVALID_DIAGNOSTICS=PASS");
    }

    @Test
    void vmmLauncherIsLoopbackOfflineAndDoesNotAlterModelPolicy() throws Exception {
        String launcher = Files.readString(Path.of("scripts/start-vmm-service.sh"));
        String state = Files.readString(Path.of("scripts/round81_provider_state.py"));

        assertThat(launcher).contains(
                "127.0.0.1:5001/health",
                "START_OWNED_ROUND81_VMM",
                "STOP_OWNED_ROUND81_VMM",
                "VMM_STATIC_PREFLIGHT_READY",
                "VMM_FAILURE_CLEANUP_INCOMPLETE");
        assertThat(state).contains(
                "HF_HUB_OFFLINE",
                "TRANSFORMERS_OFFLINE",
                "AURALINK_VMM_SERVICE_HOST\": \"127.0.0.1",
                "AURALINK_VMM_SERVICE_PORT\": \"5001");
        assertThat(launcher + state).doesNotContain(
                "pip install", "conda install", "apt install", "apt-get install");
    }
}
