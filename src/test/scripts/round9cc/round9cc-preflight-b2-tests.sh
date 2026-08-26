#!/usr/bin/env bash
set -euo pipefail
umask 077

source "$(cd "$(dirname "$0")" && pwd -P)/round9cc-lib.sh"
round9cc_require_server_local_project

diagnostic="$(mktemp /tmp/auralink-round9cc-preflight.XXXXXXXX)"
chmod 600 -- "${diagnostic}"
no_env="$(mktemp /tmp/auralink-round9cc-no-env.XXXXXXXX.properties)"
chmod 600 -- "${no_env}"
cleanup() {
  local status=$?
  local retained=''
  set +e
  rm -f -- "${no_env}" 2>/dev/null
  if (( status == 0 )); then
    rm -f -- "${diagnostic}" 2>/dev/null
  elif [[ -f "${diagnostic}" && ! -L "${diagnostic}" ]]; then
    if retained="$(mktemp /tmp/auralink-round9cc-preflight-diagnostic.XXXXXXXX.log 2>/dev/null)" \
      && chmod 600 -- "${retained}" 2>/dev/null \
      && mv -- "${diagnostic}" "${retained}" 2>/dev/null; then
      printf 'ROUND9CC_B2_PREFLIGHT_FAILED diagnostic=%s\n' "${retained}"
    else
      [[ -z "${retained}" ]] || rm -f -- "${retained}" 2>/dev/null
      printf 'ROUND9CC_B2_PREFLIGHT_FAILED diagnostic=%s\n' "${diagnostic}"
    fi
  else
    printf 'ROUND9CC_B2_PREFLIGHT_FAILED diagnostic=UNAVAILABLE\n'
  fi
  exit "${status}"
}
trap cleanup EXIT

cd "${ROUND9CC_BACKEND_ROOT}"
first_status=0
env -u AURALINK_ENV_FILE mvn -o -Dmaven.repo.local=../.m2/repository \
  -Dtest=ConfigDataImportTest test >"${diagnostic}" 2>&1 || first_status=$?
if (( first_status != 0 )); then
  exit "${first_status}"
fi
second_status=0
env AURALINK_ENV_FILE="${no_env}" mvn -o -Dmaven.repo.local=../.m2/repository \
  -Dtest=CreationRecoveryCoordinatorTest,CreationRecoveryRepositoryIntegrationTest test >>"${diagnostic}" 2>&1 || second_status=$?
if (( second_status != 0 )); then
  exit "${second_status}"
fi
printf 'ROUND9CC_B2_PREFLIGHT_TESTS_OK\n'
