#!/usr/bin/env bash
set -Eeuo pipefail

incoming=$1
destination=$2
port=$3
shift 3
ssh_options=("$@")

[[ "$incoming" =~ ^/root/autodl-tmp/auralink-core-deploy/incoming/[0-9a-f]{40}-[1-9][0-9]*-[1-9][0-9]*$ ]]
files=(auralink-backend-0.0.1-SNAPSHOT.jar r25-artifact-verification.json core-release-candidate.json)
local_files=("${files[@]/#/candidate/}")
chmod 600 "${local_files[@]}"
expected_hashes=$(cd candidate && sha256sum -- "${files[@]}")

verify_command="cd '$incoming' &&
  for path in ${files[*]}; do
    test -f \"\$path\" && test ! -L \"\$path\" && test \"\$(stat -c %a -- \"\$path\")\" = 600 || exit 1
  done &&
  sha256sum --check --strict --status"

remote_verified() {
  printf '%s\n' "$expected_hashes" |
    timeout 60 ssh "${ssh_options[@]}" -p "$port" "$destination" "$verify_command"
}

if remote_verified 2>/dev/null; then
  echo 'R25 status CANDIDATE_TRANSFER_REUSED'
  exit 0
fi

transfer_exit=0
timeout 600 scp "${ssh_options[@]}" -P "$port" -p "${local_files[@]}" "$destination:$incoming/" || transfer_exit=$?

if ! remote_verified; then
  echo 'R25 status CANDIDATE_TRANSFER_UNVERIFIED' >&2
  exit 1
fi

if (( transfer_exit != 0 )); then
  printf 'R25 status CANDIDATE_TRANSFER_VERIFIED_AFTER_SCP_EXIT exit=%s\n' "$transfer_exit"
else
  echo 'R25 status CANDIDATE_TRANSFER_VERIFIED'
fi
