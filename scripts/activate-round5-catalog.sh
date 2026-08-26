#!/usr/bin/env bash
# One-time Auralink 2.0 catalog activation.  This file is intentionally tied to
# the real AutoDL server-local checkout; it cannot activate an SSHFS checkout.

set -Eeuo pipefail
IFS=$'\n\t'
umask 077

readonly SERVER_LOCAL_ROOT="/root/autodl-tmp/auralink"
readonly BACKUP_ROOT="/root/auralink_activation_backups"
readonly MAINTENANCE_MARKER="$BACKUP_ROOT/.round51-maintenance"
readonly STARTUP_GATE="$BACKUP_ROOT/.round51-startup-gate"
readonly CONFIRMATION_TOKEN="ACTIVATE_AURALINK_2_0_CATALOG"
readonly JAVA_MAIN="com.auralink.ops.round51.Round51ActivationCommand"
readonly JAR_NAME="auralink-backend-0.0.1-SNAPSHOT.jar"
readonly EXPECTED_LEGACY_DATA_SHA256="1a0d0e7f41964ee77d4a78c9a86ec47d732f1d202400e180d41994046b941131"
readonly EXPECTED_CATALOG_FINGERPRINT="a9cf4b05e374ecaa975c51c59eda6e2a6b1adf1e02badcb69994189c7554aff6"
readonly EXPECTED_PAINTINGS=11067
readonly EXPECTED_IMAGE_FILES=9069
readonly EXPECTED_CATALOG_ASSETS=9067
readonly EXPECTED_MISSING_IMAGES=2000
readonly EXPECTED_ORPHAN_IMAGES=2
readonly EXPECTED_GENERATED_TEXT=8915
readonly EXPECTED_MUSIC_SCENE=9068
readonly EXPECTED_GALLERY_VISIBLE=9067

MODE=""
RUN_SMOKE=0
ROLLBACK_ARMED=0
SIGNAL_EXIT_STATUS=0
BACKUP_DIR=""
DATABASE_BACKUP=""
ENV_BACKUP=""
ACTIVATION_CHILD=""
ACTIVATION_CHILD_START=""
SMOKE_CHILD=""
SMOKE_CHILD_START=""
RELEASE_CHILD=""
RELEASE_CHILD_START=""
STARTUP_GATE_CHILD=""
STARTUP_GATE_CHILD_START=""
STARTUP_GATE_ORPHAN_FENCE=""
SERVICE_MONITOR_CHILD=""
SERVICE_MONITOR_CHILD_START=""
SERVICE_ALLOWANCE_FILE=""
SERVICE_VIOLATION_FILE=""
SERVICE_VIOLATION_REASON_FILE=""
SERVICE_RELEASE_INTENT_FILE=""
BUILD_ROOT=""
ACTIVE_HELPER=""
PACKAGED_JAR=""
REVIEWED_COMMIT=""
MAINTENANCE_TOKEN=""
MAINTENANCE_HELD=0

usage() {
    printf '%s\n' \
        "Usage: backend/scripts/activate-round5-catalog.sh --dry-run" \
        "       backend/scripts/activate-round5-catalog.sh --activate [--smoke]"
}

fail() {
    printf 'ROUND51_ACTIVATION_ERROR: %s\n' "$1" >&2
    exit 1
}

log_event() {
    local message="$1"
    printf '%s\n' "$message"
    if [[ -n "$BACKUP_DIR" && -d "$BACKUP_DIR" ]]; then
        printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$message" \
            >> "$BACKUP_DIR/activation.log"
    fi
}

for argument in "$@"; do
    case "$argument" in
        --dry-run)
            [[ -z "$MODE" ]] || fail "choose exactly one of --dry-run or --activate"
            MODE="dry-run"
            ;;
        --activate)
            [[ -z "$MODE" ]] || fail "choose exactly one of --dry-run or --activate"
            MODE="activate"
            ;;
        --smoke)
            RUN_SMOKE=1
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            fail "unknown argument: $argument"
            ;;
    esac
done
[[ -n "$MODE" ]] || { usage >&2; exit 2; }
[[ "$MODE" == "activate" || "$RUN_SMOKE" == 0 ]] || fail "--smoke requires --activate"

command -v python3 >/dev/null || fail "python3 is required"
command -v git >/dev/null || fail "git is required"
command -v findmnt >/dev/null || fail "findmnt is required for filesystem verification"
command -v ss >/dev/null || fail "ss is required for the service-down guard"
command -v flock >/dev/null || fail "flock is required for activation-state fencing"

readonly SCRIPT_PATH="$(readlink -f -- "${BASH_SOURCE[0]}")"
readonly SCRIPT_ROOT="$(readlink -f -- "$(dirname -- "$SCRIPT_PATH")/../..")"
readonly WORKING_ROOT="$(pwd -P)"
readonly PROJECT_ROOT="$SERVER_LOCAL_ROOT"
readonly BACKEND_ROOT="$PROJECT_ROOT/backend"
readonly LIVE_DATABASE="$BACKEND_ROOT/auralink.db"
readonly ENV_FILE="$BACKEND_ROOT/.env"
readonly HELPER="$BACKEND_ROOT/scripts/round51_state.py"
readonly CSV_FILE="$PROJECT_ROOT/frontend/public/data/paintings.csv"
readonly PICTURE_DIR="$BACKEND_ROOT/picture"
readonly V1_MIGRATION="$BACKEND_ROOT/src/main/resources/db/migration/V1__legacy_schema_baseline.sql"
readonly V2_MIGRATION="$BACKEND_ROOT/src/main/resources/db/migration/V2__create_auralink_2_0_foundation.sql"
ACTIVE_HELPER="$HELPER"

verify_regular_file() {
    local path="$1"
    local label="$2"
    [[ -f "$path" && ! -L "$path" ]] || fail "$label must be a regular non-symlink file"
}

verify_server_local_root() {
    [[ "$SCRIPT_ROOT" == "$SERVER_LOCAL_ROOT" ]] \
        || fail "script is not installed at the exact server-local project root"
    [[ "$WORKING_ROOT" == "$SERVER_LOCAL_ROOT" ]] \
        || fail "run from exactly $SERVER_LOCAL_ROOT"
    [[ "$(readlink -f -- "$PROJECT_ROOT")" == "$SERVER_LOCAL_ROOT" ]] \
        || fail "server-local project root resolves unexpectedly"
    local filesystem_type
    filesystem_type="$(findmnt -n -o FSTYPE -T "$PROJECT_ROOT")"
    [[ -n "$filesystem_type" ]] || fail "cannot determine project filesystem type"
    case "${filesystem_type,,}" in
        *sshfs*|*fuse*) fail "server-local project root is on a FUSE/SSHFS filesystem" ;;
    esac
    printf '%s\n' "SERVER_LOCAL_ROOT_VERIFIED"
}

verify_required_files() {
    verify_regular_file "$LIVE_DATABASE" "backend/auralink.db"
    verify_regular_file "$ENV_FILE" "backend/.env"
    verify_regular_file "$HELPER" "Round 5.1 state helper"
    verify_regular_file "$CSV_FILE" "official Painting CSV"
    verify_regular_file "$V1_MIGRATION" "V1 migration"
    verify_regular_file "$V2_MIGRATION" "V2 migration"
    [[ -d "$PICTURE_DIR" && ! -L "$PICTURE_DIR" ]] \
        || fail "backend/picture must be a non-symlink directory"
}

verify_commit() {
    local expected_commit="${AURALINK_ROUND51_EXPECTED_COMMIT:-}"
    [[ "$expected_commit" =~ ^[0-9a-f]{40}$ ]] \
        || fail "AURALINK_ROUND51_EXPECTED_COMMIT must be the reviewed 40-character commit"
    local actual_commit
    actual_commit="$(git -C "$PROJECT_ROOT" rev-parse HEAD)"
    [[ "$actual_commit" == "$expected_commit" ]] \
        || fail "current commit differs from the reviewed activation commit"
    [[ -z "$(git -C "$PROJECT_ROOT" status --porcelain --untracked-files=all)" ]] \
        || fail "tracked checkout is not clean"
    REVIEWED_COMMIT="$expected_commit"
    printf 'REVIEWED_COMMIT_VERIFIED=%s\n' "$actual_commit"
}

configured_port() {
    local port
    port="$(PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" env-value \
        --env-file "$ENV_FILE" --key SERVER_PORT --default 5000)"
    [[ "$port" =~ ^[0-9]{1,5}$ ]] && (( port > 0 && port <= 65535 )) \
        || fail "configured backend port is invalid"
    printf '%s\n' "$port"
}

service_down_guard() {
    local allowed_pid="${1:-}"
    local allow_listener="${2:-0}"
    local quiet="${3:-}"
    local expected_start_time="${4:-}"
    local allowed_identity=0
    if [[ -n "$allowed_pid" && "$allowed_pid" =~ ^[0-9]+$
            && -n "$expected_start_time" && "$expected_start_time" =~ ^[0-9]+$
            && -r "/proc/$allowed_pid/stat"
            && "$(awk '{print $22}' "/proc/$allowed_pid/stat" 2>/dev/null || true)" \
                == "$expected_start_time" ]]; then
        allowed_identity=1
    fi
    local port
    if ! port="$(configured_port)"; then
        printf '%s\n' "BACKEND_SERVICE_STATE_UNKNOWN" >&2
        printf '%s\n' "BACKEND_SERVICE_MUST_BE_STOPPED" >&2
        return 1
    fi
    local socket_state listeners listener listener_allowed
    if ! socket_state="$(ss -H -ltnp 2>/dev/null)"; then
        printf '%s\n' "BACKEND_SERVICE_STATE_UNKNOWN" >&2
        printf '%s\n' "BACKEND_SERVICE_MUST_BE_STOPPED" >&2
        return 1
    fi
    listeners="$(awk -v endpoint=":${port}" \
        '$4 ~ (endpoint "$") { print $4, $NF }' <<<"$socket_state")"
    local blocked=0
    while IFS= read -r listener; do
        [[ -n "$listener" ]] || continue
        listener_allowed=0
        if (( allow_listener == 1 && allowed_identity == 1 )) && [[ -n "$allowed_pid"
                && ( "$listener" == *"pid=${allowed_pid},"*
                    || "$listener" == *"pid=${allowed_pid})"* ) ]]; then
            listener_allowed=1
        fi
        if (( listener_allowed == 0 )); then
            printf 'BACKEND_PORT_OCCUPIED %s\n' "$listener" >&2
            blocked=1
        fi
    done <<<"$listeners"

    local process_dir pid command_name cwd fd target
    for process_dir in /proc/[0-9]*; do
        pid="${process_dir##*/}"
        if (( allowed_identity == 1 )) && [[ "$pid" == "$allowed_pid" ]]; then
            continue
        fi
        command_name="$(cat "$process_dir/comm" 2>/dev/null || true)"
        if [[ "$command_name" == java* ]]; then
            cwd="$(readlink -f "$process_dir/cwd" 2>/dev/null || true)"
            if [[ "$cwd" == "$PROJECT_ROOT" || "$cwd" == "$PROJECT_ROOT/"* ]]; then
                printf 'AURALINK_JAVA_PROCESS pid=%s user=%s command=%s\n' \
                    "$pid" "$(stat -c %U "$process_dir" 2>/dev/null || printf unknown)" \
                    "$command_name" >&2
                blocked=1
            fi
        fi
        for fd in "$process_dir"/fd/*; do
            [[ -e "$fd" || -L "$fd" ]] || continue
            target="$(readlink "$fd" 2>/dev/null || true)"
            target="${target% (deleted)}"
            if [[ "$target" == "$LIVE_DATABASE" || "$target" == "$LIVE_DATABASE-journal" \
                    || "$target" == "$LIVE_DATABASE-wal" || "$target" == "$LIVE_DATABASE-shm" ]]; then
                printf 'DATABASE_OPEN_BY_PROCESS pid=%s user=%s command=%s\n' \
                    "$pid" "$(stat -c %U "$process_dir" 2>/dev/null || printf unknown)" \
                    "${command_name:-unknown}" >&2
                blocked=1
                break
            fi
        done
    done
    if (( blocked != 0 )); then
        printf '%s\n' "BACKEND_SERVICE_MUST_BE_STOPPED" >&2
        return 1
    fi
    if [[ "$quiet" != "quiet" ]]; then
        printf 'BACKEND_SERVICE_STOPPED port=%s\n' "$port"
    fi
}

inspect_activation_state() {
    PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" verify-preflight \
        --database "$LIVE_DATABASE" --csv "$CSV_FILE" --pictures "$PICTURE_DIR" \
        --expected-legacy-data-sha256 "$EXPECTED_LEGACY_DATA_SHA256" \
        --expected-catalog-fingerprint "$EXPECTED_CATALOG_FINGERPRINT" \
        --expected-paintings "$EXPECTED_PAINTINGS" \
        --expected-image-files "$EXPECTED_IMAGE_FILES" \
        --expected-catalog-assets "$EXPECTED_CATALOG_ASSETS" \
        --expected-missing-images "$EXPECTED_MISSING_IMAGES" \
        --expected-orphan-images "$EXPECTED_ORPHAN_IMAGES" \
        --expected-generated-text "$EXPECTED_GENERATED_TEXT" \
        --expected-music-scene "$EXPECTED_MUSIC_SCENE" \
        --expected-gallery-visible "$EXPECTED_GALLERY_VISIBLE"
}

verify_activated_state() {
    local -a command=(env PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" verify-activated \
        --database "$LIVE_DATABASE" --csv "$CSV_FILE" --pictures "$PICTURE_DIR" \
        --expected-legacy-data-sha256 "$EXPECTED_LEGACY_DATA_SHA256" \
        --expected-catalog-fingerprint "$EXPECTED_CATALOG_FINGERPRINT" \
        --expected-paintings "$EXPECTED_PAINTINGS" \
        --expected-image-files "$EXPECTED_IMAGE_FILES" \
        --expected-catalog-assets "$EXPECTED_CATALOG_ASSETS" \
        --expected-missing-images "$EXPECTED_MISSING_IMAGES" \
        --expected-orphan-images "$EXPECTED_ORPHAN_IMAGES" \
        --expected-generated-text "$EXPECTED_GENERATED_TEXT" \
        --expected-music-scene "$EXPECTED_MUSIC_SCENE" \
        --expected-gallery-visible "$EXPECTED_GALLERY_VISIBLE")
    if [[ -n "$SERVICE_MONITOR_CHILD" ]]; then
        run_database_tool "${command[@]}"
    else
        "${command[@]}"
    fi
}

verify_free_space() {
    local database_bytes backup_minimum live_minimum backup_probe
    database_bytes="$(stat -c %s "$LIVE_DATABASE")"
    [[ "$database_bytes" =~ ^[0-9]+$ ]] \
        || fail "could not calculate activation free space"
    backup_minimum=$(( database_bytes * 3 ))
    live_minimum=$(( database_bytes * 2 ))
    (( backup_minimum >= 1073741824 )) || backup_minimum=1073741824
    (( live_minimum >= 1073741824 )) || live_minimum=1073741824
    if [[ -d "$BACKUP_ROOT" && ! -L "$BACKUP_ROOT" ]]; then
        backup_probe="$BACKUP_ROOT"
    else
        backup_probe="$(dirname -- "$BACKUP_ROOT")"
    fi

    verify_filesystem_space "$backup_probe" "$backup_minimum" "BACKUP"
    verify_filesystem_space "$(dirname -- "$LIVE_DATABASE")" "$live_minimum" "LIVE_DATABASE"
}

verify_filesystem_space() {
    local path="$1"
    local required_bytes="$2"
    local label="$3"
    local available_kib available_bytes
    available_kib="$(df -Pk "$path" | awk 'NR==2 {print $4}')"
    [[ "$available_kib" =~ ^[0-9]+$ ]] \
        || fail "could not calculate $label filesystem free space"
    available_bytes=$(( available_kib * 1024 ))
    printf 'ACTIVATION_FREE_SPACE_%s available_bytes=%s required_bytes=%s\n' \
        "$label" "$available_bytes" "$required_bytes"
    (( available_bytes >= required_bytes )) \
        || fail "insufficient $label filesystem space for activation and rollback"
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
    local label="$3"
    [[ -n "$pid" && -n "$expected_start" ]] || return 0
    if process_identity_matches "$pid" "$expected_start"; then
        kill -TERM "$pid" 2>/dev/null || true
        local attempt
        for attempt in {1..40}; do
            process_identity_matches "$pid" "$expected_start" || break
            sleep 0.25
        done
        if process_identity_matches "$pid" "$expected_start"; then
            kill -KILL "$pid" 2>/dev/null || true
        fi
        wait "$pid" 2>/dev/null || true
        log_event "OWNED_${label}_PROCESS_STOPPED pid=$pid"
    fi
}

start_startup_gate() {
    local parent_pid="$$" parent_start_time ready_file
    parent_start_time="$(awk '{print $22}' "/proc/$parent_pid/stat" 2>/dev/null || true)"
    [[ "$parent_start_time" =~ ^[0-9]+$ ]] \
        || fail "startup-gate coordinator identity could not be recorded"
    ready_file="$BACKUP_ROOT/.round51-startup-gate-ready-$$"
    STARTUP_GATE_ORPHAN_FENCE="$BACKUP_ROOT/.round51-activation-startup-gate-orphan-fence-$$"
    rm -f -- "$ready_file"
    (
        exec 9>&-
        exec env PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" \
            hold-startup-gate --gate "$STARTUP_GATE" --ready "$ready_file" \
            --parent-pid "$parent_pid" --parent-start-time "$parent_start_time" \
            --orphan-fence "$STARTUP_GATE_ORPHAN_FENCE"
    ) >/dev/null 2>&1 &
    STARTUP_GATE_CHILD=$!
    STARTUP_GATE_CHILD_START="$(awk '{print $22}' \
        "/proc/$STARTUP_GATE_CHILD/stat" 2>/dev/null || true)"
    [[ "$STARTUP_GATE_CHILD_START" =~ ^[0-9]+$ ]] \
        || fail "startup-gate holder identity could not be recorded"
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
    # The orphan path remains absent until a durable marker/binding exists.
    # A pre-mutation coordinator crash therefore releases the kernel gate with
    # no unactionable durable fence.
    log_event "BACKEND_STARTUP_KERNEL_GATE_ACQUIRED"
}

arm_startup_gate_orphan_fence() {
    [[ -n "$STARTUP_GATE_ORPHAN_FENCE" && -n "$STARTUP_GATE_CHILD" ]] \
        || fail "startup gate is not initialized"
    PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" create-bound-orphan-fence \
        --fence "$STARTUP_GATE_ORPHAN_FENCE" --binding "$RECOVERY_BINDING" \
        --database-backup "$DATABASE_BACKUP" --environment-backup "$ENV_BACKUP" \
        --pre-activation-manifest "$BACKUP_DIR/pre-activation-manifest.json" \
        --database-verification "$BACKUP_DIR/database-backup-verification.json" \
        --reviewed-commit "$REVIEWED_COMMIT" >/dev/null
    log_event "BACKEND_STARTUP_KERNEL_GATE_CRASH_FENCE_ARMED"
}

stop_startup_gate() {
    [[ -n "$STARTUP_GATE_CHILD" ]] || return 0
    if ! rm -f -- "$STARTUP_GATE_ORPHAN_FENCE"; then
        log_event "BACKEND_STARTUP_ORPHAN_FENCE_RETIREMENT_FAILED"
        return 1
    fi
    # Make the durable-fence retirement reach the directory before releasing
    # the kernel gate. If fsync fails, retain the holder so a host reset cannot
    # resurrect an orphan fence after an otherwise successful release.
    if ! PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" fsync-dir \
            --directory "$BACKUP_ROOT" >/dev/null; then
        log_event "BACKEND_STARTUP_ORPHAN_FENCE_RETIREMENT_NOT_DURABLE"
        # The namespace unlink has happened but its durability is unknown.
        # Recreate this exact run-bound orphan while the kernel gate is still
        # held. If that cannot be proved, restore the nonce-bound global
        # marker. Either result keeps ordinary Spring startup fail-closed.
        local durable_block_reestablished=0
        if [[ -n "${RECOVERY_BINDING:-}" && -f "${RECOVERY_BINDING:-}" \
                && -f "${DATABASE_BACKUP:-}" && -f "${ENV_BACKUP:-}" ]]; then
            if PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" \
                    create-bound-orphan-fence \
                    --fence "$STARTUP_GATE_ORPHAN_FENCE" \
                    --binding "$RECOVERY_BINDING" \
                    --database-backup "$DATABASE_BACKUP" \
                    --environment-backup "$ENV_BACKUP" \
                    --pre-activation-manifest \
                        "$BACKUP_DIR/pre-activation-manifest.json" \
                    --database-verification \
                        "$BACKUP_DIR/database-backup-verification.json" \
                    --reviewed-commit "$REVIEWED_COMMIT" >/dev/null 2>&1; then
                durable_block_reestablished=1
                log_event "BACKEND_STARTUP_ORPHAN_FENCE_REESTABLISHED"
            fi
        fi
        if (( durable_block_reestablished == 0 )) \
                && [[ "$MAINTENANCE_TOKEN" =~ ^[0-9a-f]{64}$ ]] \
                && ensure_rollback_maintenance_fence; then
            durable_block_reestablished=1
        fi
        if (( durable_block_reestablished == 0 )); then
            # Last-resort current-boot fence: the holder observes this path
            # after coordinator exit. Recovery will deliberately reject this
            # unauthenticated file and require operator review.
            ( umask 077; : > "$STARTUP_GATE_ORPHAN_FENCE" ) 2>/dev/null || true
            log_event "BACKEND_STARTUP_ORPHAN_FENCE_REESTABLISHMENT_UNVERIFIED"
        fi
        return 1
    fi
    stop_owned_process "$STARTUP_GATE_CHILD" "$STARTUP_GATE_CHILD_START" "STARTUP_GATE"
    STARTUP_GATE_CHILD=""
    STARTUP_GATE_CHILD_START=""
    STARTUP_GATE_ORPHAN_FENCE=""
    MAINTENANCE_TOKEN=""
    log_event "BACKEND_STARTUP_KERNEL_GATE_RELEASED"
}

refuse_preexisting_orphan_fences() {
    local candidate
    shopt -s nullglob
    local candidates=(
        "$BACKUP_ROOT"/.round51-activation-startup-gate-orphan-fence-*
        "$BACKUP_ROOT"/.round51-recovery-startup-gate-orphan-fence-*
    )
    shopt -u nullglob
    for candidate in "${candidates[@]}"; do
        [[ -e "$candidate" || -L "$candidate" ]] || continue
        printf 'STALE_STARTUP_ORPHAN_FENCE=%s\n' "$candidate" >&2
        fail "stale Round 5.1 startup fence requires named recovery before activation"
    done
}

verify_activated_operational_fences() {
    # Verification-only already-activated mode must not claim health while a
    # prior activation/recovery lease still blocks normal Spring startup.
    if [[ ! -e "$BACKUP_ROOT" && ! -L "$BACKUP_ROOT" ]]; then
        return 0
    fi
    [[ -d "$BACKUP_ROOT" && ! -L "$BACKUP_ROOT" \
            && "$(readlink -f -- "$BACKUP_ROOT")" == "$BACKUP_ROOT" \
            && "$(stat -c %u "$BACKUP_ROOT")" == "$(id -u)" ]] \
        || fail "activated backup root is unsafe"
    local backup_mode
    backup_mode="$(stat -c %a "$BACKUP_ROOT")"
    (( (8#$backup_mode & 077) == 0 )) \
        || fail "activated backup root permissions are not private"
    exec 8< "$BACKUP_ROOT"
    flock -n 8 || fail "an activation or recovery process still owns the backup-root lock"
    [[ ! -e "$MAINTENANCE_MARKER" && ! -L "$MAINTENANCE_MARKER" ]] \
        || fail "activated database is still protected by a maintenance marker"
    refuse_preexisting_orphan_fences
    if [[ -e "$STARTUP_GATE" || -L "$STARTUP_GATE" ]]; then
        [[ -f "$STARTUP_GATE" && ! -L "$STARTUP_GATE" \
                && "$(stat -c %u "$STARTUP_GATE")" == "$(id -u)" ]] \
            || fail "activated startup gate is unsafe"
        local gate_mode
        gate_mode="$(stat -c %a "$STARTUP_GATE")"
        (( (8#$gate_mode & 077) == 0 )) \
            || fail "activated startup gate permissions are not private"
    fi
}

set_service_allowance() {
    local allowed_pid="${1:-}"
    local allow_listener="${2:-0}"
    [[ "$allow_listener" == 0 || "$allow_listener" == 1 ]] \
        || fail "invalid service allowance mode"
    local allowed_start_time=""
    if [[ -n "$allowed_pid" ]]; then
        [[ "$allowed_pid" =~ ^[0-9]+$ && -r "/proc/$allowed_pid/stat" ]] \
            || fail "owned service process cannot be identified"
        allowed_start_time="$(awk '{print $22}' "/proc/$allowed_pid/stat" 2>/dev/null || true)"
        [[ "$allowed_start_time" =~ ^[0-9]+$ ]] \
            || fail "owned service process start time cannot be identified"
    fi
    local temporary_allowance="$BACKUP_DIR/.service-allowance.$$"
    printf 'pid=%s\nstart=%s\nlisten=%s\n' \
        "$allowed_pid" "$allowed_start_time" "$allow_listener" > "$temporary_allowance"
    chmod 600 "$temporary_allowance"
    mv -f -- "$temporary_allowance" "$SERVICE_ALLOWANCE_FILE"
}

start_service_exclusion_monitor() {
    local parent_pid="$$"
    local parent_start_time
    parent_start_time="$(awk '{print $22}' "/proc/$parent_pid/stat" 2>/dev/null || true)"
    [[ "$parent_start_time" =~ ^[0-9]+$ ]] \
        || fail "activation coordinator identity could not be recorded"
    [[ -n "$SERVICE_ALLOWANCE_FILE" && -n "$SERVICE_VIOLATION_FILE" ]] \
        || fail "service monitor paths are not initialized"
    (
        # Only the coordinator owns the backup-root flock. A SIGKILLed parent
        # must not leave an orphan watchdog permanently blocking recovery.
        exec 9>&-
        while true; do
            /bin/sleep 1
            if [[ ! -f "$MAINTENANCE_MARKER" ]]; then
                if [[ -f "$SERVICE_RELEASE_INTENT_FILE" \
                        && ! -L "$SERVICE_RELEASE_INTENT_FILE" ]]; then
                    exit 0
                fi
                printf '%s\n' "MARKER_LOST" > "$SERVICE_VIOLATION_REASON_FILE"
                : > "$SERVICE_VIOLATION_FILE"
                log_event "SERVICE_EXCLUSION_MONITOR_MARKER_LOST_WITHOUT_RELEASE_INTENT"
                if ! env AURALINK_ROUND51_MAINTENANCE_TOKEN="$MAINTENANCE_TOKEN" \
                        PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" \
                        recreate-maintenance-marker \
                        --marker "$MAINTENANCE_MARKER" \
                        --binding "$RECOVERY_BINDING" \
                        --database-backup "$DATABASE_BACKUP" \
                        --environment-backup "$ENV_BACKUP" \
                        --pre-activation-manifest \
                            "$BACKUP_DIR/pre-activation-manifest.json" \
                        --database-verification \
                            "$BACKUP_DIR/database-backup-verification.json" \
                        --reviewed-commit "$REVIEWED_COMMIT" >/dev/null 2>&1; then
                    log_event "SERVICE_EXCLUSION_MONITOR_FENCE_REESTABLISHMENT_FAILED"
                else
                    log_event "SERVICE_EXCLUSION_MONITOR_FENCE_REESTABLISHED"
                fi
                if process_identity_matches "$parent_pid" "$parent_start_time"; then
                    kill -TERM "$parent_pid" 2>/dev/null || true
                fi
                exit 1
            fi
            local allowed_pid="" allowed_start_time="" allow_listener=0 key value
            if [[ ! -f "$SERVICE_ALLOWANCE_FILE" || -L "$SERVICE_ALLOWANCE_FILE" ]]; then
                printf '%s\n' "ALLOWANCE_UNAVAILABLE" > "$SERVICE_VIOLATION_REASON_FILE"
                : > "$SERVICE_VIOLATION_FILE"
                log_event "SERVICE_EXCLUSION_MONITOR_ALLOWANCE_UNAVAILABLE"
                if process_identity_matches "$parent_pid" "$parent_start_time"; then
                    kill -TERM "$parent_pid" 2>/dev/null || true
                fi
                exit 1
            fi
            while IFS='=' read -r key value; do
                case "$key" in
                    pid) allowed_pid="$value" ;;
                    start) allowed_start_time="$value" ;;
                    listen) allow_listener="$value" ;;
                esac
            done < "$SERVICE_ALLOWANCE_FILE"
            if [[ "$allow_listener" != 0 && "$allow_listener" != 1 ]]; then
                printf '%s\n' "ALLOWANCE_INVALID" > "$SERVICE_VIOLATION_REASON_FILE"
                : > "$SERVICE_VIOLATION_FILE"
                log_event "SERVICE_EXCLUSION_MONITOR_ALLOWANCE_INVALID"
                if process_identity_matches "$parent_pid" "$parent_start_time"; then
                    kill -TERM "$parent_pid" 2>/dev/null || true
                fi
                exit 1
            fi
            if ! service_down_guard \
                    "$allowed_pid" "$allow_listener" quiet "$allowed_start_time"; then
                printf '%s\n' "UNAUTHORIZED_DATABASE_USER" > "$SERVICE_VIOLATION_REASON_FILE"
                : > "$SERVICE_VIOLATION_FILE"
                log_event "SERVICE_EXCLUSION_MONITOR_DETECTED_UNAUTHORIZED_DATABASE_USER"
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
        fail "service exclusion monitor identity could not be recorded"
    fi
    log_event "SERVICE_EXCLUSION_MONITOR_STARTED pid=$SERVICE_MONITOR_CHILD"
}

assert_service_monitor_healthy() {
    if [[ -z "$SERVICE_MONITOR_CHILD" || -e "$SERVICE_VIOLATION_FILE" ]] \
            || ! process_identity_matches \
                "$SERVICE_MONITOR_CHILD" "$SERVICE_MONITOR_CHILD_START"; then
        fail "service exclusion monitor is not healthy"
    fi
}

run_database_tool() {
    local gate="$BACKUP_DIR/.round51-database-tool-go-$$-$RANDOM"
    (
        exec 9>&-
        while [[ ! -f "$gate" ]]; do
            /bin/sleep 0.05
        done
        exec "$@"
    ) &
    local tool_pid=$!
    local tool_start
    tool_start="$(awk '{print $22}' "/proc/$tool_pid/stat" 2>/dev/null || true)"
    if [[ ! "$tool_start" =~ ^[0-9]+$ ]]; then
        kill -TERM "$tool_pid" 2>/dev/null || true
        wait "$tool_pid" 2>/dev/null || true
        rm -f -- "$gate"
        fail "owned database tool identity could not be recorded"
    fi
    set_service_allowance "$tool_pid" 0
    : > "$gate"
    local status=0 wait_status=0 child_reaped=0
    while process_identity_matches "$tool_pid" "$tool_start"; do
        wait "$tool_pid" || wait_status=$?
        if (( wait_status == 129 || wait_status == 130 \
                || wait_status == 143 )); then
            wait_status=0
            continue
        fi
        status=$wait_status
        child_reaped=1
        break
    done
    if (( child_reaped == 0 )); then
        wait "$tool_pid" || status=$?
    fi
    set_service_allowance "" 0
    rm -f -- "$gate"
    if [[ -e "$SERVICE_VIOLATION_FILE" ]]; then
        local violation_reason=""
        if [[ -f "$SERVICE_VIOLATION_REASON_FILE" \
                && ! -L "$SERVICE_VIOLATION_REASON_FILE" ]]; then
            violation_reason="$(tr -d '\n' < "$SERVICE_VIOLATION_REASON_FILE")"
        fi
        [[ "$violation_reason" == "MARKER_LOST" ]] \
            || fail "service monitor detected a non-recoverable exclusion violation"
        service_down_guard >/dev/null \
            || fail "service monitor violation detected a running database user"
        ensure_rollback_maintenance_fence \
            || fail "service monitor violation left no recoverable fence"
    else
        assert_service_monitor_healthy
    fi
    return "$status"
}

stop_service_exclusion_monitor() {
    [[ -n "$SERVICE_MONITOR_CHILD" ]] || return 0
    if process_identity_matches "$SERVICE_MONITOR_CHILD" "$SERVICE_MONITOR_CHILD_START"; then
        kill -TERM "$SERVICE_MONITOR_CHILD" 2>/dev/null || true
    fi
    wait "$SERVICE_MONITOR_CHILD" 2>/dev/null || true
    log_event "SERVICE_EXCLUSION_MONITOR_STOPPED pid=$SERVICE_MONITOR_CHILD"
    SERVICE_MONITOR_CHILD=""
    SERVICE_MONITOR_CHILD_START=""
}

ensure_rollback_maintenance_fence() {
    # A failed/exited watchdog is replaced before rollback touches the DB. If
    # the global marker was externally removed, recreate it only from the
    # live coordinator nonce plus the durably bound, hash-verified evidence.
    stop_service_exclusion_monitor
    if [[ ! -e "$MAINTENANCE_MARKER" && ! -L "$MAINTENANCE_MARKER" ]]; then
        if ! env AURALINK_ROUND51_MAINTENANCE_TOKEN="$MAINTENANCE_TOKEN" \
            PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" \
            recreate-maintenance-marker \
            --marker "$MAINTENANCE_MARKER" \
            --binding "$RECOVERY_BINDING" \
            --database-backup "$DATABASE_BACKUP" \
            --environment-backup "$ENV_BACKUP" \
            --pre-activation-manifest "$BACKUP_DIR/pre-activation-manifest.json" \
            --database-verification "$BACKUP_DIR/database-backup-verification.json" \
            --reviewed-commit "$REVIEWED_COMMIT" >/dev/null 2>&1; then
            # A watchdog may win the publication race. Accept only the exact
            # marker bound to this activation's evidence.
            PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" verify-recovery-binding \
                --marker "$MAINTENANCE_MARKER" --binding "$RECOVERY_BINDING" \
                --database-backup "$DATABASE_BACKUP" \
                --environment-backup "$ENV_BACKUP" \
                --pre-activation-manifest \
                    "$BACKUP_DIR/pre-activation-manifest.json" \
                --database-verification \
                    "$BACKUP_DIR/database-backup-verification.json" \
                --reviewed-commit "$REVIEWED_COMMIT" >/dev/null \
                || return 1
        fi
        log_event "ROLLBACK_MAINTENANCE_FENCE_REESTABLISHED"
    else
        if ! PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" verify-recovery-binding \
            --marker "$MAINTENANCE_MARKER" \
            --binding "$RECOVERY_BINDING" \
            --database-backup "$DATABASE_BACKUP" \
            --environment-backup "$ENV_BACKUP" \
            --pre-activation-manifest "$BACKUP_DIR/pre-activation-manifest.json" \
            --database-verification "$BACKUP_DIR/database-backup-verification.json" \
            --reviewed-commit "$REVIEWED_COMMIT" >/dev/null; then
            # The watchdog may have repaired the marker concurrently with this
            # function. Re-check once after stopping/joining that watchdog.
            PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" verify-recovery-binding \
                --marker "$MAINTENANCE_MARKER" \
                --binding "$RECOVERY_BINDING" \
                --database-backup "$DATABASE_BACKUP" \
                --environment-backup "$ENV_BACKUP" \
                --pre-activation-manifest \
                    "$BACKUP_DIR/pre-activation-manifest.json" \
                --database-verification \
                    "$BACKUP_DIR/database-backup-verification.json" \
                --reviewed-commit "$REVIEWED_COMMIT" >/dev/null \
                || return 1
        fi
    fi
    MAINTENANCE_HELD=1
    # A controlled failure can occur after marker acquisition but before the
    # continuous monitor paths are initialized. The exact bound marker above
    # is sufficient in that pre-mutation state; no monitor restart is needed.
    if [[ -z "$SERVICE_ALLOWANCE_FILE" || -z "$SERVICE_VIOLATION_FILE" \
            || -z "$SERVICE_VIOLATION_REASON_FILE" ]]; then
        return 0
    fi
    rm -f -- "$SERVICE_VIOLATION_FILE" "$SERVICE_VIOLATION_REASON_FILE" \
        "$SERVICE_RELEASE_INTENT_FILE"
    set_service_allowance "" 0
    start_service_exclusion_monitor
    assert_service_monitor_healthy
}

cleanup_build_root() {
    [[ -n "$BUILD_ROOT" ]] || return 0
    if [[ "$BUILD_ROOT" == /tmp/auralink-round51-build.* && -d "$BUILD_ROOT" ]]; then
        rm -rf -- "$BUILD_ROOT"
    else
        log_event "TEMPORARY_BUILD_ROOT_NOT_REMOVED_UNEXPECTED_PATH"
    fi
    BUILD_ROOT=""
}

release_maintenance_marker() {
    local release_state="${1:-}"
    (( MAINTENANCE_HELD == 1 )) || return 0
    [[ "$release_state" == inherited || "$release_state" == activated ]] \
        || return 1
    local release_gate="$BACKUP_DIR/.round51-marker-release-go-$$-$RANDOM"
    local released_marker="$BACKUP_DIR/.round51-released-${release_state}-marker-$$-$RANDOM"
    (
        exec 9>&-
        while [[ ! -f "$release_gate" ]]; do
            /bin/sleep 0.05
        done
        if [[ "$release_state" == inherited ]]; then
            exec env PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" \
                remove-stale-maintenance-marker \
                --marker "$MAINTENANCE_MARKER" --released-marker "$released_marker" \
                --binding "$RECOVERY_BINDING" \
                --verified-backup "$DATABASE_BACKUP" \
                --environment-backup "$ENV_BACKUP" \
                --pre-activation-manifest \
                    "$BACKUP_DIR/pre-activation-manifest.json" \
                --database-verification \
                    "$BACKUP_DIR/database-backup-verification.json" \
                --current-database "$LIVE_DATABASE" --env-file "$ENV_FILE" \
                --release-intent "$SERVICE_RELEASE_INTENT_FILE" \
                --expected-legacy-data-sha256 "$EXPECTED_LEGACY_DATA_SHA256" \
                --reviewed-commit "$REVIEWED_COMMIT"
        fi
        exec env PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" \
            remove-stale-maintenance-marker \
            --marker "$MAINTENANCE_MARKER" --released-marker "$released_marker" \
            --binding "$RECOVERY_BINDING" \
            --verified-backup "$DATABASE_BACKUP" \
            --environment-backup "$ENV_BACKUP" \
            --pre-activation-manifest "$BACKUP_DIR/pre-activation-manifest.json" \
            --database-verification "$BACKUP_DIR/database-backup-verification.json" \
            --current-database "$LIVE_DATABASE" --env-file "$ENV_FILE" \
            --release-intent "$SERVICE_RELEASE_INTENT_FILE" \
            --expected-legacy-data-sha256 "$EXPECTED_LEGACY_DATA_SHA256" \
            --reviewed-commit "$REVIEWED_COMMIT" --allow-activated-current \
            --csv "$CSV_FILE" --pictures "$PICTURE_DIR" \
            --expected-catalog-fingerprint "$EXPECTED_CATALOG_FINGERPRINT" \
            --expected-paintings "$EXPECTED_PAINTINGS" \
            --expected-image-files "$EXPECTED_IMAGE_FILES" \
            --expected-catalog-assets "$EXPECTED_CATALOG_ASSETS" \
            --expected-missing-images "$EXPECTED_MISSING_IMAGES" \
            --expected-orphan-images "$EXPECTED_ORPHAN_IMAGES" \
            --expected-generated-text "$EXPECTED_GENERATED_TEXT" \
            --expected-music-scene "$EXPECTED_MUSIC_SCENE" \
            --expected-gallery-visible "$EXPECTED_GALLERY_VISIBLE"
    ) >/dev/null &
    local release_pid=$!
    RELEASE_CHILD="$release_pid"
    RELEASE_CHILD_START="$(awk '{print $22}' "/proc/$release_pid/stat" 2>/dev/null || true)"
    if [[ ! "$RELEASE_CHILD_START" =~ ^[0-9]+$ ]]; then
        kill -TERM "$release_pid" 2>/dev/null || true
        wait "$release_pid" 2>/dev/null || true
        RELEASE_CHILD=""
        RELEASE_CHILD_START=""
        rm -f -- "$release_gate"
        return 1
    fi
    if [[ -n "$SERVICE_MONITOR_CHILD" ]]; then
        set_service_allowance "$release_pid" 0
    fi
    : > "$release_gate"
    local release_status=0 wait_status=0 child_reaped=0
    while process_identity_matches "$release_pid" "$RELEASE_CHILD_START"; do
        wait "$release_pid" || wait_status=$?
        if (( wait_status == 129 || wait_status == 130 || wait_status == 143 )); then
            if [[ -e "$SERVICE_VIOLATION_FILE" ]]; then
                stop_owned_process "$release_pid" "$RELEASE_CHILD_START" "RELEASE"
                release_status=1
                child_reaped=1
                break
            fi
            wait_status=0
            continue
        fi
        release_status=$wait_status
        child_reaped=1
        break
    done
    if (( child_reaped == 0 )); then
        wait "$release_pid" || release_status=$?
    fi
    RELEASE_CHILD=""
    RELEASE_CHILD_START=""
    rm -f -- "$release_gate"
    if [[ -e "$SERVICE_VIOLATION_FILE" ]]; then
        release_status=1
    fi
    if (( release_status == 0 )); then
        # The helper creates verified release intent immediately before its
        # atomic unlink. Join the watchdog's natural clean exit and reject any
        # late violation before disarming rollback or forgetting the nonce.
        if [[ -n "$SERVICE_MONITOR_CHILD" ]]; then
            wait "$SERVICE_MONITOR_CHILD" 2>/dev/null || release_status=1
            SERVICE_MONITOR_CHILD=""
            SERVICE_MONITOR_CHILD_START=""
        fi
        [[ ! -e "$SERVICE_VIOLATION_FILE" ]] || release_status=1
    fi
    if (( release_status == 0 )); then
        MAINTENANCE_HELD=0
        log_event "BACKEND_STARTUP_MAINTENANCE_LEASE_RELEASED"
        # Keep release intent until the monitor has observed the intentional
        # marker removal or has been joined by the coordinator.
        return 0
    fi
    # A helper can fail after durably detaching the global marker. Preserve its
    # verified release intent until the exact bound marker is proved present
    # again; otherwise a SIGKILL here would strand an orphan fence without the
    # evidence required by crash recovery.
    local fence_reestablished=0
    if (( MAINTENANCE_HELD == 1 )); then
        if ensure_rollback_maintenance_fence; then
            fence_reestablished=1
        fi
    fi
    if (( fence_reestablished == 1 )); then
        rm -f -- "$SERVICE_RELEASE_INTENT_FILE"
    fi
    log_event "BACKEND_STARTUP_MAINTENANCE_LEASE_RELEASE_FAILED"
    return 1
}

handle_signal() {
    local signal_name="$1"
    case "$signal_name" in
        INT) SIGNAL_EXIT_STATUS=130 ;;
        TERM) SIGNAL_EXIT_STATUS=143 ;;
        HUP) SIGNAL_EXIT_STATUS=129 ;;
    esac
    exit "$SIGNAL_EXIT_STATUS"
}

rollback_on_failure() {
    local exit_status=$?
    trap - EXIT INT HUP
    # TERM from the rollback watchdog is recorded, but the verified restore is
    # allowed to finish behind the fence that the watchdog re-establishes.
    trap 'SIGNAL_EXIT_STATUS=143' TERM
    set +e
    stop_owned_process "$SMOKE_CHILD" "$SMOKE_CHILD_START" "SMOKE"
    stop_owned_process "$ACTIVATION_CHILD" "$ACTIVATION_CHILD_START" "ACTIVATION"
    stop_owned_process "$RELEASE_CHILD" "$RELEASE_CHILD_START" "RELEASE"
    if [[ -n "$SERVICE_ALLOWANCE_FILE" && -d "$BACKUP_DIR" ]]; then
        set_service_allowance "" 0
    fi
    local safe_to_release_maintenance=1
    if (( exit_status != 0 && ROLLBACK_ARMED == 1 )); then
        log_event "ACTIVATION_FAILED_ROLLBACK_STARTING"
        if ! ensure_rollback_maintenance_fence; then
            log_event "ROLLBACK_BLOCKED_MAINTENANCE_FENCE_UNAVAILABLE"
            stop_service_exclusion_monitor
            cleanup_build_root
            log_event "BACKEND_STARTUP_KERNEL_GATE_RETAINED_FOR_OPERATOR_RECOVERY"
            exit "$exit_status"
        fi
        if ! service_down_guard; then
            log_event "ROLLBACK_BLOCKED_SERVICE_RUNNING"
            stop_service_exclusion_monitor
            cleanup_build_root
            log_event "BACKEND_STARTUP_KERNEL_GATE_RETAINED_FOR_OPERATOR_RECOVERY"
            exit "$exit_status"
        fi
        local snapshot_preserved=0
        if run_database_tool env PYTHONDONTWRITEBYTECODE=1 python3 \
            "$ACTIVE_HELPER" preserve-failed \
            --database "$LIVE_DATABASE" \
                --destination-prefix "$BACKUP_DIR/failed-partial.db" >/dev/null; then
            snapshot_preserved=1
            log_event "FAILED_PARTIAL_DATABASE_SNAPSHOT_PRESERVED"
        else
            log_event "FAILED_PARTIAL_DATABASE_SNAPSHOT_PRESERVATION_FAILED"
        fi
        if run_database_tool env PYTHONDONTWRITEBYTECODE=1 python3 \
                "$ACTIVE_HELPER" restore-db \
                --backup "$DATABASE_BACKUP" --database "$LIVE_DATABASE" >/dev/null \
            && PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" restore-env \
                --backup "$ENV_BACKUP" --env-file "$ENV_FILE" >/dev/null \
            && run_database_tool env PYTHONDONTWRITEBYTECODE=1 python3 \
                "$ACTIVE_HELPER" verify-inherited \
                --database "$LIVE_DATABASE" \
                --expected-legacy-data-sha256 "$EXPECTED_LEGACY_DATA_SHA256" >/dev/null \
            && cmp -s "$ENV_FILE" "$ENV_BACKUP" \
            && service_down_guard >/dev/null; then
            if (( snapshot_preserved == 1 )); then
                log_event "ROLLBACK_COMPLETED"
            else
                log_event "ROLLBACK_COMPLETED_FAILED_SNAPSHOT_UNAVAILABLE"
            fi
        else
            log_event "ROLLBACK_FAILED_OPERATOR_ACTION_REQUIRED"
            safe_to_release_maintenance=0
        fi
    fi
    if (( safe_to_release_maintenance == 1 )); then
        if [[ -n "$SERVICE_MONITOR_CHILD" && ! -e "$SERVICE_VIOLATION_FILE" ]]; then
            assert_service_monitor_healthy || safe_to_release_maintenance=0
        fi
    fi
    if (( safe_to_release_maintenance == 1 )); then
        if ! release_maintenance_marker inherited; then
            safe_to_release_maintenance=0
        fi
    fi
    stop_service_exclusion_monitor
    if (( safe_to_release_maintenance == 1 )); then
        if ! stop_startup_gate; then
            safe_to_release_maintenance=0
            log_event "BACKEND_STARTUP_KERNEL_GATE_RETAINED_FOR_OPERATOR_RECOVERY"
        fi
    else
        log_event "BACKEND_STARTUP_KERNEL_GATE_RETAINED_FOR_OPERATOR_RECOVERY"
    fi
    cleanup_build_root
    exit "$exit_status"
}
trap rollback_on_failure EXIT
trap 'handle_signal INT' INT
trap 'handle_signal TERM' TERM
trap 'handle_signal HUP' HUP

verify_server_local_root
verify_required_files
verify_commit
service_down_guard
verify_free_space

STATE_JSON="$(inspect_activation_state)"
STATE="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["state"])' <<<"$STATE_JSON")"
case "$STATE" in
    INHERITED_READY|ACTIVATED_CANDIDATE) ;;
    *) fail "database state is not safe for activation: $STATE" ;;
esac
printf 'DATABASE_PREFLIGHT_STATE=%s\n' "$STATE"
if [[ "$STATE" == "ACTIVATED_CANDIDATE" ]]; then
    verify_activated_operational_fences
    PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" verify-activation-env \
        --env-file "$ENV_FILE" >/dev/null \
        || fail "activated database exists but normal startup settings are not healthy"
    printf '%s\n' "ALREADY_ACTIVATED_AND_HEALTHY"
    printf '%s\n' "ALREADY_ACTIVATED_VERIFICATION_ONLY_NO_MUTATION"
    ROLLBACK_ARMED=0
    exit 0
fi

if [[ "$MODE" == "dry-run" ]]; then
    CATALOG_PROFILE="$(PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" catalog-profile \
        --csv "$CSV_FILE" --pictures "$PICTURE_DIR")"
    printf 'CATALOG_READ_ONLY_PROFILE=%s\n' "$CATALOG_PROFILE"
    printf 'INTENDED_BACKUP_DIRECTORY=%s/<timestamp>\n' "$BACKUP_ROOT"
    printf '%s\n' \
        "INTENDED_PHASE=private verified database and .env backup" \
        "INTENDED_PHASE=explicit Flyway version-1 baseline" \
        "INTENDED_PHASE=V2 migrate, validate, and idempotence check" \
        "INTENDED_PHASE=manual catalog import and unchanged reimport" \
        "INTENDED_PHASE=activation verification and safe .env update" \
        "INTENDED_PHASE=optional owned-process local smoke test" \
        "DRY_RUN_ZERO_MUTATION" \
        "DRY_RUN_OK"
    ROLLBACK_ARMED=0
    exit 0
fi

[[ "${AURALINK_ROUND51_CONFIRM:-}" == "$CONFIRMATION_TOKEN" ]] \
    || fail "AURALINK_ROUND51_CONFIRM confirmation token is required"
command -v mvn >/dev/null || fail "Maven is required"
command -v cmp >/dev/null || fail "cmp is required for private .env backup verification"
command -v tar >/dev/null || fail "tar is required for the reviewed source snapshot"
command -v mktemp >/dev/null || fail "mktemp is required for the reviewed source snapshot"
if (( RUN_SMOKE == 1 )); then
    command -v curl >/dev/null || fail "curl is required for --smoke"
fi

if [[ -e "$BACKUP_ROOT" || -L "$BACKUP_ROOT" ]]; then
    [[ -d "$BACKUP_ROOT" && ! -L "$BACKUP_ROOT" ]] \
        || fail "activation backup root must be a non-symlink directory"
else
    mkdir -m 700 "$BACKUP_ROOT"
    PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" fsync-dir \
        --directory "$(dirname -- "$BACKUP_ROOT")" >/dev/null
fi
chmod 700 "$BACKUP_ROOT"
[[ "$(readlink -f -- "$BACKUP_ROOT")" == "$BACKUP_ROOT" ]] \
    || fail "activation backup root resolves unexpectedly"
[[ "$(stat -c %u "$BACKUP_ROOT")" == "$(id -u)" ]] \
    || fail "activation backup root is not owned by the current operator"
# Every activation performs the parent-directory durability barrier, including
# when a reviewed Spring JVM pre-provisioned the root before this shell began.
PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" fsync-dir \
    --directory "$(dirname -- "$BACKUP_ROOT")" >/dev/null
# Lock the already-verified directory inode. Do not create/truncate a child lock
# path that an attacker or stale run could have replaced with a symlink.
exec 9< "$BACKUP_ROOT"
flock -n 9 || fail "another Round 5.1 activation process holds the lock"
refuse_preexisting_orphan_fences
start_startup_gate
service_down_guard

log_event "OFFLINE_APPLICATION_PACKAGE_STARTING"
BUILD_ROOT="$(mktemp -d /tmp/auralink-round51-build.XXXXXX)"
chmod 700 "$BUILD_ROOT"
git -C "$PROJECT_ROOT" archive "$REVIEWED_COMMIT" -- backend \
    | tar -x -C "$BUILD_ROOT"
ACTIVE_HELPER="$BUILD_ROOT/backend/scripts/round51_state.py"
verify_regular_file "$ACTIVE_HELPER" "reviewed Round 5.1 state helper"
verify_commit
service_down_guard
(
    cd "$BUILD_ROOT/backend"
    mvn -o -Dmaven.repo.local="$PROJECT_ROOT/.m2/repository" -DskipTests package
)
PACKAGED_JAR="$BUILD_ROOT/backend/target/$JAR_NAME"
verify_regular_file "$PACKAGED_JAR" "packaged reviewed backend application"
verify_commit
service_down_guard
verify_free_space
STATE_AFTER_PACKAGE="$(inspect_activation_state)"
[[ "$STATE_AFTER_PACKAGE" == "$STATE_JSON" ]] \
    || fail "database/catalog preflight changed while the offline package was built"
log_event "OFFLINE_APPLICATION_PACKAGE_READY"

BACKUP_DIR="$BACKUP_ROOT/$(date -u +%Y%m%dT%H%M%SZ)-$$"
mkdir -m 700 "$BACKUP_DIR"
PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" fsync-dir \
    --directory "$BACKUP_ROOT" >/dev/null
readonly BACKUP_DIR
SERVICE_RELEASE_INTENT_FILE="$BACKUP_DIR/.round51-service-release-intent"
DATABASE_BACKUP="$BACKUP_DIR/auralink.pre-activation.db"
ENV_BACKUP="$BACKUP_DIR/backend.env.pre-activation"
RECOVERY_BINDING="$BACKUP_DIR/round51-recovery-binding.json"
readonly DATABASE_BACKUP ENV_BACKUP RECOVERY_BINDING
: > "$BACKUP_DIR/activation.log"
chmod 600 "$BACKUP_DIR/activation.log"

PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" manifest \
    --database "$LIVE_DATABASE" --destination "$BACKUP_DIR/pre-activation-manifest.json" \
    --phase pre-activation >/dev/null
PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" backup-db \
    --source "$LIVE_DATABASE" --destination "$DATABASE_BACKUP" \
    > "$BACKUP_DIR/database-backup-result.json"
PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" backup-env \
    --source "$ENV_FILE" --destination "$ENV_BACKUP" >/dev/null
cmp -s "$ENV_FILE" "$ENV_BACKUP" \
    || fail "private backend/.env backup does not match the source"
PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" verify-backup \
    --source "$LIVE_DATABASE" --database "$DATABASE_BACKUP" \
    > "$BACKUP_DIR/database-backup-verification.json"
chmod 600 "$BACKUP_DIR/database-backup-result.json" \
    "$BACKUP_DIR/database-backup-verification.json"
PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" fsync-file \
    --file "$BACKUP_DIR/database-backup-result.json" >/dev/null
PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" fsync-file \
    --file "$BACKUP_DIR/database-backup-verification.json" >/dev/null
log_event "PRIVATE_BACKUPS_VERIFIED directory=$BACKUP_DIR"
PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" fsync-dir \
    --directory "$BACKUP_DIR" >/dev/null
PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" fsync-dir \
    --directory "$BACKUP_ROOT" >/dev/null

MAINTENANCE_TOKEN="$(PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" \
    create-recovery-binding \
    --marker "$MAINTENANCE_MARKER" \
    --binding "$RECOVERY_BINDING" \
    --database-backup "$DATABASE_BACKUP" \
    --environment-backup "$ENV_BACKUP" \
    --pre-activation-manifest "$BACKUP_DIR/pre-activation-manifest.json" \
    --database-verification "$BACKUP_DIR/database-backup-verification.json" \
    --reviewed-commit "$REVIEWED_COMMIT")"
[[ "$MAINTENANCE_TOKEN" =~ ^[0-9a-f]{64}$ ]] \
    || fail "maintenance ownership token generation failed"
MAINTENANCE_HELD=1
log_event "BACKEND_STARTUP_MAINTENANCE_LEASE_ACQUIRED"
arm_startup_gate_orphan_fence
service_down_guard
verify_commit
cmp -s "$ENV_FILE" "$ENV_BACKUP" \
    || fail "backend/.env changed after its private backup"
STATE_BEFORE_MUTATION="$(inspect_activation_state)"
[[ "$STATE_BEFORE_MUTATION" == "$STATE_JSON" ]] \
    || fail "database/catalog preflight changed before mutation was armed"
ROLLBACK_ARMED=1
SERVICE_ALLOWANCE_FILE="$BACKUP_DIR/.round51-service-allowance"
SERVICE_VIOLATION_FILE="$BACKUP_DIR/.round51-service-violation"
SERVICE_VIOLATION_REASON_FILE="$BACKUP_DIR/.round51-service-violation-reason"
set_service_allowance "" 0
start_service_exclusion_monitor
assert_service_monitor_healthy

log_event "CONTROLLED_JAVA_ACTIVATION_STARTING"
JAVA_LOG="$BACKUP_DIR/java-activation.log"
ACTIVATION_GATE="$BACKUP_DIR/.round51-activation-go"
(
    exec 9>&-
    while [[ ! -f "$ACTIVATION_GATE" ]]; do
        /bin/sleep 0.05
    done
    cd "$PROJECT_ROOT"
    exec env \
        AURALINK_DATABASE_URL="jdbc:sqlite:$LIVE_DATABASE" \
        AURALINK_ENV_FILE="$ENV_FILE" \
        AURALINK_SERVER_LOCAL_ROOT="$SERVER_LOCAL_ROOT" \
        AURALINK_FLYWAY_ENABLED=false \
        AURALINK_JPA_DDL_AUTO=none \
        AURALINK_PAINTING_CATALOG_IMPORT_ENABLED=false \
        AURALINK_ROUND51_MAINTENANCE_TOKEN="$MAINTENANCE_TOKEN" \
        AURALINK_PAINTING_CSV_PATH="$CSV_FILE" \
        AURALINK_PAINTING_PICTURE_DIR="$PICTURE_DIR" \
        AURALINK_ROUND51_CONFIRM="$CONFIRMATION_TOKEN" \
        SERVER_PORT="$(configured_port)" \
        java -Dloader.main="$JAVA_MAIN" \
        -cp "$PACKAGED_JAR" \
        org.springframework.boot.loader.launch.PropertiesLauncher \
        --project-root="$PROJECT_ROOT"
) > "$JAVA_LOG" 2>&1 &
ACTIVATION_CHILD=$!
ACTIVATION_CHILD_START="$(awk '{print $22}' "/proc/$ACTIVATION_CHILD/stat" 2>/dev/null || true)"
if [[ ! "$ACTIVATION_CHILD_START" =~ ^[0-9]+$ ]]; then
    kill -TERM "$ACTIVATION_CHILD" 2>/dev/null || true
    wait "$ACTIVATION_CHILD" 2>/dev/null || true
    ACTIVATION_CHILD=""
    rm -f -- "$ACTIVATION_GATE"
    fail "owned activation process identity could not be recorded"
fi
set_service_allowance "$ACTIVATION_CHILD" 0
: > "$ACTIVATION_GATE"
while process_identity_matches "$ACTIVATION_CHILD" "$ACTIVATION_CHILD_START"; do
    sleep 30
    if process_identity_matches "$ACTIVATION_CHILD" "$ACTIVATION_CHILD_START"; then
        assert_service_monitor_healthy
        log_event "CONTROLLED_JAVA_ACTIVATION_STILL_RUNNING pid=$ACTIVATION_CHILD"
    fi
done
activation_exit_code=0
if wait "$ACTIVATION_CHILD"; then
    activation_exit_code=0
else
    activation_exit_code=$?
fi
ACTIVATION_CHILD=""
ACTIVATION_CHILD_START=""
set_service_allowance "" 0
rm -f -- "$ACTIVATION_GATE"
assert_service_monitor_healthy
chmod 600 "$JAVA_LOG"
if (( activation_exit_code != 0 )); then
    activation_error_class="$(
        grep -E '^ROUND51_ACTIVATION_ERROR_CLASS=[A-Z][A-Z0-9_]{0,79}$' "$JAVA_LOG" \
            | tail -n 1 | cut -d= -f2- || true
    )"
    if [[ ! "$activation_error_class" =~ ^[A-Z][A-Z0-9_]{0,79}$ ]]; then
        activation_error_class="JAVA_ACTIVATION_PROCESS_FAILED"
    fi
    case "$activation_error_class" in
        ACTIVATION_CONTEXT_INITIALIZATION_FAILED)
            activation_error_summary="Dedicated non-web activation context could not be initialized"
            ;;
        ACTIVATION_PREFLIGHT_IO_FAILURE)
            activation_error_summary="Activation preflight could not verify required local resources"
            ;;
        ACTIVATION_PREFLIGHT_RUNTIME_FAILURE)
            activation_error_summary="Activation preflight could not verify required local resources"
            ;;
        ACTIVATION_EXECUTION_FAILED)
            activation_error_summary="Controlled activation execution failed"
            ;;
        JAVA_ACTIVATION_PROCESS_FAILED)
            activation_error_summary="Controlled Java activation process exited unsuccessfully"
            ;;
        *)
            activation_error_summary="Controlled activation safety check failed"
            ;;
    esac
    log_event "ROUND51_ACTIVATION_ERROR_CLASS=$activation_error_class"
    log_event "ROUND51_ACTIVATION_ERROR_SUMMARY=$activation_error_summary"
    fail "controlled Java activation process failed"
fi
if ! grep -Eq '^(ROUND51_ACTIVATION_COMPLETED|ALREADY_ACTIVATED_AND_HEALTHY)$' "$JAVA_LOG"; then
    fail "Java activation runner did not emit a success marker"
fi
log_event "CONTROLLED_JAVA_ACTIVATION_VERIFIED"

PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" update-env --env-file "$ENV_FILE" >/dev/null
PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" verify-activation-env \
    --env-file "$ENV_FILE" >/dev/null
log_event "NORMAL_STARTUP_CATALOG_SYNCHRONIZATION_ENABLED"

run_smoke_test() {
    local port
    port="$(configured_port)"
    local smoke_log="$BACKUP_DIR/backend-smoke.log"
    local health_json="$BACKUP_DIR/smoke-health.json"
    local gallery_json="$BACKUP_DIR/smoke-gallery.json"
    local daily_json="$BACKUP_DIR/smoke-daily.json"
    local smoke_gate="$BACKUP_DIR/.round51-smoke-go"
    (
        exec 9>&-
        while [[ ! -f "$smoke_gate" ]]; do
            /bin/sleep 0.05
        done
        cd "$BACKEND_ROOT"
        exec env \
        AURALINK_DATABASE_URL="jdbc:sqlite:$LIVE_DATABASE" \
        AURALINK_ENV_FILE="$ENV_FILE" \
        AURALINK_SERVER_LOCAL_ROOT="$SERVER_LOCAL_ROOT" \
            AURALINK_FLYWAY_ENABLED=false \
            AURALINK_JPA_DDL_AUTO=none \
            AURALINK_PAINTING_CATALOG_IMPORT_ENABLED=true \
            AURALINK_PAINTING_CATALOG_IMPORT_FAIL_ON_ERROR=true \
            AURALINK_PAINTING_CSV_PATH="$CSV_FILE" \
            AURALINK_PAINTING_PICTURE_DIR="$PICTURE_DIR" \
            AURALINK_ROUND51_MAINTENANCE_TOKEN="$MAINTENANCE_TOKEN" \
            SERVER_PORT="$port" \
            java -jar "$PACKAGED_JAR" --server.address=127.0.0.1
    ) > "$smoke_log" 2>&1 &
    SMOKE_CHILD=$!
    SMOKE_CHILD_START="$(awk '{print $22}' "/proc/$SMOKE_CHILD/stat" 2>/dev/null || true)"
    if [[ ! "$SMOKE_CHILD_START" =~ ^[0-9]+$ ]]; then
        kill -TERM "$SMOKE_CHILD" 2>/dev/null || true
        wait "$SMOKE_CHILD" 2>/dev/null || true
        SMOKE_CHILD=""
        rm -f -- "$smoke_gate"
        fail "owned smoke process identity could not be recorded"
    fi
    set_service_allowance "$SMOKE_CHILD" 1
    : > "$smoke_gate"
    log_event "OWNED_SMOKE_PROCESS_STARTED pid=$SMOKE_CHILD"
    local ready=0 attempt
    for attempt in {1..240}; do
        if ! process_identity_matches "$SMOKE_CHILD" "$SMOKE_CHILD_START"; then
            break
        fi
        assert_service_monitor_healthy
        if curl --noproxy '*' --fail --silent --show-error --max-time 5 \
                "http://127.0.0.1:$port/api/health" -o "$health_json"; then
            ready=1
            break
        fi
        sleep 0.5
    done
    (( ready == 1 )) || fail "smoke backend did not become healthy"
    curl --noproxy '*' --fail --silent --show-error --max-time 10 \
        "http://127.0.0.1:$port/health" >/dev/null
    curl --noproxy '*' --fail --silent --show-error --max-time 10 \
        "http://127.0.0.1:$port/api/v1/paintings?page=0&size=1" -o "$gallery_json"
    curl --noproxy '*' --fail --silent --show-error --max-time 10 \
        "http://127.0.0.1:$port/api/v1/paintings/daily" -o "$daily_json"
    PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" validate-smoke \
        --health "$health_json" --gallery "$gallery_json" --daily "$daily_json" >/dev/null
    grep -q 'status=SKIPPED' "$smoke_log" \
        || fail "normal startup did not report an unchanged catalog SKIPPED result"
    stop_owned_process "$SMOKE_CHILD" "$SMOKE_CHILD_START" "SMOKE"
    SMOKE_CHILD=""
    SMOKE_CHILD_START=""
    set_service_allowance "" 0
    rm -f -- "$smoke_gate"
    assert_service_monitor_healthy
    service_down_guard
    log_event "LOCAL_SMOKE_TEST_VERIFIED"
}

if (( RUN_SMOKE == 1 )); then
    run_smoke_test
else
    log_event "LOCAL_SMOKE_TEST_NOT_REQUESTED"
fi

verify_activated_state > "$BACKUP_DIR/final-activation-verification.json"
chmod 600 "$BACKUP_DIR/final-activation-verification.json"
PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" fsync-file \
    --file "$BACKUP_DIR/final-activation-verification.json" >/dev/null
assert_service_monitor_healthy
service_down_guard
log_event "FINAL_ACTIVATED_STATE_VERIFIED"

run_database_tool env PYTHONDONTWRITEBYTECODE=1 python3 "$ACTIVE_HELPER" manifest \
    --database "$LIVE_DATABASE" --destination "$BACKUP_DIR/post-activation-manifest.json" \
    --phase post-activation >/dev/null
log_event "POST_ACTIVATION_MANIFEST_VERIFIED"
assert_service_monitor_healthy
service_down_guard
release_maintenance_marker activated \
    || fail "could not release the backend startup maintenance lease"
stop_service_exclusion_monitor
stop_startup_gate \
    || fail "startup fence retirement could not be made durable"
ROLLBACK_ARMED=0
cleanup_build_root
if grep -q '^ALREADY_ACTIVATED_AND_HEALTHY$' "$JAVA_LOG"; then
    log_event "ALREADY_ACTIVATED_AND_HEALTHY"
else
    log_event "ROUND51_ACTIVATION_COMPLETED"
fi
exit 0
