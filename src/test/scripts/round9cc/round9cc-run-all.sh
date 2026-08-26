#!/usr/bin/env bash
set -euo pipefail
umask 077

source "$(cd "$(dirname "$0")" && pwd -P)/round9cc-lib.sh"
round9cc_require_server_local_project
[[ $# -eq 1 && "$1" == '--server-local-execute' ]] \
  || round9cc_die 'EXPLICIT_SERVER_LOCAL_EXECUTION_REQUIRED'

"${ROUND9CC_SCRIPT_DIR}/round9cc-preflight-b2-tests.sh"
"${ROUND9CC_SCRIPT_DIR}/round9cc-build-package.sh"
printf 'ROUND9CC_C3_SCENARIO_SUPERVISION_REQUIRED\n'
