#!/usr/bin/env bash
# Manual server-local lifecycle helper for the loopback-only Guide Service.

set -Eeuo pipefail
IFS=$'\n\t'
umask 077

readonly SERVER_LOCAL_ROOT="/root/autodl-tmp/auralink"
readonly SCRIPT_PATH="$(readlink -f -- "${BASH_SOURCE[0]}")"
readonly SCRIPT_ROOT="$(readlink -f -- "$(dirname -- "$SCRIPT_PATH")/../..")"
readonly PYTHON="$SERVER_LOCAL_ROOT/micromamba/envs/auralink-ai/bin/python"
readonly ENV_FILE="$SERVER_LOCAL_ROOT/backend/.env"
readonly RUNTIME_DIR="/root/auralink_runtime/guide-service"
readonly STATE_FILE="$RUNTIME_DIR/guide-service.state"
readonly LOG_FILE="$RUNTIME_DIR/guide-service.log"

fail() {
    printf 'GUIDE_SERVICE_ERROR: %s\n' "$1" >&2
    exit 1
}

usage() {
    printf '%s\n' "Usage: backend/scripts/start-guide-service.sh {start|stop|status}"
}

verify_root() {
    [[ "$SCRIPT_ROOT" == "$SERVER_LOCAL_ROOT" ]] \
        || fail "script is not installed at the server-local project root"
    [[ "$(pwd -P)" == "$SERVER_LOCAL_ROOT" ]] \
        || fail "run from exactly $SERVER_LOCAL_ROOT"
    local filesystem_type
    filesystem_type="$(findmnt -n -o FSTYPE -T "$SERVER_LOCAL_ROOT")" \
        || fail "cannot determine project filesystem type"
    case "${filesystem_type,,}" in
        *sshfs*|*fuse*) fail "Guide Service must not run from FUSE/SSHFS" ;;
    esac
}

verify_start_prerequisites() {
    [[ -x "$PYTHON" ]] || fail "project Python interpreter is unavailable"
    "$PYTHON" -c \
        'import importlib.metadata as m, sys; sys.exit(0 if (m.version("Flask"), m.version("requests")) == ("3.1.0", "2.32.5") else 1)' \
        >/dev/null 2>&1 || fail "Guide Service Python runtime dependencies do not match guide_service/requirements.txt"
    [[ -f "$ENV_FILE" && ! -L "$ENV_FILE" ]] \
        || fail "backend/.env must be a regular non-symlink file"
}

process_matches() {
    local pid="$1" expected_start="$2"
    [[ "$pid" =~ ^[0-9]+$ && "$expected_start" =~ ^[0-9]+$ \
        && -r "/proc/$pid/stat" ]] || return 1
    [[ "$(awk '{print $22}' "/proc/$pid/stat" 2>/dev/null || true)" \
        == "$expected_start" ]] || return 1
    [[ "$(readlink -f "/proc/$pid/cwd" 2>/dev/null || true)" \
        == "$SERVER_LOCAL_ROOT" ]] || return 1
    tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null \
        | grep -Fq -- '-m guide_service.app'
}

read_state() {
    STATE_PID=""
    STATE_START=""
    [[ -f "$STATE_FILE" && ! -L "$STATE_FILE" ]] || return 1
    STATE_PID="$(sed -n '1s/^pid=//p' "$STATE_FILE")"
    STATE_START="$(sed -n '2s/^start=//p' "$STATE_FILE")"
    [[ -n "$STATE_PID" && -n "$STATE_START" ]]
}

configured_port() {
    (
        cd "$SERVER_LOCAL_ROOT"
        PYTHONDONTWRITEBYTECODE=1 "$PYTHON" -c \
            'from guide_service.config import GuideServiceConfig; print(GuideServiceConfig.load().service_port)'
    )
}

listener_exists() {
    local port="$1"
    ss -H -ltn | awk -v suffix=":$port" '$4 ~ (suffix "$") { found=1 } END { exit !found }'
}

start_service() {
    verify_start_prerequisites
    command -v ss >/dev/null || fail "ss is required"
    mkdir -p -- "$RUNTIME_DIR"
    chmod 700 -- "$RUNTIME_DIR"
    if read_state && process_matches "$STATE_PID" "$STATE_START"; then
        fail "Guide Service is already running"
    fi
    local port
    port="$(configured_port)" || fail "Guide Service configuration is invalid"
    [[ "$port" =~ ^[0-9]+$ ]] || fail "Guide Service port is invalid"
    listener_exists "$port" && fail "configured Guide Service port is already occupied"

    (
        cd "$SERVER_LOCAL_ROOT"
        nohup "$PYTHON" -m guide_service.app >> "$LOG_FILE" 2>&1 &
        local child=$!
        local start_time
        start_time="$(awk '{print $22}' "/proc/$child/stat" 2>/dev/null || true)"
        [[ "$start_time" =~ ^[0-9]+$ ]] || fail "could not record service identity"
        printf 'pid=%s\nstart=%s\n' "$child" "$start_time" > "$STATE_FILE"
    )
    chmod 600 -- "$STATE_FILE" "$LOG_FILE"

    read_state || fail "Guide Service state was not recorded"
    local attempt
    for attempt in $(seq 1 60); do
        process_matches "$STATE_PID" "$STATE_START" \
            || fail "Guide Service exited during startup; inspect the private runtime log"
        if listener_exists "$port"; then
            printf 'GUIDE_SERVICE_STARTED pid=%s host=127.0.0.1 port=%s\n' \
                "$STATE_PID" "$port"
            return 0
        fi
        sleep 1
    done
    fail "Guide Service did not become ready; inspect the private runtime log"
}

stop_service() {
    read_state || fail "Guide Service state is absent"
    process_matches "$STATE_PID" "$STATE_START" \
        || fail "recorded Guide Service process identity is stale or unsafe"
    kill -TERM "$STATE_PID"
    local attempt
    for attempt in $(seq 1 30); do
        if ! process_matches "$STATE_PID" "$STATE_START"; then
            : > "$STATE_FILE"
            printf '%s\n' "GUIDE_SERVICE_STOPPED"
            return 0
        fi
        sleep 1
    done
    fail "Guide Service did not stop cleanly; no forced kill was attempted"
}

status_service() {
    if read_state && process_matches "$STATE_PID" "$STATE_START"; then
        printf 'GUIDE_SERVICE_RUNNING pid=%s\n' "$STATE_PID"
        return 0
    fi
    printf '%s\n' "GUIDE_SERVICE_NOT_RUNNING"
    return 1
}

(( $# == 1 )) || { usage >&2; exit 2; }
command -v findmnt >/dev/null || fail "findmnt is required"
verify_root
case "$1" in
    start) start_service ;;
    stop) stop_service ;;
    status) status_service ;;
    *) usage >&2; exit 2 ;;
esac
