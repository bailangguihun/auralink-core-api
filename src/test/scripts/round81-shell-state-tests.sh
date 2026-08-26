#!/usr/bin/env bash
set -euo pipefail
umask 077

readonly BACKEND_ROOT="$(cd "$(dirname "$0")/../../.." && pwd -P)"
readonly COORDINATOR="${BACKEND_ROOT}/scripts/validate-round8-live-providers.sh"
readonly LAUNCHER="${BACKEND_ROOT}/scripts/start-vmm-service.sh"
readonly STATE_TEST="${BACKEND_ROOT}/src/test/scripts/round81_state_tool_test.py"

python3 "$STATE_TEST"
bash -n "$COORDINATOR"
bash -n "$LAUNCHER"

set +e
wrong_root_output="$(
  cd /tmp
  AURALINK_ROUND81_EXPECTED_COMMIT="$(git -C "$BACKEND_ROOT/.." rev-parse HEAD)" \
    "$COORDINATOR" --dry-run --operation=text-to-painting 2>&1
)"
wrong_root_status=$?
set -e
[[ $wrong_root_status -eq 2 ]]
[[ "$wrong_root_output" == *'SERVER_LOCAL_ROOT_REQUIRED'* ]]
[[ "$wrong_root_output" != *'apiKey'* && "$wrong_root_output" != *'Authorization'* ]]

grep -Fq '/root/autodl-tmp/auralink' "$COORDINATOR"
grep -Fq 'org.springframework.boot.loader.launch.PropertiesLauncher' "$COORDINATOR"
grep -Fq 'LOCAL_LOOPBACK_ONLY' "${BACKEND_ROOT}/src/test/scripts/round81-packaged-mock-harness.sh"
grep -Fq 'CONFLICTING_VALIDATION_PROCESS' "$COORDINATOR"
grep -Fq 'VMM_OWNED_FAILURE_CLEANUP_COMPLETE' "$COORDINATOR"
grep -Fq 'VMM_FAILURE_CLEANUP_INCOMPLETE' "$LAUNCHER"
! grep -Eq -- '--prompt|--poem|--image-path|--provider-url|--validate-all' "$COORDINATOR"
! grep -Eq 'pip install|conda install|micromamba install|apt(-get)? install' "$LAUNCHER"

temporary_root="$(mktemp -d /tmp/auralink-round81-shell.XXXXXX)"
chmod 0700 "$temporary_root"
fixture_root="${temporary_root}/project"
fixture_bin="${temporary_root}/bin"
fixture_run_root="${temporary_root}/runs"
fixture_state="${fixture_root}/backend/scripts/round81_provider_state.py"
fixture_launcher="${fixture_root}/backend/scripts/start-vmm-service.sh"
fixture_coordinator="${fixture_root}/backend/scripts/validate-round8-live-providers.sh"
java_log="${temporary_root}/java-invocations.log"
provider_call_log="${temporary_root}/provider-calls.log"
launcher_log="${temporary_root}/launcher-invocations.log"
sentinel_pid=''

cleanup() {
  if [[ -n "$sentinel_pid" ]] && kill -0 "$sentinel_pid" 2>/dev/null; then
    kill -TERM "$sentinel_pid"
    wait "$sentinel_pid" 2>/dev/null || true
  fi
  rm -rf -- "$temporary_root"
}
trap cleanup EXIT INT TERM

mkdir -m 0700 -p \
  "$fixture_bin" \
  "$fixture_run_root" \
  "${fixture_root}/backend/scripts" \
  "${fixture_root}/backend/target"

sed \
  -e "s|^readonly ROUND81_ROOT=.*|readonly ROUND81_ROOT=\"${fixture_root}\"|" \
  -e "s|^readonly ROUND81_RUN_ROOT=.*|readonly ROUND81_RUN_ROOT=\"${fixture_run_root}\"|" \
  "$COORDINATOR" >"$fixture_coordinator"
chmod 0700 "$fixture_coordinator"

printf '%s\n' \
  '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  'case "${1:-}" in' \
  '  inspect-database) printf '\''DATABASE_SNAPSHOT_UNCHANGED\n'\'' ;;' \
  '  preflight) printf '\''READY_FOR_CONTROLLED_EXECUTION\n'\'' ;;' \
  '  *) printf '\''UNEXPECTED_STATE_TOOL_COMMAND=%s\n'\'' "${1:-}" >&2; exit 91 ;;' \
  'esac' >"$fixture_state"
chmod 0700 "$fixture_state"

printf '%s\n' \
  '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  'printf '\''%s\n'\'' "$*" >>"$ROUND81_TEST_LAUNCHER_LOG"' \
  '[[ "${1:-}" == '\''status'\'' ]] && printf '\''VMM_UNOWNED_OR_STOPPED\n'\''' \
  'exit 0' >"$fixture_launcher"
chmod 0700 "$fixture_launcher"

printf '%s\n' \
  '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  'for port in ${ROUND81_TEST_LISTEN_PORTS:-}; do' \
  '  printf '\''LISTEN 0 128 127.0.0.1:%s 0.0.0.0:*\n'\'' "$port"' \
  'done' >"${fixture_bin}/ss"
chmod 0700 "${fixture_bin}/ss"

printf '%s\n' \
  '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  'printf '\''%s\n'\'' "$*" >>"$ROUND81_TEST_JAVA_LOG"' \
  'if [[ " $* " != *'\'' --mode=dry-run '\''* ]]; then' \
  '  : >"$ROUND81_TEST_PROVIDER_CALL_LOG"' \
  '  exit 92' \
  'fi' \
  'printf '\''DRY_RUN_ZERO_MUTATION\n'\''' \
  'printf '\''DRY_RUN_OK\n'\''' >"${fixture_bin}/java"
chmod 0700 "${fixture_bin}/java"

printf '%s\n' 'backend/.env' 'backend/target/' >"${fixture_root}/.gitignore"
: >"${fixture_root}/backend/.env"
: >"${fixture_root}/backend/target/auralink-backend-0.0.1-SNAPSHOT.jar"
git -C "$fixture_root" init -q
git -C "$fixture_root" config user.name 'Round81 Test'
git -C "$fixture_root" config user.email 'round81-test@local.invalid'
git -C "$fixture_root" add \
  .gitignore \
  backend/scripts/round81_provider_state.py \
  backend/scripts/start-vmm-service.sh \
  backend/scripts/validate-round8-live-providers.sh
git -C "$fixture_root" commit -qm 'Create local provider validation fixture'
fixture_commit="$(git -C "$fixture_root" rev-parse HEAD)"

fixture_output=''
fixture_status=0
run_fixture_dry_run() {
  local operation="$1"
  local listening_ports="${2:-}"
  set +e
  fixture_output="$(
    cd "$fixture_root"
    PATH="${fixture_bin}:$PATH" \
      ROUND81_TEST_LISTEN_PORTS="$listening_ports" \
      ROUND81_TEST_JAVA_LOG="$java_log" \
      ROUND81_TEST_PROVIDER_CALL_LOG="$provider_call_log" \
      ROUND81_TEST_LAUNCHER_LOG="$launcher_log" \
      AURALINK_ROUND81_EXPECTED_COMMIT="$fixture_commit" \
      "$fixture_coordinator" --dry-run "--operation=${operation}" 2>&1
  )"
  fixture_status=$?
  set -e
}

java_call_count() {
  if [[ -f "$java_log" ]]; then
    wc -l <"$java_log" | tr -d '[:space:]'
  else
    printf '0'
  fi
}

assert_fixture_lock_released() {
  local released_lock
  exec {released_lock}<"$fixture_state"
  flock -n "$released_lock"
  flock -u "$released_lock"
  exec {released_lock}<&-
}

for operation in \
  text-to-painting \
  image-to-painting \
  poem-to-painting \
  painting-to-poem; do
  run_fixture_dry_run "$operation"
  [[ $fixture_status -eq 0 ]]
  [[ "$fixture_output" == *'PROVIDER_VALIDATION_EXCLUSIVE_LOCK_ACQUIRED'* ]]
  [[ "$fixture_output" == *'READY_FOR_CONTROLLED_EXECUTION'* ]]
  [[ "$fixture_output" == *'DRY_RUN_ZERO_MUTATION'* ]]
  [[ "$fixture_output" == *'DRY_RUN_OK'* ]]
  assert_fixture_lock_released
done

# A process represented by the occupied-port fixture is unowned. External
# provider preflight must reject it without invoking Java, the VMM launcher,
# or any process cleanup.
sleep 300 &
sentinel_pid=$!
calls_before="$(java_call_count)"
run_fixture_dry_run text-to-painting 5001
[[ $fixture_status -eq 2 ]]
[[ "$fixture_output" == *'ROUND81_COORDINATOR_ERROR=UNEXPECTED_LISTENER_PRESENT'* ]]
[[ "$fixture_output" != *'DRY_RUN_ZERO_MUTATION'* ]]
[[ "$(java_call_count)" == "$calls_before" ]]
[[ ! -e "$provider_call_log" && ! -e "$launcher_log" ]]
kill -0 "$sentinel_pid"
assert_fixture_lock_released

for occupied_port in 5000 5002 5003 8000; do
  calls_before="$(java_call_count)"
  run_fixture_dry_run text-to-painting "$occupied_port"
  [[ $fixture_status -eq 2 ]]
  [[ "$fixture_output" == *'ROUND81_COORDINATOR_ERROR=UNEXPECTED_LISTENER_PRESENT'* ]]
  [[ "$(java_call_count)" == "$calls_before" ]]
  kill -0 "$sentinel_pid"
  assert_fixture_lock_released
done

# Painting-to-music alone may have the owned VMM listener on 5001. All other
# guarded ports remain unrelated and must still be free.
run_fixture_dry_run painting-to-music 5001
[[ $fixture_status -eq 0 ]]
[[ "$fixture_output" == *'DRY_RUN_ZERO_MUTATION'* ]]
[[ "$fixture_output" == *'DRY_RUN_OK'* ]]
assert_fixture_lock_released
calls_before="$(java_call_count)"
run_fixture_dry_run painting-to-music 5000
[[ $fixture_status -eq 2 ]]
[[ "$fixture_output" == *'ROUND81_COORDINATOR_ERROR=UNEXPECTED_LISTENER_PRESENT'* ]]
[[ "$(java_call_count)" == "$calls_before" ]]
assert_fixture_lock_released

[[ ! -e "$provider_call_log" && ! -e "$launcher_log" ]]
[[ "$(java_call_count)" == '5' ]]
[[ -z "$(git -C "$fixture_root" status --porcelain=v1 --untracked-files=all)" ]]
[[ -z "$(find "$fixture_run_root" -mindepth 1 -print -quit)" ]]
! find "$fixture_root" -type f \( \
  -name '*.db' -o -name '*.sqlite' -o -name '*-wal' -o -name '*-shm' \
\) -print -quit | grep -q .

exec {first_lock}<"$COORDINATOR"
flock -n "$first_lock"
(
  exec {second_lock}<"$COORDINATOR"
  ! flock -n "$second_lock"
)
flock -u "$first_lock"

printf 'ROUND81_STATE_TOOL_TESTS=PASS\n'
printf 'ROUND81_SHELL_GUARD_TESTS=PASS\n'
printf 'ROUND81_EXTERNAL_FREE_PORT_DRY_RUNS=PASS\n'
printf 'ROUND81_EXTERNAL_OCCUPIED_PORT_GUARDS=PASS\n'
printf 'ROUND81_VMM_PORT_SEMANTICS=PASS\n'
printf 'ROUND81_VALIDATION_LOCK_CLEANUP=PASS\n'
printf 'ROUND81_SHELL_ZERO_PROVIDER_OR_DATABASE_MUTATION=PASS\n'
