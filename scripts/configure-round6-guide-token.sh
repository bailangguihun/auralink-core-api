#!/usr/bin/env bash
# Safely create the private Spring-to-Guide shared token in backend/.env.

set -Eeuo pipefail
set +x
IFS=$'\n\t'
umask 077

readonly SERVER_LOCAL_ROOT="/root/autodl-tmp/auralink"
readonly BACKUP_ROOT="/root/auralink_guide_validation_backups"
readonly CONFIRMATION_TOKEN="GENERATE_AURALINK_GUIDE_INTERNAL_TOKEN"

MODE=""
BACKUP_DIR=""
ENV_BACKUP=""
RESTORE_ARMED=0
COMPLETED=0

usage() {
    printf '%s\n' \
        "Usage: backend/scripts/configure-round6-guide-token.sh --dry-run" \
        "       backend/scripts/configure-round6-guide-token.sh --ensure"
}

fail() {
    printf 'ROUND61_TOKEN_CONFIGURATION_ERROR=%s\n' "$1" >&2
    exit 1
}

for argument in "$@"; do
    case "$argument" in
        --dry-run)
            [[ -z "$MODE" ]] || fail "CHOOSE_EXACTLY_ONE_MODE"
            MODE="dry-run"
            ;;
        --ensure)
            [[ -z "$MODE" ]] || fail "CHOOSE_EXACTLY_ONE_MODE"
            MODE="ensure"
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *) fail "UNSUPPORTED_ARGUMENT" ;;
    esac
done
[[ -n "$MODE" ]] || { usage >&2; exit 2; }

for required_command in python3 git findmnt flock stat cmp readlink; do
    command -v "$required_command" >/dev/null || fail "REQUIRED_TOOL_MISSING"
done

readonly SCRIPT_PATH="$(readlink -f -- "${BASH_SOURCE[0]}")"
readonly SCRIPT_ROOT="$(readlink -f -- "$(dirname -- "$SCRIPT_PATH")/../..")"
readonly PROJECT_ROOT="$SERVER_LOCAL_ROOT"
readonly ENV_FILE="$PROJECT_ROOT/backend/.env"
readonly HELPER="$PROJECT_ROOT/backend/scripts/round61_guide_state.py"

helper() {
    PYTHONDONTWRITEBYTECODE=1 python3 "$HELPER" "$@"
}

verify_root() {
    [[ "$SCRIPT_ROOT" == "$SERVER_LOCAL_ROOT" && "$(pwd -P)" == "$SERVER_LOCAL_ROOT" ]] \
        || fail "WRONG_PROJECT_ROOT"
    [[ "$(readlink -f -- "$PROJECT_ROOT")" == "$SERVER_LOCAL_ROOT" ]] \
        || fail "WRONG_PROJECT_ROOT"
    local filesystem_type
    filesystem_type="$(findmnt -n -o FSTYPE -T "$PROJECT_ROOT")" \
        || fail "FILESYSTEM_UNVERIFIED"
    case "${filesystem_type,,}" in
        *sshfs*|*fuse*) fail "SSHFS_EXECUTION_REFUSED" ;;
    esac
    printf '%s\n' "SERVER_LOCAL_ROOT_VERIFIED"
}

verify_commit() {
    local expected="${AURALINK_ROUND61_EXPECTED_COMMIT:-}" actual
    [[ "$expected" =~ ^[0-9a-f]{40}$ ]] || fail "REVIEWED_COMMIT_REQUIRED"
    actual="$(git -C "$PROJECT_ROOT" rev-parse HEAD)"
    [[ "$actual" == "$expected" ]] || fail "REVIEWED_COMMIT_MISMATCH"
    [[ -z "$(git -C "$PROJECT_ROOT" -c core.fsmonitor=false \
        -c core.untrackedCache=false status --porcelain --untracked-files=all)" ]] \
        || fail "DIRTY_WORKTREE"
    printf 'REVIEWED_COMMIT_VERIFIED=%s\n' "$actual"
}

verify_env() {
    [[ -f "$ENV_FILE" && ! -L "$ENV_FILE" ]] || fail "ENV_FILE_INVALID"
    [[ -f "$HELPER" && ! -L "$HELPER" ]] || fail "STATE_HELPER_INVALID"
    local mode owner
    mode="$(stat -c %a -- "$ENV_FILE")"
    owner="$(stat -c %u -- "$ENV_FILE")"
    [[ "$mode" =~ ^[0-7]{3,4}$ ]] || fail "ENV_PERMISSIONS_UNSAFE"
    (( (8#$mode & 077) == 0 )) || fail "ENV_PERMISSIONS_UNSAFE"
    [[ "$owner" == "$(id -u)" ]] || fail "ENV_OWNERSHIP_UNSAFE"
    git -C "$PROJECT_ROOT" check-ignore -q "$ENV_FILE" || fail "ENV_NOT_IGNORED"
    [[ -z "$(git -C "$PROJECT_ROOT" ls-files -- "$ENV_FILE")" ]] \
        || fail "ENV_TRACKED"
    printf '%s\n' "PRIVATE_ENV_FILE_VERIFIED"
}

restore_on_failure() {
    local exit_status=$?
    trap - EXIT INT TERM HUP
    set +e
    if (( exit_status != 0 && RESTORE_ARMED == 1 )); then
        if helper restore-env --backup "$ENV_BACKUP" --env-file "$ENV_FILE" >/dev/null \
                && cmp -s -- "$ENV_FILE" "$ENV_BACKUP"; then
            printf '%s\n' "ROUND61_TOKEN_ENV_RESTORED"
        else
            printf '%s\n' "ROUND61_TOKEN_ENV_RESTORE_FAILED_OPERATOR_ACTION_REQUIRED" >&2
        fi
    fi
    exit "$exit_status"
}

trap restore_on_failure EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

verify_root
verify_commit
verify_env

if [[ "$MODE" == "dry-run" ]]; then
    printf 'INTENDED_TOKEN_BACKUP_DIRECTORY=%s/<timestamp>-token\n' "$BACKUP_ROOT"
    printf '%s\n' \
        "INTENDED_CHANGE=ensure one at-least-256-bit AURALINK_GUIDE_INTERNAL_TOKEN only when blank" \
        "TOKEN_CONFIGURATION_DRY_RUN_ZERO_MUTATION" \
        "TOKEN_CONFIGURATION_DRY_RUN_OK"
    COMPLETED=1
    exit 0
fi

[[ "${AURALINK_ROUND61_TOKEN_CONFIRM:-}" == "$CONFIRMATION_TOKEN" ]] \
    || fail "TOKEN_CONFIRMATION_REQUIRED"

if [[ -e "$BACKUP_ROOT" || -L "$BACKUP_ROOT" ]]; then
    [[ -d "$BACKUP_ROOT" && ! -L "$BACKUP_ROOT" ]] || fail "BACKUP_ROOT_UNSAFE"
else
    mkdir -m 700 -- "$BACKUP_ROOT"
fi
chmod 700 -- "$BACKUP_ROOT"
[[ "$(readlink -f -- "$BACKUP_ROOT")" == "$BACKUP_ROOT" ]] || fail "BACKUP_ROOT_UNSAFE"
[[ "$(stat -c %u -- "$BACKUP_ROOT")" == "$(id -u)" ]] || fail "BACKUP_ROOT_UNSAFE"
exec 8< "$BACKUP_ROOT"
flock -n 8 || fail "GUIDE_CONFIGURATION_ALREADY_RUNNING"

verify_commit
verify_env
BACKUP_DIR="$BACKUP_ROOT/$(date -u +%Y%m%dT%H%M%SZ)-$$-token"
mkdir -m 700 -- "$BACKUP_DIR"
ENV_BACKUP="$BACKUP_DIR/backend.env.before-token"
helper backup-env --source "$ENV_FILE" --destination "$ENV_BACKUP" >/dev/null
cmp -s -- "$ENV_FILE" "$ENV_BACKUP" || fail "ENV_BACKUP_INVALID"
chmod 600 -- "$ENV_BACKUP"
RESTORE_ARMED=1

token_result="$(helper generate-token --env-file "$ENV_FILE")" \
    || fail "TOKEN_GENERATION_FAILED"
chmod 600 -- "$ENV_FILE"
verify_env
verify_commit
printf 'TOKEN_CONFIGURATION_RESULT=%s\n' "$token_result"
printf 'PRIVATE_ENV_BACKUP_RETAINED=%s\n' "$BACKUP_DIR"

RESTORE_ARMED=0
COMPLETED=1
printf '%s\n' "ROUND61_GUIDE_INTERNAL_TOKEN_READY"
exit 0
