#!/usr/bin/env bash
set -euo pipefail
umask 077

readonly BACKEND_ROOT="$(cd "$(dirname "$0")/../../.." && pwd -P)"
readonly JAR="${BACKEND_ROOT}/target/auralink-backend-0.0.1-SNAPSHOT.jar"
readonly MAIN_CLASS="com.auralink.ops.round9b2.Round9B2PackagedMockHarness"

[[ -f "$JAR" && ! -L "$JAR" ]] || {
  printf 'ROUND9B2_PACKAGED_JAR_REQUIRED\n' >&2
  exit 2
}

output_file="$(mktemp /tmp/auralink-round9b2-packaged-output.XXXXXX)"
chmod 0600 "$output_file"
preserve_output=false
cleanup() {
  local status=$?
  if [[ "$preserve_output" != true ]]; then
    rm -f -- "$output_file"
  fi
  trap - EXIT
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

java_status=0
java \
  -Dloader.main="$MAIN_CLASS" \
  -cp "$JAR" \
  org.springframework.boot.loader.launch.PropertiesLauncher >"$output_file" 2>&1 || java_status=$?
if (( java_status != 0 )); then
  preserve_output=true
  printf 'ROUND9B2_PACKAGED_MOCK_HARNESS_APPLICATION_FAILED output=%s\n' "$output_file" >&2
  exit "$java_status"
fi

if ! grep -qx 'ROUND9B2_PACKAGED_MOCK_HARNESS_OK' "$output_file"; then
  printf 'ROUND9B2_PACKAGED_MOCK_HARNESS_SUCCESS_BOUNDARY_MISSING\n' >&2
  exit 2
fi
# Do not match generic Spring class names such as AuthorizationFilter.  This
# checks only credential-shaped output that the harness itself must never emit.
if grep -Eqi 'authorization:[[:space:]]*bearer|bearer[[:space:]]+[A-Za-z0-9._-]{16,}|api.?key[[:space:]]*[=:]|signed.?url[[:space:]]*[=:]|data:image|raw.?response[[:space:]]*[=:]' "$output_file"; then
  printf 'ROUND9B2_PACKAGED_HARNESS_OUTPUT_BOUNDARY_FAILED\n' >&2
  exit 2
fi
printf 'ROUND9B2_PACKAGED_MOCK_HARNESS_OK\n'
