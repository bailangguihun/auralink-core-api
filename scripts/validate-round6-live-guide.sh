#!/usr/bin/env bash
# One-time, server-local validation of one cached AI Painting Guide.
#
# This script is intentionally pinned to the real AutoDL checkout.  It has no
# project-root override: tests exercise its Python state helper with /tmp data,
# while an operator may run this coordinator only from SERVER_LOCAL_ROOT.

set -Eeuo pipefail
set +x
IFS=$'\n\t'
umask 077

readonly SERVER_LOCAL_ROOT="/root/autodl-tmp/auralink"
readonly BACKUP_ROOT="/root/auralink_guide_validation_backups"
readonly GUIDE_RUNTIME_DIR="/root/auralink_runtime/guide-service"
readonly GUIDE_STATE_FILE="$GUIDE_RUNTIME_DIR/guide-service.state"
readonly GUIDE_RUNTIME_LOG="$GUIDE_RUNTIME_DIR/guide-service.log"
readonly GUIDE_PORT=5003
readonly SPRING_PORT=5000
readonly CONFIRMATION_TOKEN="GENERATE_ONE_LIVE_PAINTING_GUIDE"
readonly JAR_NAME="auralink-backend-0.0.1-SNAPSHOT.jar"

MODE=""
REQUESTED_PAINTING_ID=""
REVIEWED_COMMIT=""
BASELINE_GUIDES=""
SELECTED_PAINTING_ID=""
BACKUP_DIR=""
DATABASE_BACKUP=""
ENV_BACKUP=""
BASELINE_STATE_FILE=""
ROLLBACK_ARMED=0
VALIDATION_SUCCEEDED=0
CURRENT_ERROR_CODE=""
CURRENT_ERROR_SUMMARY=""
SPRING_PID=""
SPRING_START_TIME=""
GUIDE_PID=""
GUIDE_START_TIME=""
GUIDE_OWNED=0
GUIDE_LOG_INITIAL_SIZE=0
GUIDE_START_FLOOR=0
GUIDE_START_EPOCH=0
RUNTIME_EVIDENCE_SANITIZED=0
AUTH_DIR=""
LOGIN_RESPONSE=""
JWT_FILE=""
AUTH_CONFIG=""
CANDIDATE_JSON=""

usage() {
    printf '%s\n' \
        "Usage: backend/scripts/validate-round6-live-guide.sh --dry-run [--painting-id=<UUID>]" \
        "       backend/scripts/validate-round6-live-guide.sh --validate [--painting-id=<UUID>]"
}

log_event() {
    local message="$1"
    printf '%s\n' "$message"
    if [[ -n "$BACKUP_DIR" && -d "$BACKUP_DIR" ]]; then
        printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$message" \
            >> "$BACKUP_DIR/validation.log"
    fi
}

fail() {
    CURRENT_ERROR_CODE="$1"
    CURRENT_ERROR_SUMMARY="$2"
    printf 'ROUND61_VALIDATION_ERROR_CODE=%s\n' "$CURRENT_ERROR_CODE" >&2
    printf 'ROUND61_VALIDATION_ERROR_SUMMARY=%s\n' "$CURRENT_ERROR_SUMMARY" >&2
    exit 1
}

for argument in "$@"; do
    case "$argument" in
        --dry-run)
            [[ -z "$MODE" ]] \
                || fail "INVALID_ARGUMENTS" "Choose exactly one validation mode"
            MODE="dry-run"
            ;;
        --validate)
            [[ -z "$MODE" ]] \
                || fail "INVALID_ARGUMENTS" "Choose exactly one validation mode"
            MODE="validate"
            ;;
        --painting-id=*)
            [[ -z "$REQUESTED_PAINTING_ID" ]] \
                || fail "INVALID_ARGUMENTS" "Painting ID was supplied more than once"
            REQUESTED_PAINTING_ID="${argument#--painting-id=}"
            [[ "$REQUESTED_PAINTING_ID" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$ ]] \
                || fail "INVALID_PAINTING_ID" "The reviewed Painting ID is not a canonical UUID"
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            fail "INVALID_ARGUMENTS" "An unsupported command-line argument was supplied"
            ;;
    esac
done
[[ -n "$MODE" ]] || { usage >&2; exit 2; }

for required_command in python3 git findmnt ss lsof flock curl mvn java stat df cmp readlink \
        ps sort sed awk grep tail find mktemp truncate; do
    command -v "$required_command" >/dev/null \
        || fail "REQUIRED_TOOL_MISSING" "A required local operating-system tool is unavailable"
done

readonly JAVA_BIN="$(command -v java)"
readonly MVN_BIN="$(command -v mvn)"
readonly SAFE_RUNTIME_PATH="$(dirname -- "$JAVA_BIN"):/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

readonly SCRIPT_PATH="$(readlink -f -- "${BASH_SOURCE[0]}")"
readonly SCRIPT_ROOT="$(readlink -f -- "$(dirname -- "$SCRIPT_PATH")/../..")"
readonly WORKING_ROOT="$(pwd -P)"
readonly PROJECT_ROOT="$SERVER_LOCAL_ROOT"
readonly BACKEND_ROOT="$PROJECT_ROOT/backend"
readonly LIVE_DATABASE="$BACKEND_ROOT/auralink.db"
readonly ENV_FILE="$BACKEND_ROOT/.env"
readonly HELPER="$BACKEND_ROOT/scripts/round61_guide_state.py"
readonly GUIDE_LAUNCHER="$BACKEND_ROOT/scripts/start-guide-service.sh"
readonly TOKEN_HELPER="$BACKEND_ROOT/scripts/configure-round6-guide-token.sh"
readonly POM_FILE="$BACKEND_ROOT/pom.xml"
readonly APPLICATION_CONFIG="$BACKEND_ROOT/src/main/resources/application.yml"
readonly GUIDE_PACKAGE="$PROJECT_ROOT/guide_service"
readonly JAR_FILE="$BACKEND_ROOT/target/$JAR_NAME"

helper() {
    PYTHONDONTWRITEBYTECODE=1 python3 "$HELPER" "$@"
}

verify_regular_file() {
    local path="$1" label="$2"
    [[ -f "$path" && ! -L "$path" ]] \
        || fail "REQUIRED_FILE_INVALID" "$label must be a regular non-symlink file"
}

verify_server_local_root() {
    [[ "$SCRIPT_ROOT" == "$SERVER_LOCAL_ROOT" ]] \
        || fail "WRONG_PROJECT_ROOT" "The validator is not installed at the exact server-local root"
    [[ "$WORKING_ROOT" == "$SERVER_LOCAL_ROOT" ]] \
        || fail "WRONG_PROJECT_ROOT" "Run the validator from the exact server-local root"
    [[ "$(readlink -f -- "$PROJECT_ROOT")" == "$SERVER_LOCAL_ROOT" ]] \
        || fail "WRONG_PROJECT_ROOT" "The server-local project root resolves unexpectedly"
    local filesystem_type
    filesystem_type="$(findmnt -n -o FSTYPE -T "$PROJECT_ROOT")" \
        || fail "FILESYSTEM_UNVERIFIED" "The project filesystem type could not be verified"
    case "${filesystem_type,,}" in
        *sshfs*|*fuse*)
            fail "SSHFS_EXECUTION_REFUSED" "Live Guide validation must not run through FUSE or SSHFS"
            ;;
    esac
    printf '%s\n' "SERVER_LOCAL_ROOT_VERIFIED"
}

verify_required_files() {
    verify_regular_file "$LIVE_DATABASE" "backend/auralink.db"
    verify_regular_file "$ENV_FILE" "backend/.env"
    verify_regular_file "$HELPER" "Round 6.1 state helper"
    verify_regular_file "$GUIDE_LAUNCHER" "Guide Service launcher"
    verify_regular_file "$TOKEN_HELPER" "Guide internal-token helper"
    verify_regular_file "$POM_FILE" "backend/pom.xml"
    verify_regular_file "$APPLICATION_CONFIG" "backend application configuration"
    [[ -d "$GUIDE_PACKAGE" && ! -L "$GUIDE_PACKAGE" ]] \
        || fail "REQUIRED_FILE_INVALID" "guide_service must be a non-symlink directory"
}

verify_private_env() {
    local mode owner
    mode="$(stat -c %a -- "$ENV_FILE")"
    owner="$(stat -c %u -- "$ENV_FILE")"
    [[ "$mode" =~ ^[0-7]{3,4}$ ]] \
        || fail "ENV_PERMISSIONS_UNSAFE" "backend/.env permissions could not be verified"
    (( (8#$mode & 077) == 0 )) \
        || fail "ENV_PERMISSIONS_UNSAFE" "backend/.env must not be accessible to group or other users"
    [[ "$owner" == "$(id -u)" ]] \
        || fail "ENV_OWNERSHIP_UNSAFE" "backend/.env is not owned by the current operator"
    [[ "$(git -C "$PROJECT_ROOT" check-ignore -q "$ENV_FILE"; printf '%s' "$?")" == "0" ]] \
        || fail "ENV_TRACKING_UNSAFE" "backend/.env is not ignored by Git"
    [[ -z "$(git -C "$PROJECT_ROOT" ls-files -- "$ENV_FILE")" ]] \
        || fail "ENV_TRACKING_UNSAFE" "backend/.env is tracked by Git"
    printf '%s\n' "PRIVATE_ENV_FILE_VERIFIED"
}

verify_commit() {
    local expected_commit="${AURALINK_ROUND61_EXPECTED_COMMIT:-}"
    [[ "$expected_commit" =~ ^[0-9a-f]{40}$ ]] \
        || fail "REVIEWED_COMMIT_REQUIRED" "AURALINK_ROUND61_EXPECTED_COMMIT must be a reviewed 40-character commit"
    local actual_commit
    actual_commit="$(git -C "$PROJECT_ROOT" rev-parse HEAD)"
    [[ "$actual_commit" == "$expected_commit" ]] \
        || fail "REVIEWED_COMMIT_MISMATCH" "The checked-out commit is not the reviewed validation commit"
    [[ -z "$(git -C "$PROJECT_ROOT" -c core.fsmonitor=false \
        -c core.untrackedCache=false status --porcelain --untracked-files=all)" ]] \
        || fail "DIRTY_WORKTREE" "The reviewed checkout is not clean"
    REVIEWED_COMMIT="$actual_commit"
    printf 'REVIEWED_COMMIT_VERIFIED=%s\n' "$actual_commit"
}

listener_lines() {
    local port="$1"
    ss -H -ltnp 2>/dev/null | awk -v suffix=":$port" '$4 ~ (suffix "$") { print }'
}

require_port_free() {
    local port="$1" label="$2" listeners
    listeners="$(listener_lines "$port")"
    if [[ -n "$listeners" ]]; then
        printf '%s\n' "PORT_OCCUPIED=$port" >&2
        printf '%s\n' "$listeners" >&2
        fail "PORT_OCCUPIED" "$label port must be free before validation"
    fi
}

require_loopback_listener() {
    local port="$1" label="$2" listeners endpoint
    listeners="$(listener_lines "$port")"
    [[ -n "$listeners" ]] \
        || fail "SERVICE_LISTENER_MISSING" "$label did not create its expected loopback listener"
    while IFS= read -r endpoint; do
        [[ -n "$endpoint" ]] || continue
        [[ "$endpoint" == "127.0.0.1:$port" ]] \
            || fail "PUBLIC_LISTENER_REFUSED" "$label attempted to bind outside IPv4 loopback"
    done < <(awk '{print $4}' <<<"$listeners")
    printf '%s_LOOPBACK_LISTENER_VERIFIED=%s\n' "${label^^}" "$port"
}

guide_launcher() {
    (
        cd "$PROJECT_ROOT"
        exec env \
            -u QWEN_API_KEY \
            -u QWEN_BASE_URL \
            -u QWEN_MODEL \
            -u AURALINK_GUIDE_INTERNAL_TOKEN \
            -u AURALINK_GUIDE_SERVICE_HOST \
            -u AURALINK_GUIDE_SERVICE_PORT \
            "$GUIDE_LAUNCHER" "$1"
    )
}

require_services_down() {
    require_port_free "$SPRING_PORT" "Spring backend"
    require_port_free "$GUIDE_PORT" "Guide Service"

    local guide_status
    if guide_status="$(guide_launcher status 2>&1)"; then
        printf '%s\n' "$guide_status" >&2
        fail "GUIDE_SERVICE_ALREADY_RUNNING" "An existing Guide Service must be stopped before validation"
    fi
    [[ "$guide_status" == *"GUIDE_SERVICE_NOT_RUNNING"* ]] \
        || fail "GUIDE_SERVICE_STATE_UNKNOWN" "The existing Guide Service state could not be verified safely"

    local conflicting_pids
    conflicting_pids="$(
        ps -eo pid=,args= | awk '
            /java/ && /auralink-backend-[^ ]*\.jar/ { print $1 }
            /python/ && /-m guide_service\.app/ { print $1 }
        ' | sort -u
    )"
    if [[ -n "$conflicting_pids" ]]; then
        printf 'CONFLICTING_PROCESS_PIDS=%s\n' "$(tr '\n' ',' <<<"$conflicting_pids" | sed 's/,$//')" >&2
        fail "CONFLICTING_PROCESS" "An Auralink backend or Guide Service process is already running"
    fi

    local database_users
    database_users="$(lsof -t -- "$LIVE_DATABASE" 2>/dev/null | sort -u || true)"
    if [[ -n "$database_users" ]]; then
        printf 'DATABASE_USER_PIDS=%s\n' "$(tr '\n' ',' <<<"$database_users" | sed 's/,$//')" >&2
        fail "PRODUCTION_DATABASE_IN_USE" "The production database is open in another process"
    fi
    printf '%s\n' "SERVICE_DOWN_PREFLIGHT_VERIFIED"
}

verify_only_owned_database_user() {
    local database_users pid owned_spring_open=0
    database_users="$(lsof -t -- "$LIVE_DATABASE" 2>/dev/null | sort -u || true)"
    while IFS= read -r pid; do
        [[ -n "$pid" ]] || continue
        if [[ -n "$SPRING_PID" && "$pid" == "$SPRING_PID" ]]; then
            owned_spring_open=1
        else
            fail "UNEXPECTED_DATABASE_USER" "An unowned process opened the production database"
        fi
    done <<<"$database_users"
    (( owned_spring_open == 1 )) \
        || fail "SPRING_DATABASE_NOT_OPEN" "The owned Spring backend did not open the exact production database"
    printf '%s\n' "OWNED_SPRING_DATABASE_HANDLE_VERIFIED"
}

verify_existing_backup_root() {
    [[ -e "$BACKUP_ROOT" || -L "$BACKUP_ROOT" ]] || {
        printf '%s\n' "BACKUP_ROOT_PREFLIGHT=ABSENT_WILL_CREATE_PRIVATELY"
        return 0
    }
    [[ -d "$BACKUP_ROOT" && ! -L "$BACKUP_ROOT" ]] \
        || fail "BACKUP_ROOT_UNSAFE" "The private backup root is not a regular non-symlink directory"
    [[ "$(readlink -f -- "$BACKUP_ROOT")" == "$BACKUP_ROOT" ]] \
        || fail "BACKUP_ROOT_UNSAFE" "The private backup root resolves unexpectedly"
    [[ "$(stat -c %u -- "$BACKUP_ROOT")" == "$(id -u)" ]] \
        || fail "BACKUP_ROOT_UNSAFE" "The private backup root is not owned by the operator"
    local mode
    mode="$(stat -c %a -- "$BACKUP_ROOT")"
    [[ "$mode" =~ ^[0-7]{3,4}$ ]] \
        || fail "BACKUP_ROOT_UNSAFE" "The private backup root permissions could not be verified"
    (( (8#$mode & 077) == 0 && (8#$mode & 0700) == 0700 )) \
        || fail "BACKUP_ROOT_UNSAFE" "The private backup root must be accessible only by its owner"
    printf '%s\n' "EXISTING_BACKUP_ROOT_PRIVATE_VERIFIED"
}

verify_free_space() {
    local database_bytes available_kib available_bytes required_bytes space_target
    database_bytes="$(stat -c %s -- "$LIVE_DATABASE")"
    if [[ -d "$BACKUP_ROOT" && ! -L "$BACKUP_ROOT" ]]; then
        space_target="$BACKUP_ROOT"
    else
        space_target="$(dirname -- "$BACKUP_ROOT")"
    fi
    available_kib="$(df -Pk -- "$space_target" | awk 'NR==2 {print $4}')"
    [[ "$database_bytes" =~ ^[0-9]+$ && "$available_kib" =~ ^[0-9]+$ ]] \
        || fail "DISK_SPACE_UNVERIFIED" "Backup disk capacity could not be measured"
    available_bytes=$(( available_kib * 1024 ))
    required_bytes=$(( database_bytes * 3 + 536870912 ))
    (( available_bytes >= required_bytes )) \
        || fail "INSUFFICIENT_BACKUP_SPACE" "Insufficient private backup capacity for validation and rollback"
    printf 'BACKUP_SPACE_VERIFIED_BYTES=%s\n' "$available_bytes"
}

json_number() {
    local document="$1" key="$2"
    printf '%s' "$document" | python3 -c \
        'import functools,json,operator,sys; v=functools.reduce(operator.getitem,sys.argv[1].split("."),json.load(sys.stdin)); assert isinstance(v,int) and not isinstance(v,bool); print(v)' \
        "$key"
}

json_string() {
    local document="$1" key="$2"
    printf '%s' "$document" | python3 -c \
        'import functools,json,operator,sys; v=functools.reduce(operator.getitem,sys.argv[1].split("."),json.load(sys.stdin)); assert isinstance(v,str) and v; print(v)' \
        "$key"
}

run_read_only_preflight() {
    local env_profile knowledge_profile database_profile candidate_args=()
    env_profile="$(helper preflight-env --env-file "$ENV_FILE")" \
        || fail "GUIDE_CONFIGURATION_INVALID" "Guide provider configuration preflight failed"
    printf 'GUIDE_CONFIGURATION_PREFLIGHT=%s\n' "$env_profile"

    knowledge_profile="$(helper preflight-knowledge --project-root "$PROJECT_ROOT")" \
        || fail "GUIDE_KNOWLEDGE_INVALID" "Reviewed static Guide knowledge failed read-only verification"
    printf 'GUIDE_KNOWLEDGE_PREFLIGHT=%s\n' "$knowledge_profile"

    database_profile="$(helper inspect-db --database "$LIVE_DATABASE")" \
        || fail "PRODUCTION_DATABASE_INVALID" "The activated production database failed read-only inspection"
    BASELINE_GUIDES="$(json_number "$database_profile" "counts.paintingGuides")" \
        || fail "PRODUCTION_DATABASE_INVALID" "The Guide baseline count could not be read"
    (( BASELINE_GUIDES == 0 || BASELINE_GUIDES == 1 )) \
        || fail "UNEXPECTED_GUIDE_STATE" "Production contains an unsupported number of Painting Guides"
    helper inspect-db --database "$LIVE_DATABASE" --expect-production \
        --expected-guides "$BASELINE_GUIDES" >/dev/null \
        || fail "PRODUCTION_DATABASE_INVALID" "The activated production database failed read-only verification"
    printf 'PRODUCTION_DATABASE_PREFLIGHT=%s\n' "$database_profile"

    if [[ -n "$REQUESTED_PAINTING_ID" ]]; then
        candidate_args+=(--painting-id "$REQUESTED_PAINTING_ID")
    fi
    CANDIDATE_JSON="$(helper select-painting --database "$LIVE_DATABASE" "${candidate_args[@]}")" \
        || fail "PAINTING_SELECTION_FAILED" "No deterministic annotation-rich Painting satisfied validation requirements"
    SELECTED_PAINTING_ID="$(json_string "$CANDIDATE_JSON" "paintingId")" \
        || fail "PAINTING_SELECTION_FAILED" "The selected Painting did not expose a valid public UUID"
    printf 'SELECTED_PAINTING=%s\n' "$CANDIDATE_JSON"

    if (( BASELINE_GUIDES == 1 )); then
        local existing_guide_painting
        existing_guide_painting="$(json_string "$database_profile" "guideState.paintingId")" \
            || fail "UNEXPECTED_GUIDE_STATE" "The existing Guide could not be bound to a public Painting ID"
        [[ "$existing_guide_painting" == "$SELECTED_PAINTING_ID" ]] \
            || fail "UNEXPECTED_GUIDE_STATE" "A Guide exists for a different Painting; refusing a second paid generation"
        printf '%s\n' "ALREADY_VALIDATED_CANDIDATE_DETECTED"
    fi
}

prepare_backup_root() {
    if [[ -e "$BACKUP_ROOT" || -L "$BACKUP_ROOT" ]]; then
        verify_existing_backup_root
    else
        mkdir -m 700 -- "$BACKUP_ROOT"
        verify_existing_backup_root
    fi
    exec 9< "$BACKUP_ROOT"
    flock -n 9 \
        || fail "VALIDATION_ALREADY_RUNNING" "Another live Guide validation holds the private lock"
}

process_identity_matches() {
    local pid="$1" expected_start="$2" required_text="$3"
    [[ "$pid" =~ ^[0-9]+$ && "$expected_start" =~ ^[0-9]+$ && -r "/proc/$pid/stat" ]] \
        || return 1
    [[ "$(awk '{print $22}' "/proc/$pid/stat" 2>/dev/null || true)" == "$expected_start" ]] \
        || return 1
    tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -Fq -- "$required_text"
}

guide_process_identity_matches() {
    local pid="$1" expected_start="$2"
    process_identity_matches "$pid" "$expected_start" "-m guide_service.app" \
        && [[ "$(readlink -f -- "/proc/$pid/cwd" 2>/dev/null || true)" == "$SERVER_LOCAL_ROOT" ]] \
        && [[ "$(stat -c %u -- "/proc/$pid" 2>/dev/null || true)" == "$(id -u)" ]]
}

adopt_partial_guide_start() {
    [[ -f "$GUIDE_STATE_FILE" && ! -L "$GUIDE_STATE_FILE" ]] || return 1
    [[ "$(stat -c %u -- "$GUIDE_STATE_FILE" 2>/dev/null || true)" == "$(id -u)" ]] || return 1
    local state_mtime state_pid state_start
    state_mtime="$(stat -c %Y -- "$GUIDE_STATE_FILE" 2>/dev/null || true)"
    state_pid="$(sed -n '1s/^pid=//p' "$GUIDE_STATE_FILE")"
    state_start="$(sed -n '2s/^start=//p' "$GUIDE_STATE_FILE")"
    [[ "$state_mtime" =~ ^[0-9]+$ && "$state_mtime" -ge "$GUIDE_START_EPOCH" ]] || return 1
    [[ "$state_pid" =~ ^[0-9]+$ && "$state_start" =~ ^[0-9]+$ ]] || return 1
    (( state_start >= GUIDE_START_FLOOR )) || return 1
    guide_process_identity_matches "$state_pid" "$state_start" || return 1
    GUIDE_PID="$state_pid"
    GUIDE_START_TIME="$state_start"
    GUIDE_OWNED=1
    log_event "PARTIAL_GUIDE_START_OWNERSHIP_ADOPTED_FOR_CLEANUP pid=$GUIDE_PID"
}

stop_owned_spring() {
    [[ -n "$SPRING_PID" ]] || return 0
    if process_identity_matches "$SPRING_PID" "$SPRING_START_TIME" "$JAR_NAME"; then
        kill -TERM "$SPRING_PID" 2>/dev/null || true
        local attempt
        for attempt in $(seq 1 60); do
            process_identity_matches "$SPRING_PID" "$SPRING_START_TIME" "$JAR_NAME" || break
            sleep 0.5
        done
        if process_identity_matches "$SPRING_PID" "$SPRING_START_TIME" "$JAR_NAME"; then
            kill -KILL "$SPRING_PID" 2>/dev/null || true
            sleep 0.5
        fi
    fi
    if process_identity_matches "$SPRING_PID" "$SPRING_START_TIME" "$JAR_NAME"; then
        return 1
    fi
    wait "$SPRING_PID" 2>/dev/null || true
    SPRING_PID=""
    SPRING_START_TIME=""
    log_event "OWNED_SPRING_PROCESS_STOPPED"
}

guide_state_matches() {
    [[ "$GUIDE_OWNED" == 1 && -f "$GUIDE_STATE_FILE" && ! -L "$GUIDE_STATE_FILE" ]] \
        || return 1
    local state_pid state_start
    state_pid="$(sed -n '1s/^pid=//p' "$GUIDE_STATE_FILE")"
    state_start="$(sed -n '2s/^start=//p' "$GUIDE_STATE_FILE")"
    [[ "$state_pid" == "$GUIDE_PID" && "$state_start" == "$GUIDE_START_TIME" ]]
}

stop_owned_guide() {
    (( GUIDE_OWNED == 1 )) || return 0
    if guide_state_matches && guide_process_identity_matches "$GUIDE_PID" "$GUIDE_START_TIME"; then
        guide_launcher stop >/dev/null 2>&1 || true
    fi
    if guide_process_identity_matches "$GUIDE_PID" "$GUIDE_START_TIME"; then
        kill -TERM "$GUIDE_PID" 2>/dev/null || true
        local attempt
        for attempt in $(seq 1 30); do
            guide_process_identity_matches "$GUIDE_PID" "$GUIDE_START_TIME" || break
            sleep 0.5
        done
    fi
    if guide_process_identity_matches "$GUIDE_PID" "$GUIDE_START_TIME"; then
        kill -KILL "$GUIDE_PID" 2>/dev/null || true
        sleep 0.5
    fi
    if guide_process_identity_matches "$GUIDE_PID" "$GUIDE_START_TIME"; then
        return 1
    fi
    if guide_state_matches; then
        : > "$GUIDE_STATE_FILE"
    fi
    GUIDE_OWNED=0
    GUIDE_PID=""
    GUIDE_START_TIME=""
    log_event "OWNED_GUIDE_SERVICE_STOPPED"
}

capture_owned_guide_log() {
    (( RUNTIME_EVIDENCE_SANITIZED == 0 )) || return 0
    [[ -n "$BACKUP_DIR" && -f "$GUIDE_RUNTIME_LOG" && ! -L "$GUIDE_RUNTIME_LOG" ]] || return 0
    local current_size start_byte
    current_size="$(stat -c %s -- "$GUIDE_RUNTIME_LOG" 2>/dev/null || printf '0')"
    [[ "$current_size" =~ ^[0-9]+$ ]] || return 0
    if (( current_size >= GUIDE_LOG_INITIAL_SIZE )); then
        start_byte=$(( GUIDE_LOG_INITIAL_SIZE + 1 ))
        tail -c "+$start_byte" -- "$GUIDE_RUNTIME_LOG" > "$BACKUP_DIR/guide-service.log" || true
        chmod 600 -- "$BACKUP_DIR/guide-service.log"
    fi
}

discard_owned_guide_log_delta() {
    if [[ ! -e "$GUIDE_RUNTIME_LOG" && ! -L "$GUIDE_RUNTIME_LOG" ]]; then
        (( GUIDE_LOG_INITIAL_SIZE == 0 ))
        return
    fi
    [[ -f "$GUIDE_RUNTIME_LOG" && ! -L "$GUIDE_RUNTIME_LOG" ]] \
        || return 1
    [[ "$(stat -c %u -- "$GUIDE_RUNTIME_LOG" 2>/dev/null || true)" == "$(id -u)" ]] \
        || return 1
    local current_size
    current_size="$(stat -c %s -- "$GUIDE_RUNTIME_LOG" 2>/dev/null || true)"
    [[ "$current_size" =~ ^[0-9]+$ && "$current_size" -ge "$GUIDE_LOG_INITIAL_SIZE" ]] \
        || return 1
    truncate -s "$GUIDE_LOG_INITIAL_SIZE" -- "$GUIDE_RUNTIME_LOG" \
        || return 1
    chmod 600 -- "$GUIDE_RUNTIME_LOG"
}

sanitize_runtime_evidence() {
    local outcome="$1"
    [[ -n "$BACKUP_DIR" && -d "$BACKUP_DIR" ]] || return 0
    local catalog_skip_observed="false"
    if [[ -f "$BACKUP_DIR/spring.log" ]] \
            && grep -q 'Official painting catalog import completed: status=SKIPPED' \
                "$BACKUP_DIR/spring.log"; then
        catalog_skip_observed="true"
    fi
    printf '%s\n' \
        "OUTCOME=$outcome" \
        "ERROR_CODE=${CURRENT_ERROR_CODE:-NONE}" \
        "CATALOG_SKIP_OBSERVED=$catalog_skip_observed" \
        "RAW_SERVICE_LOGS_RETAINED=false" \
        > "$BACKUP_DIR/sanitized-runtime-summary.log"
    chmod 600 -- "$BACKUP_DIR/sanitized-runtime-summary.log"

    # Spring and Guide service logs are operational inputs only. They can
    # contain framework/provider detail, so they are never retained as audit
    # evidence. The fixed-field summary and validation.log remain available.
    rm -f -- "$BACKUP_DIR/spring.log" "$BACKUP_DIR/guide-service.log"
    discard_owned_guide_log_delta \
        || return 1
    RUNTIME_EVIDENCE_SANITIZED=1

    local evidence_file token_scan_args=()
    if [[ -n "$JWT_FILE" && -f "$JWT_FILE" && ! -L "$JWT_FILE" ]]; then
        token_scan_args+=(--token-file "$JWT_FILE")
    fi
    while IFS= read -r -d '' evidence_file; do
        if ! helper secret-scan --env-file "$ENV_FILE" "${token_scan_args[@]}" \
                --paths "$evidence_file" >/dev/null 2>&1; then
            rm -f -- "$evidence_file"
            printf '%s\n' "SECRET_BEARING_EVIDENCE_REMOVED" \
                >> "$BACKUP_DIR/validation.log"
        fi
    done < <(
        find "$BACKUP_DIR" -maxdepth 1 -type f \
            ! -name 'backend.env.pre-validation' \
            ! -name '*.db' \
            -print0
    )
    log_event "RAW_RUNTIME_LOGS_REMOVED_SECRET_SAFE_EVIDENCE_RETAINED"
}

cleanup_auth_material() {
    unset -v LOGIN_PASSWORD 2>/dev/null || true
    if [[ -n "$AUTH_DIR" && -d "$AUTH_DIR" ]]; then
        [[ -z "$LOGIN_RESPONSE" ]] || rm -f -- "$LOGIN_RESPONSE"
        [[ -z "$JWT_FILE" ]] || rm -f -- "$JWT_FILE"
        [[ -z "$AUTH_CONFIG" ]] || rm -f -- "$AUTH_CONFIG"
        rmdir -- "$AUTH_DIR" 2>/dev/null || true
    fi
    AUTH_DIR=""
    LOGIN_RESPONSE=""
    JWT_FILE=""
    AUTH_CONFIG=""
}

rollback_validation() {
    log_event "ROUND61_VALIDATION_FAILED_ROLLBACK_STARTING"
    local rollback_safe=1 evidence_safe=1
    stop_owned_spring || rollback_safe=0
    stop_owned_guide || rollback_safe=0
    capture_owned_guide_log
    sanitize_runtime_evidence "FAILED" || {
        evidence_safe=0
        log_event "RAW_RUNTIME_LOG_CLEANUP_FAILED_OPERATOR_ACTION_REQUIRED"
    }
    cleanup_auth_material

    if [[ -n "$(lsof -t -- "$LIVE_DATABASE" 2>/dev/null || true)" ]]; then
        rollback_safe=0
        log_event "ROUND61_ROLLBACK_BLOCKED_DATABASE_IN_USE"
    fi
    if (( rollback_safe == 1 )); then
        if helper preserve-failed --source "$LIVE_DATABASE" \
                --destination "$BACKUP_DIR/failed-validation.db" >/dev/null; then
            log_event "FAILED_VALIDATION_DATABASE_SNAPSHOT_PRESERVED"
        else
            log_event "FAILED_VALIDATION_DATABASE_SNAPSHOT_FAILED"
        fi
    fi
    if (( rollback_safe == 1 )); then
        local database_restored=0 environment_restored=0 database_verified=0 environment_verified=0
        if helper restore-db --backup "$DATABASE_BACKUP" --database "$LIVE_DATABASE" >/dev/null; then
            database_restored=1
        else
            log_event "ROUND61_DATABASE_RESTORE_FAILED"
        fi
        if helper restore-env --backup "$ENV_BACKUP" --env-file "$ENV_FILE" >/dev/null; then
            environment_restored=1
        else
            log_event "ROUND61_ENV_RESTORE_FAILED"
        fi
        if (( database_restored == 1 )) \
                && helper inspect-db --database "$LIVE_DATABASE" --expect-production \
                    --expected-guides "$BASELINE_GUIDES" \
                    --output "$BACKUP_DIR/rollback-verification.json" >/dev/null; then
            database_verified=1
        fi
        if (( environment_restored == 1 )) && cmp -s -- "$ENV_FILE" "$ENV_BACKUP"; then
            environment_verified=1
        fi
        if (( database_verified == 1 && environment_verified == 1 )); then
            log_event "ROUND61_ROLLBACK_COMPLETED"
        else
            rollback_safe=0
            log_event "ROUND61_ROLLBACK_FAILED_OPERATOR_ACTION_REQUIRED"
        fi
    fi
    (( rollback_safe == 1 && evidence_safe == 1 ))
}

handle_signal() {
    local signal_name="$1"
    case "$signal_name" in
        INT) exit 130 ;;
        TERM) exit 143 ;;
        HUP) exit 129 ;;
    esac
}

on_exit() {
    local exit_status=$?
    trap - EXIT INT TERM HUP
    set +e
    if (( VALIDATION_SUCCEEDED == 0 )); then
        [[ -n "$CURRENT_ERROR_CODE" ]] || CURRENT_ERROR_CODE="UNEXPECTED_LOCAL_FAILURE"
        [[ -n "$CURRENT_ERROR_SUMMARY" ]] || CURRENT_ERROR_SUMMARY="The controlled local validation did not complete"
        log_event "ROUND61_VALIDATION_ERROR_CODE=$CURRENT_ERROR_CODE"
        log_event "ROUND61_VALIDATION_ERROR_SUMMARY=$CURRENT_ERROR_SUMMARY"
    fi
    if (( exit_status != 0 && ROLLBACK_ARMED == 1 )); then
        rollback_validation || true
    else
        stop_owned_spring || true
        stop_owned_guide || true
        capture_owned_guide_log
        cleanup_auth_material
    fi
    exit "$exit_status"
}

trap on_exit EXIT
trap 'handle_signal INT' INT
trap 'handle_signal TERM' TERM
trap 'handle_signal HUP' HUP

verify_server_local_root
verify_required_files
verify_private_env
verify_commit
require_services_down
verify_existing_backup_root
verify_free_space
run_read_only_preflight

if [[ "$MODE" == "dry-run" ]]; then
    printf 'INTENDED_BACKUP_DIRECTORY=%s/<timestamp>\n' "$BACKUP_ROOT"
    printf '%s\n' \
        "INTENDED_PHASE=private SQLite and backend/.env backup" \
        "INTENDED_PHASE=owned loopback Guide Service startup and health check" \
        "INTENDED_PHASE=offline Spring package and owned loopback startup" \
        "INTENDED_PHASE=interactive existing-user authentication" \
        "INTENDED_PHASE=one GENERATED request followed by HIT and GET verification" \
        "INTENDED_PHASE=database, content, secret, and cleanup verification" \
        "EXISTING_USER_CREDENTIALS_REQUIRED_INTERACTIVELY" \
        "DRY_RUN_ZERO_MUTATION" \
        "DRY_RUN_OK"
    VALIDATION_SUCCEEDED=1
    exit 0
fi

[[ "${AURALINK_ROUND61_CONFIRM:-}" == "$CONFIRMATION_TOKEN" ]] \
    || fail "CONFIRMATION_REQUIRED" "AURALINK_ROUND61_CONFIRM does not contain the required one-time confirmation"

prepare_backup_root
verify_commit
require_services_down

log_event "OFFLINE_BACKEND_PACKAGE_STARTING"
(
    cd "$BACKEND_ROOT"
    exec env -i \
        HOME=/root \
        PATH="$SAFE_RUNTIME_PATH" \
        LANG=C.UTF-8 \
        LC_ALL=C.UTF-8 \
        TMPDIR=/tmp \
        "$MVN_BIN" -o -Dmaven.repo.local="$PROJECT_ROOT/.m2/repository" \
            clean package -DskipTests
)
verify_regular_file "$JAR_FILE" "reviewed packaged backend"
verify_commit
require_services_down
log_event "OFFLINE_BACKEND_PACKAGE_READY"

BACKUP_DIR="$BACKUP_ROOT/$(date -u +%Y%m%dT%H%M%SZ)-$$"
mkdir -m 700 -- "$BACKUP_DIR"
DATABASE_BACKUP="$BACKUP_DIR/auralink.pre-validation.db"
ENV_BACKUP="$BACKUP_DIR/backend.env.pre-validation"
BASELINE_STATE_FILE="$BACKUP_DIR/pre-validation-state.json"
: > "$BACKUP_DIR/validation.log"
chmod 600 -- "$BACKUP_DIR/validation.log"
printf '%s' "$CANDIDATE_JSON" > "$BACKUP_DIR/selected-painting.json"
chmod 600 -- "$BACKUP_DIR/selected-painting.json"

helper snapshot-db --database "$LIVE_DATABASE" --output "$BASELINE_STATE_FILE" >/dev/null
helper manifest --phase PRE_VALIDATION --database "$LIVE_DATABASE" \
    --destination "$BACKUP_DIR/pre-validation-manifest.json" \
    --painting-file "$BACKUP_DIR/selected-painting.json" >/dev/null
helper backup-db --source "$LIVE_DATABASE" --destination "$DATABASE_BACKUP" \
    > "$BACKUP_DIR/database-backup-result.json"
helper verify-backup --source "$LIVE_DATABASE" --backup "$DATABASE_BACKUP" \
    > "$BACKUP_DIR/database-backup-verification.json"
helper backup-env --source "$ENV_FILE" --destination "$ENV_BACKUP" >/dev/null
cmp -s -- "$ENV_FILE" "$ENV_BACKUP" \
    || fail "ENV_BACKUP_INVALID" "The private backend/.env backup differs from its source"
chmod 600 -- "$BACKUP_DIR"/*
log_event "PRIVATE_BACKUPS_VERIFIED"
ROLLBACK_ARMED=1

verify_commit
require_services_down
GUIDE_LOG_INITIAL_SIZE="$(stat -c %s -- "$GUIDE_RUNTIME_LOG" 2>/dev/null || printf '0')"
GUIDE_START_EPOCH="$(date +%s)"
GUIDE_START_FLOOR="$(python3 -c 'import os; print(int(float(open("/proc/uptime", encoding="ascii").read().split()[0]) * os.sysconf("SC_CLK_TCK")))')"
if ! guide_start_output="$(guide_launcher start)"; then
    adopt_partial_guide_start || true
    fail "GUIDE_SERVICE_START_FAILED" "The reviewed Guide Service launcher failed"
fi
[[ "$guide_start_output" =~ GUIDE_SERVICE_STARTED\ pid=([0-9]+)\ host=127\.0\.0\.1\ port=5003 ]] \
    || {
        adopt_partial_guide_start || true
        fail "GUIDE_SERVICE_START_FAILED" "The Guide Service launcher returned an unexpected ownership record"
    }
GUIDE_PID="${BASH_REMATCH[1]}"
GUIDE_START_TIME="$(awk '{print $22}' "/proc/$GUIDE_PID/stat" 2>/dev/null || true)"
if [[ ! "$GUIDE_START_TIME" =~ ^[0-9]+$ ]]; then
    adopt_partial_guide_start || true
    fail "GUIDE_SERVICE_START_FAILED" "The owned Guide Service identity could not be recorded"
fi
GUIDE_OWNED=1
if ! guide_process_identity_matches "$GUIDE_PID" "$GUIDE_START_TIME"; then
    GUIDE_OWNED=0
    adopt_partial_guide_start || true
    fail "GUIDE_SERVICE_START_FAILED" "The Guide Service process identity did not match the reviewed launcher"
fi
require_loopback_listener "$GUIDE_PORT" "GUIDE_SERVICE"

curl -q --noproxy '*' --fail --silent --show-error --max-time 10 \
    "http://127.0.0.1:$GUIDE_PORT/health" \
    --output "$BACKUP_DIR/guide-health.json" \
    || fail "GUIDE_SERVICE_HEALTH_FAILED" "The loopback Guide Service health check failed"
helper validate-health --file "$BACKUP_DIR/guide-health.json" --service guide >/dev/null \
    || fail "GUIDE_SERVICE_HEALTH_FAILED" "The Guide Service reported an unsafe or incomplete configuration"
log_event "OWNED_GUIDE_SERVICE_HEALTHY"

(
    cd "$BACKEND_ROOT"
    exec env -i \
        HOME=/root \
        PATH="$SAFE_RUNTIME_PATH" \
        LANG=C.UTF-8 \
        LC_ALL=C.UTF-8 \
        TMPDIR=/tmp \
        TZ=Asia/Shanghai \
        AURALINK_ENV_FILE="$ENV_FILE" \
        "$JAVA_BIN" -jar "$JAR_FILE" \
        --spring.config.location=classpath:/application.yml \
        --spring.config.import="optional:file:$ENV_FILE[.properties]" \
        --spring.datasource.url="jdbc:sqlite:$LIVE_DATABASE" \
        --spring.datasource.hikari.connection-init-sql="PRAGMA foreign_keys=ON" \
        --spring.flyway.enabled=false \
        --spring.flyway.baseline-on-migrate=false \
        --spring.flyway.clean-disabled=true \
        --spring.jpa.generate-ddl=false \
        --spring.jpa.hibernate.ddl-auto=none \
        --spring.jpa.properties.hibernate.hbm2ddl.auto=none \
        --spring.sql.init.mode=never \
        --auralink.paintings.metadata-csv-path="$PROJECT_ROOT/frontend/public/data/paintings.csv" \
        --auralink.paintings.picture-dir="$BACKEND_ROOT/picture" \
        --auralink.paintings.import-enabled=true \
        --auralink.paintings.import-fail-on-error=true \
        --auralink.guide.enabled=true \
        --auralink.guide.service-url="http://127.0.0.1:$GUIDE_PORT" \
        --auralink.guide.service-host=127.0.0.1 \
        --auralink.guide.service-port="$GUIDE_PORT" \
        --auralink.guide.schema-version=1 \
        --auralink.guide.poetry-graph-path="$PROJECT_ROOT/frontend/public/data/poetry-graph.json" \
        --auralink.guide.poetry-stats-path="$PROJECT_ROOT/frontend/public/data/poetry-stats.json" \
        --server.address=127.0.0.1 \
        --server.port="$SPRING_PORT"
) > "$BACKUP_DIR/spring.log" 2>&1 &
SPRING_PID=$!
SPRING_START_TIME="$(awk '{print $22}' "/proc/$SPRING_PID/stat" 2>/dev/null || true)"
[[ "$SPRING_START_TIME" =~ ^[0-9]+$ ]] \
    || fail "SPRING_START_FAILED" "The owned Spring process identity could not be recorded"
log_event "OWNED_SPRING_PROCESS_STARTED pid=$SPRING_PID"

spring_ready=0
for attempt in $(seq 1 360); do
    process_identity_matches "$SPRING_PID" "$SPRING_START_TIME" "$JAR_NAME" \
        || break
        if curl -q --noproxy '*' --fail --silent --show-error --max-time 5 \
            "http://127.0.0.1:$SPRING_PORT/api/health" \
            --output "$BACKUP_DIR/spring-health.json"; then
        spring_ready=1
        break
    fi
    sleep 0.5
done
(( spring_ready == 1 )) \
    || fail "SPRING_START_FAILED" "The owned Spring backend did not become healthy"
require_loopback_listener "$SPRING_PORT" "SPRING_BACKEND"
helper validate-health --file "$BACKUP_DIR/spring-health.json" --service spring >/dev/null \
    || fail "SPRING_HEALTH_FAILED" "The Spring backend health response was invalid"

catalog_skipped=0
for attempt in $(seq 1 600); do
    process_identity_matches "$SPRING_PID" "$SPRING_START_TIME" "$JAR_NAME" \
        || break
    if grep -q 'Official painting catalog import completed: status=SKIPPED' "$BACKUP_DIR/spring.log"; then
        catalog_skipped=1
        break
    fi
    sleep 0.5
done
(( catalog_skipped == 1 )) \
    || fail "CATALOG_STARTUP_NOT_SKIPPED" "Normal startup did not complete the expected unchanged-catalog SKIPPED audit"

curl -q --noproxy '*' --fail --silent --show-error --max-time 10 \
    "http://127.0.0.1:$SPRING_PORT/api/v1/paintings?page=0&size=1" \
    --output "$BACKUP_DIR/gallery-health.json" \
    || fail "GALLERY_HEALTH_FAILED" "The public Painting gallery health probe failed"
python3 - "$BACKUP_DIR/gallery-health.json" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as stream:
    body = json.load(stream)
items = body.get("items")
if not isinstance(items, list) or not items:
    raise SystemExit(1)
serialized = json.dumps(body, ensure_ascii=False).lower()
if "storagekey" in serialized or "/root/" in serialized:
    raise SystemExit(1)
PY
verify_only_owned_database_user
log_event "OWNED_SPRING_BACKEND_HEALTHY_CATALOG_SKIPPED"

username="${AURALINK_ROUND61_USERNAME:-}"
if [[ -z "$username" ]]; then
    [[ -t 0 ]] \
        || fail "INTERACTIVE_LOGIN_REQUIRED" "An interactive terminal or AURALINK_ROUND61_USERNAME is required"
    read -r -p "Existing Auralink username: " username
fi
[[ -n "$username" && "$username" != *$'\n'* && ${#username} -le 128 ]] \
    || fail "INVALID_USERNAME" "The existing username is blank or malformed"
[[ -t 0 ]] \
    || fail "INTERACTIVE_LOGIN_REQUIRED" "The existing user's password must be entered through a hidden terminal prompt"
read -r -s -p "Existing Auralink password: " LOGIN_PASSWORD
printf '\n' >&2
[[ -n "$LOGIN_PASSWORD" ]] \
    || fail "INTERACTIVE_LOGIN_REQUIRED" "The existing user's password was blank"

AUTH_DIR="$(mktemp -d /tmp/auralink-round61-auth.XXXXXX)"
chmod 700 -- "$AUTH_DIR"
LOGIN_RESPONSE="$AUTH_DIR/login-response.json"
JWT_FILE="$AUTH_DIR/jwt"
AUTH_CONFIG="$AUTH_DIR/curl-auth.conf"
: > "$LOGIN_RESPONSE"
chmod 600 -- "$LOGIN_RESPONSE"
if ! login_http_code="$(
    printf '%s' "$LOGIN_PASSWORD" \
        | helper build-login-request --username "$username" --output-fd 3 3>&1 1>/dev/null \
        | curl -q --noproxy '*' --silent --show-error --max-time 20 \
            --request POST \
            --header 'Content-Type: application/json' \
            --data-binary @- \
            --output "$LOGIN_RESPONSE" \
            --write-out '%{http_code}' \
            "http://127.0.0.1:$SPRING_PORT/api/auth/login"
)"; then
    unset LOGIN_PASSWORD
    fail "LOGIN_TRANSPORT_FAILED" "Existing-user authentication could not reach the local Spring backend"
fi
unset LOGIN_PASSWORD
[[ "$login_http_code" == "200" ]] \
    || fail "LOGIN_REJECTED" "Existing-user authentication failed before any Guide provider request"
helper extract-token --response "$LOGIN_RESPONSE" --token-file "$JWT_FILE" >/dev/null \
    || fail "LOGIN_RESPONSE_INVALID" "The local login response did not contain a usable authentication token"
chmod 600 -- "$JWT_FILE"
helper write-auth-config --token-file "$JWT_FILE" --output "$AUTH_CONFIG" >/dev/null
chmod 600 -- "$AUTH_CONFIG"
rm -f -- "$LOGIN_RESPONSE"
LOGIN_RESPONSE=""
log_event "EXISTING_USER_AUTHENTICATED_TOKEN_PRIVATE"

call_guide_api() {
    local method="$1" destination="$2" endpoint="$3" request_policy="${4:-standard}" http_code
    if ! http_code="$(curl -q --config "$AUTH_CONFIG" --noproxy '*' \
            --silent --show-error --max-time 420 \
            --request "$method" \
            --output "$destination" \
            --write-out '%{http_code}' \
            "http://127.0.0.1:$SPRING_PORT$endpoint")"; then
        if [[ "$request_policy" == "cache-only" ]]; then
            fail "ALREADY_VALIDATED_CACHE_NOT_CURRENT" \
                "The existing Guide could not be read safely; no provider request was made"
        fi
        fail "GUIDE_REQUEST_TRANSPORT_FAILED" "The local Spring-to-Guide request did not complete"
    fi
    chmod 600 -- "$destination"
    if [[ "$http_code" != "200" ]]; then
        if [[ "$request_policy" == "cache-only" ]]; then
            fail "ALREADY_VALIDATED_CACHE_NOT_CURRENT" \
                "The existing Guide is unavailable or stale; no provider request was made"
        fi
        case "$http_code" in
            401|403) fail "AUTHENTICATION_REJECTED" "The authenticated Guide request was rejected" ;;
            429) fail "GUIDE_RATE_LIMITED" "The controlled Guide request was rate limited" ;;
            502) fail "GUIDE_PROVIDER_REJECTED" "The provider rejected the request or returned invalid structured data" ;;
            503) fail "GUIDE_PROVIDER_UNAVAILABLE" "The Guide provider is unavailable or misconfigured" ;;
            504) fail "GUIDE_PROVIDER_TIMEOUT" "The Guide provider request timed out" ;;
            *) fail "GUIDE_REQUEST_FAILED" "The controlled Guide request returned an unexpected safe status" ;;
        esac
    fi
}

generated_raw="$BACKUP_DIR/guide-generated-response.json"
generated_canonical="$BACKUP_DIR/guide-generated-canonical.json"
hit_raw="$BACKUP_DIR/guide-hit-response.json"
hit_canonical="$BACKUP_DIR/guide-hit-canonical.json"
get_raw="$BACKUP_DIR/guide-get-response.json"
get_canonical="$BACKUP_DIR/guide-get-canonical.json"
generated_state="$BACKUP_DIR/generated-state.json"

# The knowledge corpus is ignored legacy/frontend data, so repeat its pinned
# read-only fingerprint check immediately before any Guide API sequence. This
# prevents a post-preflight source swap from reaching a paid provider call.
helper preflight-knowledge --project-root "$PROJECT_ROOT" \
    --output "$BACKUP_DIR/provider-knowledge-preflight.json" >/dev/null \
    || fail "GUIDE_KNOWLEDGE_INVALID" \
        "Reviewed static Guide knowledge changed before the Guide request"

if (( BASELINE_GUIDES == 0 )); then
    log_event "ONE_PAID_GUIDE_GENERATION_REQUEST_STARTING"
    call_guide_api POST "$generated_raw" "/api/v1/paintings/$SELECTED_PAINTING_ID/guide"
    helper validate-guide-response --file "$generated_raw" \
        --painting-id "$SELECTED_PAINTING_ID" --cache-status GENERATED \
        --output "$generated_canonical" >/dev/null \
        || fail "GUIDE_INVALID_RESPONSE" "The generated public Guide response failed strict validation"
    helper verify-generated --database "$LIVE_DATABASE" --before "$BASELINE_STATE_FILE" \
        --painting-id "$SELECTED_PAINTING_ID" --response "$generated_raw" \
        --output "$generated_state" >/dev/null \
        || fail "GUIDE_DATABASE_INVARIANT_FAILED" "The generated Guide changed an unexpected production invariant"
    verify_only_owned_database_user
    log_event "ONE_PAID_GUIDE_GENERATION_VERIFIED"

    call_guide_api POST "$hit_raw" "/api/v1/paintings/$SELECTED_PAINTING_ID/guide"
    helper validate-guide-response --file "$hit_raw" \
        --painting-id "$SELECTED_PAINTING_ID" --cache-status HIT \
        --output "$hit_canonical" >/dev/null \
        || fail "GUIDE_CACHE_HIT_INVALID" "The second POST did not return a valid cached Guide"
else
    generated_state="$BASELINE_STATE_FILE"
    # A structurally healthy database row may still be stale relative to the
    # current Painting/knowledge source hash. GET is cache-only by contract, so
    # prove currentness before any POST that could incur a provider call.
    call_guide_api GET "$get_raw" \
        "/api/v1/paintings/$SELECTED_PAINTING_ID/guide" cache-only
    helper validate-guide-response --file "$get_raw" \
        --painting-id "$SELECTED_PAINTING_ID" --cache-status HIT \
        --output "$get_canonical" >/dev/null \
        || fail "ALREADY_VALIDATED_CACHE_NOT_CURRENT" \
            "The existing Guide failed cache-only validation; no provider request was made"
    helper verify-hit --database "$LIVE_DATABASE" --generated-state "$generated_state" \
        --painting-id "$SELECTED_PAINTING_ID" --response "$get_raw" \
        --output "$BACKUP_DIR/cache-only-get-state.json" >/dev/null \
        || fail "ALREADY_VALIDATED_CACHE_NOT_CURRENT" \
            "The existing Guide failed cache-only database validation; no provider request was made"
    log_event "ALREADY_VALIDATED_CACHE_ONLY_GET_VERIFIED"

    call_guide_api POST "$hit_raw" "/api/v1/paintings/$SELECTED_PAINTING_ID/guide"
    helper validate-guide-response --file "$hit_raw" \
        --painting-id "$SELECTED_PAINTING_ID" --cache-status HIT \
        --output "$hit_canonical" >/dev/null \
        || fail "GUIDE_CACHE_HIT_INVALID" "The already-validated Painting did not return a cache HIT"
fi

helper verify-hit --database "$LIVE_DATABASE" --generated-state "$generated_state" \
    --painting-id "$SELECTED_PAINTING_ID" --response "$hit_raw" \
    --output "$BACKUP_DIR/hit-state.json" >/dev/null \
    || fail "GUIDE_CACHE_INVARIANT_FAILED" "The cache-HIT request changed production data"
log_event "SECOND_POST_CACHE_HIT_VERIFIED"

if (( BASELINE_GUIDES == 0 )); then
    call_guide_api GET "$get_raw" "/api/v1/paintings/$SELECTED_PAINTING_ID/guide"
    helper validate-guide-response --file "$get_raw" \
        --painting-id "$SELECTED_PAINTING_ID" --cache-status HIT \
        --output "$get_canonical" >/dev/null \
        || fail "GUIDE_CACHE_GET_INVALID" "GET did not return the persisted validated Guide"
    helper verify-hit --database "$LIVE_DATABASE" --generated-state "$generated_state" \
        --painting-id "$SELECTED_PAINTING_ID" --response "$get_raw" >/dev/null \
        || fail "GUIDE_CACHE_INVARIANT_FAILED" "The cached GET changed production data"
    helper compare-guide-responses --generated "$generated_raw" \
        --hit "$hit_raw" --get "$get_raw" \
        --output "$BACKUP_DIR/guide-response-comparison.json" >/dev/null \
        || fail "GUIDE_CACHE_CONTENT_MISMATCH" "GENERATED, HIT, and GET did not expose the same persisted Guide"
else
    cmp -s -- "$hit_canonical" "$get_canonical" \
        || fail "GUIDE_CACHE_CONTENT_MISMATCH" "HIT and GET did not expose the same persisted Guide"
    printf '%s\n' \
        '{"cacheSequence":["HIT","HIT"],"publicGuideStable":true}' \
        > "$BACKUP_DIR/guide-response-comparison.json"
    chmod 600 -- "$BACKUP_DIR/guide-response-comparison.json"
fi
helper content-quality --response "$get_raw" \
    --output "$BACKUP_DIR/content-quality.json" >/dev/null \
    || fail "GUIDE_CONTENT_VALIDATION_FAILED" "The Guide failed structural and content-safety checks"
log_event "STRUCTURALLY_VALID"
log_event "OPERATOR_REVIEW_REQUIRED"

stop_owned_spring \
    || fail "SPRING_STOP_FAILED" "The owned Spring backend could not be stopped safely"
stop_owned_guide \
    || fail "GUIDE_SERVICE_STOP_FAILED" "The owned Guide Service could not be stopped safely"
capture_owned_guide_log
sanitize_runtime_evidence "SUCCESS" \
    || fail "SECRET_SAFE_EVIDENCE_FAILED" \
        "Owned raw runtime logs could not be removed from retained evidence"
require_port_free "$SPRING_PORT" "Spring backend"
require_port_free "$GUIDE_PORT" "Guide Service"
[[ -z "$(lsof -t -- "$LIVE_DATABASE" 2>/dev/null || true)" ]] \
    || fail "PRODUCTION_DATABASE_IN_USE" "The production database remained open after owned-process cleanup"
cmp -s -- "$ENV_FILE" "$ENV_BACKUP" \
    || fail "ENV_CHANGED_UNEXPECTEDLY" "The one-time validator changed backend/.env unexpectedly"
verify_commit

expected_guides=$(( BASELINE_GUIDES == 0 ? 1 : BASELINE_GUIDES ))
helper inspect-db --database "$LIVE_DATABASE" --expect-production \
    --expected-guides "$expected_guides" \
    --output "$BACKUP_DIR/final-database-verification.json" >/dev/null \
    || fail "FINAL_DATABASE_INVALID" "Final production database verification failed"
helper manifest --phase POST_VALIDATION --database "$LIVE_DATABASE" \
    --destination "$BACKUP_DIR/post-validation-manifest.json" \
    --painting-file "$BACKUP_DIR/selected-painting.json" >/dev/null

mapfile -d '' secret_scan_paths < <(
    find "$BACKUP_DIR" -maxdepth 1 -type f \
        ! -name 'backend.env.pre-validation' \
        ! -name '*.db' \
        -print0
)
helper secret-scan --env-file "$ENV_FILE" --token-file "$JWT_FILE" \
    --paths "${secret_scan_paths[@]}" >/dev/null \
    || fail "SECRET_SCAN_FAILED" "Secret material was detected in validation evidence"
log_event "SECRET_SAFE_AUDIT_EVIDENCE_VERIFIED"
cleanup_auth_material

ROLLBACK_ARMED=0
VALIDATION_SUCCEEDED=1
if (( BASELINE_GUIDES == 1 )); then
    log_event "ALREADY_VALIDATED_AND_HEALTHY"
else
    log_event "ROUND61_ONE_LIVE_GUIDE_VALIDATION_COMPLETED"
fi
exit 0
