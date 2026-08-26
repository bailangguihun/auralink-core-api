#!/usr/bin/env bash
set -euo pipefail
umask 077

readonly ROUND81_ROOT="/root/autodl-tmp/auralink"
readonly ROUND81_RUN_ROOT="/root/auralink_provider_validation_runs"
readonly ROUND81_JAR="${ROUND81_ROOT}/backend/target/auralink-backend-0.0.1-SNAPSHOT.jar"
readonly ROUND81_STATE="${ROUND81_ROOT}/backend/scripts/round81_provider_state.py"
readonly ROUND81_VMM_LAUNCHER="${ROUND81_ROOT}/backend/scripts/start-vmm-service.sh"
readonly ROUND81_MAIN="com.auralink.ops.round81.Round81ProviderValidationCommand"
round81_lock_fd=''
round81_vmm_failure_cleanup_armed=0

usage() {
  printf '%s\n' \
    'Usage:' \
    '  validate-round8-live-providers.sh --preflight-all' \
    '  validate-round8-live-providers.sh --dry-run --operation=<operation>' \
    '  validate-round8-live-providers.sh --validate --operation=<operation>' >&2
  exit 64
}

fail() {
  local code="$1"
  printf 'ROUND81_COORDINATOR_ERROR=%s\n' "$code" >&2
  exit 2
}

cleanup_owned_vmm_on_failure() {
  local status=$?
  if (( status != 0 && round81_vmm_failure_cleanup_armed == 1 )); then
    local service_status
    service_status="$($ROUND81_VMM_LAUNCHER status 2>/dev/null || true)"
    if [[ "$service_status" == *'VMM_OWNED_SERVICE_RUNNING'* ]]; then
      AURALINK_ROUND81_VMM_ACTION='STOP_OWNED_ROUND81_VMM' \
        "$ROUND81_VMM_LAUNCHER" stop >/dev/null 2>&1 || true
    fi
    if port_is_listening 5001; then
      printf 'ROUND81_COORDINATOR_ERROR=VMM_FAILURE_CLEANUP_INCOMPLETE\n' >&2
    else
      printf 'VMM_OWNED_FAILURE_CLEANUP_COMPLETE\n' >&2
    fi
  fi
  exit "$status"
}
trap cleanup_owned_vmm_on_failure EXIT

operation_valid() {
  case "$1" in
    text-to-painting|image-to-painting|poem-to-painting|painting-to-poem|painting-to-music) return 0 ;;
    *) return 1 ;;
  esac
}

required_confirmation() {
  case "$1" in
    text-to-painting) printf 'VALIDATE_ONE_LIVE_TEXT_TO_PAINTING' ;;
    image-to-painting) printf 'VALIDATE_ONE_LIVE_IMAGE_TO_PAINTING' ;;
    poem-to-painting) printf 'VALIDATE_ONE_LIVE_POEM_TO_PAINTING' ;;
    painting-to-poem) printf 'VALIDATE_ONE_LIVE_PAINTING_TO_POEM' ;;
    painting-to-music) printf 'VALIDATE_ONE_LIVE_PAINTING_TO_MUSIC' ;;
    *) fail 'UNSUPPORTED_OPERATION' ;;
  esac
}

verify_server_local_guards() {
  [[ "$(pwd -P)" == "$ROUND81_ROOT" ]] || fail 'SERVER_LOCAL_ROOT_REQUIRED'
  [[ ! -L "$ROUND81_ROOT" ]] || fail 'SERVER_LOCAL_ROOT_REQUIRED'
  local fs_type
  fs_type="$(findmnt -n -o FSTYPE -T "$ROUND81_ROOT" 2>/dev/null || true)"
  [[ -n "$fs_type" ]] || fail 'FILESYSTEM_TYPE_UNAVAILABLE'
  case "${fs_type,,}" in
    *fuse*|*sshfs*) fail 'SSHFS_EXECUTION_REFUSED' ;;
  esac
  [[ "${AURALINK_ROUND81_EXPECTED_COMMIT:-}" =~ ^[0-9a-f]{40}$ ]] \
    || fail 'EXPECTED_COMMIT_REQUIRED'
  local actual_commit
  actual_commit="$(git -C "$ROUND81_ROOT" rev-parse HEAD)" || fail 'GIT_GUARD_FAILED'
  [[ "$actual_commit" == "$AURALINK_ROUND81_EXPECTED_COMMIT" ]] \
    || fail 'REVIEWED_COMMIT_MISMATCH'
  [[ -z "$(git -C "$ROUND81_ROOT" status --porcelain=v1 --untracked-files=normal)" ]] \
    || fail 'WORKTREE_NOT_CLEAN'
  [[ -f "$ROUND81_ROOT/backend/.env" && ! -L "$ROUND81_ROOT/backend/.env" ]] \
    || fail 'BACKEND_ENV_REQUIRED'
  git -C "$ROUND81_ROOT" check-ignore -q backend/.env || fail 'BACKEND_ENV_NOT_IGNORED'
  [[ -f "$ROUND81_JAR" && ! -L "$ROUND81_JAR" ]] || fail 'PACKAGED_JAR_REQUIRED'
  [[ -x "$ROUND81_STATE" && ! -L "$ROUND81_STATE" ]] || fail 'STATE_TOOL_REQUIRED'
  [[ -x "$ROUND81_VMM_LAUNCHER" && ! -L "$ROUND81_VMM_LAUNCHER" ]] \
    || fail 'VMM_LAUNCHER_REQUIRED'
  printf 'SERVER_LOCAL_ROOT_VERIFIED\n'
  printf 'REVIEWED_COMMIT_VERIFIED=%s\n' "$actual_commit"
  printf 'PROVIDER_VALIDATION_WORKTREE_CLEAN\n'
}

acquire_validation_lock() {
  command -v flock >/dev/null 2>&1 || fail 'VALIDATION_LOCK_UNAVAILABLE'
  exec {round81_lock_fd}<"$ROUND81_STATE" || fail 'VALIDATION_LOCK_UNAVAILABLE'
  flock -n "$round81_lock_fd" || fail 'CONFLICTING_VALIDATION_PROCESS'
  printf 'PROVIDER_VALIDATION_EXCLUSIVE_LOCK_ACQUIRED\n'
}

port_is_listening() {
  local port="$1"
  ss -ltnH 2>/dev/null | awk -v suffix=":${port}" '$4 ~ suffix "$" { found=1 } END { exit !found }'
}

verify_unrelated_ports_free() {
  local operation="$1"
  local port
  for port in 5000 5002 5003 8000; do
    if port_is_listening "$port"; then
      fail 'UNEXPECTED_LISTENER_PRESENT'
    fi
  done
  if [[ "$operation" != 'painting-to-music' ]]; then
    if port_is_listening 5001; then
      fail 'UNEXPECTED_LISTENER_PRESENT'
    fi
  fi
  return 0
}

run_java_cli() {
  local mode="$1"
  local operation="$2"
  java \
    -Dloader.main="$ROUND81_MAIN" \
    -cp "$ROUND81_JAR" \
    org.springframework.boot.loader.launch.PropertiesLauncher \
    "--mode=${mode}" \
    "--operation=${operation}"
}

run_preflight_all() {
  local database_before database_after operation failed=0
  database_before="$("$ROUND81_STATE" inspect-database --project-root "$ROUND81_ROOT")"
  "$ROUND81_STATE" inspect-painting --project-root "$ROUND81_ROOT"
  for operation in text-to-painting image-to-painting poem-to-painting painting-to-poem; do
    if ! "$ROUND81_STATE" preflight \
      --project-root "$ROUND81_ROOT" \
      --operation "$operation" \
      --expected-commit "$AURALINK_ROUND81_EXPECTED_COMMIT" \
      --mode dry-run; then
      failed=1
    fi
  done
  local vmm_preflight
  vmm_preflight="$("$ROUND81_STATE" vmm-static-preflight --project-root "$ROUND81_ROOT")" || failed=1
  printf '%s\n' "$vmm_preflight"
  if [[ "$vmm_preflight" != *'"state":"VMM_STATIC_PREFLIGHT_READY"'* ]]; then
    failed=1
  fi
  database_after="$("$ROUND81_STATE" inspect-database --project-root "$ROUND81_ROOT")"
  [[ "$database_before" == "$database_after" ]] || fail 'PRODUCTION_DATABASE_CHANGED'
  printf 'PREFLIGHT_ALL_ZERO_PROVIDER_CALLS\n'
  (( failed == 0 )) || fail 'PREFLIGHT_ALL_HAS_BLOCKERS'
}

run_operation() {
  local mode="$1"
  local operation="$2"
  operation_valid "$operation" || fail 'UNSUPPORTED_OPERATION'
  verify_unrelated_ports_free "$operation"
  local database_before database_after
  database_before="$("$ROUND81_STATE" inspect-database --project-root "$ROUND81_ROOT")"
  "$ROUND81_STATE" preflight \
    --project-root "$ROUND81_ROOT" \
    --operation "$operation" \
    --expected-commit "$AURALINK_ROUND81_EXPECTED_COMMIT" \
    --mode "$mode"

  if [[ "$mode" == 'dry-run' ]]; then
    run_java_cli dry-run "$operation"
    database_after="$("$ROUND81_STATE" inspect-database --project-root "$ROUND81_ROOT")"
    [[ "$database_before" == "$database_after" ]] || fail 'PRODUCTION_DATABASE_CHANGED'
    verify_unrelated_ports_free "$operation"
    return
  fi

  [[ "${AURALINK_ROUND81_CONFIRM:-}" == "$(required_confirmation "$operation")" ]] \
    || fail 'OPERATION_CONFIRMATION_REQUIRED'

  set +e
  "$ROUND81_STATE" healthy-run \
    --project-root "$ROUND81_ROOT" \
    --operation "$operation" \
    --expected-commit "$AURALINK_ROUND81_EXPECTED_COMMIT"
  local healthy_status=$?
  set -e
  if (( healthy_status == 0 )); then
    database_after="$("$ROUND81_STATE" inspect-database --project-root "$ROUND81_ROOT")"
    [[ "$database_before" == "$database_after" ]] || fail 'PRODUCTION_DATABASE_CHANGED'
    verify_unrelated_ports_free "$operation"
    return
  fi
  (( healthy_status == 1 )) || fail 'ALREADY_VALIDATED_CHECK_FAILED'

  local run_dir
  run_dir="$("$ROUND81_STATE" create-run \
    --project-root "$ROUND81_ROOT" \
    --operation "$operation" \
    --expected-commit "$AURALINK_ROUND81_EXPECTED_COMMIT")"
  [[ "$run_dir" == "${ROUND81_RUN_ROOT}/"* ]] || fail 'PRIVATE_RUN_DIRECTORY_INVALID'
  export AURALINK_ROUND81_RUN_DIR="$run_dir"

  local validation_status=0
  run_java_cli validate "$operation" || validation_status=$?
  if (( validation_status != 0 )); then
    "$ROUND81_STATE" cleanup-failure \
      --project-root "$ROUND81_ROOT" \
      --run-dir "$run_dir" || true
    database_after="$("$ROUND81_STATE" inspect-database --project-root "$ROUND81_ROOT")"
    [[ "$database_before" == "$database_after" ]] || fail 'PRODUCTION_DATABASE_CHANGED'
    verify_unrelated_ports_free "$operation"
    fail 'CONTROLLED_PROVIDER_VALIDATION_FAILED'
  fi

  "$ROUND81_STATE" finalize-run \
    --project-root "$ROUND81_ROOT" \
    --run-dir "$run_dir"
  database_after="$("$ROUND81_STATE" inspect-database --project-root "$ROUND81_ROOT")"
  [[ "$database_before" == "$database_after" ]] || fail 'PRODUCTION_DATABASE_CHANGED'
  verify_unrelated_ports_free "$operation"
  printf 'PRIVATE_VALIDATION_ARTIFACT_RETAINED\n'
  printf 'STRUCTURALLY_VALID\n'
  if [[ "$operation" == 'painting-to-music' ]]; then
    printf 'OPERATOR_AUDIO_REVIEW_REQUIRED\n'
  else
    printf 'OPERATOR_REVIEW_REQUIRED\n'
  fi
}

main() {
  local mode operation
  if (( $# == 1 )) && [[ "$1" == '--preflight-all' ]]; then
    verify_server_local_guards
    acquire_validation_lock
    run_preflight_all
    return
  fi
  (( $# == 2 )) || usage
  case "$1" in
    --dry-run) mode='dry-run' ;;
    --validate) mode='validate' ;;
    *) usage ;;
  esac
  case "$2" in
    --operation=*) operation="${2#--operation=}" ;;
    *) usage ;;
  esac
  verify_server_local_guards
  acquire_validation_lock
  if [[ "$mode" == 'validate' && "$operation" == 'painting-to-music' ]]; then
    round81_vmm_failure_cleanup_armed=1
  fi
  run_operation "$mode" "$operation"
  round81_vmm_failure_cleanup_armed=0
}

main "$@"
