#!/usr/bin/env bash

# End-to-end regression for the exact Round 5.1 production JAR launcher.
#
# This harness is intentionally not part of the production activation path. It
# copies authored backend sources to /tmp, patches the fixed production root and
# production catalog expectations only in that disposable copy, packages that
# copy offline, and launches Round51ActivationCommand through PropertiesLauncher.
# No arbitrary project-root override is added to shipped code.

set -euo pipefail
umask 077

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly BACKEND_ROOT="$(cd -- "$SCRIPT_DIR/../../.." && pwd -P)"
readonly PROJECT_ROOT="$(cd -- "$BACKEND_ROOT/.." && pwd -P)"
readonly MAVEN_REPOSITORY="${AURALINK_TEST_MAVEN_REPO:-$PROJECT_ROOT/.m2/repository}"
readonly PRODUCTION_ROOT="/root/autodl-tmp/auralink"
readonly ACTIVATION_DEADLINE_SECONDS="${AURALINK_TEST_ACTIVATION_DEADLINE_SECONDS:-300}"

fail() {
    printf 'ROUND51_PACKAGED_CLI_REGRESSION_FAILED: %s\n' "$1" >&2
    exit 1
}

[[ -f "$BACKEND_ROOT/pom.xml" ]] || fail "backend pom.xml is missing"
[[ -d "$MAVEN_REPOSITORY" ]] || fail "offline Maven repository is missing"
[[ "$ACTIVATION_DEADLINE_SECONDS" =~ ^[1-9][0-9]*$ ]] \
    || fail "activation deadline must be a positive integer"
[[ -f "$BACKEND_ROOT/src/test/resources/db/legacy/inherited_schema_fixture.sql" ]] \
    || fail "inherited database fixture is missing"
[[ -f "$BACKEND_ROOT/scripts/round51_state.py" ]] \
    || fail "catalog profile helper is missing"

# Never run this fixture on the production host. On the development host this
# also guarantees that the fixed maintenance post-processor detects no real
# server checkout and cannot touch the private production backup directory.
[[ ! -e "$PRODUCTION_ROOT" ]] \
    || fail "refusing packaged regression on a host containing the production root"

readonly WORK_ROOT="$(mktemp -d /tmp/auralink-round51a1-packaged.XXXXXX)"
readonly FIXTURE_ROOT="$WORK_ROOT/project"
readonly FIXTURE_BACKEND="$FIXTURE_ROOT/backend"
readonly FIXTURE_CSV="$FIXTURE_ROOT/frontend/public/data/paintings.csv"
readonly FIXTURE_PICTURES="$FIXTURE_BACKEND/picture"
readonly FIXTURE_DATABASE="$FIXTURE_BACKEND/auralink.db"
readonly PACKAGE_LOG="$WORK_ROOT/maven-package.log"
readonly ACTIVATION_LOG="$WORK_ROOT/java-activation.log"
readonly SOCKET_EVIDENCE="$WORK_ROOT/listening-socket-evidence.txt"

mkdir -p -- \
    "$FIXTURE_BACKEND" \
    "$FIXTURE_BACKEND/src" \
    "$FIXTURE_ROOT/frontend/public/data" \
    "$FIXTURE_PICTURES"
cp -a -- "$BACKEND_ROOT/pom.xml" "$FIXTURE_BACKEND/pom.xml"
cp -a -- "$BACKEND_ROOT/src/main" "$FIXTURE_BACKEND/src/main"

python3 - "$BACKEND_ROOT/src/test/resources/db/legacy/inherited_schema_fixture.sql" \
    "$FIXTURE_DATABASE" <<'PY'
import sqlite3
import sys
from pathlib import Path

fixture = Path(sys.argv[1])
database = Path(sys.argv[2])
connection = sqlite3.connect(database)
try:
    connection.executescript(fixture.read_text(encoding="utf-8"))
    connection.commit()
    assert connection.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    assert connection.execute("SELECT COUNT(*) FROM users").fetchone()[0] == 2
    assert connection.execute("SELECT COUNT(*) FROM generation_logs").fetchone()[0] == 3
finally:
    connection.close()
PY

python3 - "$FIXTURE_CSV" <<'PY'
import csv
import sys
from pathlib import Path

headers = (
    "序号", "图像存储名称", "画作名称", "作者姓名", "作者出生年份", "作者出生地", "作者流派",
    "创作年代", "创作朝代", "实际尺寸", "收藏机构", "分类", "题材", "画作流派", "风格", "色彩",
    "构图", "意境", "笔法", "墨法", "绘画材料", "颜料", "印章", "文化符号", "文本生成",
    "音乐情境生成", "收集平台",
)
rows = (
    ("1", "matched", "匹配画作", "作者", "", "", "", "", "清", "", "", "", "", "", "", "",
     "", "", "", "", "", "", "", "", "官方文本", "官方音乐", ""),
    ("1", "missing", "缺图画作", "作者", "", "", "", "", "宋", "", "", "", "", "", "", "",
     "", "", "", "", "", "", "", "", "", "", ""),
)
path = Path(sys.argv[1])
with path.open("w", encoding="utf-8", newline="") as output:
    writer = csv.writer(output, lineterminator="\n")
    writer.writerow(headers)
    writer.writerows(rows)
PY

cat > "$WORK_ROOT/WriteFixtureJpegs.java" <<'JAVA'
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class WriteFixtureJpegs {
    public static void main(String[] args) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.RED.getRGB());
        for (String argument : args) {
            if (!ImageIO.write(image, "jpg", new File(argument))) {
                throw new IllegalStateException("JPEG writer unavailable");
            }
        }
    }
}
JAVA
java "$WORK_ROOT/WriteFixtureJpegs.java" \
    "$FIXTURE_PICTURES/matched.jpg" \
    "$FIXTURE_PICTURES/orphan.jpg"

readonly CATALOG_PROFILE="$(
    PYTHONDONTWRITEBYTECODE=1 python3 "$BACKEND_ROOT/scripts/round51_state.py" \
        catalog-profile \
        --csv "$FIXTURE_CSV" \
        --pictures "$FIXTURE_PICTURES"
)"
readonly CATALOG_FINGERPRINT="$(
    python3 -c 'import json,sys; print(json.load(sys.stdin)["catalogFingerprint"])' \
        <<< "$CATALOG_PROFILE"
)"
[[ "$CATALOG_FINGERPRINT" =~ ^[0-9a-f]{64}$ ]] \
    || fail "synthetic catalog fingerprint was not produced"

# The substitutions below affect only the disposable /tmp source copy. Exact
# one-match assertions make source drift fail closed rather than producing a
# weakened or ambiguous activation build.
python3 - \
    "$FIXTURE_BACKEND/src/main/java/com/auralink/ops/round51/Round51ActivationCommand.java" \
    "$FIXTURE_BACKEND/src/main/java/com/auralink/ops/round51/Round51DatabaseVerifier.java" \
    "$FIXTURE_ROOT" \
    "$CATALOG_FINGERPRINT" <<'PY'
import json
import re
import sys
from pathlib import Path

command_path = Path(sys.argv[1])
verifier_path = Path(sys.argv[2])
fixture_root = sys.argv[3]
fingerprint = sys.argv[4]

command = command_path.read_text(encoding="utf-8")
old_root = 'static final Path SERVER_LOCAL_ROOT = Path.of("/root/autodl-tmp/auralink");'
new_root = f"static final Path SERVER_LOCAL_ROOT = Path.of({json.dumps(fixture_root)});"
if command.count(old_root) != 1:
    raise SystemExit("fixed server-root declaration did not match exactly once")
command_path.write_text(command.replace(old_root, new_root), encoding="utf-8")

verifier = verifier_path.read_text(encoding="utf-8")
pattern = re.compile(
    r"static Expectations production\(\) \{\s*"
    r"return new Expectations\(\s*"
    r"7,\s*118,\s*11_067,\s*9_067,\s*2_000,\s*2,\s*"
    r"8_915,\s*9_068,\s*9_067,\s*"
    r'"a9cf4b05e374ecaa975c51c59eda6e2a6b1adf1e02badcb69994189c7554aff6"\);\s*'
    r"\}",
    re.MULTILINE,
)
replacement = """static Expectations production() {
            return new Expectations(
                    2,
                    3,
                    2,
                    1,
                    1,
                    1,
                    1,
                    1,
                    1,
                    \"%s\");
        }""" % fingerprint
verifier, count = pattern.subn(replacement, verifier)
if count != 1:
    raise SystemExit("production expectation block did not match exactly once")
verifier_path.write_text(verifier, encoding="utf-8")
PY

cat > "$FIXTURE_BACKEND/.env" <<'ENV'
# Synthetic packaged-CLI fixture; contains no production credentials.
AURALINK_JWT_SECRET=round51-packaged-cli-safe-test-placeholder-only
AURALINK_JWT_EXPIRATION_MS=604800000
AURALINK_FLYWAY_ENABLED=false
AURALINK_JPA_DDL_AUTO=none
AURALINK_PAINTING_CATALOG_IMPORT_ENABLED=false
ENV
chmod 600 "$FIXTURE_BACKEND/.env"

(
    cd "$FIXTURE_BACKEND"
    mvn -o \
        -Dmaven.repo.local="$MAVEN_REPOSITORY" \
        -Dmaven.test.skip=true \
        package
) > "$PACKAGE_LOG" 2>&1 \
    || fail "offline packaging failed; see $PACKAGE_LOG"

readonly PACKAGED_JAR="$FIXTURE_BACKEND/target/auralink-backend-0.0.1-SNAPSHOT.jar"
[[ -f "$PACKAGED_JAR" ]] || fail "repackaged Spring Boot JAR is missing"

snapshot_listeners() {
    python3 - <<'PY'
from pathlib import Path

listeners = set()
for name in ("/proc/net/tcp", "/proc/net/tcp6"):
    path = Path(name)
    if not path.exists():
        continue
    for line in path.read_text(encoding="ascii").splitlines()[1:]:
        fields = line.split()
        if len(fields) >= 10 and fields[3] == "0A":
            listeners.add(fields[9])
print(" ".join(sorted(listeners)))
PY
}

child_listening_sockets() {
    local pid="$1"
    python3 - "$pid" <<'PY'
import os
import re
import sys
from pathlib import Path

pid = sys.argv[1]
owned = set()
fd_root = Path("/proc") / pid / "fd"
try:
    descriptors = list(fd_root.iterdir())
except (FileNotFoundError, PermissionError):
    descriptors = []
for descriptor in descriptors:
    try:
        target = os.readlink(descriptor)
    except (FileNotFoundError, PermissionError, OSError):
        continue
    match = re.fullmatch(r"socket:\[(\d+)\]", target)
    if match:
        owned.add(match.group(1))

listening = set()
for name in ("/proc/net/tcp", "/proc/net/tcp6"):
    try:
        lines = Path(name).read_text(encoding="ascii").splitlines()[1:]
    except (FileNotFoundError, PermissionError):
        continue
    for line in lines:
        fields = line.split()
        if len(fields) >= 10 and fields[3] == "0A":
            listening.add(fields[9])
print(" ".join(sorted(owned & listening)))
PY
}

readonly LISTENERS_BEFORE="$(snapshot_listeners)"
(
    cd "$FIXTURE_ROOT"
    exec env \
        AURALINK_DATABASE_URL="jdbc:sqlite:$FIXTURE_DATABASE" \
        AURALINK_ENV_FILE="$FIXTURE_BACKEND/.env" \
        AURALINK_FLYWAY_ENABLED=false \
        AURALINK_JPA_DDL_AUTO=none \
        AURALINK_PAINTING_CATALOG_IMPORT_ENABLED=false \
        AURALINK_ROUND51_CONFIRM=ACTIVATE_AURALINK_2_0_CATALOG \
        java \
        -Dloader.main=com.auralink.ops.round51.Round51ActivationCommand \
        -cp "$PACKAGED_JAR" \
        org.springframework.boot.loader.launch.PropertiesLauncher \
        --project-root="$FIXTURE_ROOT"
) > "$ACTIVATION_LOG" 2>&1 &
activation_pid=$!

process_is_running() {
    local pid="$1"
    local state
    [[ -r "/proc/$pid/stat" ]] || return 1
    state="$(awk '{print $3}' "/proc/$pid/stat" 2>/dev/null || true)"
    [[ -n "$state" && "$state" != "Z" ]]
}

terminate_fixture_process() {
    local pid="$activation_pid"
    local attempts=0
    [[ -n "$pid" ]] || return 0
    if process_is_running "$pid"; then
        kill -TERM "$pid" 2>/dev/null || true
        while process_is_running "$pid" && [[ "$attempts" -lt 50 ]]; do
            attempts=$((attempts + 1))
            sleep 0.1
        done
        if process_is_running "$pid"; then
            kill -KILL "$pid" 2>/dev/null || true
        fi
    fi
    wait "$pid" 2>/dev/null || true
    activation_pid=""
}
trap terminate_fixture_process EXIT HUP INT TERM

listen_violation=""
termination_reason=""
samples=0
activation_started_at=$SECONDS
while process_is_running "$activation_pid"; do
    samples=$((samples + 1))
    child_listeners="$(child_listening_sockets "$activation_pid")"
    if [[ -n "$child_listeners" ]]; then
        listen_violation="$child_listeners"
        termination_reason="LISTEN_SOCKET_DETECTED"
        terminate_fixture_process
        break
    fi
    if (( SECONDS - activation_started_at >= ACTIVATION_DEADLINE_SECONDS )); then
        termination_reason="ACTIVATION_DEADLINE_EXCEEDED"
        terminate_fixture_process
        break
    fi
    sleep 0.05
done

activation_exit=0
if [[ -n "$termination_reason" ]]; then
    activation_exit=124
elif wait "$activation_pid"; then
    activation_exit=0
else
    activation_exit=$?
fi
activation_pid=""
trap - EXIT HUP INT TERM
readonly LISTENERS_AFTER="$(snapshot_listeners)"

printf 'listeners_before=%s\n' "$LISTENERS_BEFORE" > "$SOCKET_EVIDENCE"
printf 'child_samples=%s\n' "$samples" >> "$SOCKET_EVIDENCE"
printf 'child_listen_violation=%s\n' "$listen_violation" >> "$SOCKET_EVIDENCE"
printf 'termination_reason=%s\n' "$termination_reason" >> "$SOCKET_EVIDENCE"
printf 'listeners_after=%s\n' "$LISTENERS_AFTER" >> "$SOCKET_EVIDENCE"

[[ -z "$termination_reason" ]] \
    || fail "packaged activation was terminated: $termination_reason"
[[ "$activation_exit" -eq 0 ]] \
    || fail "packaged activation exited $activation_exit; see $ACTIVATION_LOG"
[[ -z "$listen_violation" ]] \
    || fail "packaged activation opened a listening TCP socket: $listen_violation"
[[ "$samples" -gt 0 ]] || fail "activation process was not sampled for listening sockets"

grep -Fq 'SERVER_LOCAL_ROOT_VERIFIED' "$ACTIVATION_LOG" \
    || fail "activation log omitted server-local root verification"
grep -Fq 'ROUND51_ACTIVATION_COMPLETED' "$ACTIVATION_LOG" \
    || fail "activation log omitted completion marker"
grep -Fq 'ROUND51_DATABASE_ACTIVATION_VERIFIED' "$ACTIVATION_LOG" \
    || fail "activation log omitted database verification marker"
if grep -Eiq 'Tomcat started|Starting service \[Tomcat\]|Netty started|ROUND51_ACTIVATION_ERROR' \
    "$ACTIVATION_LOG"; then
    fail "activation log indicates web startup or activation failure"
fi

python3 - "$FIXTURE_DATABASE" "$FIXTURE_BACKEND/temp_uploads/media-assets" <<'PY'
import sqlite3
import sys
import uuid
from pathlib import Path

database = Path(sys.argv[1])
managed = Path(sys.argv[2])
connection = sqlite3.connect(f"file:{database}?mode=ro", uri=True)
try:
    scalar = lambda sql: connection.execute(sql).fetchone()[0]
    assert scalar("PRAGMA integrity_check") == "ok"
    assert connection.execute("PRAGMA foreign_key_check").fetchall() == []
    assert scalar("SELECT COUNT(*) FROM users") == 2
    assert scalar("SELECT COUNT(*) FROM generation_logs") == 3
    assert scalar("SELECT COUNT(*) FROM flyway_schema_history "
                  "WHERE version='1' AND type='BASELINE' AND success=1") == 1
    assert scalar("SELECT COUNT(*) FROM flyway_schema_history "
                  "WHERE version='2' AND type='SQL' AND success=1") == 1
    assert scalar("SELECT COUNT(*) FROM paintings") == 2
    assert scalar("SELECT COUNT(*) FROM paintings WHERE image_available=1") == 1
    assert scalar("SELECT COUNT(*) FROM paintings WHERE image_available=0") == 1
    assert scalar("SELECT COUNT(*) FROM paintings WHERE visible_in_gallery=1") == 1
    assert scalar("SELECT COUNT(*) FROM media_assets") == 1
    assert scalar("SELECT COUNT(*) FROM media_assets "
                  "WHERE source_type='CATALOG_REFERENCE' AND visibility='PUBLIC' "
                  "AND status='ACTIVE'") == 1
    assert scalar("SELECT COUNT(*) FROM catalog_import_runs WHERE status='SUCCESS'") == 1
    assert scalar("SELECT COUNT(*) FROM catalog_import_runs WHERE status='SKIPPED'") == 1
    assert scalar("SELECT COUNT(DISTINCT source_key) FROM paintings") == 2
    assert scalar("SELECT COUNT(DISTINCT public_id) FROM paintings") == 2
    assert scalar("SELECT COUNT(DISTINCT public_id) FROM media_assets") == 1
    for (value,) in connection.execute("SELECT public_id FROM paintings UNION ALL SELECT public_id FROM media_assets"):
        assert str(uuid.UUID(value)) == value
finally:
    connection.close()

if managed.exists():
    assert not any(path.is_file() for path in managed.rglob("*")), \
        "catalog activation copied an official image into managed storage"
PY

printf '%s\n' 'ROUND51_PACKAGED_CLI_REGRESSION_PASS'
printf 'fixture_root=%s\n' "$FIXTURE_ROOT"
printf 'fixture_database=%s\n' "$FIXTURE_DATABASE"
printf 'activation_log=%s\n' "$ACTIVATION_LOG"
printf 'socket_evidence=%s\n' "$SOCKET_EVIDENCE"
printf 'catalog_fingerprint=%s\n' "$CATALOG_FINGERPRINT"
printf 'properties_launcher=org.springframework.boot.loader.launch.PropertiesLauncher\n'
printf 'activation_main=com.auralink.ops.round51.Round51ActivationCommand\n'
printf 'http_listen_bound=no\n'
