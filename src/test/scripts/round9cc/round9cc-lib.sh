#!/usr/bin/env bash
set -euo pipefail
umask 077

readonly ROUND9CC_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly ROUND9CC_BACKEND_ROOT="$(cd "${ROUND9CC_SCRIPT_DIR}/../../../.." && pwd -P)"
readonly ROUND9CC_JAR="${ROUND9CC_BACKEND_ROOT}/target/auralink-backend-0.0.1-SNAPSHOT.jar"
readonly ROUND9CC_HARNESS_MAIN="com.auralink.ops.round9cc.Round9CcPackagedFailureHarness"
readonly ROUND9CC_FIXTURE_TOOL_MAIN="com.auralink.ops.round9cc.Round9CcFixtureTool"
readonly ROUND9CC_STARTUP_DEADLINE_SECONDS=60

round9cc_die() {
  printf 'ROUND9CC_ERROR:%s\n' "$1" >&2
  exit "${2:-2}"
}

round9cc_require_server_local_project() {
  [[ -f "${ROUND9CC_BACKEND_ROOT}/pom.xml" ]] || round9cc_die 'BACKEND_ROOT_INVALID'
  local fs_type
  fs_type="$(stat -f -c %T "${ROUND9CC_BACKEND_ROOT}")"
  case "${fs_type}" in
    fuse*|sshfs*) round9cc_die 'SERVER_LOCAL_FILESYSTEM_REQUIRED' ;;
  esac
}

round9cc_require_jar() {
  [[ -f "${ROUND9CC_JAR}" && ! -L "${ROUND9CC_JAR}" ]] || round9cc_die 'PACKAGED_JAR_REQUIRED'
}

round9cc_valid_label() {
  [[ "$1" =~ ^[A-Z][A-Z0-9_]{1,63}$ ]]
}

round9cc_valid_instance() {
  [[ "$1" =~ ^[A-Za-z][A-Za-z0-9_-]{0,31}$ ]]
}

round9cc_valid_phase() {
  case "$1" in INITIAL|SEED|RECOVERY) ;; *) return 1 ;; esac
}

round9cc_validate_fixture() {
  local root="${1:-}"
  [[ -n "${root}" && ! -L "${root}" && -d "${root}" ]] || round9cc_die 'FIXTURE_INVALID'
  local real_root
  real_root="$(readlink -f -- "${root}")"
  [[ "${real_root}" =~ ^/tmp/auralink-round9cc\.[A-Za-z0-9_-]{8,64}$ ]] || round9cc_die 'FIXTURE_INVALID'
  [[ "$(dirname -- "${real_root}")" == '/tmp' && -O "${real_root}" ]] || round9cc_die 'FIXTURE_INVALID'
  [[ "$(stat -c %a -- "${real_root}")" == '700' ]] || round9cc_die 'FIXTURE_INVALID'
  local marker="${real_root}/.round9cc-fixture"
  [[ -f "${marker}" && ! -L "${marker}" && -O "${marker}" ]] || round9cc_die 'FIXTURE_INVALID'
  [[ "$(stat -c %a -- "${marker}")" == '600' && "$(stat -c %h -- "${marker}")" == '1' ]] \
    || round9cc_die 'FIXTURE_INVALID'
  [[ "$(<"${marker}")" == 'ROUND9CC_FIXTURE' ]] || round9cc_die 'FIXTURE_INVALID'
  local part
  for part in db managed provider-staging env control counters logs runtime manifest; do
    [[ -d "${real_root}/${part}" && ! -L "${real_root}/${part}" && -O "${real_root}/${part}" ]] \
      || round9cc_die 'FIXTURE_INVALID'
    [[ "$(stat -c %a -- "${real_root}/${part}")" == '700' ]] || round9cc_die 'FIXTURE_INVALID'
  done
  printf '%s\n' "${real_root}"
}

round9cc_private_write() {
  local file="$1"
  local value="$2"
  [[ ! -e "${file}" && ! -L "${file}" ]] || round9cc_die 'RUNTIME_FILE_EXISTS'
  printf '%s\n' "${value}" >"${file}"
  chmod 600 -- "${file}"
}

round9cc_atomic_private_scalar_file_is_valid() {
  local file="$1"
  local expected="$2"
  local actual line_count
  [[ -f "${file}" && ! -L "${file}" && -O "${file}" \
      && "$(stat -c %a -- "${file}")" == '600' && "$(stat -c %h -- "${file}")" == '1' ]] \
    || return 1
  line_count="$(wc -l <"${file}")" || return 1
  [[ "${line_count}" == '1' ]] || return 1
  actual="$(<"${file}")"
  [[ -n "${actual}" && "${actual}" != *$'\n'* && "${actual}" == "${expected}" ]]
}

round9cc_before_atomic_runtime_publish() {
  # Test-only shell-private observation seam. Operator scripts never override
  # it; it has no environment or application-property activation path and
  # performs no process or signal action.
  :
}

round9cc_atomic_private_publish() {
  # Publish one already-validated scalar runtime value without exposing a
  # partially-written final evidence file. This intentionally does not change
  # round9cc_private_write(), whose callers have different publication rules.
  local requested_root="$1"
  local final_file="$2"
  local value="$3"
  local root runtime final_name temporary
  root="$(round9cc_validate_fixture "${requested_root}")"
  runtime="${root}/runtime"
  final_name="${final_file##*/}"
  [[ "${final_file}" == "${runtime}/${final_name}" \
      && "${final_name}" =~ ^[A-Za-z][A-Za-z0-9_-]{0,31}\.(pid|start)$ \
      && -n "${value}" && "${value}" != *$'\n'* ]] \
    || round9cc_die 'INSTANCE_RUNTIME_EVIDENCE_INVALID'
  [[ ! -e "${final_file}" && ! -L "${final_file}" ]] || round9cc_die 'RUNTIME_FILE_EXISTS'

  temporary="$(mktemp "${runtime}/.round9cc-runtime.XXXXXXXX")" \
    || round9cc_die 'INSTANCE_RUNTIME_EVIDENCE_INVALID'
  if [[ "${temporary}" == "${final_file}" || "${temporary}" != "${runtime}/.round9cc-runtime."* ]]; then
    rm -f -- "${temporary}"
    round9cc_die 'INSTANCE_RUNTIME_EVIDENCE_INVALID'
  fi
  if ! chmod 600 -- "${temporary}" \
      || ! printf '%s\n' "${value}" >"${temporary}" \
      || ! round9cc_atomic_private_scalar_file_is_valid "${temporary}" "${value}"; then
    rm -f -- "${temporary}"
    round9cc_die 'INSTANCE_RUNTIME_EVIDENCE_INVALID'
  fi

  if ! round9cc_before_atomic_runtime_publish "${temporary}" "${final_file}"; then
    rm -f -- "${temporary}"
    round9cc_die 'INSTANCE_RUNTIME_EVIDENCE_INVALID'
  fi
  if ! mv -n -- "${temporary}" "${final_file}"; then
    rm -f -- "${temporary}"
    round9cc_die 'INSTANCE_RUNTIME_EVIDENCE_INVALID'
  fi
  if [[ -e "${temporary}" || -L "${temporary}" ]]; then
    rm -f -- "${temporary}"
    if [[ -e "${final_file}" || -L "${final_file}" ]]; then
      round9cc_die 'RUNTIME_FILE_EXISTS'
    fi
    round9cc_die 'INSTANCE_RUNTIME_EVIDENCE_INVALID'
  fi
  round9cc_atomic_private_scalar_file_is_valid "${final_file}" "${value}" \
    || round9cc_die 'INSTANCE_RUNTIME_EVIDENCE_INVALID'
}

round9cc_manifest_value() {
  local root="$1"
  local key="$2"
  [[ "${key}" =~ ^[A-Za-z][A-Za-z0-9]*$ ]] || round9cc_die 'SCENARIO_MANIFEST_INVALID'
  local manifest="${root}/manifest/scenario.properties"
  [[ -f "${manifest}" && ! -L "${manifest}" && -O "${manifest}" \
      && "$(stat -c %a -- "${manifest}")" == '600' && "$(stat -c %h -- "${manifest}")" == '1' ]] \
    || round9cc_die 'SCENARIO_MANIFEST_INVALID'
  local values
  values="$(sed -n "s/^${key}=//p" "${manifest}")"
  [[ -n "${values}" && "$(printf '%s\n' "${values}" | wc -l)" == '1' ]] \
    || round9cc_die 'SCENARIO_MANIFEST_INVALID'
  printf '%s\n' "${values}"
}

round9cc_runtime_value() {
  local root="$1"
  local instance="$2"
  local suffix="$3"
  local file
  file="$(round9cc_runtime_file "${root}" "${instance}" "${suffix}")"
  local value
  value="$(<"${file}")"
  [[ -n "${value}" && "${value}" != *$'\n'* ]] || round9cc_die 'INSTANCE_RUNTIME_EVIDENCE_INVALID'
  printf '%s\n' "${value}"
}

round9cc_runtime_file() {
  local root="$1"
  local instance="$2"
  local suffix="$3"
  round9cc_valid_instance "${instance}" || round9cc_die 'INSTANCE_INVALID'
  [[ "${suffix}" =~ ^[a-z][a-z0-9_-]{0,31}$ ]] || round9cc_die 'INSTANCE_RUNTIME_EVIDENCE_INVALID'
  local file="${root}/runtime/${instance}.${suffix}"
  [[ -f "${file}" && ! -L "${file}" && -O "${file}" \
      && "$(stat -c %a -- "${file}")" == '600' && "$(stat -c %h -- "${file}")" == '1' ]] \
    || round9cc_die 'INSTANCE_RUNTIME_EVIDENCE_INVALID'
  printf '%s\n' "${file}"
}

round9cc_runtime_port() {
  local root="$1"
  local instance="$2"
  local port
  port="$(round9cc_runtime_value "${root}" "${instance}" 'port')"
  [[ "${port}" =~ ^[1-9][0-9]{0,4}$ && "${port}" -le 65535 ]] \
    || round9cc_die 'INSTANCE_PORT_INVALID'
  printf '%s\n' "${port}"
}

round9cc_expected_exit_for_phase() {
  local root="$1"
  local phase="$2"
  round9cc_valid_phase "${phase}" || round9cc_die 'INSTANCE_RUNTIME_EVIDENCE_INVALID'
  local key
  case "${phase}" in
    INITIAL) key='initialExpectedExit' ;;
    SEED) key='seedExpectedExit' ;;
    RECOVERY) key='recoveryExpectedExit' ;;
  esac
  local exit_code
  exit_code="$(round9cc_manifest_value "${root}" "${key}")"
  [[ "${exit_code}" =~ ^[0-9]{1,3}$ ]] || round9cc_die 'SCENARIO_MANIFEST_INVALID'
  printf '%s\n' "${exit_code}"
}

round9cc_assert_no_listener() {
  local root="$1"
  local instance="$2"
  local port
  port="$(round9cc_runtime_port "${root}" "${instance}")"
  command -v ss >/dev/null 2>&1 || round9cc_die 'LISTENER_CHECK_REQUIRED'
  local listeners
  listeners="$(ss -ltnH "sport = :${port}" 2>/dev/null)" || round9cc_die 'LISTENER_CHECK_FAILED'
  [[ -z "${listeners}" ]] || round9cc_die 'LISTENER_REMAINS'
}

round9cc_listener_present() {
  local root="$1"
  local instance="$2"
  local port
  port="$(round9cc_runtime_port "${root}" "${instance}")"
  command -v ss >/dev/null 2>&1 || round9cc_die 'LISTENER_CHECK_REQUIRED'
  local listeners
  listeners="$(ss -ltnH "sport = :${port}" 2>/dev/null)" || round9cc_die 'LISTENER_CHECK_FAILED'
  [[ -n "${listeners}" ]]
}

round9cc_assert_phase_runtime_evidence() {
  local root="$1"
  local instance="$2"
  local phase="$3"
  local role="$4"
  [[ "$(round9cc_runtime_value "${root}" "${instance}" 'phase')" == "${phase}" ]] \
    || round9cc_die 'SCENARIO_MANIFEST_MISMATCH'
  [[ "$(round9cc_runtime_value "${root}" "${instance}" 'role')" == "${role}" ]] \
    || round9cc_die 'SCENARIO_MANIFEST_MISMATCH'
  local boundary_file boundary
  boundary_file="$(round9cc_runtime_file "${root}" "${instance}" 'boundary')"
  boundary="$(<"${boundary_file}")"
  [[ "${boundary}" == $'NO_BACKEND_ENV\nMOCK_ONLY_NO_REAL_PROVIDER' ]] \
    || round9cc_die 'ENVIRONMENT_BOUNDARY_FAILED'
  round9cc_runtime_port "${root}" "${instance}" >/dev/null
}

round9cc_phase_completion_file() {
  local root="$1"
  local instance="$2"
  local suffix="$3"
  local code="$4"
  round9cc_valid_instance "${instance}" || round9cc_die 'INSTANCE_INVALID'
  local file="${root}/runtime/${instance}.${suffix}"
  [[ -f "${file}" && ! -L "${file}" && -O "${file}" \
      && "$(stat -c %a -- "${file}")" == '600' && "$(stat -c %h -- "${file}")" == '1' ]] \
    || round9cc_die "${code}"
  printf '%s\n' "${file}"
}

round9cc_assert_one_shot_completion() {
  local root="$1"
  local instance="$2"
  local scenario="$3"
  local phase="$4"
  local role="$5"
  local expected_exit actual_exit completion_file completion expected
  expected_exit="$(round9cc_expected_exit_for_phase "${root}" "${phase}")"
  actual_exit="$(round9cc_runtime_value "${root}" "${instance}" 'exit')"
  [[ "${actual_exit}" == "${expected_exit}" ]] || round9cc_die 'PROCESS_EXIT_MISMATCH'
  round9cc_assert_phase_runtime_evidence "${root}" "${instance}" "${phase}" "${role}"
  round9cc_assert_terminated_instance "${root}" "${instance}"
  case "${phase}" in
    SEED)
      completion_file="$(round9cc_phase_completion_file \
        "${root}" "${instance}" 'seed' 'SEED_COMPLETION_EVIDENCE_INVALID')"
      completion="$(<"${completion_file}")"
      expected="SCENARIO=${scenario}"$'\n'"ROLE=${role}"$'\nCREATIONS=1\nEXECUTION_ATTEMPTS=1\nMOCK_PROVIDER_CALLS=0'
      [[ "${completion}" == "${expected}" ]] || round9cc_die 'SEED_COMPLETION_EVIDENCE_INVALID'
      ;;
    RECOVERY)
      completion_file="$(round9cc_phase_completion_file \
        "${root}" "${instance}" 'recovery' 'RECOVERY_EVIDENCE_INVALID')"
      completion="$(<"${completion_file}")"
      expected="SCENARIO=${scenario}"$'\n'"ROLE=${role}"$'\nRECOVERY_GATE_OPEN\nRECOVERY_PROVIDER_CALLS='"$(round9cc_manifest_value "${root}" recoveryProviderCalls)"$'\nORDINARY_DISPATCH_RESUMES='"$(round9cc_manifest_value "${root}" ordinaryDispatchResumes)"
      [[ "${completion}" == "${expected}" ]] || round9cc_die 'RECOVERY_EVIDENCE_INVALID'
      ;;
    *) round9cc_die 'PHASE_INVALID' ;;
  esac
}

round9cc_wait_for_phase_readiness() {
  local root="$1"
  local instance="$2"
  local scenario="$3"
  local phase="$4"
  local role="$5"
  local timeout_seconds="$6"
  [[ "${timeout_seconds}" =~ ^[1-9][0-9]{0,2}$ ]] || round9cc_die 'STARTUP_DEADLINE_INVALID'
  local deadline=$((SECONDS + timeout_seconds))
  local state runtime
  local common_evidence_seen=false listener_seen=false exact_owner_seen=false
  runtime="${root}/runtime"
  while (( SECONDS < deadline )); do
    state="$(round9cc_probe_instance_state "${root}" "${instance}")"
    case "${state}" in
      ALIVE)
        # A one-shot JVM can exit after its exact identity was observed but
        # before its monitor publishes the private exit evidence. Record this
        # before listener readiness so that narrow publication race is bounded
        # by this existing deadline without weakening the ownership probe.
        exact_owner_seen=true
        if [[ -f "${runtime}/${instance}.port" && -f "${runtime}/${instance}.role" \
            && -f "${runtime}/${instance}.boundary" ]]; then
          round9cc_assert_phase_runtime_evidence "${root}" "${instance}" "${phase}" "${role}"
          common_evidence_seen=true
          if round9cc_listener_present "${root}" "${instance}"; then
            listener_seen=true
            if [[ "${phase}" == 'INITIAL' ]]; then
              return 0
            fi
          fi
        fi
        ;;
      STOPPED)
        if [[ -f "${runtime}/${instance}.exit" ]]; then
          if [[ "${phase}" == 'INITIAL' ]]; then
            round9cc_die 'INITIAL_LIVE_REQUIRED'
          fi
          round9cc_assert_one_shot_completion "${root}" "${instance}" "${scenario}" "${phase}" "${role}"
          return 0
        fi
        ;;
      PID_REUSED) round9cc_die 'PID_REUSE_REJECTED' ;;
      OWNERSHIP_REJECTED)
        if [[ "${phase}" != 'INITIAL' && "${exact_owner_seen}" == true ]]; then
          # Do not infer STOPPED or accept completion here. A later STOPPED
          # probe plus complete one-shot evidence remains mandatory.
          :
        else
          round9cc_die 'PID_OWNERSHIP_REJECTED'
        fi
        ;;
      EVIDENCE_PENDING) ;;
      EVIDENCE_INVALID) round9cc_die 'INSTANCE_RUNTIME_EVIDENCE_INVALID' ;;
      *) round9cc_die 'INSTANCE_RUNTIME_EVIDENCE_INVALID' ;;
    esac
    sleep 0.05
  done
  state="$(round9cc_probe_instance_state "${root}" "${instance}")"
  round9cc_before_final_phase_readiness_state_handling "${state}" "${root}" "${instance}" "${phase}"
  case "${state}" in
    STOPPED)
      if [[ -f "${runtime}/${instance}.exit" ]]; then
        if [[ "${phase}" == 'INITIAL' ]]; then
          round9cc_die 'INITIAL_LIVE_REQUIRED'
        fi
        round9cc_assert_one_shot_completion "${root}" "${instance}" "${scenario}" "${phase}" "${role}"
        return 0
      fi
      ;;
    PID_REUSED) round9cc_die 'PID_REUSE_REJECTED' ;;
    OWNERSHIP_REJECTED)
      # A persistent rejected identity is never a timeout or success, even
      # after a previously exact-owned one-shot process was observed alive.
      round9cc_die 'PID_OWNERSHIP_REJECTED'
      ;;
    EVIDENCE_PENDING) ;;
    EVIDENCE_INVALID) round9cc_die 'INSTANCE_RUNTIME_EVIDENCE_INVALID' ;;
    ALIVE) ;;
    *) round9cc_die 'INSTANCE_RUNTIME_EVIDENCE_INVALID' ;;
  esac
  if [[ "${state}" == 'ALIVE' && "${common_evidence_seen}" == true \
      && "${listener_seen}" == false ]]; then
    round9cc_die 'LISTENER_NOT_READY' 124
  fi
  round9cc_die 'INSTANCE_START_TIMEOUT' 124
}

round9cc_assert_terminated_instance() {
  local root="$1"
  local instance="$2"
  local state
  state="$(round9cc_probe_instance_state "${root}" "${instance}")"
  case "${state}" in
    STOPPED) ;;
    ALIVE) round9cc_die 'INSTANCE_STILL_RUNNING' ;;
    PID_REUSED) round9cc_die 'PID_REUSE_REJECTED' ;;
    OWNERSHIP_REJECTED) round9cc_die 'PID_OWNERSHIP_REJECTED' ;;
    EVIDENCE_PENDING|EVIDENCE_INVALID) round9cc_die 'INSTANCE_RUNTIME_EVIDENCE_INVALID' ;;
    *) round9cc_die 'INSTANCE_RUNTIME_EVIDENCE_INVALID' ;;
  esac
  round9cc_assert_no_listener "${root}" "${instance}"
}

round9cc_wait_for_regular_file() {
  local file="$1"
  local timeout_seconds="$2"
  local deadline=$((SECONDS + timeout_seconds))
  while (( SECONDS < deadline )); do
    if [[ -f "${file}" && ! -L "${file}" ]]; then
      return 0
    fi
    sleep 0.05
  done
  return 1
}

round9cc_process_state_is_stopped() {
  case "${1:-}" in
    Z|X|x) return 0 ;;
    *) return 1 ;;
  esac
}

round9cc_read_process_snapshot() {
  # Emits exactly one KIND|STATE|START_TIME line. Command-line identity is
  # intentionally separate so an exiting process cannot be classified from an
  # empty /proc/<pid>/cmdline read.
  local pid="$1"

  if [[ ! -d "/proc/${pid}" ]]; then
    printf '%s\n' 'STOPPED||'
    return 0
  fi

  local stat after_stat process_state process_start
  local -a stat_fields=()
  if ! stat="$(<"/proc/${pid}/stat")"; then
    if ! kill -0 "${pid}" 2>/dev/null; then
      printf '%s\n' 'STOPPED||'
    else
      printf '%s\n' 'UNREADABLE||'
    fi
    return 0
  fi
  if [[ -z "${stat}" || "${stat}" != *') '* ]]; then
    if ! kill -0 "${pid}" 2>/dev/null; then
      printf '%s\n' 'STOPPED||'
    else
      printf '%s\n' 'UNREADABLE||'
    fi
    return 0
  fi
  after_stat="${stat##*) }"
  read -r -a stat_fields <<<"${after_stat}"
  process_state="${stat_fields[0]:-}"
  process_start="${stat_fields[19]:-}"
  if [[ ! "${process_state}" =~ ^[A-Za-z]$ || ! "${process_start}" =~ ^[1-9][0-9]*$ ]]; then
    if ! kill -0 "${pid}" 2>/dev/null; then
      printf '%s\n' 'STOPPED||'
    else
      printf '%s\n' 'UNREADABLE||'
    fi
    return 0
  fi
  if ! kill -0 "${pid}" 2>/dev/null; then
    printf '%s\n' 'STOPPED||'
    return 0
  fi

  if round9cc_process_state_is_stopped "${process_state}"; then
    printf '%s\n' 'STOPPED||'
  else
    printf '%s|%s|%s\n' 'LIVE' "${process_state}" "${process_start}"
  fi
}

round9cc_cmdline_is_exact_harness_owner() {
  local cmdline="$1"
  local root="$2"
  grep -Fqx -- "-Dloader.main=${ROUND9CC_HARNESS_MAIN}" <<<"${cmdline}" >/dev/null \
    && grep -Fqx -- "--fixture-root=${root}" <<<"${cmdline}" >/dev/null
}

round9cc_read_process_cmdline() {
  local pid="$1"
  # The shell opens this redirection before tr starts. Group it so a process
  # disappearing at that instant cannot leak a /proc diagnostic into a probe.
  {
    tr '\0' '\n' <"/proc/${pid}/cmdline"
  } 2>/dev/null || true
}

round9cc_probe_after_first_snapshot() {
  # Deliberately empty. Focused script tests override this local test seam to
  # force a deterministic exit between the first snapshot and cmdline read.
  # Operator scripts never override it and it performs no process action.
  :
}

round9cc_probe_identity_scalar() {
  # Emits exactly one ABSENT|, VALID|<positive-decimal>, or INVALID| line.
  # A dangling symlink is visible unsafe evidence, not an absent file.
  local file="$1"
  local mode links line_count value
  if [[ ! -e "${file}" && ! -L "${file}" ]]; then
    printf '%s\n' 'ABSENT|'
    return 0
  fi
  if [[ ! -f "${file}" || -L "${file}" || ! -O "${file}" ]]; then
    printf '%s\n' 'INVALID|'
    return 0
  fi
  if ! mode="$(stat -c %a -- "${file}" 2>/dev/null)" \
      || ! links="$(stat -c %h -- "${file}" 2>/dev/null)" \
      || [[ "${mode}" != '600' || "${links}" != '1' ]]; then
    printf '%s\n' 'INVALID|'
    return 0
  fi
  if ! line_count="$(wc -l <"${file}")" || ! value="$(<"${file}")" \
      || [[ "${line_count//[[:space:]]/}" != '1' \
          || ! "${value}" =~ ^[1-9][0-9]*$ || "${value}" == *$'\n'* ]]; then
    printf '%s\n' 'INVALID|'
    return 0
  fi
  printf 'VALID|%s\n' "${value}"
}

round9cc_before_final_phase_readiness_state_handling() {
  # Test-only shell-private seam for the final post-deadline probe result.
  # Operator scripts never override it; it has no external activation path and
  # performs no process or signal action.
  :
}

round9cc_probe_instance_state() {
  # This probe accepts an already-validated fixture root. Unlike
  # round9cc_instance_pid, it never exits: audit needs to distinguish normal
  # historical process exit from malformed runtime evidence and a live owner.
  local root="$1"
  local instance="$2"
  if ! round9cc_valid_instance "${instance}"; then
    printf '%s\n' 'EVIDENCE_INVALID'
    return 0
  fi

  local runtime="${root}/runtime"
  local pid_file="${runtime}/${instance}.pid"
  local start_file="${runtime}/${instance}.start"
  local pid_evidence start_evidence pid_kind pid pid_extra start_kind start start_extra
  pid_evidence="$(round9cc_probe_identity_scalar "${pid_file}")"
  start_evidence="$(round9cc_probe_identity_scalar "${start_file}")"
  IFS='|' read -r pid_kind pid pid_extra <<<"${pid_evidence}"
  IFS='|' read -r start_kind start start_extra <<<"${start_evidence}"
  if [[ "${pid_evidence}" == *$'\n'* || "${start_evidence}" == *$'\n'* \
      || -n "${pid_extra}" || -n "${start_extra}" ]]; then
    printf '%s\n' 'EVIDENCE_INVALID'
    return 0
  fi
  case "${pid_kind}:${start_kind}" in
    INVALID:*|*:INVALID)
      printf '%s\n' 'EVIDENCE_INVALID'
      return 0
      ;;
    ABSENT:ABSENT)
      [[ -z "${pid}" && -z "${start}" ]] || {
        printf '%s\n' 'EVIDENCE_INVALID'
        return 0
      }
      printf '%s\n' 'EVIDENCE_PENDING'
      return 0
      ;;
    VALID:ABSENT)
      [[ "${pid}" =~ ^[1-9][0-9]*$ && -z "${start}" ]] || {
        printf '%s\n' 'EVIDENCE_INVALID'
        return 0
      }
      printf '%s\n' 'EVIDENCE_PENDING'
      return 0
      ;;
    ABSENT:VALID)
      [[ -z "${pid}" && "${start}" =~ ^[1-9][0-9]*$ ]] || {
        printf '%s\n' 'EVIDENCE_INVALID'
        return 0
      }
      printf '%s\n' 'EVIDENCE_PENDING'
      return 0
      ;;
    VALID:VALID)
      [[ "${pid}" =~ ^[1-9][0-9]*$ && "${start}" =~ ^[1-9][0-9]*$ ]] || {
        printf '%s\n' 'EVIDENCE_INVALID'
        return 0
      }
      ;;
    *)
      printf '%s\n' 'EVIDENCE_INVALID'
      return 0
      ;;
  esac

  local snapshot snapshot_kind snapshot_state snapshot_start snapshot_extra
  snapshot="$(round9cc_read_process_snapshot "${pid}")"
  IFS='|' read -r snapshot_kind snapshot_state snapshot_start snapshot_extra <<<"${snapshot}"
  if [[ "${snapshot}" == *$'\n'* || -n "${snapshot_extra}" ]]; then
    printf '%s\n' 'OWNERSHIP_REJECTED'
    return 0
  fi
  case "${snapshot_kind}" in
    STOPPED)
      [[ -z "${snapshot_state}" && -z "${snapshot_start}" ]] || {
        printf '%s\n' 'OWNERSHIP_REJECTED'
        return 0
      }
      printf '%s\n' 'STOPPED'
      return 0
      ;;
    UNREADABLE)
      [[ -z "${snapshot_state}" && -z "${snapshot_start}" ]] || {
        printf '%s\n' 'OWNERSHIP_REJECTED'
        return 0
      }
      printf '%s\n' 'OWNERSHIP_REJECTED'
      return 0
      ;;
    LIVE)
      [[ "${snapshot_state}" =~ ^[A-Za-z]$ && "${snapshot_start}" =~ ^[1-9][0-9]*$ ]] || {
        printf '%s\n' 'OWNERSHIP_REJECTED'
        return 0
      }
      ;;
    *)
      printf '%s\n' 'OWNERSHIP_REJECTED'
      return 0
      ;;
  esac
  if [[ "${snapshot_start}" != "${start}" ]]; then
    printf '%s\n' 'PID_REUSED'
    return 0
  fi

  round9cc_probe_after_first_snapshot "${pid}"
  local cmdline
  cmdline="$(round9cc_read_process_cmdline "${pid}")"
  if round9cc_cmdline_is_exact_harness_owner "${cmdline}" "${root}"; then
    printf '%s\n' 'ALIVE'
    return 0
  fi

  # The process can exit after the liveness snapshot but before /proc cmdline
  # is read. Recheck state before rejecting the exact ownership contract.
  snapshot="$(round9cc_read_process_snapshot "${pid}")"
  IFS='|' read -r snapshot_kind snapshot_state snapshot_start snapshot_extra <<<"${snapshot}"
  if [[ "${snapshot}" == *$'\n'* || -n "${snapshot_extra}" ]]; then
    printf '%s\n' 'OWNERSHIP_REJECTED'
    return 0
  fi
  case "${snapshot_kind}" in
    STOPPED)
      [[ -z "${snapshot_state}" && -z "${snapshot_start}" ]] || {
        printf '%s\n' 'OWNERSHIP_REJECTED'
        return 0
      }
      printf '%s\n' 'STOPPED'
      return 0
      ;;
    UNREADABLE)
      [[ -z "${snapshot_state}" && -z "${snapshot_start}" ]] || {
        printf '%s\n' 'OWNERSHIP_REJECTED'
        return 0
      }
      printf '%s\n' 'OWNERSHIP_REJECTED'
      return 0
      ;;
    LIVE)
      [[ "${snapshot_state}" =~ ^[A-Za-z]$ && "${snapshot_start}" =~ ^[1-9][0-9]*$ ]] || {
        printf '%s\n' 'OWNERSHIP_REJECTED'
        return 0
      }
      ;;
    *)
      printf '%s\n' 'OWNERSHIP_REJECTED'
      return 0
      ;;
  esac
  if [[ "${snapshot_start}" != "${start}" ]]; then
    printf '%s\n' 'PID_REUSED'
    return 0
  fi

  cmdline="$(round9cc_read_process_cmdline "${pid}")"
  if round9cc_cmdline_is_exact_harness_owner "${cmdline}" "${root}"; then
    printf '%s\n' 'ALIVE'
  else
    printf '%s\n' 'OWNERSHIP_REJECTED'
  fi
}

round9cc_instance_pid() {
  local root="$1"
  local instance="$2"
  round9cc_valid_instance "${instance}" || round9cc_die 'INSTANCE_INVALID'
  local state pid
  state="$(round9cc_probe_instance_state "${root}" "${instance}")"
  case "${state}" in
    ALIVE)
      pid="$(<"${root}/runtime/${instance}.pid")"
      printf '%s\n' "${pid}"
      ;;
    PID_REUSED) round9cc_die 'PID_REUSE_REJECTED' ;;
    OWNERSHIP_REJECTED) round9cc_die 'PID_OWNERSHIP_REJECTED' ;;
    STOPPED|EVIDENCE_PENDING|EVIDENCE_INVALID) round9cc_die 'INSTANCE_NOT_RUNNING' ;;
    *) round9cc_die 'INSTANCE_NOT_RUNNING' ;;
  esac
}
