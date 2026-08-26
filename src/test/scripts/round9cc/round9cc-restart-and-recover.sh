#!/usr/bin/env bash
set -euo pipefail
umask 077

source "$(cd "$(dirname "$0")" && pwd -P)/round9cc-lib.sh"
round9cc_require_server_local_project
[[ $# -eq 3 || $# -eq 4 ]] || round9cc_die 'USAGE:round9cc-restart-and-recover.sh FIXTURE NEW_INSTANCE SCENARIO [WAIT_SECONDS]'
root="$(round9cc_validate_fixture "$1")"
instance="$2"
scenario="$3"
wait_seconds="${4:-4}"
round9cc_valid_instance "${instance}" || round9cc_die 'INSTANCE_INVALID'
round9cc_valid_label "${scenario}" || round9cc_die 'SCENARIO_INVALID'
[[ "${wait_seconds}" =~ ^[1-9][0-9]{0,1}$ && "${wait_seconds}" -le 30 ]] \
  || round9cc_die 'RECOVERY_WAIT_INVALID'
[[ "$(round9cc_manifest_value "${root}" scenario)" == "${scenario}" \
    && "$(round9cc_manifest_value "${root}" requiresRecoveryRestart)" == 'true' ]] \
  || round9cc_die 'RECOVERY_RESTART_NOT_ALLOWED'
sleep "${wait_seconds}"
"${ROUND9CC_SCRIPT_DIR}/round9cc-start-instance.sh" "${root}" "${instance}" "${scenario}" RECOVERY
printf 'ROUND9CC_RECOVERY_RESTARTED instance=%s wait=%s\n' "${instance}" "${wait_seconds}"
