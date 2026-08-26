#!/usr/bin/env bash
set -euo pipefail
umask 077

source "$(cd "$(dirname "$0")" && pwd -P)/round9cc-lib.sh"
round9cc_require_server_local_project
[[ $# -eq 1 ]] || round9cc_die 'USAGE:round9cc-audit-fixture.sh FIXTURE'
root="$(round9cc_validate_fixture "$1")"
db="${root}/db/fixture.db"
[[ -f "${db}" && ! -L "${db}" ]] || round9cc_die 'FIXTURE_DATABASE_MISSING'

while IFS= read -r pid_file; do
  instance="$(basename -- "${pid_file}" .pid)"
  state="$(round9cc_probe_instance_state "${root}" "${instance}")"
  case "${state}" in
    STOPPED) ;;
    PID_REUSED) round9cc_die 'PID_REUSE_REJECTED' ;;
    ALIVE) round9cc_die 'INSTANCE_STILL_RUNNING' ;;
    EVIDENCE_INVALID) round9cc_die 'INSTANCE_RUNTIME_EVIDENCE_INVALID' ;;
    OWNERSHIP_REJECTED) round9cc_die 'PID_OWNERSHIP_REJECTED' ;;
    *) round9cc_die 'INSTANCE_RUNTIME_EVIDENCE_INVALID' ;;
  esac
done < <(find "${root}/runtime" -maxdepth 1 -type f -name '*.pid' -print)

command -v sqlite3 >/dev/null 2>&1 || round9cc_die 'SQLITE3_REQUIRED'
integrity="$(sqlite3 -readonly "${db}" 'PRAGMA integrity_check;')"
[[ "${integrity}" == 'ok' ]] || round9cc_die 'SQLITE_INTEGRITY_FAILED'
[[ -z "$(sqlite3 -readonly "${db}" 'PRAGMA foreign_key_check;')" ]] || round9cc_die 'SQLITE_FOREIGN_KEY_FAILED'
versions="$(sqlite3 -readonly "${db}" 'SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank;')"
[[ "${versions}" == $'1\n2\n3\n4' ]] || round9cc_die 'FLYWAY_HISTORY_FAILED'

assert_empty() {
  local query="$1"
  local code="$2"
  [[ -z "$(sqlite3 -readonly "${db}" "${query}")" ]] || round9cc_die "${code}"
}
assert_empty 'SELECT creation_id FROM creation_execution_attempts WHERE finished_at IS NULL GROUP BY creation_id HAVING COUNT(*) > 1;' 'ACTIVE_ATTEMPT_DUPLICATE'
assert_empty 'SELECT creation_id, attempt_number FROM creation_execution_attempts GROUP BY creation_id, attempt_number HAVING COUNT(*) > 1;' 'EXECUTION_ATTEMPT_DUPLICATE'
assert_empty 'SELECT creation_step_id, creation_execution_attempt_id FROM creation_step_dispatch_attempts GROUP BY creation_step_id, creation_execution_attempt_id HAVING COUNT(*) > 1;' 'DISPATCH_ATTEMPT_DUPLICATE'
assert_empty 'SELECT provider_request_key FROM creation_step_dispatch_attempts WHERE provider_request_key IS NOT NULL GROUP BY provider_request_key HAVING COUNT(*) > 1;' 'REQUEST_KEY_DUPLICATE'
assert_empty 'SELECT d.id FROM creation_step_dispatch_attempts d JOIN creation_steps s ON s.id=d.creation_step_id JOIN creation_execution_attempts a ON a.id=d.creation_execution_attempt_id WHERE s.creation_id <> a.creation_id;' 'LEDGER_OWNERSHIP_FAILED'

scenario="$(round9cc_manifest_value "${root}" scenario)"
round9cc_valid_label "${scenario}" || round9cc_die 'SCENARIO_MANIFEST_INVALID'
expected_status="$(round9cc_manifest_value "${root}" expectedCreationStatus)"
[[ -n "${expected_status}" ]] || round9cc_die 'SCENARIO_MANIFEST_INVALID'
if [[ "${scenario}" != 'TWO_RECOVERY_FENCE_RACE' ]]; then
  assert_empty "SELECT creation_id FROM creation_steps WHERE status='RUNNING' GROUP BY creation_id HAVING COUNT(*) > 1;" 'MULTIPLE_RUNNING_STEPS'
fi
[[ "$(sqlite3 -readonly "${db}" 'SELECT COUNT(*) FROM generation_logs;')" == '0' ]] || round9cc_die 'GENERATION_LOG_WRITE_DETECTED'
[[ "$(sqlite3 -readonly "${db}" 'SELECT COUNT(*) FROM paintings;')" == '0' ]] || round9cc_die 'OFFICIAL_PAINTING_DETECTED'
counts_manifest="${root}/manifest/expected-counts.properties"
[[ -f "${counts_manifest}" && ! -L "${counts_manifest}" && "$(stat -c %a -- "${counts_manifest}")" == '600' ]] \
  || round9cc_die 'SYNTHETIC_COUNT_MANIFEST_INVALID'
while IFS='=' read -r table expected; do
  case "${table}" in generation_logs|paintings|catalog_import_runs) ;; *) round9cc_die 'SYNTHETIC_COUNT_MANIFEST_INVALID' ;; esac
  [[ "${expected}" =~ ^[0-9]+$ ]] || round9cc_die 'SYNTHETIC_COUNT_MANIFEST_INVALID'
  [[ "$(sqlite3 -readonly "${db}" "SELECT COUNT(*) FROM ${table};")" == "${expected}" ]] \
    || round9cc_die 'SYNTHETIC_TABLE_COUNT_MISMATCH'
done <"${counts_manifest}"
if [[ "${expected_status}" != 'N/A' ]]; then
  [[ "$(sqlite3 -readonly "${db}" 'SELECT COUNT(*) FROM creations;')" != '0' ]] \
    || round9cc_die 'CREATION_STATUS_MISMATCH'
  [[ "$(sqlite3 -readonly "${db}" "SELECT COUNT(*) FROM creations WHERE status <> '${expected_status}';")" == '0' ]] \
    || round9cc_die 'CREATION_STATUS_MISMATCH'
fi

actual_entry=0
actual_return=0
actual_close=0
journal_count=0
for journal in "${root}/counters"/*.journal; do
  [[ -e "${journal}" ]] || continue
  [[ -f "${journal}" && ! -L "${journal}" && "$(stat -c %a -- "${journal}")" == '600' ]] \
    || round9cc_die 'MOCK_JOURNAL_INVALID'
  awk -F'|' 'NF != 5 || $1 !~ /^[1-9][0-9]*$/ || $5 !~ /^(ENTRY|RETURN|CLOSE)$/ { exit 1 }' "${journal}" \
    || round9cc_die 'MOCK_JOURNAL_INVALID'
  actual_entry=$((actual_entry + $(awk -F'|' '$5 == "ENTRY" { count++ } END { print count + 0 }' "${journal}")))
  actual_return=$((actual_return + $(awk -F'|' '$5 == "RETURN" { count++ } END { print count + 0 }' "${journal}")))
  actual_close=$((actual_close + $(awk -F'|' '$5 == "CLOSE" { count++ } END { print count + 0 }' "${journal}")))
  journal_count=$((journal_count + 1))
done
(( journal_count > 0 )) || round9cc_die 'MOCK_JOURNAL_MISSING'
expected_entry="$(round9cc_manifest_value "${root}" expectedMockEntry)"
expected_return="$(round9cc_manifest_value "${root}" expectedMockReturn)"
expected_close="$(round9cc_manifest_value "${root}" expectedMockClose)"
[[ "${actual_entry}" == "${expected_entry}" && "${actual_return}" == "${expected_return}" \
  && "${actual_close}" == "${expected_close}" ]] || round9cc_die 'MOCK_CALL_COUNT_MISMATCH'

boundary_count=0
while IFS= read -r boundary_file; do
  [[ -f "${boundary_file}" && ! -L "${boundary_file}" ]] || round9cc_die 'ENVIRONMENT_BOUNDARY_FAILED'
  grep -qx 'NO_BACKEND_ENV' "${boundary_file}" >/dev/null || round9cc_die 'ENVIRONMENT_BOUNDARY_FAILED'
  grep -qx 'MOCK_ONLY_NO_REAL_PROVIDER' "${boundary_file}" >/dev/null || round9cc_die 'PROVIDER_BOUNDARY_FAILED'
  boundary_count=$((boundary_count + 1))
done < <(find "${root}/runtime" -maxdepth 1 -type f -name '*.boundary' -print)
(( boundary_count > 0 )) || round9cc_die 'ENVIRONMENT_BOUNDARY_FAILED'

round9cc_is_batch1() {
  case "$1" in
    TERM_BEFORE_CLAIM|TERM_AFTER_CLAIM|TERM_DURING_NOT_SENT|INT_AFTER_SEND_STARTED) return 0 ;;
    *) return 1 ;;
  esac
}

round9cc_assert_exact_query() {
  local query="$1"
  local expected="$2"
  local code="$3"
  [[ "$(sqlite3 -readonly "${db}" "${query}")" == "${expected}" ]] || round9cc_die "${code}"
}

round9cc_assert_batch1_runtime() {
  local expected_phase_count=0 seed_count=0 initial_count=0 recovery_count=0
  local phase_file instance phase expected_exit actual_exit role_key failpoint_key expected_role expected_failpoint role_file recovery_file reached_count
  for phase_file in "${root}/runtime"/*.phase; do
    [[ -e "${phase_file}" ]] || continue
    [[ -f "${phase_file}" && ! -L "${phase_file}" && -O "${phase_file}" \
        && "$(stat -c %a -- "${phase_file}")" == '600' && "$(stat -c %h -- "${phase_file}")" == '1' ]] \
      || round9cc_die 'INSTANCE_RUNTIME_EVIDENCE_INVALID'
    instance="$(basename -- "${phase_file}" .phase)"
    phase="$(round9cc_runtime_value "${root}" "${instance}" 'phase')"
    round9cc_valid_phase "${phase}" || round9cc_die 'INSTANCE_RUNTIME_EVIDENCE_INVALID'
    expected_exit="$(round9cc_expected_exit_for_phase "${root}" "${phase}")"
    actual_exit="$(round9cc_runtime_value "${root}" "${instance}" 'exit')"
    [[ "${actual_exit}" == "${expected_exit}" ]] || round9cc_die 'PROCESS_EXIT_MISMATCH'
    case "${phase}" in
      INITIAL) role_key='initialRole'; failpoint_key='initialFailpoint'; initial_count=$((initial_count + 1)) ;;
      SEED) role_key='seedRole'; failpoint_key='seedFailpoint'; seed_count=$((seed_count + 1)) ;;
      RECOVERY) role_key='recoveryRole'; failpoint_key='recoveryFailpoint'; recovery_count=$((recovery_count + 1)) ;;
    esac
    expected_role="$(round9cc_manifest_value "${root}" "${role_key}")"
    expected_failpoint="$(round9cc_manifest_value "${root}" "${failpoint_key}")"
    role_file="$(round9cc_runtime_value "${root}" "${instance}" 'role')"
    [[ "${role_file}" == "${expected_role}" ]] || round9cc_die 'SCENARIO_MANIFEST_MISMATCH'
    reached_count="$(find "${root}/control/${instance}" -xdev -maxdepth 1 -type f -name '*.reached' -print | wc -l)"
    if [[ "${expected_failpoint}" == 'NONE' ]]; then
      [[ "${reached_count}" == '0' ]] || round9cc_die 'SCENARIO_MANIFEST_MISMATCH'
    else
      [[ "${reached_count}" == '1' && -f "${root}/control/${instance}/${expected_failpoint}.reached" \
          && ! -L "${root}/control/${instance}/${expected_failpoint}.reached" ]] \
        || round9cc_die 'SCENARIO_MANIFEST_MISMATCH'
    fi
    round9cc_assert_no_listener "${root}" "${instance}"
    if [[ "${phase}" == 'RECOVERY' ]]; then
      recovery_file="${root}/runtime/${instance}.recovery"
      [[ -f "${recovery_file}" && ! -L "${recovery_file}" && -O "${recovery_file}" \
          && "$(stat -c %a -- "${recovery_file}")" == '600' && "$(stat -c %h -- "${recovery_file}")" == '1' ]] \
        || round9cc_die 'RECOVERY_EVIDENCE_INVALID'
      grep -qx 'RECOVERY_GATE_OPEN' "${recovery_file}" >/dev/null || round9cc_die 'RECOVERY_EVIDENCE_INVALID'
      grep -qx "RECOVERY_PROVIDER_CALLS=$(round9cc_manifest_value "${root}" recoveryProviderCalls)" "${recovery_file}" \
        >/dev/null || round9cc_die 'RECOVERY_EVIDENCE_INVALID'
      grep -qx "ORDINARY_DISPATCH_RESUMES=$(round9cc_manifest_value "${root}" ordinaryDispatchResumes)" "${recovery_file}" \
        >/dev/null || round9cc_die 'RECOVERY_EVIDENCE_INVALID'
    fi
    expected_phase_count=$((expected_phase_count + 1))
  done
  [[ "${expected_phase_count}" == '3' && "${seed_count}" == '1' && "${initial_count}" == '1' \
      && "${recovery_count}" == '1' ]] || round9cc_die 'INSTANCE_RUNTIME_EVIDENCE_INVALID'
}

round9cc_assert_batch1_state() {
  local expected_step expected_dispatch expected_attempt expected_claim expected_safe expected_files recovery_calls ordinary
  expected_step="$(round9cc_manifest_value "${root}" expectedStepStatus)"
  expected_dispatch="$(round9cc_manifest_value "${root}" expectedDispatchState)"
  expected_attempt="$(round9cc_manifest_value "${root}" expectedAttemptState)"
  expected_claim="$(round9cc_manifest_value "${root}" expectedClaimLease)"
  expected_safe="$(round9cc_manifest_value "${root}" safeCode)"
  expected_files="$(round9cc_manifest_value "${root}" expectedFiles)"
  recovery_calls="$(round9cc_manifest_value "${root}" recoveryProviderCalls)"
  ordinary="$(round9cc_manifest_value "${root}" ordinaryDispatchResumes)"
  [[ "$(round9cc_manifest_value "${root}" requiresRecoveryRestart)" == 'true' ]] \
    || round9cc_die 'SCENARIO_MANIFEST_MISMATCH'
  round9cc_assert_exact_query 'SELECT COUNT(*) FROM creations;' '1' 'CREATION_STATUS_MISMATCH'
  round9cc_assert_exact_query "SELECT status FROM creations;" "${expected_status}" 'CREATION_STATUS_MISMATCH'
  round9cc_assert_exact_query 'SELECT COUNT(*) FROM creation_steps;' '1' 'STEP_STATUS_MISMATCH'
  round9cc_assert_exact_query "SELECT status FROM creation_steps;" "${expected_step}" 'STEP_STATUS_MISMATCH'
  round9cc_assert_exact_query "SELECT provider_dispatch_state FROM creation_steps;" "${expected_dispatch}" 'DISPATCH_STATE_MISMATCH'
  round9cc_assert_exact_query 'SELECT COUNT(*) FROM creation_execution_attempts;' '1' 'EXECUTION_ATTEMPT_MISMATCH'
  case "${expected_attempt}" in
    ACTIVE) round9cc_assert_exact_query 'SELECT COUNT(*) FROM creation_execution_attempts WHERE finished_at IS NULL;' '1' 'EXECUTION_ATTEMPT_MISMATCH' ;;
    FINISHED) round9cc_assert_exact_query 'SELECT COUNT(*) FROM creation_execution_attempts WHERE finished_at IS NULL;' '0' 'EXECUTION_ATTEMPT_MISMATCH' ;;
    *) round9cc_die 'SCENARIO_MANIFEST_MISMATCH' ;;
  esac
  case "${expected_claim}" in
    CLEAR) round9cc_assert_exact_query 'SELECT COUNT(*) FROM creations WHERE claim_token IS NULL AND lease_expires_at IS NULL;' '1' 'CLAIM_LEASE_MISMATCH' ;;
    PRESENT) round9cc_assert_exact_query 'SELECT COUNT(*) FROM creations WHERE claim_token IS NOT NULL AND lease_expires_at IS NOT NULL;' '1' 'CLAIM_LEASE_MISMATCH' ;;
    *) round9cc_die 'SCENARIO_MANIFEST_MISMATCH' ;;
  esac
  if [[ "${expected_safe}" == 'NONE' ]]; then
    round9cc_assert_exact_query 'SELECT COUNT(*) FROM creations WHERE error_code IS NULL AND error_message IS NULL;' '1' 'SAFE_CODE_MISMATCH'
    round9cc_assert_exact_query 'SELECT COUNT(*) FROM creation_steps WHERE error_code IS NULL AND error_message IS NULL;' '1' 'SAFE_CODE_MISMATCH'
  else
    round9cc_assert_exact_query "SELECT COUNT(*) FROM creations WHERE error_code = '${expected_safe}' AND error_message = '${expected_safe}';" '1' 'SAFE_CODE_MISMATCH'
    round9cc_assert_exact_query "SELECT COUNT(*) FROM creation_steps WHERE error_code = '${expected_safe}' AND error_message = '${expected_safe}';" '1' 'SAFE_CODE_MISMATCH'
  fi
  [[ "${recovery_calls}" == 'ZERO' && "${ordinary}" =~ ^(true|false)$ ]] \
    || round9cc_die 'SCENARIO_MANIFEST_MISMATCH'
  case "${expected_files}" in
    none)
      [[ -z "$(find "${root}/managed" "${root}/provider-staging" -xdev -mindepth 1 -print -quit)" ]] \
        || round9cc_die 'FIXTURE_FILE_STATE_MISMATCH'
      ;;
    *) round9cc_die 'SCENARIO_MANIFEST_MISMATCH' ;;
  esac
}

if round9cc_is_batch1 "${scenario}"; then
  round9cc_assert_batch1_runtime
  round9cc_assert_batch1_state
fi
printf 'ROUND9CC_FIXTURE_AUDIT_OK scenario=%s\n' "${scenario}"
