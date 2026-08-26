#!/usr/bin/env bash
# Explicit crash recovery for a stale Round 5.1 maintenance lease. This never
# chooses a backup automatically and never performs Flyway or catalog work.

set -Eeuo pipefail
IFS=$'\n\t'
umask 077

readonly SERVER_LOCAL_ROOT="/root/autodl-tmp/auralink"
readonly BACKUP_ROOT="/root/auralink_activation_backups"
readonly MAINTENANCE_MARKER="$BACKUP_ROOT/.round51-maintenance"
readonly STARTUP_GATE="$BACKUP_ROOT/.round51-startup-gate"
readonly RECOVERY_CONFIRMATION="RESTORE_AURALINK_ROUND51_PRE_ACTIVATION_BACKUP"
readonly EXPECTED_LEGACY_DATA_SHA256="1a0d0e7f41964ee77d4a78c9a86ec47d732f1d202400e180d41994046b941131"

BACKUP_DIR=""
SERVICE_MONITOR_CHILD=""
SERVICE_MONITOR_CHILD_START=""
RECOVERY_TOOL_CHILD=""
RECOVERY_TOOL_CHILD_START=""
STARTUP_GATE_CHILD=""
STARTUP_GATE_CHILD_START=""
STARTUP_GATE_ORPHAN_FENCE=""
RECOVERY_MARKER_TOKEN=""
RECOVERY_FENCE_AUTHORIZED=0
SERVICE_VIOLATION_FILE=""
SERVICE_ALLOWANCE_FILE=""
SERVICE_RELEASE_INTENT_FILE=""
MARKER_EVIDENCE=""
MARKER_WAS_RELEASED=0
RELEASED_MARKER_CANDIDATES=()
ORPHAN_CANDIDATES=()
MATCHING_ORPHAN_FENCES=()

fail() {
    printf 'ROUND51_RECOVERY_ERROR: %s\n' "$1" >&2
    exit 1
}

usage() {
    printf '%s\n' \
        "Usage: backend/scripts/recover-round5-catalog-activation.sh" \
        "       --backup-dir /root/auralink_activation_backups/<exact-run-directory>"
}

while (( $# > 0 )); do
    case "$1" in
        --backup-dir)
            (( $# >= 2 )) || fail "--backup-dir requires an exact directory"
            [[ -z "$BACKUP_DIR" ]] || fail "--backup-dir may be supplied only once"
            BACKUP_DIR="$2"
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *) fail "unknown recovery argument: $1" ;;
    esac
done
[[ -n "$BACKUP_DIR" ]] || { usage >&2; exit 2; }
[[ "${AURALINK_ROUND51_RECOVERY_CONFIRM:-}" == "$RECOVERY_CONFIRMATION" ]] \
    || fail "AURALINK_ROUND51_RECOVERY_CONFIRM recovery latch is required"

for command_name in python3 git findmnt ss flock cmp; do
    command -v "$command_name" >/dev/null || fail "$command_name is required"
done

readonly SCRIPT_PATH="$(readlink -f -- "${BASH_SOURCE[0]}")"
readonly SCRIPT_ROOT="$(readlink -f -- "$(dirname -- "$SCRIPT_PATH")/../..")"
readonly PROJECT_ROOT="$SERVER_LOCAL_ROOT"
readonly LIVE_DATABASE="$PROJECT_ROOT/backend/auralink.db"
readonly ENV_FILE="$PROJECT_ROOT/backend/.env"
readonly HELPER="$PROJECT_ROOT/backend/scripts/round51_state.py"

[[ "$SCRIPT_ROOT" == "$SERVER_LOCAL_ROOT" && "$(pwd -P)" == "$SERVER_LOCAL_ROOT" ]] \
    || fail "recovery must run from the exact server-local project root"
[[ "$(readlink -f -- "$PROJECT_ROOT")" == "$SERVER_LOCAL_ROOT" ]] \
    || fail "server-local project root resolves unexpectedly"
filesystem_type="$(findmnt -n -o FSTYPE -T "$PROJECT_ROOT")" \
    || fail "cannot determine project filesystem type"
[[ -n "$filesystem_type" ]] || fail "cannot determine project filesystem type"
case "$filesystem_type" in
    *sshfs*|*SSHFS*|*fuse*|*FUSE*) fail "recovery is forbidden on FUSE/SSHFS" ;;
esac
printf '%s\n' "SERVER_LOCAL_ROOT_VERIFIED"

for required in "$LIVE_DATABASE" "$ENV_FILE" "$HELPER"; do
    [[ -f "$required" && ! -L "$required" ]] \
        || fail "required recovery input is missing or unsafe"
done
[[ -d "$BACKUP_ROOT" && ! -L "$BACKUP_ROOT" ]] \
    || fail "activation backup root is missing or unsafe"
[[ -d "$BACKUP_DIR" && ! -L "$BACKUP_DIR" ]] \
    || fail "recovery backup directory is missing or unsafe"
BACKUP_DIR="$(readlink -f -- "$BACKUP_DIR")"
[[ "$(dirname -- "$BACKUP_DIR")" == "$BACKUP_ROOT" ]] \
    || fail "recovery backup must be an exact direct child of the private backup root"
[[ "$(stat -c %u "$BACKUP_ROOT")" == "$(id -u)"
    && "$(stat -c %u "$BACKUP_DIR")" == "$(id -u)" ]] \
    || fail "recovery directories are not owned by the current operator"
backup_dir_mode="$(stat -c %a "$BACKUP_DIR")"
(( (8#$backup_dir_mode & 077) == 0 )) || fail "recovery backup directory permissions are not private"

readonly DATABASE_BACKUP="$BACKUP_DIR/auralink.pre-activation.db"
readonly ENV_BACKUP="$BACKUP_DIR/backend.env.pre-activation"
readonly RECOVERY_BINDING="$BACKUP_DIR/round51-recovery-binding.json"
readonly PRE_ACTIVATION_MANIFEST="$BACKUP_DIR/pre-activation-manifest.json"
readonly DATABASE_VERIFICATION="$BACKUP_DIR/database-backup-verification.json"
for required in "$DATABASE_BACKUP" "$ENV_BACKUP" \
        "$RECOVERY_BINDING" "$PRE_ACTIVATION_MANIFEST" "$DATABASE_VERIFICATION"; do
    [[ -f "$required" && ! -L "$required" ]] \
        || fail "named activation backup is incomplete or unsafe"
    mode="$(stat -c %a "$required")"
    (( (8#$mode & 077) == 0 )) || fail "activation backup file permissions are not private"
done

shopt -s nullglob
ORPHAN_CANDIDATES=(
    "$BACKUP_ROOT"/.round51-activation-startup-gate-orphan-fence-*
    "$BACKUP_ROOT"/.round51-recovery-startup-gate-orphan-fence-*
)
shopt -u nullglob

if [[ -f "$MAINTENANCE_MARKER" && ! -L "$MAINTENANCE_MARKER" ]]; then
    MARKER_EVIDENCE="$MAINTENANCE_MARKER"
    [[ "$(stat -c %u "$MARKER_EVIDENCE")" == "$(id -u)" ]] \
        || fail "maintenance marker evidence is not owned by the current operator"
    marker_mode="$(stat -c %a "$MARKER_EVIDENCE")"
    (( (8#$marker_mode & 077) == 0 )) \
        || fail "maintenance marker evidence permissions are not private"
else
    [[ ! -e "$MAINTENANCE_MARKER" && ! -L "$MAINTENANCE_MARKER" ]] \
        || fail "global maintenance marker is unsafe"
    release_intent_candidates=()
    activation_release_intent="$BACKUP_DIR/.round51-service-release-intent"
    if [[ -e "$activation_release_intent" || -L "$activation_release_intent" ]]; then
        release_intent_candidates+=("$activation_release_intent")
    fi
    shopt -s nullglob
    recovery_release_intents=(
        "$BACKUP_DIR"/.round51-recovery-release-intent-*
    )
    release_intent_candidates+=("${recovery_release_intents[@]}")
    RELEASED_MARKER_CANDIDATES=(
        "$BACKUP_DIR"/.round51-released-*-marker-*
    )
    shopt -u nullglob
    valid_release_intents=0
    for release_intent in "${release_intent_candidates[@]}"; do
        [[ -f "$release_intent" && ! -L "$release_intent" \
            && "$(stat -c %u "$release_intent")" == "$(id -u)" ]] \
            || fail "marker release intent is unsafe"
        release_intent_mode="$(stat -c %a "$release_intent")"
        (( (8#$release_intent_mode & 077) == 0 )) \
            || fail "marker release intent is not private"
        [[ "$(tr -d '\r\n' < "$release_intent")" == "verified-release" ]] \
            || fail "marker release intent is invalid"
        (( valid_release_intents += 1 ))
    done
    (( valid_release_intents > 0 )) \
        || fail "marker is absent without durable verified release intent"
    (( ${#RELEASED_MARKER_CANDIDATES[@]} > 0 )) \
        || fail "marker is absent without released-marker evidence"
    for released_marker_candidate in "${RELEASED_MARKER_CANDIDATES[@]}"; do
        [[ -f "$released_marker_candidate" && ! -L "$released_marker_candidate" \
            && "$(stat -c %u "$released_marker_candidate")" == "$(id -u)" ]] \
            || fail "released-marker evidence is unsafe"
        released_marker_mode="$(stat -c %a "$released_marker_candidate")"
        (( (8#$released_marker_mode & 077) == 0 )) \
            || fail "released-marker evidence is not private"
    done
    (( ${#ORPHAN_CANDIDATES[@]} > 0 )) \
        || fail "marker is absent without a durable startup orphan fence"
    MARKER_WAS_RELEASED=1
fi

expected_commit="${AURALINK_ROUND51_EXPECTED_COMMIT:-}"
[[ "$expected_commit" =~ ^[0-9a-f]{40}$ ]] \
    || fail "AURALINK_ROUND51_EXPECTED_COMMIT must be the reviewed commit"
[[ "$(git -C "$PROJECT_ROOT" rev-parse HEAD)" == "$expected_commit" ]] \
    || fail "current commit differs from the reviewed recovery commit"
[[ -z "$(git -C "$PROJECT_ROOT" status --porcelain --untracked-files=all)" ]] \
    || fail "tracked checkout is not clean"

configured_port="$(PYTHONDONTWRITEBYTECODE=1 python3 "$HELPER" env-value \
    --env-file "$ENV_FILE" --key SERVER_PORT --default 5000)"
[[ "$configured_port" =~ ^[0-9]{1,5}$ ]] && (( configured_port > 0 && configured_port <= 65535 )) \
    || fail "configured backend port is invalid"

service_down_guard() {
    local allowed_pid="${1:-}"
    local quiet="${2:-}"
    local expected_start_time="${3:-}"
    local allowed_identity=0
    if [[ -n "$allowed_pid" && "$allowed_pid" =~ ^[0-9]+$
            && -n "$expected_start_time" && "$expected_start_time" =~ ^[0-9]+$
            && -r "/proc/$allowed_pid/stat"
            && "$(awk '{print $22}' "/proc/$allowed_pid/stat" 2>/dev/null || true)" \
                == "$expected_start_time" ]]; then
        allowed_identity=1
    fi
    local socket_state process_dir pid command_name target fd
    socket_state="$(ss -H -ltnp 2>/dev/null)" \
        || { printf '%s\n' "BACKEND_SERVICE_MUST_BE_STOPPED" >&2; return 1; }
    if awk -v endpoint=":${configured_port}" '$4 ~ (endpoint "$") { found=1 } END { exit !found }' \
            <<<"$socket_state"; then
        printf '%s\n' "BACKEND_SERVICE_MUST_BE_STOPPED" >&2
        return 1
    fi
    for process_dir in /proc/[0-9]*; do
        pid="${process_dir##*/}"
        if (( allowed_identity == 1 )) && [[ "$pid" == "$allowed_pid" ]]; then
            continue
        fi
        command_name="$(cat "$process_dir/comm" 2>/dev/null || true)"
        if [[ "$command_name" == java* ]]; then
            target="$(readlink -f "$process_dir/cwd" 2>/dev/null || true)"
            if [[ "$target" == "$PROJECT_ROOT" || "$target" == "$PROJECT_ROOT/"* ]]; then
                printf 'AURALINK_JAVA_PROCESS pid=%s\n' "$pid" >&2
                printf '%s\n' "BACKEND_SERVICE_MUST_BE_STOPPED" >&2
                return 1
            fi
        fi
        for fd in "$process_dir"/fd/*; do
            [[ -e "$fd" || -L "$fd" ]] || continue
            target="$(readlink "$fd" 2>/dev/null || true)"
            target="${target% (deleted)}"
            if [[ "$target" == "$LIVE_DATABASE" || "$target" == "$LIVE_DATABASE-journal" \
                    || "$target" == "$LIVE_DATABASE-wal" || "$target" == "$LIVE_DATABASE-shm" ]]; then
                printf 'DATABASE_OPEN_BY_PROCESS pid=%s\n' "$pid" >&2
                printf '%s\n' "BACKEND_SERVICE_MUST_BE_STOPPED" >&2
                return 1
            fi
        done
    done
    if [[ "$quiet" != "quiet" ]]; then
        printf '%s\n' "BACKEND_SERVICE_STOPPED"
    fi
}

process_identity_matches() {
    local pid="$1"
    local expected_start="$2"
    [[ "$pid" =~ ^[0-9]+$ && "$expected_start" =~ ^[0-9]+$ \
            && -r "/proc/$pid/stat" \
            && "$(awk '{print $22}' "/proc/$pid/stat" 2>/dev/null || true)" \
                == "$expected_start" ]]
}

stop_owned_process() {
    local pid="$1"
    local expected_start="$2"
    [[ -n "$pid" && -n "$expected_start" ]] || return 0
    if process_identity_matches "$pid" "$expected_start"; then
        kill -TERM "$pid" 2>/dev/null || true
        local attempt
        for attempt in {1..40}; do
            process_identity_matches "$pid" "$expected_start" || break
            /bin/sleep 0.25
        done
        if process_identity_matches "$pid" "$expected_start"; then
            kill -KILL "$pid" 2>/dev/null || true
        fi
        wait "$pid" 2>/dev/null || true
    fi
}

start_startup_gate() {
    local parent_pid="$$" parent_start_time ready_file
    parent_start_time="$(awk '{print $22}' "/proc/$parent_pid/stat" 2>/dev/null || true)"
    [[ "$parent_start_time" =~ ^[0-9]+$ ]] \
        || fail "recovery startup-gate coordinator identity could not be recorded"
    ready_file="$BACKUP_ROOT/.round51-recovery-startup-gate-ready-$$"
    STARTUP_GATE_ORPHAN_FENCE="$BACKUP_ROOT/.round51-recovery-startup-gate-orphan-fence-$$"
    rm -f -- "$ready_file"
    PYTHONDONTWRITEBYTECODE=1 python3 "$HELPER" create-bound-orphan-fence \
        --fence "$STARTUP_GATE_ORPHAN_FENCE" --binding "$RECOVERY_BINDING" \
        --database-backup "$DATABASE_BACKUP" --environment-backup "$ENV_BACKUP" \
        --pre-activation-manifest "$PRE_ACTIVATION_MANIFEST" \
        --database-verification "$DATABASE_VERIFICATION" \
        --reviewed-commit "$expected_commit" >/dev/null
    (
        exec 9>&-
        exec env PYTHONDONTWRITEBYTECODE=1 python3 "$HELPER" \
            hold-startup-gate --gate "$STARTUP_GATE" --ready "$ready_file" \
            --parent-pid "$parent_pid" --parent-start-time "$parent_start_time" \
            --orphan-fence "$STARTUP_GATE_ORPHAN_FENCE"
    ) >/dev/null 2>&1 &
    STARTUP_GATE_CHILD=$!
    STARTUP_GATE_CHILD_START="$(awk '{print $22}' \
        "/proc/$STARTUP_GATE_CHILD/stat" 2>/dev/null || true)"
    [[ "$STARTUP_GATE_CHILD_START" =~ ^[0-9]+$ ]] \
        || fail "recovery startup-gate holder identity could not be recorded"
    local attempt
    for attempt in {1..100}; do
        [[ -f "$ready_file" && ! -L "$ready_file" ]] && break
        process_identity_matches "$STARTUP_GATE_CHILD" "$STARTUP_GATE_CHILD_START" \
            || fail "backend startup gate is already held or could not be acquired"
        /bin/sleep 0.05
    done
    [[ -f "$ready_file" && ! -L "$ready_file" ]] \
        || fail "backend startup gate acquisition timed out"
    rm -f -- "$ready_file"
}

stop_startup_gate() {
    [[ -n "$STARTUP_GATE_CHILD" ]] || return 0
    if ! rm -f -- "$STARTUP_GATE_ORPHAN_FENCE"; then
        printf '%s\n' "RECOVERY_STARTUP_ORPHAN_FENCE_RETIREMENT_FAILED" >&2
        return 1
    fi
    if ! PYTHONDONTWRITEBYTECODE=1 python3 "$HELPER" fsync-dir \
            --directory "$BACKUP_ROOT" >/dev/null; then
        printf '%s\n' "RECOVERY_STARTUP_ORPHAN_FENCE_RETIREMENT_NOT_DURABLE" >&2
        local durable_block_reestablished=0
        if PYTHONDONTWRITEBYTECODE=1 python3 "$HELPER" \
                create-bound-orphan-fence \
                --fence "$STARTUP_GATE_ORPHAN_FENCE" \
                --binding "$RECOVERY_BINDING" \
                --database-backup "$DATABASE_BACKUP" \
                --environment-backup "$ENV_BACKUP" \
                --pre-activation-manifest "$PRE_ACTIVATION_MANIFEST" \
                --database-verification "$DATABASE_VERIFICATION" \
                --reviewed-commit "$expected_commit" >/dev/null 2>&1; then
            durable_block_reestablished=1
            printf '%s\n' "RECOVERY_STARTUP_ORPHAN_FENCE_REESTABLISHED" >&2
        fi
        if (( durable_block_reestablished == 0 )) \
                && reestablish_recovery_fence_if_needed; then
            durable_block_reestablished=1
        fi
        if (( durable_block_reestablished == 0 )); then
            ( umask 077; : > "$STARTUP_GATE_ORPHAN_FENCE" ) 2>/dev/null || true
            printf '%s\n' \
                "RECOVERY_STARTUP_ORPHAN_FENCE_REESTABLISHMENT_UNVERIFIED" >&2
        fi
        return 1
    fi
    stop_owned_process "$STARTUP_GATE_CHILD" "$STARTUP_GATE_CHILD_START"
    STARTUP_GATE_CHILD=""
    STARTUP_GATE_CHILD_START=""
    STARTUP_GATE_ORPHAN_FENCE=""
}

start_recovery_service_monitor() {
    local parent_pid="$$"
    local parent_start_time
    parent_start_time="$(awk '{print $22}' "/proc/$parent_pid/stat" 2>/dev/null || true)"
    [[ "$parent_start_time" =~ ^[0-9]+$ ]] \
        || fail "recovery coordinator identity could not be recorded"
    SERVICE_VIOLATION_FILE="$BACKUP_DIR/.round51-recovery-service-violation-$$"
    SERVICE_ALLOWANCE_FILE="$BACKUP_DIR/.round51-recovery-service-allowance-$$"
    SERVICE_RELEASE_INTENT_FILE="$BACKUP_DIR/.round51-recovery-release-intent-$$"
    set_recovery_service_allowance ""
    (
        # The recovery coordinator alone owns fd 9. Orphaned watchdogs must
        # never retain the lock after an uncatchable coordinator termination.
        exec 9>&-
        while true; do
            /bin/sleep 1
            if [[ ! -f "$MAINTENANCE_MARKER" ]]; then
                if [[ -f "$SERVICE_RELEASE_INTENT_FILE" \
                        && ! -L "$SERVICE_RELEASE_INTENT_FILE" ]]; then
                    exit 0
                fi
                : > "$SERVICE_VIOLATION_FILE"
                printf '%s\n' "RECOVERY_MAINTENANCE_MARKER_LOST_WITHOUT_RELEASE_INTENT" >&2
                if ! env AURALINK_ROUND51_MAINTENANCE_TOKEN="$RECOVERY_MARKER_TOKEN" \
                        PYTHONDONTWRITEBYTECODE=1 python3 "$HELPER" \
                        recreate-maintenance-marker \
                        --marker "$MAINTENANCE_MARKER" --binding "$RECOVERY_BINDING" \
                        --database-backup "$DATABASE_BACKUP" \
                        --environment-backup "$ENV_BACKUP" \
                        --pre-activation-manifest "$PRE_ACTIVATION_MANIFEST" \
                        --database-verification "$DATABASE_VERIFICATION" \
                        --reviewed-commit "$expected_commit" >/dev/null 2>&1; then
                    printf '%s\n' \
                        "RECOVERY_MAINTENANCE_FENCE_REESTABLISHMENT_FAILED" >&2
                else
                    printf '%s\n' "RECOVERY_MAINTENANCE_FENCE_REESTABLISHED" >&2
                fi
                if process_identity_matches "$parent_pid" "$parent_start_time"; then
                    kill -TERM "$parent_pid" 2>/dev/null || true
                fi
                exit 1
            fi
            local allowed_pid="" allowed_start_time="" key value
            if [[ ! -f "$SERVICE_ALLOWANCE_FILE" || -L "$SERVICE_ALLOWANCE_FILE" ]]; then
                : > "$SERVICE_VIOLATION_FILE"
                printf '%s\n' "RECOVERY_SERVICE_ALLOWANCE_UNAVAILABLE" >&2
                if process_identity_matches "$parent_pid" "$parent_start_time"; then
                    kill -TERM "$parent_pid" 2>/dev/null || true
                fi
                exit 1
            fi
            while IFS='=' read -r key value; do
                case "$key" in
                    pid) allowed_pid="$value" ;;
                    start) allowed_start_time="$value" ;;
                esac
            done < "$SERVICE_ALLOWANCE_FILE"
            if ! service_down_guard "$allowed_pid" quiet "$allowed_start_time"; then
                : > "$SERVICE_VIOLATION_FILE"
                printf '%s\n' "RECOVERY_SERVICE_EXCLUSION_VIOLATION" >&2
                if process_identity_matches "$parent_pid" "$parent_start_time"; then
                    kill -TERM "$parent_pid" 2>/dev/null || true
                fi
                exit 1
            fi
        done
    ) &
    SERVICE_MONITOR_CHILD=$!
    SERVICE_MONITOR_CHILD_START="$(awk '{print $22}' \
        "/proc/$SERVICE_MONITOR_CHILD/stat" 2>/dev/null || true)"
    if [[ ! "$SERVICE_MONITOR_CHILD_START" =~ ^[0-9]+$ ]]; then
        kill -TERM "$SERVICE_MONITOR_CHILD" 2>/dev/null || true
        wait "$SERVICE_MONITOR_CHILD" 2>/dev/null || true
        SERVICE_MONITOR_CHILD=""
        fail "recovery service monitor identity could not be recorded"
    fi
}

set_recovery_service_allowance() {
    local allowed_pid="${1:-}"
    local allowed_start_time=""
    if [[ -n "$allowed_pid" ]]; then
        [[ "$allowed_pid" =~ ^[0-9]+$ && -r "/proc/$allowed_pid/stat" ]] \
            || fail "owned recovery process cannot be identified"
        allowed_start_time="$(awk '{print $22}' "/proc/$allowed_pid/stat" 2>/dev/null || true)"
        [[ "$allowed_start_time" =~ ^[0-9]+$ ]] \
            || fail "owned recovery process start time cannot be identified"
    fi
    local temporary="$BACKUP_DIR/.round51-recovery-allowance-$$-$RANDOM"
    printf 'pid=%s\nstart=%s\n' "$allowed_pid" "$allowed_start_time" > "$temporary"
    chmod 600 "$temporary"
    mv -f -- "$temporary" "$SERVICE_ALLOWANCE_FILE"
}

assert_recovery_monitor_healthy() {
    if [[ -z "$SERVICE_MONITOR_CHILD" || -e "$SERVICE_VIOLATION_FILE" ]] \
            || ! process_identity_matches \
                "$SERVICE_MONITOR_CHILD" "$SERVICE_MONITOR_CHILD_START"; then
        fail "recovery service exclusion monitor is not healthy"
    fi
}

run_recovery_database_tool() {
    local releases_marker=0
    if [[ "${1:-}" == "--releases-marker" ]]; then
        releases_marker=1
        shift
    fi
    local gate="$BACKUP_DIR/.round51-recovery-tool-go-$$-$RANDOM"
    (
        exec 9>&-
        while [[ ! -f "$gate" ]]; do
            /bin/sleep 0.05
        done
        exec "$@"
    ) &
    local tool_pid=$!
    RECOVERY_TOOL_CHILD="$tool_pid"
    RECOVERY_TOOL_CHILD_START="$(awk '{print $22}' "/proc/$tool_pid/stat" 2>/dev/null || true)"
    if [[ ! "$RECOVERY_TOOL_CHILD_START" =~ ^[0-9]+$ ]]; then
        kill -TERM "$tool_pid" 2>/dev/null || true
        wait "$tool_pid" 2>/dev/null || true
        RECOVERY_TOOL_CHILD=""
        rm -f -- "$gate"
        fail "owned recovery tool identity could not be recorded"
    fi
    set_recovery_service_allowance "$tool_pid"
    : > "$gate"
    local status=0
    wait "$tool_pid" || status=$?
    RECOVERY_TOOL_CHILD=""
    RECOVERY_TOOL_CHILD_START=""
    set_recovery_service_allowance ""
    rm -f -- "$gate"
    if (( releases_marker == 0 )); then
        assert_recovery_monitor_healthy
    elif (( status == 0 )); then
        wait "$SERVICE_MONITOR_CHILD" 2>/dev/null || status=1
        SERVICE_MONITOR_CHILD=""
        SERVICE_MONITOR_CHILD_START=""
        [[ ! -e "$SERVICE_VIOLATION_FILE" ]] || status=1
    elif (( status != 0 )); then
        # Retain a helper-published verified-release intent. EXIT cleanup first
        # re-establishes the bound marker; if that cannot be proved, this intent
        # plus released-marker evidence keeps markerless crash recovery possible.
        :
    fi
    return "$status"
}

stop_recovery_service_monitor() {
    [[ -n "$SERVICE_MONITOR_CHILD" ]] || return 0
    if process_identity_matches "$SERVICE_MONITOR_CHILD" "$SERVICE_MONITOR_CHILD_START"; then
        kill -TERM "$SERVICE_MONITOR_CHILD" 2>/dev/null || true
    fi
    wait "$SERVICE_MONITOR_CHILD" 2>/dev/null || true
    SERVICE_MONITOR_CHILD=""
    SERVICE_MONITOR_CHILD_START=""
}

reestablish_recovery_fence_if_needed() {
    [[ "$RECOVERY_MARKER_TOKEN" =~ ^[0-9a-f]{64}$ ]] || return 1
    if [[ -e "$MAINTENANCE_MARKER" || -L "$MAINTENANCE_MARKER" ]]; then
        PYTHONDONTWRITEBYTECODE=1 python3 "$HELPER" verify-recovery-binding \
            --marker "$MAINTENANCE_MARKER" --binding "$RECOVERY_BINDING" \
            --database-backup "$DATABASE_BACKUP" \
            --environment-backup "$ENV_BACKUP" \
            --pre-activation-manifest "$PRE_ACTIVATION_MANIFEST" \
            --database-verification "$DATABASE_VERIFICATION" \
            --reviewed-commit "$expected_commit" >/dev/null
        return $?
    fi
    if ! env AURALINK_ROUND51_MAINTENANCE_TOKEN="$RECOVERY_MARKER_TOKEN" \
        PYTHONDONTWRITEBYTECODE=1 python3 "$HELPER" recreate-maintenance-marker \
        --marker "$MAINTENANCE_MARKER" --binding "$RECOVERY_BINDING" \
        --database-backup "$DATABASE_BACKUP" --environment-backup "$ENV_BACKUP" \
        --pre-activation-manifest "$PRE_ACTIVATION_MANIFEST" \
        --database-verification "$DATABASE_VERIFICATION" \
        --reviewed-commit "$expected_commit" >/dev/null 2>&1; then
        PYTHONDONTWRITEBYTECODE=1 python3 "$HELPER" verify-recovery-binding \
            --marker "$MAINTENANCE_MARKER" --binding "$RECOVERY_BINDING" \
            --database-backup "$DATABASE_BACKUP" \
            --environment-backup "$ENV_BACKUP" \
            --pre-activation-manifest "$PRE_ACTIVATION_MANIFEST" \
            --database-verification "$DATABASE_VERIFICATION" \
            --reviewed-commit "$expected_commit" >/dev/null \
            || return 1
    fi
    printf '%s\n' "RECOVERY_MAINTENANCE_FENCE_REESTABLISHED" >&2
}

cleanup_recovery_monitor() {
    local exit_status=$?
    trap - EXIT INT TERM HUP
    set +e
    stop_owned_process "$RECOVERY_TOOL_CHILD" "$RECOVERY_TOOL_CHILD_START"
    local fence_safe=1
    if (( exit_status != 0 && RECOVERY_FENCE_AUTHORIZED == 1 )); then
        if ! reestablish_recovery_fence_if_needed; then
            fence_safe=0
            printf '%s\n' "RECOVERY_MAINTENANCE_FENCE_REESTABLISHMENT_FAILED" >&2
            printf '%s\n' "BACKEND_STARTUP_KERNEL_GATE_RETAINED_FOR_OPERATOR_RECOVERY" >&2
        fi
    fi
    stop_recovery_service_monitor
    if (( exit_status == 0 && fence_safe == 1 )); then
        stop_startup_gate
    elif [[ -n "$STARTUP_GATE_CHILD" ]]; then
        printf '%s\n' "BACKEND_STARTUP_KERNEL_GATE_RETAINED_FOR_OPERATOR_RECOVERY" >&2
    fi
    exit "$exit_status"
}
trap cleanup_recovery_monitor EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

# A free lock proves the old activation shell no longer owns the recovery
# boundary. The marker itself remains in place throughout recovery.
exec 9< "$BACKUP_ROOT"
flock -n 9 || fail "an activation process still owns the backup-root lock"
if (( MARKER_WAS_RELEASED == 0 )); then
    RECOVERY_MARKER_TOKEN="$(PYTHONDONTWRITEBYTECODE=1 python3 "$HELPER" \
        retain-verified-recovery-token \
        --marker "$MARKER_EVIDENCE" \
        --binding "$RECOVERY_BINDING" \
        --database-backup "$DATABASE_BACKUP" \
        --environment-backup "$ENV_BACKUP" \
        --pre-activation-manifest "$PRE_ACTIVATION_MANIFEST" \
        --database-verification "$DATABASE_VERIFICATION" \
        --reviewed-commit "$expected_commit")"
    [[ "$RECOVERY_MARKER_TOKEN" =~ ^[0-9a-f]{64}$ ]] \
        || fail "verified recovery marker token cannot be retained"
    RECOVERY_FENCE_AUTHORIZED=1
else
    authenticated_marker_count=0
    for released_marker_candidate in "${RELEASED_MARKER_CANDIDATES[@]}"; do
        candidate_token="$(PYTHONDONTWRITEBYTECODE=1 python3 "$HELPER" \
            retain-verified-recovery-token \
            --marker "$released_marker_candidate" \
            --binding "$RECOVERY_BINDING" \
            --database-backup "$DATABASE_BACKUP" \
            --environment-backup "$ENV_BACKUP" \
            --pre-activation-manifest "$PRE_ACTIVATION_MANIFEST" \
            --database-verification "$DATABASE_VERIFICATION" \
            --reviewed-commit "$expected_commit" 2>/dev/null)" || continue
        [[ "$candidate_token" =~ ^[0-9a-f]{64}$ ]] || continue
        if [[ -n "$RECOVERY_MARKER_TOKEN" \
                && "$RECOVERY_MARKER_TOKEN" != "$candidate_token" ]]; then
            fail "authenticated released-marker evidence disagrees"
        fi
        RECOVERY_MARKER_TOKEN="$candidate_token"
        MARKER_EVIDENCE="$released_marker_candidate"
        (( authenticated_marker_count += 1 ))
    done
    (( authenticated_marker_count > 0 )) \
        || fail "no released-marker evidence authenticates the selected recovery binding"
fi
[[ "$RECOVERY_MARKER_TOKEN" =~ ^[0-9a-f]{64}$ ]] \
    || fail "verified recovery marker token cannot be retained"
for orphan_candidate in "${ORPHAN_CANDIDATES[@]}"; do
    if ! PYTHONDONTWRITEBYTECODE=1 python3 "$HELPER" verify-bound-orphan-fence \
            --fence "$orphan_candidate" --binding "$RECOVERY_BINDING" \
            --database-backup "$DATABASE_BACKUP" --environment-backup "$ENV_BACKUP" \
            --pre-activation-manifest "$PRE_ACTIVATION_MANIFEST" \
            --database-verification "$DATABASE_VERIFICATION" \
            --reviewed-commit "$expected_commit" >/dev/null 2>&1; then
        fail "an unrelated startup orphan fence belongs to a different activation run"
    fi
    MATCHING_ORPHAN_FENCES+=("$orphan_candidate")
done
if (( MARKER_WAS_RELEASED == 1 && ${#MATCHING_ORPHAN_FENCES[@]} == 0 )); then
    fail "marker is absent without a startup orphan fence bound to this recovery run"
fi
if (( MARKER_WAS_RELEASED == 1 )); then
    # Only the conjunction of bound released-marker and bound orphan evidence
    # authorizes recreating the global marker for a markerless recovery. Before
    # this point EXIT cleanup must not mutate a marker belonging to another run.
    RECOVERY_FENCE_AUTHORIZED=1
    env AURALINK_ROUND51_MAINTENANCE_TOKEN="$RECOVERY_MARKER_TOKEN" \
        PYTHONDONTWRITEBYTECODE=1 python3 "$HELPER" recreate-maintenance-marker \
        --marker "$MAINTENANCE_MARKER" --binding "$RECOVERY_BINDING" \
        --database-backup "$DATABASE_BACKUP" --environment-backup "$ENV_BACKUP" \
        --pre-activation-manifest "$PRE_ACTIVATION_MANIFEST" \
        --database-verification "$DATABASE_VERIFICATION" \
        --reviewed-commit "$expected_commit" >/dev/null \
        || fail "released maintenance marker could not be safely re-established"
fi
# Only the exact nonce/binding proof above authorizes retiring a crash-stale
# activation holder/fence. Removing its private orphan file makes a surviving
# holder release the kernel gate; after host reset it clears the durable Spring
# startup block. Recovery then acquires its own separately named fence.
for stale_orphan_fence in "${MATCHING_ORPHAN_FENCES[@]}"; do
    rm -f -- "$stale_orphan_fence"
done
PYTHONDONTWRITEBYTECODE=1 python3 "$HELPER" fsync-dir --directory "$BACKUP_ROOT" >/dev/null
/bin/sleep 1.25
start_startup_gate
service_down_guard
PYTHONDONTWRITEBYTECODE=1 python3 "$HELPER" verify-inherited \
    --database "$DATABASE_BACKUP" \
    --expected-legacy-data-sha256 "$EXPECTED_LEGACY_DATA_SHA256" >/dev/null
start_recovery_service_monitor
assert_recovery_monitor_healthy

CURRENT_STATE_JSON=""
CURRENT_STATE=""
if CURRENT_STATE_JSON="$(run_recovery_database_tool env PYTHONDONTWRITEBYTECODE=1 python3 \
        "$HELPER" verify-preflight \
        --database "$LIVE_DATABASE" --csv "$PROJECT_ROOT/frontend/public/data/paintings.csv" \
        --pictures "$PROJECT_ROOT/backend/picture" \
        --expected-legacy-data-sha256 "$EXPECTED_LEGACY_DATA_SHA256" \
        --expected-catalog-fingerprint "a9cf4b05e374ecaa975c51c59eda6e2a6b1adf1e02badcb69994189c7554aff6" \
        --expected-paintings 11067 --expected-image-files 9069 \
        --expected-catalog-assets 9067 --expected-missing-images 2000 \
        --expected-orphan-images 2 --expected-generated-text 8915 \
        --expected-music-scene 9068 --expected-gallery-visible 9067 2>/dev/null)"; then
    CURRENT_STATE="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["state"])' \
        <<<"$CURRENT_STATE_JSON")"
fi
if [[ "$CURRENT_STATE" == "ACTIVATED_CANDIDATE" ]] \
    && PYTHONDONTWRITEBYTECODE=1 python3 "$HELPER" verify-activation-env \
        --env-file "$ENV_FILE" >/dev/null 2>&1; then
    service_down_guard
    assert_recovery_monitor_healthy
    run_recovery_database_tool --releases-marker \
        env PYTHONDONTWRITEBYTECODE=1 python3 \
        "$HELPER" remove-stale-maintenance-marker \
        --marker "$MAINTENANCE_MARKER" \
        --released-marker "$BACKUP_DIR/.round51-released-recovery-activated-marker-$$" \
        --binding "$RECOVERY_BINDING" \
        --verified-backup "$DATABASE_BACKUP" --environment-backup "$ENV_BACKUP" \
        --pre-activation-manifest "$PRE_ACTIVATION_MANIFEST" \
        --database-verification "$DATABASE_VERIFICATION" \
        --current-database "$LIVE_DATABASE" --allow-activated-current \
        --release-intent "$SERVICE_RELEASE_INTENT_FILE" \
        --env-file "$ENV_FILE" \
        --csv "$PROJECT_ROOT/frontend/public/data/paintings.csv" \
        --pictures "$PROJECT_ROOT/backend/picture" \
        --expected-catalog-fingerprint \
            "a9cf4b05e374ecaa975c51c59eda6e2a6b1adf1e02badcb69994189c7554aff6" \
        --expected-paintings 11067 --expected-image-files 9069 \
        --expected-catalog-assets 9067 --expected-missing-images 2000 \
        --expected-orphan-images 2 --expected-generated-text 8915 \
        --expected-music-scene 9068 --expected-gallery-visible 9067 \
        --expected-legacy-data-sha256 "$EXPECTED_LEGACY_DATA_SHA256" \
        --reviewed-commit "$expected_commit" >/dev/null
    stop_recovery_service_monitor
    stop_startup_gate \
        || fail "recovery startup fence retirement could not be made durable"
    printf '%s\n' "ALREADY_ACTIVATED_AND_HEALTHY"
    exit 0
fi

timestamp="$(date -u +%Y%m%dT%H%M%SZ)-$$"
if ! run_recovery_database_tool env PYTHONDONTWRITEBYTECODE=1 python3 \
        "$HELPER" preserve-failed \
        --database "$LIVE_DATABASE" \
        --destination-prefix "$BACKUP_DIR/crash-recovery-partial-$timestamp.db" >/dev/null; then
    printf '%s\n' "CRASH_RECOVERY_PARTIAL_DATABASE_SNAPSHOT_UNAVAILABLE" >&2
fi
if ! PYTHONDONTWRITEBYTECODE=1 python3 "$HELPER" backup-env \
        --source "$ENV_FILE" \
        --destination "$BACKUP_DIR/crash-recovery-current-$timestamp.env" >/dev/null; then
    printf '%s\n' "CRASH_RECOVERY_CURRENT_ENV_SNAPSHOT_UNAVAILABLE" >&2
fi
PYTHONDONTWRITEBYTECODE=1 python3 "$HELPER" fsync-dir --directory "$BACKUP_DIR" >/dev/null
service_down_guard
assert_recovery_monitor_healthy
run_recovery_database_tool env PYTHONDONTWRITEBYTECODE=1 python3 "$HELPER" restore-db \
    --backup "$DATABASE_BACKUP" --database "$LIVE_DATABASE" >/dev/null
PYTHONDONTWRITEBYTECODE=1 python3 "$HELPER" restore-env \
    --backup "$ENV_BACKUP" --env-file "$ENV_FILE" >/dev/null
run_recovery_database_tool env PYTHONDONTWRITEBYTECODE=1 python3 \
    "$HELPER" verify-inherited \
    --database "$LIVE_DATABASE" \
    --expected-legacy-data-sha256 "$EXPECTED_LEGACY_DATA_SHA256" >/dev/null
cmp -s "$ENV_FILE" "$ENV_BACKUP" || fail "restored backend/.env differs from its named backup"
service_down_guard
assert_recovery_monitor_healthy
run_recovery_database_tool --releases-marker \
    env PYTHONDONTWRITEBYTECODE=1 python3 \
    "$HELPER" remove-stale-maintenance-marker \
    --marker "$MAINTENANCE_MARKER" \
    --released-marker "$BACKUP_DIR/.round51-released-recovery-inherited-marker-$$" \
    --binding "$RECOVERY_BINDING" \
    --verified-backup "$DATABASE_BACKUP" --environment-backup "$ENV_BACKUP" \
    --pre-activation-manifest "$PRE_ACTIVATION_MANIFEST" \
    --database-verification "$DATABASE_VERIFICATION" \
    --current-database "$LIVE_DATABASE" \
    --release-intent "$SERVICE_RELEASE_INTENT_FILE" \
    --env-file "$ENV_FILE" \
    --expected-legacy-data-sha256 "$EXPECTED_LEGACY_DATA_SHA256" \
    --reviewed-commit "$expected_commit" >/dev/null
stop_recovery_service_monitor
stop_startup_gate \
    || fail "recovery startup fence retirement could not be made durable"
printf '%s\n' "STALE_MAINTENANCE_RECOVERY_COMPLETED"
