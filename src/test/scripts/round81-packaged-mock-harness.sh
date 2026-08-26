#!/usr/bin/env bash
set -euo pipefail
umask 077

readonly BACKEND_ROOT="$(cd "$(dirname "$0")/../../.." && pwd -P)"
readonly PROJECT_ROOT="$(cd "$BACKEND_ROOT/.." && pwd -P)"
readonly JAR="${BACKEND_ROOT}/target/auralink-backend-0.0.1-SNAPSHOT.jar"
readonly MOCK_TOOL="${BACKEND_ROOT}/src/test/scripts/round81_mock_provider.py"
readonly MAIN_CLASS="com.auralink.ops.round81.Round81ProviderValidationCommand"
readonly LIVE_DB="${AURALINK_ROUND81_TEST_LIVE_DB:-}"

[[ -f "$JAR" && ! -L "$JAR" ]] || { printf 'PACKAGED_JAR_REQUIRED\n' >&2; exit 2; }
[[ -x "$MOCK_TOOL" ]] || { printf 'MOCK_TOOL_REQUIRED\n' >&2; exit 2; }

temporary_root="$(mktemp -d /tmp/auralink-round81-mock.XXXXXX)"
chmod 0700 "$temporary_root"
mock_pid=''
cleanup() {
  if [[ -n "$mock_pid" ]] && kill -0 "$mock_pid" 2>/dev/null; then
    kill -TERM "$mock_pid"
    wait "$mock_pid" 2>/dev/null || true
  fi
  rm -rf -- "$temporary_root"
}
trap cleanup EXIT INT TERM

db_before=''
if [[ -n "$LIVE_DB" ]]; then
  [[ -f "$LIVE_DB" && ! -L "$LIVE_DB" ]] || { printf 'LIVE_DATABASE_FIXTURE_INVALID\n' >&2; exit 2; }
  db_before="$(sha256sum "$LIVE_DB" | awk '{print $1}')"
fi

confirmation_for() {
  case "$1" in
    text-to-painting) printf 'VALIDATE_ONE_LIVE_TEXT_TO_PAINTING' ;;
    image-to-painting) printf 'VALIDATE_ONE_LIVE_IMAGE_TO_PAINTING' ;;
    poem-to-painting) printf 'VALIDATE_ONE_LIVE_POEM_TO_PAINTING' ;;
    painting-to-poem) printf 'VALIDATE_ONE_LIVE_PAINTING_TO_POEM' ;;
    painting-to-music) printf 'VALIDATE_ONE_LIVE_PAINTING_TO_MUSIC' ;;
  esac
}

needs_image() {
  case "$1" in
    image-to-painting|painting-to-poem|painting-to-music) return 0 ;;
    *) return 1 ;;
  esac
}

for operation in \
  text-to-painting \
  image-to-painting \
  poem-to-painting \
  painting-to-poem \
  painting-to-music; do
  operation_root="${temporary_root}/${operation}"
  run_dir="${operation_root}/run"
  staging_dir="${operation_root}/staging"
  vmm_output="${operation_root}/vmm-output"
  state_file="${operation_root}/counts.json"
  port_file="${operation_root}/port"
  mkdir -m 0700 "$operation_root" "$run_dir" "$vmm_output"
  if needs_image "$operation"; then
    "$MOCK_TOOL" prepare-fixture --run-dir "$run_dir"
  fi
  "$MOCK_TOOL" serve \
    --operation "$operation" \
    --state-file "$state_file" \
    --port-file "$port_file" \
    --vmm-output "$vmm_output" &
  mock_pid=$!
  for _attempt in $(seq 1 100); do
    [[ -f "$port_file" ]] && break
    kill -0 "$mock_pid" 2>/dev/null || { printf 'MOCK_START_FAILED\n' >&2; exit 2; }
    sleep 0.05
  done
  [[ -f "$port_file" ]] || { printf 'MOCK_START_TIMEOUT\n' >&2; exit 2; }
  port="$(<"$port_file")"
  [[ "$port" =~ ^[1-9][0-9]*$ ]] || { printf 'MOCK_PORT_INVALID\n' >&2; exit 2; }

  export AURALINK_ROUND81_MOCK_MODE='LOCAL_LOOPBACK_ONLY'
  export AURALINK_ROUND81_MOCK_BASE_URL="http://127.0.0.1:${port}"
  export AURALINK_ROUND81_RUN_DIR="$run_dir"
  export AURALINK_PROVIDER_STAGING_DIR="$staging_dir"
  export AURALINK_VMM_OUTPUT_DIR="$vmm_output"
  export AURALINK_ROUND81_CONFIRM="$(confirmation_for "$operation")"

  output_file="${operation_root}/cli-output"
  java \
    -Dloader.main="$MAIN_CLASS" \
    -Djava.io.tmpdir="$temporary_root" \
    -cp "$JAR" \
    org.springframework.boot.loader.launch.PropertiesLauncher \
    --mock \
    --mode=validate \
    "--operation=${operation}" >"$output_file" 2>&1
  chmod 0600 "$output_file"

  grep -qx 'PROVIDER_CALL_COUNTS_VERIFIED' "$output_file"
  grep -qx 'PROVIDER_STAGING_CLEANED' "$output_file"
  grep -qx 'STRUCTURALLY_VALID' "$output_file"
  if grep -Eqi 'authorization|bearer|api.?key|signed.?url|data:image|raw.?response|password|secret' "$output_file"; then
    printf 'MOCK_OUTPUT_SECRET_BOUNDARY_FAILED\n' >&2
    exit 2
  fi
  "$MOCK_TOOL" assert-counts --operation "$operation" --state-file "$state_file"
  [[ -d "$staging_dir" && -z "$(find "$staging_dir" -mindepth 1 -maxdepth 1 -print -quit)" ]]
  [[ -f "$run_dir/result-metadata.json" && -f "$run_dir/cleanup-result.json" ]]
  [[ "$(stat -c %a "$run_dir/result-metadata.json")" == '600' ]]
  if [[ "$operation" == 'painting-to-poem' ]]; then
    [[ -f "$run_dir/validated-poem.json" ]]
  elif [[ "$operation" == 'painting-to-music' ]]; then
    [[ -f "$run_dir/validated-result.wav" ]]
  else
    find "$run_dir" -maxdepth 1 -type f \( -name 'validated-result.png' -o -name 'validated-result.jpg' \) \
      -print -quit | grep -q .
  fi

  kill -TERM "$mock_pid"
  wait "$mock_pid"
  mock_pid=''
done

# Optional Qwen message metadata must not prevent a structurally valid poem
# from completing the same one-call, no-persistence validation path.
run_optional_reasoning_success() {
  local scenario="$1"
  local case_root="${temporary_root}/${scenario}"
  local case_run="${case_root}/run"
  local case_staging="${case_root}/staging"
  local case_vmm="${case_root}/vmm-output"
  local case_state="${case_root}/counts.json"
  local case_port_file="${case_root}/port"
  local case_output="${case_root}/cli-output"
  local case_port

  mkdir -m 0700 "$case_root" "$case_run" "$case_vmm"
  "$MOCK_TOOL" prepare-fixture --run-dir "$case_run"
  "$MOCK_TOOL" serve \
    --operation painting-to-poem \
    --failure "$scenario" \
    --state-file "$case_state" \
    --port-file "$case_port_file" \
    --vmm-output "$case_vmm" &
  mock_pid=$!
  for _attempt in $(seq 1 100); do
    [[ -f "$case_port_file" ]] && break
    kill -0 "$mock_pid" 2>/dev/null || { printf 'MOCK_START_FAILED\n' >&2; exit 2; }
    sleep 0.05
  done
  [[ -f "$case_port_file" ]] || { printf 'MOCK_START_TIMEOUT\n' >&2; exit 2; }
  case_port="$(<"$case_port_file")"
  export AURALINK_ROUND81_MOCK_MODE='LOCAL_LOOPBACK_ONLY'
  export AURALINK_ROUND81_MOCK_BASE_URL="http://127.0.0.1:${case_port}"
  export AURALINK_ROUND81_RUN_DIR="$case_run"
  export AURALINK_PROVIDER_STAGING_DIR="$case_staging"
  export AURALINK_VMM_OUTPUT_DIR="$case_vmm"
  export AURALINK_ROUND81_CONFIRM='VALIDATE_ONE_LIVE_PAINTING_TO_POEM'

  java \
    -Dloader.main="$MAIN_CLASS" \
    -Djava.io.tmpdir="$temporary_root" \
    -cp "$JAR" \
    org.springframework.boot.loader.launch.PropertiesLauncher \
    --mock --mode=validate --operation=painting-to-poem >"$case_output" 2>&1
  chmod 0600 "$case_output"
  grep -qx 'PROVIDER_CALL_COUNTS_VERIFIED' "$case_output"
  grep -qx 'PROVIDER_STAGING_CLEANED' "$case_output"
  grep -qx 'STRUCTURALLY_VALID' "$case_output"
  if grep -Eqi 'authorization|bearer|api.?key|signed.?url|data:image|raw.?response|password|secret|reasoning_content' "$case_output"; then
    printf 'MOCK_OUTPUT_SECRET_BOUNDARY_FAILED\n' >&2
    exit 2
  fi
  "$MOCK_TOOL" assert-custom-counts \
    --state-file "$case_state" --seedream 0 --qwen 1 --vmm 0
  [[ -d "$case_staging" && -z "$(find "$case_staging" -mindepth 1 -maxdepth 1 -print -quit)" ]]
  [[ -f "$case_run/validated-poem.json" && -f "$case_run/result-metadata.json" ]]
  [[ -f "$case_run/cleanup-result.json" && ! -f "$case_run/failure.json" ]]

  kill -TERM "$mock_pid"
  wait "$mock_pid"
  mock_pid=''
}

for optional_reasoning_case in \
  qwen-poem-reasoning-null \
  qwen-poem-reasoning-empty \
  qwen-poem-reasoning-whitespace; do
  run_optional_reasoning_success "$optional_reasoning_case"
done

# A malformed composite plan must stop after exactly one Qwen call, retain no
# result, and leave the controlled staging directory empty.
failure_root="${temporary_root}/failure-qwen"
failure_run="${failure_root}/run"
failure_staging="${failure_root}/staging"
failure_vmm="${failure_root}/vmm-output"
failure_state="${failure_root}/counts.json"
failure_port_file="${failure_root}/port"
mkdir -m 0700 "$failure_root" "$failure_run" "$failure_vmm"
"$MOCK_TOOL" serve \
  --operation poem-to-painting \
  --failure qwen-invalid \
  --state-file "$failure_state" \
  --port-file "$failure_port_file" \
  --vmm-output "$failure_vmm" &
mock_pid=$!
for _attempt in $(seq 1 100); do
  [[ -f "$failure_port_file" ]] && break
  sleep 0.05
done
failure_port="$(<"$failure_port_file")"
export AURALINK_ROUND81_MOCK_BASE_URL="http://127.0.0.1:${failure_port}"
export AURALINK_ROUND81_RUN_DIR="$failure_run"
export AURALINK_PROVIDER_STAGING_DIR="$failure_staging"
export AURALINK_VMM_OUTPUT_DIR="$failure_vmm"
export AURALINK_ROUND81_CONFIRM='VALIDATE_ONE_LIVE_POEM_TO_PAINTING'
set +e
java \
  -Dloader.main="$MAIN_CLASS" \
  -Djava.io.tmpdir="$temporary_root" \
  -cp "$JAR" \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  --mock --mode=validate --operation=poem-to-painting \
  >"${failure_root}/cli-output" 2>&1
failure_status=$?
set -e
[[ $failure_status -eq 2 || $failure_status -eq 3 ]]
"$MOCK_TOOL" assert-custom-counts \
  --state-file "$failure_state" --seedream 0 --qwen 1 --vmm 0
[[ -f "$failure_run/failure.json" && -f "$failure_run/cleanup-result.json" ]]
[[ ! -f "$failure_run/result-metadata.json" ]]
[[ -d "$failure_staging" && -z "$(find "$failure_staging" -mindepth 1 -maxdepth 1 -print -quit)" ]]
kill -TERM "$mock_pid"
wait "$mock_pid"
mock_pid=''

# Strict PAINTING_TO_POEM response failures must retain only typed structural
# diagnostics after exactly one Qwen call and complete input-artifact cleanup.
for poem_failure_case in \
  'qwen-poem-numeric-schema:POEM_SCHEMA:QWEN_SCHEMA_VERSION_TYPE_INVALID' \
  'qwen-poem-five-lines:POEM_SCHEMA:QWEN_LINES_COUNT_INVALID' \
  'qwen-poem-fenced:CONTENT:QWEN_CONTENT_MARKDOWN_FENCE' \
  'qwen-poem-text-mismatch:POEM_SEMANTICS:QWEN_TEXT_MISMATCH' \
  'qwen-poem-reasoning-nonblank:MESSAGE:QWEN_CONTENT_REASONING_MARKER' \
  'qwen-poem-reasoning-object:MESSAGE:QWEN_REASONING_CONTENT_TYPE_INVALID'; do
  IFS=: read -r poem_failure expected_stage expected_code <<<"$poem_failure_case"
  poem_root="${temporary_root}/failure-${poem_failure}"
  poem_run="${poem_root}/run"
  poem_staging="${poem_root}/staging"
  poem_vmm="${poem_root}/vmm-output"
  poem_state="${poem_root}/counts.json"
  poem_port_file="${poem_root}/port"
  poem_output="${poem_root}/cli-output"
  mkdir -m 0700 "$poem_root" "$poem_run" "$poem_vmm"
  "$MOCK_TOOL" prepare-fixture --run-dir "$poem_run"
  "$MOCK_TOOL" serve \
    --operation painting-to-poem \
    --failure "$poem_failure" \
    --state-file "$poem_state" \
    --port-file "$poem_port_file" \
    --vmm-output "$poem_vmm" &
  mock_pid=$!
  for _attempt in $(seq 1 100); do
    [[ -f "$poem_port_file" ]] && break
    kill -0 "$mock_pid" 2>/dev/null || { printf 'MOCK_START_FAILED\n' >&2; exit 2; }
    sleep 0.05
  done
  [[ -f "$poem_port_file" ]] || { printf 'MOCK_START_TIMEOUT\n' >&2; exit 2; }
  poem_port="$(<"$poem_port_file")"
  export AURALINK_ROUND81_MOCK_MODE='LOCAL_LOOPBACK_ONLY'
  export AURALINK_ROUND81_MOCK_BASE_URL="http://127.0.0.1:${poem_port}"
  export AURALINK_ROUND81_RUN_DIR="$poem_run"
  export AURALINK_PROVIDER_STAGING_DIR="$poem_staging"
  export AURALINK_VMM_OUTPUT_DIR="$poem_vmm"
  export AURALINK_ROUND81_CONFIRM='VALIDATE_ONE_LIVE_PAINTING_TO_POEM'
  set +e
  java \
    -Dloader.main="$MAIN_CLASS" \
    -Djava.io.tmpdir="$temporary_root" \
    -cp "$JAR" \
    org.springframework.boot.loader.launch.PropertiesLauncher \
    --mock --mode=validate --operation=painting-to-poem \
    >"$poem_output" 2>&1
  poem_exit=$?
  set -e
  chmod 0600 "$poem_output"
  [[ $poem_exit -eq 2 ]]
  grep -qx 'ROUND81_VALIDATION_ERROR_CODE=PROVIDER_INVALID_RESPONSE' "$poem_output"
  grep -qx "ROUND81_RESPONSE_VALIDATION_STAGE=${expected_stage}" "$poem_output"
  grep -qx "ROUND81_RESPONSE_VALIDATION_CODE=${expected_code}" "$poem_output"
  [[ "$(wc -l <"$poem_output")" -eq 3 ]]
  "$MOCK_TOOL" assert-custom-counts \
    --state-file "$poem_state" --seedream 0 --qwen 1 --vmm 0
  [[ -f "$poem_run/failure.json" && -f "$poem_run/cleanup-result.json" ]]
  [[ "$(stat -c %a "$poem_run/failure.json")" == '600' ]]
  [[ ! -f "$poem_run/validated-poem.json" && ! -f "$poem_run/result-metadata.json" ]]
  [[ -d "$poem_staging" && -z "$(find "$poem_staging" -mindepth 1 -maxdepth 1 -print -quit)" ]]
  python3 - "$poem_run/failure.json" "$expected_stage" "$expected_code" "$poem_failure" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
stage = sys.argv[2]
code = sys.argv[3]
scenario = sys.argv[4]
document = json.loads(path.read_text(encoding="utf-8"))
assert set(document) == {
    "status", "safeErrorCategory", "operation", "providerCode", "providerFamily",
    "localCallCount", "calls", "retryHandlerInvoked", "validationStage",
    "validationCode", "responseShape", "cleanupComplete", "stagingEmpty",
    "providerArtifactClosed",
}
assert document["status"] == "FAILED"
assert document["safeErrorCategory"] == "PROVIDER_INVALID_RESPONSE"
assert document["operation"] == "PAINTING_TO_POEM"
assert document["providerCode"] == "qwen3-vl-plus"
assert document["providerFamily"] == "QWEN"
assert document["localCallCount"] == 1
assert document["calls"] == {"seedream": 0, "qwen": 1, "vmm": 0}
assert document["retryHandlerInvoked"] is False
assert document["validationStage"] == stage
assert document["validationCode"] == code
assert document["cleanupComplete"] is True
assert document["stagingEmpty"] is True
assert document["providerArtifactClosed"] is True
shape = document["responseShape"]
allowed_shape = {
    "providerEnvelopePresent", "choicesPresent", "choiceCount", "messagePresent",
    "reasoningContentPresent", "reasoningContentType", "reasoningContentNonblank",
    "contentPresent", "contentType", "contentLength", "jsonParsed", "topLevelType",
    "schemaVersionPresent", "schemaVersionType", "titlePresent", "titleType",
    "titleLength", "linesPresent", "linesType", "lineCount", "stringLineCount",
    "nonblankLineCount", "chineseDominantLineCount", "duplicateLineCount",
    "minimumLineLength", "maximumLineLength", "textPresent", "textType",
    "textLength", "textMatchesLines", "unknownFieldCount", "duplicateFieldCount",
    "hasLeadingOrTrailingContent", "hasMarkdownFence", "hasHtml",
    "hasReasoningMarker", "hasAiSelfReference",
}
assert set(shape) <= allowed_shape
assert shape["providerEnvelopePresent"] is True
assert shape["choicesPresent"] is True
assert shape["choiceCount"] == 1
assert shape["messagePresent"] is True
if scenario == "qwen-poem-reasoning-nonblank":
    assert shape["reasoningContentPresent"] is True
    assert shape["reasoningContentType"] == "STRING"
    assert shape["reasoningContentNonblank"] is True
    assert "contentPresent" not in shape
elif scenario == "qwen-poem-reasoning-object":
    assert shape["reasoningContentPresent"] is True
    assert shape["reasoningContentType"] == "OBJECT"
    assert "reasoningContentNonblank" not in shape
    assert "contentPresent" not in shape
else:
    assert shape["contentPresent"] is True
    assert shape["contentType"] == "STRING"
    assert isinstance(shape["contentLength"], int) and 0 <= shape["contentLength"] <= 1048576
if scenario == "qwen-poem-numeric-schema":
    assert shape["jsonParsed"] is True
    assert shape["schemaVersionType"] == "NUMBER"
    assert shape["lineCount"] == 4
elif scenario == "qwen-poem-five-lines":
    assert shape["jsonParsed"] is True
    assert shape["lineCount"] == 5
elif scenario == "qwen-poem-fenced":
    assert shape["jsonParsed"] is False
    assert shape["hasMarkdownFence"] is True
elif scenario == "qwen-poem-text-mismatch":
    assert shape["jsonParsed"] is True
    assert shape["textMatchesLines"] is False
rendered = json.dumps(document, ensure_ascii=False, sort_keys=True)
for forbidden in (
    "ROUND81_PRIVATE_POEM_MARKER_8A4", "私密诗句甲", "私密诗句乙",
    "私密正文不匹配", "PRIVATE_PROMPT_MARKER", "PRIVATE_BASE64_MARKER",
    "PRIVATE_API_KEY_MARKER", "PRIVATE_MODEL_VALUE", "Authorization", "Bearer",
    "data:image", "http://", "https://", "ProviderExecutionException",
    "stackTrace", "reasoning_content", "ROUND81_PRIVATE_REASONING_MARKER",
    "ROUND81_PRIVATE_REASONING_OBJECT",
):
    assert forbidden not in rendered
PY
  if grep -Eqi '私密诗句|private_poem|authorization|bearer|api.?key|data:image|raw.?response|stacktrace' "$poem_output"; then
    printf 'MOCK_QWEN_DIAGNOSTIC_LEAKAGE_FAILED\n' >&2
    exit 2
  fi
  kill -TERM "$mock_pid"
  wait "$mock_pid"
  mock_pid=''
done

# IMAGE_TO_PAINTING Ark 400/401/403 rejections must make exactly one Seedream
# call and retain only bounded safe diagnostics after Java cleanup completes.
for rejection_case in \
  '400:InvalidParameter:PROVIDER_REJECTED' \
  '401:AuthenticationError:PROVIDER_CONFIGURATION_INVALID' \
  '403:AccessDenied:PROVIDER_CONFIGURATION_INVALID'; do
  IFS=: read -r provider_status provider_code safe_category <<<"$rejection_case"
  rejection_root="${temporary_root}/failure-seedream-${provider_status}"
  rejection_run="${rejection_root}/run"
  rejection_staging="${rejection_root}/staging"
  rejection_vmm="${rejection_root}/vmm-output"
  rejection_state="${rejection_root}/counts.json"
  rejection_port_file="${rejection_root}/port"
  mkdir -m 0700 "$rejection_root" "$rejection_run" "$rejection_vmm"
  "$MOCK_TOOL" prepare-fixture --run-dir "$rejection_run"
  "$MOCK_TOOL" serve \
    --operation image-to-painting \
    --failure "seedream-http-${provider_status}" \
    --state-file "$rejection_state" \
    --port-file "$rejection_port_file" \
    --vmm-output "$rejection_vmm" &
  mock_pid=$!
  for _attempt in $(seq 1 100); do
    [[ -f "$rejection_port_file" ]] && break
    sleep 0.05
  done
  rejection_port="$(<"$rejection_port_file")"
  export AURALINK_ROUND81_MOCK_BASE_URL="http://127.0.0.1:${rejection_port}"
  export AURALINK_ROUND81_RUN_DIR="$rejection_run"
  export AURALINK_PROVIDER_STAGING_DIR="$rejection_staging"
  export AURALINK_VMM_OUTPUT_DIR="$rejection_vmm"
  export AURALINK_ROUND81_CONFIRM='VALIDATE_ONE_LIVE_IMAGE_TO_PAINTING'
  set +e
  java \
    -Dloader.main="$MAIN_CLASS" \
    -Djava.io.tmpdir="$temporary_root" \
    -cp "$JAR" \
    org.springframework.boot.loader.launch.PropertiesLauncher \
    --mock --mode=validate --operation=image-to-painting \
    >"${rejection_root}/cli-output" 2>&1
  rejection_exit=$?
  set -e
  [[ $rejection_exit -eq 2 ]]
  "$MOCK_TOOL" assert-custom-counts \
    --state-file "$rejection_state" --seedream 1 --qwen 0 --vmm 0
  [[ -f "$rejection_run/failure.json" && -f "$rejection_run/cleanup-result.json" ]]
  [[ ! -f "$rejection_run/result-metadata.json" ]]
  [[ -d "$rejection_staging" && -z "$(find "$rejection_staging" -mindepth 1 -maxdepth 1 -print -quit)" ]]
  python3 - "$rejection_run/failure.json" "$provider_status" "$provider_code" "$safe_category" <<'PY'
import json
import re
import sys
from pathlib import Path

path = Path(sys.argv[1])
status = int(sys.argv[2])
code = sys.argv[3]
category = sys.argv[4]
document = json.loads(path.read_text(encoding="utf-8"))
expected_keys = {
    "status", "safeErrorCategory", "operation", "providerCode", "providerFamily",
    "localCallCount", "calls", "retryHandlerInvoked", "providerHttpStatus",
    "providerErrorCode", "safeRequestId", "cleanupComplete", "stagingEmpty",
    "providerArtifactClosed",
}
assert set(document) == expected_keys
assert document["status"] == "FAILED"
assert document["safeErrorCategory"] == category
assert document["operation"] == "IMAGE_TO_PAINTING"
assert document["providerCode"] == "seedream-5"
assert document["providerFamily"] == "SEEDREAM"
assert document["localCallCount"] == 1
assert document["calls"] == {"seedream": 1, "qwen": 0, "vmm": 0}
assert document["retryHandlerInvoked"] is False
assert document["providerHttpStatus"] == status
assert document["providerErrorCode"] == code
assert re.fullmatch(r"sha256:[0-9a-f]{32}", document["safeRequestId"])
assert document["cleanupComplete"] is True
assert document["stagingEmpty"] is True
assert document["providerArtifactClosed"] is True
rendered = json.dumps(document, sort_keys=True)
for forbidden in (
    "RAW_PROVIDER_MESSAGE_MUST_NOT_ESCAPE",
    "RAW_BODY_REQUEST_ID_MUST_NOT_ESCAPE",
    "RAW_HEADER_REQUEST_ID_MUST_NOT_ESCAPE",
    "PRIVATE_MODEL_MUST_NOT_ESCAPE",
    "PRIVATE_PROMPT_MUST_NOT_ESCAPE",
    "PRIVATE_API_KEY_MUST_NOT_ESCAPE",
    "Authorization", "Bearer", "/images/generations", "http://", "https://",
):
    assert forbidden not in rendered
PY
  kill -TERM "$mock_pid"
  wait "$mock_pid"
  mock_pid=''
done

# Wrong confirmation and unsupported batch-like operations fail before a
# provider transport is entered.
negative_root="${temporary_root}/negative"
negative_run="${negative_root}/run"
negative_vmm="${negative_root}/vmm-output"
negative_state="${negative_root}/counts.json"
negative_port_file="${negative_root}/port"
mkdir -m 0700 "$negative_root" "$negative_run" "$negative_vmm"
"$MOCK_TOOL" serve \
  --operation text-to-painting \
  --state-file "$negative_state" \
  --port-file "$negative_port_file" \
  --vmm-output "$negative_vmm" &
mock_pid=$!
for _attempt in $(seq 1 100); do
  [[ -f "$negative_port_file" ]] && break
  sleep 0.05
done
negative_port="$(<"$negative_port_file")"
export AURALINK_ROUND81_MOCK_BASE_URL="http://127.0.0.1:${negative_port}"
export AURALINK_ROUND81_RUN_DIR="$negative_run"
export AURALINK_PROVIDER_STAGING_DIR="${negative_root}/staging"
export AURALINK_VMM_OUTPUT_DIR="$negative_vmm"
export AURALINK_ROUND81_CONFIRM='YES'
set +e
java -Dloader.main="$MAIN_CLASS" -Djava.io.tmpdir="$temporary_root" -cp "$JAR" \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  --mock --mode=validate --operation=text-to-painting >"${negative_root}/wrong-confirmation" 2>&1
confirmation_status=$?
java -Dloader.main="$MAIN_CLASS" -Djava.io.tmpdir="$temporary_root" -cp "$JAR" \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  --mock --mode=validate --operation=all >"${negative_root}/unsupported-operation" 2>&1
operation_status=$?
set -e
[[ $confirmation_status -eq 2 && $operation_status -eq 2 ]]
"$MOCK_TOOL" assert-custom-counts \
  --state-file "$negative_state" --seedream 0 --qwen 0 --vmm 0
kill -TERM "$mock_pid"
wait "$mock_pid"
mock_pid=''

if [[ -n "$LIVE_DB" ]]; then
  db_after="$(sha256sum "$LIVE_DB" | awk '{print $1}')"
  [[ "$db_before" == "$db_after" ]] || { printf 'LIVE_DATABASE_CHANGED\n' >&2; exit 2; }
fi
! find "$temporary_root" -type f \( -name '*.db' -o -name '*.sqlite' -o -name '*-wal' -o -name '*-shm' \) -print -quit | grep -q .
printf 'PACKAGED_MOCK_TEXT_TO_PAINTING=PASS\n'
printf 'PACKAGED_MOCK_IMAGE_TO_PAINTING=PASS\n'
printf 'PACKAGED_MOCK_POEM_TO_PAINTING=PASS\n'
printf 'PACKAGED_MOCK_PAINTING_TO_POEM=PASS\n'
printf 'PACKAGED_MOCK_PAINTING_TO_MUSIC=PASS\n'
printf 'PACKAGED_MOCK_ALL_CALL_COUNTS_EXACT\n'
printf 'PACKAGED_MOCK_FAILURE_CLEANUP=PASS\n'
printf 'PACKAGED_MOCK_QWEN_INVALID_DIAGNOSTICS=PASS\n'
printf 'PACKAGED_MOCK_CONFIRMATION_GUARD=PASS\n'
printf 'PACKAGED_MOCK_BATCH_MODE_REFUSED=PASS\n'
printf 'PACKAGED_MOCK_NO_DATABASE_WRITE\n'
