#!/usr/bin/env bash
set -euo pipefail
umask 077

source "$(cd "$(dirname "$0")" && pwd -P)/round9cc-lib.sh"
round9cc_require_server_local_project
[[ $# -eq 1 ]] || round9cc_die 'USAGE:round9cc-cleanup.sh FIXTURE'
root="$(round9cc_validate_fixture "$1")"

while IFS= read -r pid_file; do
  instance="$(basename -- "${pid_file}" .pid)"
  if pid="$(round9cc_instance_pid "${root}" "${instance}" 2>/dev/null)"; then
    kill -TERM "${pid}" 2>/dev/null || true
    deadline=$((SECONDS + 10))
    while kill -0 "${pid}" 2>/dev/null && (( SECONDS < deadline )); do sleep 0.05; done
    if kill -0 "${pid}" 2>/dev/null; then
      kill -KILL "${pid}" 2>/dev/null || true
    fi
  fi
done < <(find "${root}/runtime" -maxdepth 1 -type f -name '*.pid' -print)

find "${root}" -xdev -mindepth 1 -depth -delete
rmdir -- "${root}"
printf 'ROUND9CC_FIXTURE_CLEANED\n'
