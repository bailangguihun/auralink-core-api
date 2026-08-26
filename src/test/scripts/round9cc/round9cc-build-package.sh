#!/usr/bin/env bash
set -euo pipefail
umask 077

source "$(cd "$(dirname "$0")" && pwd -P)/round9cc-lib.sh"
round9cc_require_server_local_project
diagnostic="$(mktemp /tmp/auralink-round9cc-build.XXXXXXXX)"
chmod 600 -- "${diagnostic}"
cleanup() {
  local status=$?
  if (( status == 0 )); then rm -f -- "${diagnostic}"; fi
  exit "${status}"
}
trap cleanup EXIT
cd "${ROUND9CC_BACKEND_ROOT}"
if ! env -u AURALINK_ENV_FILE mvn -o -Dmaven.repo.local=../.m2/repository -DskipTests clean package >"${diagnostic}" 2>&1; then
  round9cc_die 'PACKAGE_BUILD_FAILED' 1
fi
round9cc_require_jar
printf 'ROUND9CC_PACKAGED_JAR_OK\n'
