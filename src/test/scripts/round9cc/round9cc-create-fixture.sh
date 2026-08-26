#!/usr/bin/env bash
set -euo pipefail
umask 077

source "$(cd "$(dirname "$0")" && pwd -P)/round9cc-lib.sh"
round9cc_require_server_local_project
round9cc_require_jar
[[ $# -eq 1 ]] || round9cc_die 'USAGE:round9cc-create-fixture.sh SCENARIO'
scenario="$1"
round9cc_valid_label "${scenario}" || round9cc_die 'SCENARIO_INVALID'

root="$(mktemp -d /tmp/auralink-round9cc.XXXXXXXX)"
chmod 700 -- "${root}"
for directory in db managed provider-staging env control counters logs runtime manifest; do
  mkdir -- "${root}/${directory}"
  chmod 700 -- "${root}/${directory}"
done
printf 'ROUND9CC_FIXTURE\n' >"${root}/.round9cc-fixture"
chmod 600 -- "${root}/.round9cc-fixture"
cat >"${root}/env/fixture.properties" <<EOF
auralink.creations.lease-duration=2s
auralink.creations.heartbeat-interval=100ms
auralink.creations.recovery-grace=1s
auralink.creations.recovery-interval=250ms
auralink.creations.recovery-batch-size=50
auralink.creations.startup-max-batches=20
auralink.creations.recovery-fence-lease=300s
auralink.creations.shutdown-await=2s
EOF
chmod 600 -- "${root}/env/fixture.properties"
cat >"${root}/manifest/expected-counts.properties" <<EOF
generation_logs=0
paintings=0
catalog_import_runs=0
EOF
chmod 600 -- "${root}/manifest/expected-counts.properties"

manifest_log="${root}/logs/fixture-manifest.log"
if ! env -u AURALINK_ENV_FILE java -Dloader.main="${ROUND9CC_FIXTURE_TOOL_MAIN}" -cp "${ROUND9CC_JAR}" \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  --fixture-root="${root}" --scenario="${scenario}" >"${manifest_log}" 2>&1; then
  printf 'ROUND9CC_ERROR:FIXTURE_MANIFEST_FAILED\n' >&2
  exit 1
fi
chmod 600 -- "${manifest_log}"
grep -qx 'ROUND9CC_FIXTURE_MANIFEST_OK' "${manifest_log}" >/dev/null \
  || round9cc_die 'FIXTURE_MANIFEST_FAILED'
root="$(round9cc_validate_fixture "${root}")"
printf 'ROUND9CC_FIXTURE_READY root=%s scenario=%s\n' "${root}" "${scenario}"
