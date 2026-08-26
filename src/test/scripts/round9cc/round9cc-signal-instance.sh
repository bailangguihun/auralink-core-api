#!/usr/bin/env bash
set -euo pipefail
umask 077

source "$(cd "$(dirname "$0")" && pwd -P)/round9cc-lib.sh"
round9cc_require_server_local_project
[[ $# -eq 3 ]] || round9cc_die 'USAGE:round9cc-signal-instance.sh FIXTURE INSTANCE TERM|INT|KILL'
root="$(round9cc_validate_fixture "$1")"
instance="$2"
signal="$3"
round9cc_valid_instance "${instance}" || round9cc_die 'INSTANCE_INVALID'
case "${signal}" in TERM|INT|KILL) ;; *) round9cc_die 'SIGNAL_INVALID' ;; esac
phase="$(round9cc_runtime_value "${root}" "${instance}" 'phase')"
round9cc_valid_phase "${phase}" || round9cc_die 'INSTANCE_RUNTIME_EVIDENCE_INVALID'
expected_exit="$(round9cc_expected_exit_for_phase "${root}" "${phase}")"
case "${signal}" in
  TERM) signal_exit=143 ;;
  INT) signal_exit=130 ;;
  KILL) signal_exit=137 ;;
esac
[[ "${expected_exit}" == "${signal_exit}" ]] || round9cc_die 'SIGNAL_SCENARIO_MISMATCH'
pid="$(round9cc_instance_pid "${root}" "${instance}")"
kill "-${signal}" "${pid}"
exit_file="${root}/runtime/${instance}.exit"
round9cc_wait_for_regular_file "${exit_file}" 45 || round9cc_die 'PROCESS_EXIT_TIMEOUT' 124
exit_code="$(round9cc_runtime_value "${root}" "${instance}" 'exit')"
[[ "${exit_code}" =~ ^[0-9]{1,3}$ ]] || round9cc_die 'PROCESS_EXIT_INVALID'
[[ "${exit_code}" == "${expected_exit}" ]] || round9cc_die 'PROCESS_EXIT_MISMATCH'
round9cc_assert_terminated_instance "${root}" "${instance}"
printf 'ROUND9CC_SIGNAL_DELIVERED signal=%s exit=%s\n' "${signal}" "${exit_code}"
