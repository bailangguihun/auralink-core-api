package com.auralink.ops.round9cc;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class Round9CcScriptContractTest {

    @Test
    void scriptsUsePrivateBoundedFixtureConventionsWithoutBroadProcessOrTmpDeletion() throws Exception {
        Path scripts = Path.of("src/test/scripts/round9cc");
        List<String> required = List.of(
                "round9cc-preflight-b2-tests.sh",
                "round9cc-build-package.sh",
                "round9cc-create-fixture.sh",
                "round9cc-start-instance.sh",
                "round9cc-await-failpoint.sh",
                "round9cc-release-failpoint.sh",
                "round9cc-signal-instance.sh",
                "round9cc-restart-and-recover.sh",
                "round9cc-audit-fixture.sh",
                "round9cc-cleanup.sh",
                "round9cc-run-all.sh");

        for (String name : required) {
            String source = Files.readString(scripts.resolve(name));
            assertThat(source).contains("set -euo pipefail", "umask 077");
            assertThat(source).doesNotContain("pkill java", "killall", "rm -rf /tmp/auralink");
        }
        String preflight = Files.readString(scripts.resolve("round9cc-preflight-b2-tests.sh"));
        assertThat(preflight).contains("env -u AURALINK_ENV_FILE", "-Dmaven.repo.local=../.m2/repository");
        assertThat(preflight).contains("ConfigDataImportTest", "CreationRecoveryCoordinatorTest",
                "CreationRecoveryRepositoryIntegrationTest");
        String signal = Files.readString(scripts.resolve("round9cc-signal-instance.sh"));
        assertThat(signal).contains("TERM|INT|KILL", "round9cc_instance_pid");

        String library = Files.readString(scripts.resolve("round9cc-lib.sh"));
        String createFixture = Files.readString(scripts.resolve("round9cc-create-fixture.sh"));
        assertThat(library).contains("readonly ROUND9CC_FIXTURE_TOOL_MAIN=");
        assertThat(createFixture).contains("${ROUND9CC_FIXTURE_TOOL_MAIN}");
        assertThat(createFixture).doesNotContain("ROUND9CCFIXTURE_TOOL_MAIN");

        String audit = Files.readString(scripts.resolve("round9cc-audit-fixture.sh"));
        assertThat(audit).contains(
                "round9cc_manifest_value \"${root}\" expectedMockEntry",
                "round9cc_manifest_value \"${root}\" expectedMockReturn",
                "round9cc_manifest_value \"${root}\" expectedMockClose",
                "FIXTURE_DATABASE_MISSING",
                "MOCK_JOURNAL_INVALID",
                "MOCK_JOURNAL_MISSING",
                "MOCK_CALL_COUNT_MISMATCH",
                "boundary_count > 0");
        assertThat(audit).doesNotContain("${root}manifest/scenario.properties");
        assertThat(audit).contains("round9cc_probe_instance_state", "INSTANCE_STILL_RUNNING",
                "INSTANCE_RUNTIME_EVIDENCE_INVALID", "PID_OWNERSHIP_REJECTED");
        assertThat(audit).doesNotContain("round9cc_instance_pid \"${root}\" \"${instance}\"");
        assertThat(audit).contains(
                "PROCESS_EXIT_MISMATCH",
                "STEP_STATUS_MISMATCH",
                "DISPATCH_STATE_MISMATCH",
                "EXECUTION_ATTEMPT_MISMATCH",
                "CLAIM_LEASE_MISMATCH",
                "SAFE_CODE_MISMATCH",
                "FIXTURE_FILE_STATE_MISMATCH",
                "round9cc_assert_no_listener");
        assertThat(library).contains("round9cc_probe_instance_state", "STOPPED", "PID_REUSED",
                "EVIDENCE_PENDING", "EVIDENCE_INVALID", "OWNERSHIP_REJECTED", "ALIVE");
        assertThat(library).contains("round9cc_valid_phase", "round9cc_expected_exit_for_phase",
                "round9cc_assert_no_listener", "round9cc_assert_terminated_instance", "LISTENER_REMAINS");

        String await = Files.readString(scripts.resolve("round9cc-await-failpoint.sh"));
        String release = Files.readString(scripts.resolve("round9cc-release-failpoint.sh"));
        assertThat(await).contains("round9cc_instance_pid");
        assertThat(release).contains("round9cc_instance_pid \"${root}\" \"${instance}\" >/dev/null");

        String cleanup = Files.readString(scripts.resolve("round9cc-cleanup.sh"));
        assertThat(cleanup).contains("root=\"$(round9cc_validate_fixture \"$1\")\"",
                "find \"${root}\" -xdev -mindepth 1 -depth -delete", "rmdir -- \"${root}\"");

        String start = Files.readString(scripts.resolve("round9cc-start-instance.sh"));
        assertThat(start).contains("INITIAL|SEED|RECOVERY", "--phase=\"${phase}\"",
                "ROUND9CC_STARTUP_DEADLINE_SECONDS", "round9cc_wait_for_phase_readiness")
                .doesNotContain("--failpoint=\"${failpoint}\"", "kill ");
        assertThat(signal).contains("SIGNAL_SCENARIO_MISMATCH", "PROCESS_EXIT_MISMATCH",
                "round9cc_assert_terminated_instance");
        assertThat(library).contains(
                "ROUND9CC_STARTUP_DEADLINE_SECONDS=60",
                "round9cc_assert_one_shot_completion",
                "round9cc_phase_completion_file",
                "SEED_COMPLETION_EVIDENCE_INVALID",
                "INITIAL_LIVE_REQUIRED",
                "round9cc_listener_present",
                "common_evidence_seen=false",
                "listener_seen=false",
                "round9cc_assert_no_listener");
    }

    @Test
    void processProbeUsesTheExactCommandLineVariableAndOnlyTheStableOwnershipCode() throws Exception {
        Path scripts = Path.of("src/test/scripts/round9cc");
        String library = Files.readString(scripts.resolve("round9cc-lib.sh"));
        String start = Files.readString(scripts.resolve("round9cc-start-instance.sh"));
        String probe = library.substring(
                library.indexOf("round9cc_probe_instance_state() {"),
                library.indexOf("round9cc_instance_pid() {"));
        String ownershipCheck = library.substring(
                library.indexOf("round9cc_cmdline_is_exact_harness_owner() {"),
                library.indexOf("round9cc_probe_after_first_snapshot() {"));
        String snapshotAndProbe = library.substring(
                library.indexOf("round9cc_read_process_snapshot() {"),
                library.indexOf("round9cc_instance_pid() {"));

        assertThat(ownershipCheck).contains("<<<\"${cmdline}\"")
                .doesNotContain("${cmdlin}", "kill -TERM", "kill -INT", "kill -KILL");
        assertThat(snapshotAndProbe.lines().filter(line -> line.contains("kill ")).toList())
                .isNotEmpty()
                .allMatch(line -> line.contains("kill -0 "));
        assertThat(start).doesNotContain("kill -TERM", "kill -INT", "kill -KILL");
        assertThat(library).contains(
                "round9cc_wait_for_phase_readiness()",
                "round9cc_assert_terminated_instance()",
                "round9cc_instance_pid()",
                "PID_REUSE_REJECTED",
                "PID_OWNERSHIP_REJECTED");

        Pattern ownershipLiteral = Pattern.compile("PID_OWNERSHIP_REJECT[A-Z]*");
        Pattern truncatedOwnershipLiteral = Pattern.compile("PID_OWNERSHIP_REJECTE(?!D)");
        List<String> ownershipLiterals = new ArrayList<>();
        List<String> truncatedOwnershipLiterals = new ArrayList<>();
        try (var paths = Files.list(scripts)) {
            for (Path path : paths.filter(candidate -> candidate.getFileName().toString().endsWith(".sh")).toList()) {
                String source = Files.readString(path);
                Matcher matcher = ownershipLiteral.matcher(source);
                while (matcher.find()) {
                    ownershipLiterals.add(matcher.group());
                }
                Matcher truncatedMatcher = truncatedOwnershipLiteral.matcher(source);
                while (truncatedMatcher.find()) {
                    truncatedOwnershipLiterals.add(truncatedMatcher.group());
                }
            }
        }
        assertThat(ownershipLiterals).isNotEmpty().containsOnly("PID_OWNERSHIP_REJECTED");
        assertThat(truncatedOwnershipLiterals).isEmpty();
    }

    @Test
    void processProbeTakesAFinalSnapshotBeforeRejectingCommandLineOwnership() throws Exception {
        String library = Files.readString(Path.of("src/test/scripts/round9cc/round9cc-lib.sh"));
        String probe = library.substring(
                library.indexOf("round9cc_probe_instance_state() {"),
                library.indexOf("round9cc_instance_pid() {"));
        int firstSnapshot = probe.indexOf("round9cc_read_process_snapshot \"${pid}\"");
        int secondSnapshot = probe.indexOf("round9cc_read_process_snapshot \"${pid}\"", firstSnapshot + 1);
        int finalOwnershipRejection = probe.lastIndexOf("printf '%s\\n' 'OWNERSHIP_REJECTED'");

        assertThat(firstSnapshot).isGreaterThanOrEqualTo(0);
        assertThat(secondSnapshot).isGreaterThan(firstSnapshot);
        assertThat(finalOwnershipRejection).isGreaterThan(secondSnapshot);
        assertThat(probe).contains(
                "round9cc_read_process_snapshot",
                "round9cc_probe_after_first_snapshot \"${pid}\"",
                "The process can exit after the liveness snapshot");
        assertThat(library).contains("round9cc_process_state_is_stopped()", "case \"${1:-}\" in", "Z|X|x");
        assertThat(library).contains(
                "printf '%s|%s|%s\\n' 'LIVE'",
                "snapshot=\"$(round9cc_read_process_snapshot \"${pid}\")\"",
                "IFS='|' read -r snapshot_kind snapshot_state snapshot_start snapshot_extra")
                .doesNotContain("ROUND9CC_PROBE_SNAPSHOT=");
        assertThat(library).contains(
                "round9cc_read_process_cmdline()",
                "tr '\\0' '\\n' <\"/proc/${pid}/cmdline\"",
                "} 2>/dev/null || true");
    }

    @Test
    void startScriptPublishesTheValidatedPidAndStartPairAtomicallyWithoutDirectFinalWrites() throws Exception {
        String library = Files.readString(Path.of("src/test/scripts/round9cc/round9cc-lib.sh"));
        String start = Files.readString(Path.of("src/test/scripts/round9cc/round9cc-start-instance.sh"));
        String publisher = library.substring(
                library.indexOf("round9cc_atomic_private_publish() {"),
                library.indexOf("round9cc_manifest_value() {"));
        String readiness = library.substring(
                library.indexOf("round9cc_wait_for_phase_readiness() {"),
                library.indexOf("round9cc_assert_terminated_instance() {"));

        int javaPid = start.indexOf("java_pid=$!");
        int javaStart = start.indexOf("java_start=\"$(awk '{print $22}'");
        int validation = start.indexOf("[[ \"${java_pid}\" =~ ^[1-9][0-9]*$");
        int publishPid = start.indexOf(
                "round9cc_atomic_private_publish \"${root}\" \"${runtime}/${instance}.pid\" \"${java_pid}\"");
        int publishStart = start.indexOf(
                "round9cc_atomic_private_publish \"${root}\" \"${runtime}/${instance}.start\" \"${java_start}\"");
        int wait = start.indexOf("wait \"${java_pid}\"");
        Pattern directIdentityWrite = Pattern.compile(
                "(?m)>\\s*\"\\$\\{runtime}/\\$\\{instance}\\.(?:pid|start)\"");

        assertThat(publisher).contains(
                "mktemp \"${runtime}/.round9cc-runtime.XXXXXXXX\"",
                "chmod 600 -- \"${temporary}\"",
                "round9cc_atomic_private_scalar_file_is_valid \"${temporary}\" \"${value}\"",
                "mv -n -- \"${temporary}\" \"${final_file}\"",
                "RUNTIME_FILE_EXISTS",
                "round9cc_before_atomic_runtime_publish \"${temporary}\" \"${final_file}\"",
                "round9cc_atomic_private_scalar_file_is_valid \"${final_file}\" \"${value}\"")
                .doesNotContain("sleep ");
        assertThat(library).contains(
                "round9cc_atomic_private_publish()",
                "Test-only shell-private observation seam",
                "round9cc_probe_identity_scalar()",
                "EVIDENCE_PENDING",
                "round9cc_die 'INSTANCE_RUNTIME_EVIDENCE_INVALID'",
                "PID_REUSE_REJECTED")
                .doesNotContain("if [[ -e \"${runtime}/${instance}.pid\" && -e \"${runtime}/${instance}.start\" ]]; then");
        assertThat(directIdentityWrite.matcher(start).find()).isFalse();
        assertThat(javaPid).isGreaterThanOrEqualTo(0);
        assertThat(javaStart).isGreaterThan(javaPid);
        assertThat(validation).isGreaterThan(javaStart);
        assertThat(publishPid).isGreaterThan(validation);
        assertThat(publishStart).isGreaterThan(publishPid);
        assertThat(wait).isGreaterThan(publishStart);
        assertThat(readiness).contains("EVIDENCE_INVALID", "INSTANCE_RUNTIME_EVIDENCE_INVALID");
        assertThat(start).doesNotContain("kill ", "pkill", "killall", "sleep ");
    }

    @Test
    void startScriptResetsInheritedSigintAtTheJavaExecBoundary() throws Exception {
        String start = Files.readString(Path.of("src/test/scripts/round9cc/round9cc-start-instance.sh"));
        String signal = Files.readString(Path.of("src/test/scripts/round9cc/round9cc-signal-instance.sh"));
        String launch = start.substring(
                start.indexOf("launch_args=("),
                start.indexOf("java_pid=$!"));

        int resetBoundary = launch.indexOf("env --default-signal=INT -u AURALINK_ENV_FILE --");
        int javaExec = launch.indexOf("java -Dloader.main=\"${ROUND9CC_HARNESS_MAIN}\"");

        assertThat(resetBoundary).isGreaterThanOrEqualTo(0);
        assertThat(javaExec).isGreaterThan(resetBoundary);
        assertThat(launch).contains(
                "env --default-signal=INT -u AURALINK_ENV_FILE -- \\\n"
                        + "    java -Dloader.main=\"${ROUND9CC_HARNESS_MAIN}\"",
                "java -Dloader.main=\"${ROUND9CC_HARNESS_MAIN}\"",
                "org.springframework.boot.loader.launch.PropertiesLauncher",
                "\"${launch_args[@]}\" &")
                .doesNotContain(
                        "env -u AURALINK_ENV_FILE java",
                        "trap - INT",
                        "setsid",
                        "nohup");
        assertThat(Pattern.compile("(?m)^\\s*java\\s+-Dloader\\.main=.*&\\s*$").matcher(launch).find())
                .as("direct background Java launch must not bypass the signal-reset exec boundary")
                .isFalse();
        assertThat(launch.split("env --default-signal=INT", -1)).hasSize(2);
        assertThat(launch.split("java -Dloader.main=", -1)).hasSize(2);
        assertThat(start).contains("\"${launch_args[@]}\" &\n  java_pid=$!");
        assertThat(start).doesNotContain("kill -INT", "kill -TERM", "kill -KILL", "pkill", "killall");
        assertThat(signal).contains(
                "TERM) signal_exit=143",
                "INT) signal_exit=130",
                "KILL) signal_exit=137");
    }

    @Test
    void readinessConsumesTheSingleProbeStateWithoutReinterpretingIdentityPaths() throws Exception {
        String library = Files.readString(Path.of("src/test/scripts/round9cc/round9cc-lib.sh"));
        String probe = library.substring(
                library.indexOf("round9cc_probe_identity_scalar() {"),
                library.indexOf("round9cc_instance_pid() {"));
        String readiness = library.substring(
                library.indexOf("round9cc_wait_for_phase_readiness() {"),
                library.indexOf("round9cc_assert_terminated_instance() {"));
        String terminated = library.substring(
                library.indexOf("round9cc_assert_terminated_instance() {"),
                library.indexOf("round9cc_wait_for_regular_file() {"));

        assertThat(probe).contains(
                "ABSENT|",
                "VALID|%s",
                "INVALID|",
                "ABSENT:ABSENT",
                "VALID:ABSENT",
                "ABSENT:VALID",
                "EVIDENCE_PENDING",
                "INVALID:*|*:INVALID")
                .doesNotContain("sleep ");
        assertThat(readiness).contains(
                "EVIDENCE_PENDING) ;;",
                "EVIDENCE_INVALID) round9cc_die 'INSTANCE_RUNTIME_EVIDENCE_INVALID'",
                "round9cc_before_final_phase_readiness_state_handling",
                "round9cc_die 'INSTANCE_START_TIMEOUT' 124",
                "PID_REUSED) round9cc_die 'PID_REUSE_REJECTED'",
                "PID_OWNERSHIP_REJECTED")
                .doesNotContain("${runtime}/${instance}.pid", "${runtime}/${instance}.start");
        assertThat(terminated).contains("EVIDENCE_PENDING|EVIDENCE_INVALID")
                .contains("INSTANCE_RUNTIME_EVIDENCE_INVALID");
        assertThat(library).contains(
                "ROUND9CC_STARTUP_DEADLINE_SECONDS=60",
                "round9cc_atomic_private_publish()",
                "round9cc_atomic_private_scalar_file_is_valid")
                .doesNotContain("ROUND9CC_PROBE_PENDING=");
    }

    @Test
    void termBeforeClaimCannotRegressToTheNonCanonicalRecoveryCallVocabulary() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/auralink/ops/round9cc/Round9CcScenario.java"));
        String audit = Files.readString(Path.of("src/test/scripts/round9cc/round9cc-audit-fixture.sh"));
        String termBeforeClaim = source.substring(
                source.indexOf("TERM_BEFORE_CLAIM("),
                source.indexOf("TERM_AFTER_CLAIM("));

        assertThat(termBeforeClaim)
                .contains("RecoveryProviderCallExpectation.ZERO.value(), \"none\", \"NONE\")")
                .doesNotContain("true, \"NONE\", \"none\", \"NONE\"");
        assertThat(audit).contains("[[ \"${recovery_calls}\" == 'ZERO'")
                .doesNotContain("NONE|ZERO", "== 'NONE' ||");
    }
}
