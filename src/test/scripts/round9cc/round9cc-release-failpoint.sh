#!/usr/bin/env bash
set -euo pipefail
umask 077

source "$(cd "$(dirname "$0")" && pwd -P)/round9cc-lib.sh"
round9cc_require_server_local_project
[[ $# -eq 3 ]] || round9cc_die 'USAGE:round9cc-release-failpoint.sh FIXTURE INSTANCE FAILPOINT'
root="$(round9cc_validate_fixture "$1")"
instance="$2"
failpoint="$3"
round9cc_valid_instance "${instance}" || round9cc_die 'INSTANCE_INVALID'
round9cc_valid_label "${failpoint}" || round9cc_die 'FAILPOINT_INVALID'
reached="${root}/control/${instance}/${failpoint}.reached"
release="${root}/control/${instance}/${failpoint}.release"
[[ -f "${reached}" && ! -L "${reached}" && ! -e "${release}" && ! -L "${release}" ]] \
  || round9cc_die 'FAILPOINT_RELEASE_REJECTED'
round9cc_instance_pid "${root}" "${instance}" >/dev/null
printf 'RELEASE\n' >"${release}"
chmod 600 -- "${release}"
printf 'ROUND9CC_FAILPOINT_RELEASED failpoint=%s\n' "${failpoint}"
