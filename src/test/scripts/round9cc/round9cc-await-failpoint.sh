#!/usr/bin/env bash
set -euo pipefail
umask 077

source "$(cd "$(dirname "$0")" && pwd -P)/round9cc-lib.sh"
round9cc_require_server_local_project
[[ $# -eq 4 ]] || round9cc_die 'USAGE:round9cc-await-failpoint.sh FIXTURE INSTANCE FAILPOINT TIMEOUT_SECONDS'
root="$(round9cc_validate_fixture "$1")"
instance="$2"
failpoint="$3"
timeout="$4"
round9cc_valid_instance "${instance}" || round9cc_die 'INSTANCE_INVALID'
round9cc_valid_label "${failpoint}" || round9cc_die 'FAILPOINT_INVALID'
[[ "${timeout}" =~ ^[1-9][0-9]{0,2}$ ]] || round9cc_die 'TIMEOUT_INVALID'
round9cc_instance_pid "${root}" "${instance}" >/dev/null
marker="${root}/control/${instance}/${failpoint}.reached"
round9cc_wait_for_regular_file "${marker}" "${timeout}" || round9cc_die 'FAILPOINT_TIMEOUT' 124
[[ ! -L "${marker}" && "$(stat -c %a -- "${marker}")" == '600' && "$(<"${marker}")" == "${failpoint}" ]] \
  || round9cc_die 'FAILPOINT_MARKER_INVALID'
printf 'ROUND9CC_FAILPOINT_REACHED failpoint=%s\n' "${failpoint}"
