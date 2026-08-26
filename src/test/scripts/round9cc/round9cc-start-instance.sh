#!/usr/bin/env bash
set -euo pipefail
umask 077

source "$(cd "$(dirname "$0")" && pwd -P)/round9cc-lib.sh"
round9cc_require_server_local_project
round9cc_require_jar
[[ $# -eq 3 || $# -eq 4 ]] || round9cc_die 'USAGE:round9cc-start-instance.sh FIXTURE INSTANCE SCENARIO [INITIAL|SEED|RECOVERY]'
root="$(round9cc_validate_fixture "$1")"
instance="$2"
scenario="$3"
phase="${4:-INITIAL}"
round9cc_valid_instance "${instance}" || round9cc_die 'INSTANCE_INVALID'
round9cc_valid_label "${scenario}" || round9cc_die 'SCENARIO_INVALID'
round9cc_valid_phase "${phase}" || round9cc_die 'PHASE_INVALID'
[[ "$(round9cc_manifest_value "${root}" scenario)" == "${scenario}" ]] \
  || round9cc_die 'SCENARIO_MANIFEST_MISMATCH'
case "${phase}" in
  INITIAL) failpoint_key='initialFailpoint'; role_key='initialRole' ;;
  SEED) failpoint_key='seedFailpoint'; role_key='seedRole' ;;
  RECOVERY) failpoint_key='recoveryFailpoint'; role_key='recoveryRole' ;;
esac
failpoint="$(round9cc_manifest_value "${root}" "${failpoint_key}")"
role="$(round9cc_manifest_value "${root}" "${role_key}")"
[[ "${failpoint}" == 'NONE' ]] || round9cc_valid_label "${failpoint}" \
  || round9cc_die 'SCENARIO_MANIFEST_MISMATCH'
round9cc_valid_label "${role}" || round9cc_die 'SCENARIO_MANIFEST_MISMATCH'
round9cc_expected_exit_for_phase "${root}" "${phase}" >/dev/null

runtime="${root}/runtime"
for suffix in pid start monitor exit port phase role boundary seed recovery; do
  [[ ! -e "${runtime}/${instance}.${suffix}" && ! -L "${runtime}/${instance}.${suffix}" ]] \
    || round9cc_die 'INSTANCE_RUNTIME_EXISTS'
done
log="${root}/logs/${instance}.log"
round9cc_private_write "${runtime}/${instance}.phase" "${phase}"
(
  set +e
  launch_args=(--fixture-root="${root}" --instance="${instance}" --scenario="${scenario}" \
    --phase="${phase}" --failpoint-timeout-seconds=30)
  env --default-signal=INT -u AURALINK_ENV_FILE -- \
    java -Dloader.main="${ROUND9CC_HARNESS_MAIN}" -cp "${ROUND9CC_JAR}" \
    org.springframework.boot.loader.launch.PropertiesLauncher \
    "${launch_args[@]}" &
  java_pid=$!
  java_start="$(awk '{print $22}' "/proc/${java_pid}/stat" 2>/dev/null)"
  [[ "${java_pid}" =~ ^[1-9][0-9]*$ && "${java_start}" =~ ^[1-9][0-9]*$ \
      && "${java_start}" != *$'\n'* ]] \
    || round9cc_die 'INSTANCE_RUNTIME_EVIDENCE_INVALID'
  round9cc_atomic_private_publish "${root}" "${runtime}/${instance}.pid" "${java_pid}"
  round9cc_atomic_private_publish "${root}" "${runtime}/${instance}.start" "${java_start}"
  wait "${java_pid}"
  status=$?
  printf '%s\n' "${status}" >"${runtime}/${instance}.exit"
  chmod 600 -- "${runtime}/${instance}.exit"
  exit "${status}"
) >"${log}" 2>&1 &
monitor_pid=$!
round9cc_private_write "${runtime}/${instance}.monitor" "${monitor_pid}"
chmod 600 -- "${log}"
round9cc_wait_for_phase_readiness "${root}" "${instance}" "${scenario}" "${phase}" "${role}" \
  "${ROUND9CC_STARTUP_DEADLINE_SECONDS}"
printf 'ROUND9CC_INSTANCE_STARTED instance=%s phase=%s role=%s\n' "${instance}" "${phase}" "${role}"
