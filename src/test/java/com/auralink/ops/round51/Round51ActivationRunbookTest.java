package com.auralink.ops.round51;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Round51ActivationRunbookTest {

    private static final Path RUNBOOK = Path.of("docs/round5-1-live-activation.md");

    @Test
    void runbookPinsTheReviewedServerLocalWorkflow() throws IOException {
        String runbook = Files.readString(RUNBOOK);

        assertTrue(runbook.contains("/root/autodl-tmp/auralink"));
        assertTrue(runbook.contains("AURALINK_ROUND51_EXPECTED_COMMIT=REPLACE_WITH_FULL_40_HEX_COMMIT"));
        assertFalse(runbook.contains("AURALINK_ROUND51_EXPECTED_COMMIT=<"));
        assertTrue(runbook.contains("--dry-run"));
        assertTrue(runbook.contains("AURALINK_ROUND51_CONFIRM=ACTIVATE_AURALINK_2_0_CATALOG"));
        assertTrue(runbook.contains("--activate --smoke"));
        assertTrue(runbook.contains("/root/auralink_activation_backups/<timestamp>/"));
        assertTrue(runbook.contains("SERVER_LOCAL_ROOT_VERIFIED"));
        assertTrue(runbook.contains("BACKEND_SERVICE_MUST_BE_STOPPED"));
        assertTrue(runbook.contains("ROLLBACK_COMPLETED"));
        assertTrue(runbook.contains("ALREADY_ACTIVATED_AND_HEALTHY"));
        assertTrue(runbook.contains("recover-round5-catalog-activation.sh"));
        assertTrue(runbook.contains("RESTORE_AURALINK_ROUND51_PRE_ACTIVATION_BACKUP"));
        assertTrue(runbook.contains("Never remove that marker with `rm`"));
        assertTrue(runbook.contains("cryptographically bound to the exact selected recovery binding"));
        assertTrue(runbook.contains("recovery refuses unrelated fences from another run"));

        int dryRunCommand = runbook.indexOf("activate-round5-catalog.sh --dry-run");
        int activationCommand = runbook.indexOf("activate-round5-catalog.sh --activate --smoke");
        assertTrue(dryRunCommand >= 0 && activationCommand > dryRunCommand,
                "The runbook must instruct operators to dry-run before activation");
    }

    @Test
    void runbookDocumentsRecoveryAndExpectedCatalogStateWithoutSecrets() throws IOException {
        String runbook = Files.readString(RUNBOOK);

        assertTrue(runbook.contains("users = 7"));
        assertTrue(runbook.contains("generation_logs = 118"));
        assertTrue(runbook.contains("paintings = 11,067"));
        assertTrue(runbook.contains("media_assets = 9,067"));
        assertTrue(runbook.contains("approximately 71 minutes"));
        assertTrue(runbook.contains("Automatic rollback on controlled failure"));
        assertTrue(runbook.contains("failed/partial database"));
        assertTrue(runbook.contains("AURALINK_FLYWAY_ENABLED"));
        assertTrue(runbook.contains("AURALINK_JPA_DDL_AUTO"));

        assertFalse(runbook.contains("AURALINK_JWT_SECRET="));
        assertFalse(runbook.contains("SEEDREAM_API_KEY="));
        assertFalse(runbook.contains("QWEN_API_KEY="));
        assertFalse(runbook.contains("Authorization: Bearer"));
        assertFalse(runbook.contains("-----BEGIN PRIVATE KEY-----"));
    }
}
