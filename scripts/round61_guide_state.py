#!/usr/bin/env python3
"""Secret-safe state primitives for the Round 6.1 live Guide validation.

The shell workflow owns the fixed server-local root, Git and process guards.
This helper owns deterministic environment, SQLite, response and evidence
validation.  It deliberately has no provider client and cannot call Qwen.
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import sqlite3
import stat
import sys
import tempfile
from typing import Any, Iterable, Mapping
from urllib.parse import urlsplit
import uuid


EXPECTED_PRODUCTION = {
    "users": 7,
    "generationLogs": 118,
    "paintings": 11_067,
    "catalogMediaAssets": 9_067,
    "flywayHistory": 2,
}
IMMUTABLE_VALIDATION_TABLES = (
    "users",
    "generation_logs",
    "paintings",
    "media_assets",
    "user_workflows",
    "creations",
    "creation_steps",
    "painting_favorites",
    "creation_favorites",
)
SNAPSHOT_TABLES = IMMUTABLE_VALIDATION_TABLES + (
    "catalog_import_runs",
    "painting_guides",
)
GUIDE_SECTIONS = (
    "artistAndEra",
    "subjectAndScene",
    "composition",
    "brushworkAndInk",
    "colorAndMaterial",
    "artisticConception",
    "culturalMeaning",
    "musicAssociation",
)
PUBLIC_GUIDE_FIELDS = {
    "paintingId",
    "schemaVersion",
    "summary",
    "sections",
    "highlights",
    "knowledgeReferences",
    "cacheStatus",
    "generatedAt",
    "updatedAt",
}
INTERNAL_PUBLIC_FIELDS = {
    "id", "sourceHash", "source_hash", "resultJson", "result_json",
    "provider", "model", "prompt", "reasoning", "reasoning_content",
    "user", "userId", "paintingInternalId",
}
SECRET_ENV_KEYS = {
    "QWEN_API_KEY",
    "AURALINK_GUIDE_INTERNAL_TOKEN",
    "AURALINK_JWT_SECRET",
}
OFFICIAL_PUBLIC_HOSTS = {
    "dashscope.aliyuncs.com": ("PUBLIC", "cn-beijing"),
    "dashscope-intl.aliyuncs.com": ("PUBLIC", "ap-southeast-1"),
    "dashscope-us.aliyuncs.com": ("PUBLIC", "us-east-1"),
}
WORKSPACE_HOST = re.compile(
    r"^(?P<workspace>[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\."
    r"(?P<region>cn-beijing|ap-southeast-1|us-east-1)\.maas\.aliyuncs\.com$"
)
ENV_LINE = re.compile(r"^(?P<prefix>\s*)(?P<key>[A-Za-z_][A-Za-z0-9_]*)=(?P<value>.*)$")
HAN = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")
HTML = re.compile(r"(?is)<\s*/?\s*[a-z][^>]*>")
FORBIDDEN_SELF_REFERENCE = re.compile(r"作为\s*(?:一个\s*)?(?:AI|人工智能)|我是\s*(?:AI|人工智能)", re.I)
FORBIDDEN_PROMPT_LEAK = re.compile(r"系统提示|system\s+prompt|chain[ -]?of[ -]?thought|思维链", re.I)
SHA256 = re.compile(r"^[0-9a-f]{64}$")
REVIEWED_KNOWLEDGE = {
    "poetryGraph": {
        "relativePath": "frontend/public/data/poetry-graph.json",
        "sha256": "899511b6b7d02e6ba515986db651c67b3702497d2031bdbb75029c34491da2c0",
        "maximumBytes": 1_048_576,
    },
    "poetryStats": {
        "relativePath": "frontend/public/data/poetry-stats.json",
        "sha256": "27e91284d28c05c9b7dde9fcb4181c713761d3c2eebf84816b45c9f7d9af7501",
        "maximumBytes": 262_144,
    },
}


class Round61Error(RuntimeError):
    """A classified, operator-safe validation failure."""

    def __init__(self, code: str, summary: str):
        super().__init__(summary)
        self.code = code
        self.summary = summary


def fail(code: str, summary: str) -> None:
    raise Round61Error(code, summary)


def _absolute(path: Path) -> Path:
    return Path(os.path.abspath(os.fspath(path)))


def _require_regular(path: Path, label: str, private: bool = False) -> Path:
    absolute = _absolute(path)
    try:
        metadata = os.lstat(absolute)
    except FileNotFoundError:
        fail("FILE_MISSING", f"{label} is missing")
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
        fail("UNSAFE_FILE", f"{label} must be a regular non-symlink file")
    if private and (metadata.st_uid != os.getuid() or stat.S_IMODE(metadata.st_mode) & 0o077):
        fail("UNSAFE_FILE_PERMISSIONS", f"{label} must be owner-only")
    return absolute


def _require_safe_parent(path: Path, label: str) -> Path:
    parent = _absolute(path).parent
    if parent.is_symlink() or not parent.is_dir():
        fail("UNSAFE_DESTINATION", f"{label} parent must be a non-symlink directory")
    resolved = parent.resolve(strict=True)
    if resolved != parent:
        fail("UNSAFE_DESTINATION", f"{label} parent must not traverse symlinks")
    return resolved


def _fsync_directory(directory: Path) -> None:
    descriptor = os.open(directory, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _write_private_bytes(destination: Path, content: bytes, replace: bool = False) -> None:
    destination = _absolute(destination)
    parent = _require_safe_parent(destination, "output")
    if destination.exists() and not replace:
        fail("OUTPUT_EXISTS", "refusing to overwrite an existing output")
    descriptor, temporary_name = tempfile.mkstemp(prefix=".round61-", dir=parent)
    temporary = Path(temporary_name)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "wb") as output:
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, destination)
        os.chmod(destination, 0o600)
        _fsync_directory(parent)
    except BaseException:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass
        raise


def _write_json(destination: Path, value: Any, replace: bool = False) -> None:
    content = (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")
    _write_private_bytes(destination, content, replace=replace)


def _emit(value: Any, output: Path | None = None) -> None:
    if output is None:
        print(json.dumps(value, ensure_ascii=False, sort_keys=True))
    else:
        _write_json(output, value)
        print("SAFE_JSON_WRITTEN")


def _read_json(path: Path, label: str, maximum: int = 1_048_576) -> Any:
    regular = _require_regular(path, label)
    if regular.stat().st_size > maximum:
        fail("JSON_TOO_LARGE", f"{label} exceeds its size limit")
    try:
        return json.loads(regular.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeError):
        fail("INVALID_JSON", f"{label} is not valid JSON")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _parse_env(path: Path, require_private: bool = True) -> tuple[list[str], dict[str, str], dict[str, int]]:
    regular = _require_regular(path, "backend/.env", private=require_private)
    if regular.stat().st_size > 1024 * 1024:
        fail("ENV_TOO_LARGE", "backend/.env is unexpectedly large")
    try:
        text = regular.read_text(encoding="utf-8")
    except UnicodeError:
        fail("ENV_INVALID", "backend/.env is not valid UTF-8")
    lines = text.splitlines(keepends=True)
    values: dict[str, str] = {}
    positions: dict[str, int] = {}
    for index, original in enumerate(lines):
        line = original.rstrip("\r\n")
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        match = ENV_LINE.fullmatch(line)
        if match is None:
            fail("ENV_INVALID", "backend/.env contains an unsupported assignment line")
        key = match.group("key")
        if key in values:
            fail("ENV_DUPLICATE_KEY", f"backend/.env repeats {key}")
        value = match.group("value").strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        values[key] = value
        positions[key] = index
    return lines, values, positions


def _validate_base_url(raw: str) -> dict[str, str]:
    if not raw:
        fail("QWEN_BASE_URL_MISSING", "QWEN_BASE_URL is blank")
    if any(ord(character) < 0x20 or ord(character) > 0x7e for character in raw):
        fail("QWEN_BASE_URL_INVALID", "QWEN_BASE_URL must contain printable ASCII only")
    if "\\" in raw or "%" in raw:
        fail("QWEN_BASE_URL_INVALID", "QWEN_BASE_URL contains unsafe encoded or path characters")
    try:
        parsed = urlsplit(raw)
        port = parsed.port
    except ValueError:
        fail("QWEN_BASE_URL_INVALID", "QWEN_BASE_URL is malformed")
    if parsed.scheme != "https":
        fail("QWEN_BASE_URL_INVALID", "QWEN_BASE_URL must use HTTPS")
    if parsed.username is not None or parsed.password is not None:
        fail("QWEN_BASE_URL_INVALID", "QWEN_BASE_URL must not contain user information")
    if parsed.query or parsed.fragment:
        fail("QWEN_BASE_URL_INVALID", "QWEN_BASE_URL must not contain a query or fragment")
    if port is not None or parsed.netloc.lower() != (parsed.hostname or "").lower():
        fail("QWEN_BASE_URL_INVALID", "QWEN_BASE_URL must not specify an explicit port")
    if parsed.path not in {"/compatible-mode/v1", "/compatible-mode/v1/"}:
        fail("QWEN_BASE_URL_INVALID", "QWEN_BASE_URL path must be the compatible-mode API root")
    host = (parsed.hostname or "").lower()
    if host.endswith("."):
        fail("QWEN_BASE_URL_INVALID", "QWEN_BASE_URL must not use a trailing-dot host")
    if host in OFFICIAL_PUBLIC_HOSTS:
        host_class, region = OFFICIAL_PUBLIC_HOSTS[host]
    else:
        workspace = WORKSPACE_HOST.fullmatch(host)
        if workspace is None:
            fail("QWEN_BASE_URL_NOT_ALLOWED", "QWEN_BASE_URL host is not a reviewed Alibaba Model Studio host")
        host_class, region = "WORKSPACE", workspace.group("region")
    safe_host = host if host_class == "PUBLIC" else f"*.{region}.maas.aliyuncs.com"
    return {"host": safe_host, "hostClass": host_class, "region": region}


def preflight_env(path: Path) -> dict[str, Any]:
    _, values, _ = _parse_env(path)
    if not values.get("QWEN_API_KEY", "").strip():
        fail("QWEN_API_KEY_MISSING", "QWEN_API_KEY is blank")
    if values.get("QWEN_MODEL", "").strip() != "qwen3-vl-plus":
        fail("QWEN_MODEL_INVALID", "QWEN_MODEL must be qwen3-vl-plus for initial validation")
    if not values.get("AURALINK_GUIDE_INTERNAL_TOKEN", "").strip():
        fail("GUIDE_INTERNAL_TOKEN_MISSING", "AURALINK_GUIDE_INTERNAL_TOKEN is blank")
    guide_host = values.get("AURALINK_GUIDE_SERVICE_HOST", "").strip() or "127.0.0.1"
    guide_port = values.get("AURALINK_GUIDE_SERVICE_PORT", "").strip() or "5003"
    service_url = values.get("GUIDE_AI_SERVICE_URL", "").strip() or "http://127.0.0.1:5003"
    guide_enabled = values.get("AURALINK_GUIDE_ENABLED", "").strip().lower() or "false"
    if guide_enabled not in {"true", "false"}:
        fail("GUIDE_ENABLED_INVALID", "AURALINK_GUIDE_ENABLED must be true or false")
    if guide_host != "127.0.0.1":
        fail("GUIDE_BIND_INVALID", "Guide Service host must be 127.0.0.1")
    if guide_port != "5003":
        fail("GUIDE_BIND_INVALID", "Guide Service port must be 5003")
    if service_url.rstrip("/") != "http://127.0.0.1:5003":
        fail("GUIDE_SERVICE_URL_INVALID", "GUIDE_AI_SERVICE_URL must target loopback port 5003")
    base = _validate_base_url(values.get("QWEN_BASE_URL", "").strip())
    return {
        "apiKeyPresent": True,
        "baseUrlHost": base["host"],
        "baseUrlHostClass": base["hostClass"],
        "baseUrlRegion": base["region"],
        "guideCanBeEnabled": True,
        "guideHost": "127.0.0.1",
        "guideInternalTokenPresent": True,
        "guidePort": 5003,
        "model": "qwen3-vl-plus",
    }


def verify_static_knowledge(project_root: Path) -> dict[str, Any]:
    """Pin the ignored Guide knowledge inputs to the reviewed Round 6 corpus.

    These files intentionally remain outside the backend Git history.  The live
    validation therefore verifies their exact server-local paths, JSON shapes,
    byte bounds and Round 6 SHA-256 values before a paid provider call.
    """
    root = _absolute(project_root)
    try:
        metadata = os.lstat(root)
    except FileNotFoundError:
        fail("PROJECT_ROOT_MISSING", "reviewed project root is missing")
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
        fail("PROJECT_ROOT_UNSAFE", "reviewed project root must be a non-symlink directory")
    if root.resolve(strict=True) != root:
        fail("PROJECT_ROOT_UNSAFE", "reviewed project root resolves unexpectedly")

    loaded: dict[str, Any] = {}
    for name, specification in REVIEWED_KNOWLEDGE.items():
        relative = Path(str(specification["relativePath"]))
        candidate = root / relative
        regular = _require_regular(candidate, f"reviewed {name}")
        if regular.resolve(strict=True) != regular or not regular.is_relative_to(root):
            fail("KNOWLEDGE_PATH_UNSAFE", f"reviewed {name} path resolves unexpectedly")
        size = regular.stat().st_size
        if size > int(specification["maximumBytes"]):
            fail("KNOWLEDGE_TOO_LARGE", f"reviewed {name} exceeds its size limit")
        digest = _sha256(regular)
        if digest != specification["sha256"]:
            fail("KNOWLEDGE_FINGERPRINT_MISMATCH", f"reviewed {name} fingerprint does not match")
        loaded[name] = {
            "relativePath": relative.as_posix(),
            "sha256": digest,
            "sizeBytes": size,
        }

    graph = _read_json(
        root / REVIEWED_KNOWLEDGE["poetryGraph"]["relativePath"],
        "reviewed poetry graph",
        int(REVIEWED_KNOWLEDGE["poetryGraph"]["maximumBytes"]),
    )
    statistics = _read_json(
        root / REVIEWED_KNOWLEDGE["poetryStats"]["relativePath"],
        "reviewed poetry statistics",
        int(REVIEWED_KNOWLEDGE["poetryStats"]["maximumBytes"]),
    )
    if not isinstance(graph, dict) or set(graph) != {"nodes", "links"}:
        fail("KNOWLEDGE_STRUCTURE_INVALID", "reviewed poetry graph structure is invalid")
    if not isinstance(graph["nodes"], list) or not isinstance(graph["links"], list):
        fail("KNOWLEDGE_STRUCTURE_INVALID", "reviewed poetry graph collections are invalid")
    expected_statistics = {"overview", "entityTypeDistribution", "relationTypeDistribution"}
    if not isinstance(statistics, dict) or set(statistics) != expected_statistics:
        fail("KNOWLEDGE_STRUCTURE_INVALID", "reviewed poetry statistics structure is invalid")
    if not isinstance(statistics["overview"], dict):
        fail("KNOWLEDGE_STRUCTURE_INVALID", "reviewed poetry statistics overview is invalid")
    if not isinstance(statistics["entityTypeDistribution"], list) \
            or not isinstance(statistics["relationTypeDistribution"], list):
        fail("KNOWLEDGE_STRUCTURE_INVALID", "reviewed poetry statistics distributions are invalid")

    return {
        "result": "REVIEWED_STATIC_KNOWLEDGE_VERIFIED",
        "poetryGraph": {
            **loaded["poetryGraph"],
            "linkCount": len(graph["links"]),
            "nodeCount": len(graph["nodes"]),
        },
        "poetryStats": {
            **loaded["poetryStats"],
            "entityTypeCount": len(statistics["entityTypeDistribution"]),
            "relationTypeCount": len(statistics["relationTypeDistribution"]),
        },
    }


@contextmanager
def _readonly(database: Path):
    regular = _require_regular(database, "SQLite database")
    connection = sqlite3.connect(f"{regular.as_uri()}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA query_only=ON")
    try:
        yield connection
    finally:
        connection.close()


def _tables(connection: sqlite3.Connection) -> set[str]:
    return {str(row[0]) for row in connection.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
    )}


def _quote_identifier(value: str) -> str:
    return '"' + value.replace('"', '""') + '"'


def _digest_value(digest: Any, value: Any) -> None:
    if value is None:
        digest.update(b"N\0")
    elif isinstance(value, bytes):
        digest.update(b"B" + value.hex().encode("ascii") + b"\0")
    else:
        digest.update(type(value).__name__.encode("ascii") + b":" + str(value).encode("utf-8") + b"\0")


def _table_digest(connection: sqlite3.Connection, table: str, max_id: int | None = None) -> str:
    digest = hashlib.sha256()
    digest.update(table.encode("utf-8") + b"\n")
    where = " WHERE id <= ?" if max_id is not None else ""
    query = f"SELECT * FROM {_quote_identifier(table)}{where} ORDER BY rowid"
    parameters: tuple[Any, ...] = (max_id,) if max_id is not None else ()
    for row in connection.execute(query, parameters):
        for value in row:
            _digest_value(digest, value)
        digest.update(b"\n")
    return digest.hexdigest()


def _schema_digest(connection: sqlite3.Connection) -> str:
    digest = hashlib.sha256()
    for row in connection.execute(
        "SELECT type,name,tbl_name,COALESCE(sql,'') FROM sqlite_master "
        "WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name"
    ):
        digest.update("\x1f".join(str(value) for value in row).encode("utf-8") + b"\n")
    return digest.hexdigest()


def inspect_database(database: Path) -> dict[str, Any]:
    regular = _require_regular(database, "SQLite database")
    with _readonly(regular) as connection:
        tables = _tables(connection)
        integrity = [str(row[0]) for row in connection.execute("PRAGMA integrity_check")]
        foreign_keys = [tuple(row) for row in connection.execute("PRAGMA foreign_key_check")]

        def count(table: str, where: str = "", args: tuple[Any, ...] = ()) -> int | None:
            if table not in tables:
                return None
            return int(connection.execute(
                f"SELECT COUNT(*) FROM {_quote_identifier(table)} {where}", args
            ).fetchone()[0])

        counts = {
            "users": count("users"),
            "generationLogs": count("generation_logs"),
            "paintings": count("paintings"),
            "catalogMediaAssets": count("media_assets", "WHERE source_type=?", ("CATALOG_REFERENCE",)),
            "mediaAssets": count("media_assets"),
            "flywayHistory": count("flyway_schema_history"),
            "paintingGuides": count("painting_guides"),
            "catalogImportRuns": count("catalog_import_runs"),
            "creations": count("creations"),
            "creationSteps": count("creation_steps"),
            "paintingFavorites": count("painting_favorites"),
            "creationFavorites": count("creation_favorites"),
            "userWorkflows": count("user_workflows"),
        }
        table_digests = {
            table: _table_digest(connection, table)
            for table in SNAPSHOT_TABLES if table in tables
        }
        max_ids = {
            table: int(connection.execute(
                f"SELECT COALESCE(MAX(id),0) FROM {_quote_identifier(table)}"
            ).fetchone()[0])
            for table in SNAPSHOT_TABLES if table in tables
        }
        guide_state: dict[str, Any] = {
            "healthy": counts["paintingGuides"] == 0,
            "paintingId": None,
            "publicId": None,
        }
        if counts["paintingGuides"] == 1:
            guide = connection.execute(
                "SELECT g.public_id,g.source_hash,g.status,g.result_json,g.generated_at,g.updated_at,"
                "p.public_id AS painting_public_id FROM painting_guides g "
                "JOIN paintings p ON p.id=g.painting_id"
            ).fetchone()
            healthy = False
            if guide is not None:
                try:
                    public_id_valid = str(uuid.UUID(guide["public_id"])) == guide["public_id"]
                    painting_id_valid = (
                        str(uuid.UUID(guide["painting_public_id"])) == guide["painting_public_id"]
                    )
                    stored = _validate_result(json.loads(guide["result_json"]))
                    healthy = bool(
                        public_id_valid
                        and painting_id_valid
                        and guide["status"] == "SUCCESS"
                        and isinstance(guide["source_hash"], str)
                        and SHA256.fullmatch(guide["source_hash"])
                        and guide["generated_at"] is not None
                        and guide["updated_at"] is not None
                        and stored
                    )
                except (ValueError, TypeError, json.JSONDecodeError, Round61Error):
                    healthy = False
                guide_state = {
                    "healthy": healthy,
                    "paintingId": guide["painting_public_id"],
                    "publicId": guide["public_id"],
                }
        return {
            "counts": counts,
            "databaseSha256": _sha256(regular),
            "foreignKeyViolations": len(foreign_keys),
            "integrityCheck": integrity,
            "schemaSha256": _schema_digest(connection),
            "guideState": guide_state,
            "tableDigests": table_digests,
            "tableMaxIds": max_ids,
            "tables": sorted(tables),
        }


def verify_production_state(snapshot: Mapping[str, Any], expected_guides: int) -> None:
    if snapshot.get("integrityCheck") != ["ok"]:
        fail("DATABASE_INTEGRITY_FAILED", "production database integrity_check failed")
    if snapshot.get("foreignKeyViolations") != 0:
        fail("DATABASE_FOREIGN_KEY_FAILED", "production database has foreign-key violations")
    counts = snapshot.get("counts") if isinstance(snapshot.get("counts"), dict) else {}
    for key, expected in EXPECTED_PRODUCTION.items():
        if counts.get(key) != expected:
            fail("DATABASE_COUNT_MISMATCH", f"production database {key} count differs from reviewed state")
    if counts.get("paintingGuides") != expected_guides:
        fail("GUIDE_COUNT_MISMATCH", "production painting_guides count differs from the expected validation state")
    if expected_guides == 1 and snapshot.get("guideState", {}).get("healthy") is not True:
        fail("GUIDE_ROW_INVALID", "the existing live Guide is not healthy")


def verify_backup(source: Path, backup: Path) -> dict[str, Any]:
    source_snapshot = inspect_database(source)
    backup_snapshot = inspect_database(backup)
    for key in ("schemaSha256", "integrityCheck", "foreignKeyViolations", "counts", "tableDigests"):
        if source_snapshot[key] != backup_snapshot[key]:
            fail("BACKUP_VERIFICATION_FAILED", f"SQLite backup differs logically for {key}")
    return {
        "backupDatabaseSha256": backup_snapshot["databaseSha256"],
        "counts": backup_snapshot["counts"],
        "integrityCheck": backup_snapshot["integrityCheck"],
        "logicalVerification": "MATCH",
        "schemaSha256": backup_snapshot["schemaSha256"],
        "sourceDatabaseSha256": source_snapshot["databaseSha256"],
    }


def sqlite_backup(source: Path, destination: Path) -> dict[str, Any]:
    source = _require_regular(source, "SQLite source")
    destination = _absolute(destination)
    parent = _require_safe_parent(destination, "SQLite backup")
    if destination.exists():
        fail("OUTPUT_EXISTS", "refusing to overwrite an existing SQLite backup")
    try:
        with _readonly(source) as source_connection:
            destination_connection = sqlite3.connect(destination)
            try:
                source_connection.backup(destination_connection)
                destination_connection.commit()
            finally:
                destination_connection.close()
        os.chmod(destination, 0o600)
        with destination.open("rb") as saved:
            os.fsync(saved.fileno())
        _fsync_directory(parent)
        return verify_backup(source, destination)
    except BaseException:
        try:
            destination.unlink()
            _fsync_directory(parent)
        except FileNotFoundError:
            pass
        raise


def restore_database(backup: Path, database: Path) -> dict[str, Any]:
    backup = _require_regular(backup, "verified SQLite backup", private=True)
    backup_snapshot = inspect_database(backup)
    if backup_snapshot["integrityCheck"] != ["ok"] or backup_snapshot["foreignKeyViolations"]:
        fail("BACKUP_VERIFICATION_FAILED", "refusing to restore an invalid SQLite backup")
    database = _absolute(database)
    parent = _require_safe_parent(database, "SQLite database")
    sidecars = [Path(str(database) + suffix) for suffix in ("-wal", "-shm")]
    for sidecar in sidecars:
        if sidecar.exists():
            metadata = os.lstat(sidecar)
            if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
                fail("UNSAFE_DATABASE_SIDECAR", "database sidecar is not a regular file")
    descriptor, temporary_name = tempfile.mkstemp(prefix=".round61-restore-", dir=parent)
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        temporary.unlink()
        with _readonly(backup) as source_connection:
            destination_connection = sqlite3.connect(temporary)
            try:
                source_connection.backup(destination_connection)
                destination_connection.commit()
            finally:
                destination_connection.close()
        os.chmod(temporary, 0o600)
        restored_snapshot = inspect_database(temporary)
        for key in ("schemaSha256", "counts", "tableDigests"):
            if restored_snapshot[key] != backup_snapshot[key]:
                fail("RESTORE_VERIFICATION_FAILED", f"restored database differs for {key}")
        os.replace(temporary, database)
        os.chmod(database, 0o600)
        for sidecar in sidecars:
            if sidecar.exists():
                sidecar.unlink()
        _fsync_directory(parent)
    finally:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass
    return verify_backup(backup, database)


def backup_env(source: Path, destination: Path) -> None:
    source = _require_regular(source, "backend/.env", private=True)
    _write_private_bytes(destination, source.read_bytes())


def restore_env(backup: Path, env_file: Path) -> None:
    backup = _require_regular(backup, "environment backup", private=True)
    _write_private_bytes(env_file, backup.read_bytes(), replace=True)


def generate_internal_token(env_file: Path) -> str:
    lines, values, positions = _parse_env(env_file)
    existing = values.get("AURALINK_GUIDE_INTERNAL_TOKEN", "")
    if existing.strip():
        return "INTERNAL_TOKEN_ALREADY_CONFIGURED"
    token = secrets.token_urlsafe(32)
    if len(token.encode("utf-8")) < 32:
        fail("TOKEN_GENERATION_FAILED", "generated internal token lacks 256 bits of source randomness")
    rendered = f"AURALINK_GUIDE_INTERNAL_TOKEN={token}\n"
    if "AURALINK_GUIDE_INTERNAL_TOKEN" in positions:
        index = positions["AURALINK_GUIDE_INTERNAL_TOKEN"]
        newline = "\r\n" if lines[index].endswith("\r\n") else "\n"
        lines[index] = rendered.rstrip("\n") + newline
    else:
        if lines and not lines[-1].endswith(("\n", "\r")):
            lines[-1] += "\n"
        lines.append(rendered)
    _write_private_bytes(env_file, "".join(lines).encode("utf-8"), replace=True)
    return "INTERNAL_TOKEN_GENERATED"


def _meaningful(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip()) and value.strip() != "0"


def select_painting(database: Path, requested_id: str | None = None) -> dict[str, Any]:
    if requested_id is not None:
        try:
            requested_id = str(uuid.UUID(requested_id))
        except (ValueError, AttributeError):
            fail("INVALID_PAINTING_ID", "reviewed Painting ID is not a canonical UUID")
    with _readonly(database) as connection:
        rows = connection.execute(
            "SELECT public_id,source_key,title,author_name,creation_dynasty_normalized,"
            "generated_text,music_scene_description,artistic_conception,composition,"
            "brushwork,ink_method,subject,painting_school,style,color,cultural_symbol "
            "FROM paintings WHERE status='ACTIVE' AND image_available=1 "
            "AND visible_in_gallery=1" + (" AND public_id=?" if requested_id else ""),
            (requested_id,) if requested_id else (),
        ).fetchall()
    if not rows:
        fail("PAINTING_NOT_FOUND", "no eligible official Painting was found")

    def qualify(row: sqlite3.Row) -> bool:
        return (
            _meaningful(row["title"])
            and _meaningful(row["generated_text"])
            and _meaningful(row["music_scene_description"])
            and _meaningful(row["artistic_conception"])
            and _meaningful(row["composition"])
            and (_meaningful(row["brushwork"]) or _meaningful(row["ink_method"]))
        )

    rich = [row for row in rows if qualify(row)]
    if not rich:
        fail("PAINTING_SOURCE_QUALITY_LOW", "no Painting satisfies the minimum Guide source-quality profile")
    scored: list[tuple[int, str, str, sqlite3.Row]] = []
    coverage_fields = (
        "title", "author_name", "creation_dynasty_normalized", "generated_text",
        "music_scene_description", "artistic_conception", "composition", "brushwork",
        "ink_method", "subject", "painting_school", "style", "color", "cultural_symbol",
    )
    for row in rich:
        score = sum(1 for field in coverage_fields if _meaningful(row[field]))
        scored.append((-score, str(row["source_key"]), str(row["public_id"]), row))
    _, _, _, selected = min(scored)
    painting_id = str(selected["public_id"])
    try:
        if str(uuid.UUID(painting_id)) != painting_id:
            raise ValueError
    except ValueError:
        fail("INVALID_PAINTING_ID", "selected Painting has a non-canonical public UUID")
    score = sum(1 for field in coverage_fields if _meaningful(selected[field]))
    return {
        "annotationCoverageScore": score,
        "author": selected["author_name"],
        "dynasty": selected["creation_dynasty_normalized"],
        "paintingId": painting_id,
        "selection": "REVIEWED_OVERRIDE" if requested_id else "DETERMINISTIC_RICH_DEFAULT",
        "title": selected["title"],
    }


def build_login_request(username: str, output_fd: int) -> None:
    if output_fd < 3:
        fail("UNSAFE_SECRET_OUTPUT", "login JSON requires an explicit pipe descriptor of 3 or greater")
    username = username.strip()
    if not username or len(username) > 255 or any(character in username for character in "\r\n\0"):
        fail("INVALID_USERNAME", "username is invalid")
    password = sys.stdin.readline()
    if password.endswith("\n"):
        password = password[:-1]
    if password.endswith("\r"):
        password = password[:-1]
    if not password:
        fail("PASSWORD_MISSING", "password input is blank")
    payload = json.dumps(
        {"username": username, "password": password},
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    try:
        with os.fdopen(os.dup(output_fd), "wb", closefd=True) as destination:
            destination.write(payload)
            destination.flush()
    except OSError:
        fail("LOGIN_PIPE_FAILED", "could not write login JSON to the private pipe")


def extract_token(response_file: Path, token_file: Path) -> None:
    response = _read_json(response_file, "login response", maximum=64 * 1024)
    if not isinstance(response, dict) or set(response) != {"success", "message", "data"}:
        fail("LOGIN_RESPONSE_INVALID", "login response shape is invalid")
    data = response.get("data")
    if response.get("success") is not True or not isinstance(data, dict):
        fail("LOGIN_FAILED", "login response did not report success")
    if set(data) != {"token", "userId", "username", "fullName"}:
        fail("LOGIN_RESPONSE_INVALID", "login response data shape is invalid")
    token = data.get("token")
    if not isinstance(token, str) or not token.strip() or len(token) > 16_384 or any(c.isspace() for c in token):
        fail("LOGIN_FAILED", "login response did not contain a valid token")
    _write_private_bytes(token_file, token.encode("utf-8"))


def write_auth_config(token_file: Path, output: Path) -> None:
    token_path = _require_regular(token_file, "JWT token file", private=True)
    token = token_path.read_text(encoding="utf-8")
    if not token or any(character.isspace() for character in token):
        fail("TOKEN_INVALID", "JWT token file is invalid")
    escaped = token.replace("\\", "\\\\").replace('"', '\\"')
    _write_private_bytes(output, f'header = "Authorization: Bearer {escaped}"\n'.encode("utf-8"))


def validate_health(path: Path, service: str) -> dict[str, Any]:
    value = _read_json(path, f"{service} health response", maximum=64 * 1024)
    if not isinstance(value, dict) or value.get("status") not in {"UP", "ok", "OK"}:
        fail("HEALTH_CHECK_FAILED", f"{service} health response is not healthy")
    if service == "guide":
        allowed = {"status", "providerConfigured", "modelConfigured"}
        if set(value) != allowed or value.get("providerConfigured") is not True or value.get("modelConfigured") is not True:
            fail("GUIDE_HEALTH_INVALID", "Guide Service health is not ready")
    return {"service": service, "status": "HEALTHY"}


def _require_text(value: Any, field: str, maximum: int, chinese: bool = False) -> str:
    if not isinstance(value, str) or not value.strip():
        fail("GUIDE_RESPONSE_INVALID", f"{field} must be nonblank text")
    normalized = value.strip()
    if len(normalized) > maximum:
        fail("GUIDE_RESPONSE_INVALID", f"{field} exceeds its size limit")
    if "```" in normalized or HTML.search(normalized):
        fail("GUIDE_CONTENT_UNSAFE", f"{field} contains forbidden markup")
    if FORBIDDEN_SELF_REFERENCE.search(normalized) or FORBIDDEN_PROMPT_LEAK.search(normalized):
        fail("GUIDE_CONTENT_UNSAFE", f"{field} contains forbidden self-reference or prompt leakage")
    if chinese and not HAN.search(normalized):
        fail("GUIDE_RESPONSE_INVALID", f"{field} must contain Chinese text")
    return normalized


def _validate_result(value: Any) -> dict[str, Any]:
    expected = {"schemaVersion", "summary", "sections", "highlights", "knowledgeReferences"}
    if not isinstance(value, dict) or set(value) != expected:
        fail("GUIDE_RESPONSE_INVALID", "Guide result fields do not match schema version 1")
    if value.get("schemaVersion") != "1":
        fail("GUIDE_RESPONSE_INVALID", "Guide schemaVersion must be 1")
    summary = _require_text(value.get("summary"), "summary", 2_000, chinese=True)
    raw_sections = value.get("sections")
    if not isinstance(raw_sections, dict) or set(raw_sections) != set(GUIDE_SECTIONS):
        fail("GUIDE_RESPONSE_INVALID", "sections must contain exactly the eight version-1 fields")
    sections: dict[str, str | None] = {}
    for name in GUIDE_SECTIONS:
        raw = raw_sections[name]
        sections[name] = None if raw is None else _require_text(raw, f"sections.{name}", 4_000, chinese=True)
    raw_highlights = value.get("highlights")
    if not isinstance(raw_highlights, list) or not 2 <= len(raw_highlights) <= 5:
        fail("GUIDE_RESPONSE_INVALID", "highlights must contain between 2 and 5 items")
    highlights = [_require_text(item, "highlight", 500, chinese=True) for item in raw_highlights]
    if len({" ".join(item.split()).casefold() for item in highlights}) != len(highlights):
        fail("GUIDE_RESPONSE_INVALID", "highlights must be distinct")
    raw_references = value.get("knowledgeReferences")
    if not isinstance(raw_references, list) or len(raw_references) > 5:
        fail("GUIDE_RESPONSE_INVALID", "knowledgeReferences must contain at most five items")
    references: list[dict[str, str]] = []
    seen: set[str] = set()
    for item in raw_references:
        if not isinstance(item, dict) or set(item) != {"sourceId", "sourceType", "title"}:
            fail("GUIDE_RESPONSE_INVALID", "knowledge reference shape is invalid")
        source_id = _require_text(item.get("sourceId"), "knowledgeReferences.sourceId", 256)
        if source_id in seen:
            fail("GUIDE_RESPONSE_INVALID", "knowledge references must be distinct")
        seen.add(source_id)
        references.append({
            "sourceId": source_id,
            "sourceType": _require_text(item.get("sourceType"), "knowledgeReferences.sourceType", 64),
            "title": _require_text(item.get("title"), "knowledgeReferences.title", 512, chinese=True),
        })
    normalized = {
        "schemaVersion": "1",
        "summary": summary,
        "sections": sections,
        "highlights": highlights,
        "knowledgeReferences": references,
    }
    if len(json.dumps(normalized, ensure_ascii=False, separators=(",", ":")).encode("utf-8")) > 65_536:
        fail("GUIDE_RESPONSE_INVALID", "Guide result exceeds its total size limit")
    return normalized


def validate_guide_response(
        path: Path, painting_id: str, expected_cache: str | None = None) -> dict[str, Any]:
    value = _read_json(path, "public Guide response", maximum=128 * 1024)
    if not isinstance(value, dict) or set(value) != PUBLIC_GUIDE_FIELDS:
        fail("GUIDE_RESPONSE_INVALID", "public Guide response fields do not match the reviewed contract")
    if set(value).intersection(INTERNAL_PUBLIC_FIELDS):
        fail("GUIDE_RESPONSE_INVALID", "public Guide response contains internal fields")
    try:
        canonical_id = str(uuid.UUID(painting_id))
    except ValueError:
        fail("INVALID_PAINTING_ID", "expected Painting ID is invalid")
    if value.get("paintingId") != canonical_id:
        fail("GUIDE_RESPONSE_INVALID", "public Guide response Painting ID does not match")
    cache_status = value.get("cacheStatus")
    if cache_status not in {"GENERATED", "HIT"} or (expected_cache and cache_status != expected_cache):
        fail("GUIDE_CACHE_STATUS_INVALID", "public Guide response cacheStatus is unexpected")
    result = _validate_result({key: value[key] for key in (
        "schemaVersion", "summary", "sections", "highlights", "knowledgeReferences"
    )})
    generated_at = _require_iso_instant_millis(value.get("generatedAt"), "generatedAt")
    updated_at = _require_iso_instant_millis(value.get("updatedAt"), "updatedAt")
    return {
        "cacheStatus": cache_status,
        "generatedAt": generated_at,
        "paintingId": canonical_id,
        "result": result,
        "updatedAt": updated_at,
    }


def _require_iso_instant_millis(value: Any, field: str) -> str:
    """Require an offset-bearing ISO instant with exactly millisecond precision."""
    candidate = _require_text(value, field, 64)
    match = re.fullmatch(
        r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}"
        r"(?:Z|[+-]\d{2}:\d{2})",
        candidate,
    )
    if match is None:
        fail("GUIDE_RESPONSE_INVALID", f"{field} is not a fixed-millisecond ISO-8601 instant")
    parse_candidate = candidate[:-1] + "+00:00" if candidate.endswith("Z") else candidate
    try:
        parsed = datetime.datetime.fromisoformat(parse_candidate)
    except ValueError:
        fail("GUIDE_RESPONSE_INVALID", f"{field} is not an ISO-8601 instant")
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        fail("GUIDE_RESPONSE_INVALID", f"{field} is not an ISO-8601 instant")
    return candidate


def compare_guide_responses(generated: Path, hit: Path, get: Path) -> dict[str, Any]:
    generated_value = _read_json(generated, "GENERATED Guide response")
    if not isinstance(generated_value, dict):
        fail("GUIDE_RESPONSE_INVALID", "GENERATED Guide response is invalid")
    painting_id = generated_value.get("paintingId")
    validated_generated = validate_guide_response(generated, painting_id, "GENERATED")
    validated_hit = validate_guide_response(hit, painting_id, "HIT")
    validated_get = validate_guide_response(get, painting_id, "HIT")
    comparable_generated = {key: value for key, value in validated_generated.items() if key != "cacheStatus"}
    comparable_hit = {key: value for key, value in validated_hit.items() if key != "cacheStatus"}
    comparable_get = {key: value for key, value in validated_get.items() if key != "cacheStatus"}
    if comparable_generated != comparable_hit or comparable_generated != comparable_get:
        fail("GUIDE_CACHE_MISMATCH", "GENERATED, HIT, and GET Guide responses differ")
    return {
        "cacheSequence": ["GENERATED", "HIT", "HIT"],
        "generatedAtStable": True,
        "paintingId": painting_id,
        "publicGuideStable": True,
        "timestampComparison": "BYTE_FOR_BYTE_FIXED_MILLISECOND_INSTANT",
        "updatedAtStable": True,
    }


def _load_snapshot(path: Path) -> dict[str, Any]:
    value = _read_json(path, "database state snapshot", maximum=2 * 1024 * 1024)
    if not isinstance(value, dict) or not isinstance(value.get("counts"), dict):
        fail("SNAPSHOT_INVALID", "database state snapshot is invalid")
    return value


def _verify_immutable(before: Mapping[str, Any], after: Mapping[str, Any]) -> None:
    before_digests = before.get("tableDigests", {})
    after_digests = after.get("tableDigests", {})
    for table in IMMUTABLE_VALIDATION_TABLES:
        if before_digests.get(table) != after_digests.get(table):
            fail("UNRELATED_DATABASE_WRITE", f"unrelated table changed: {table}")
    if before.get("schemaSha256") != after.get("schemaSha256"):
        fail("DATABASE_SCHEMA_CHANGED", "database schema changed during Guide validation")


def _verify_catalog_skip(database: Path, before: Mapping[str, Any], after: Mapping[str, Any]) -> int:
    old_count = int(before["counts"].get("catalogImportRuns") or 0)
    new_count = int(after["counts"].get("catalogImportRuns") or 0)
    difference = new_count - old_count
    if difference not in {0, 1}:
        fail("CATALOG_AUDIT_UNEXPECTED", "catalog startup created an unexpected number of audit rows")
    if difference == 0:
        if before.get("tableDigests", {}).get("catalog_import_runs") != after.get("tableDigests", {}).get("catalog_import_runs"):
            fail("CATALOG_AUDIT_UNEXPECTED", "catalog audit changed without a new row")
        return 0
    maximum = int(before.get("tableMaxIds", {}).get("catalog_import_runs", 0))
    with _readonly(database) as connection:
        if _table_digest(connection, "catalog_import_runs", maximum) != before["tableDigests"]["catalog_import_runs"]:
            fail("CATALOG_AUDIT_UNEXPECTED", "existing catalog audit rows changed")
        row = connection.execute(
            "SELECT status,inserted_rows,updated_rows FROM catalog_import_runs "
            "WHERE id>? ORDER BY id", (maximum,)
        ).fetchall()
        if len(row) != 1 or row[0]["status"] != "SKIPPED" or row[0]["inserted_rows"] != 0 or row[0]["updated_rows"] != 0:
            fail("CATALOG_AUDIT_UNEXPECTED", "startup catalog audit row is not an unchanged SKIPPED run")
    return 1


def verify_generated(
        database: Path, before_file: Path, painting_id: str, response_file: Path) -> dict[str, Any]:
    before = _load_snapshot(before_file)
    after = inspect_database(database)
    _verify_immutable(before, after)
    skip_count = _verify_catalog_skip(database, before, after)
    if skip_count != 1:
        fail("CATALOG_AUDIT_UNEXPECTED", "normal Spring startup did not record exactly one SKIPPED catalog audit")
    before_guides = int(before["counts"].get("paintingGuides") or 0)
    if before_guides != 0:
        fail("GUIDE_COUNT_MISMATCH", "GENERATED validation requires the reviewed zero-Guide baseline")
    if after["counts"].get("paintingGuides") != before_guides + 1:
        fail("GUIDE_COUNT_MISMATCH", "first Guide validation did not add exactly one Guide row")
    response = validate_guide_response(response_file, painting_id, "GENERATED")
    with _readonly(database) as connection:
        row = connection.execute(
            "SELECT g.public_id,g.source_hash,g.status,g.result_json,g.generated_at,g.updated_at "
            "FROM painting_guides g JOIN paintings p ON p.id=g.painting_id WHERE p.public_id=?",
            (painting_id,),
        ).fetchone()
    if row is None:
        fail("GUIDE_ROW_INVALID", "generated Guide row was not found")
    try:
        if str(uuid.UUID(row["public_id"])) != row["public_id"]:
            raise ValueError
    except (ValueError, AttributeError):
        fail("GUIDE_ROW_INVALID", "generated Guide public UUID is invalid")
    if row["status"] != "SUCCESS" or not isinstance(row["source_hash"], str) or not SHA256.fullmatch(row["source_hash"]):
        fail("GUIDE_ROW_INVALID", "generated Guide status or source hash is invalid")
    if row["generated_at"] is None or row["updated_at"] is None:
        fail("GUIDE_ROW_INVALID", "generated Guide timestamps are missing")
    try:
        stored_result = _validate_result(json.loads(row["result_json"]))
    except (json.JSONDecodeError, TypeError):
        fail("GUIDE_ROW_INVALID", "stored Guide result JSON is invalid")
    if stored_result != response["result"]:
        fail("GUIDE_ROW_INVALID", "stored and public Guide results differ")
    after["validation"] = {
        "catalogSkippedRowsAdded": skip_count,
        "guidePublicId": row["public_id"],
        "guideSourceHash": row["source_hash"],
        "guideStatus": "SUCCESS",
        "paintingId": painting_id,
    }
    return after


def verify_hit(
        database: Path, generated_state_file: Path, painting_id: str, response_file: Path) -> dict[str, Any]:
    generated_state = _load_snapshot(generated_state_file)
    current = inspect_database(database)
    _verify_immutable(generated_state, current)
    catalog_skip_count = _verify_catalog_skip(database, generated_state, current)
    if generated_state["counts"].get("paintingGuides") != current["counts"].get("paintingGuides"):
        fail("CACHE_HIT_WROTE_DATABASE", "cache HIT changed the painting_guides row count")
    if generated_state.get("tableDigests", {}).get("painting_guides") != current.get("tableDigests", {}).get("painting_guides"):
        fail("CACHE_HIT_WROTE_DATABASE", "cache HIT changed the persisted Guide row")
    response = validate_guide_response(response_file, painting_id, "HIT")
    if current["counts"].get("paintingGuides") != 1:
        fail("GUIDE_COUNT_MISMATCH", "cache HIT validation requires exactly one persisted Guide")
    guide_state = current.get("guideState", {})
    if guide_state.get("healthy") is not True or guide_state.get("paintingId") != painting_id:
        fail("GUIDE_ROW_INVALID", "cache HIT did not resolve the selected healthy Guide")
    with _readonly(database) as connection:
        stored = connection.execute(
            "SELECT g.result_json FROM painting_guides g JOIN paintings p ON p.id=g.painting_id "
            "WHERE p.public_id=?", (painting_id,)
        ).fetchone()
    try:
        stored_result = _validate_result(json.loads(stored["result_json"])) if stored else None
    except (json.JSONDecodeError, TypeError):
        stored_result = None
    if stored_result != response["result"]:
        fail("GUIDE_ROW_INVALID", "cache HIT response differs from the persisted Guide")
    return {
        "cacheStatus": "HIT",
        "catalogSkippedRowsAdded": catalog_skip_count,
        "databaseUnchanged": True,
        "generatedAt": response["generatedAt"],
        "paintingId": painting_id,
        "updatedAt": response["updatedAt"],
    }


def content_quality(response_file: Path) -> dict[str, Any]:
    raw = _read_json(response_file, "public Guide response")
    if not isinstance(raw, dict) or not isinstance(raw.get("paintingId"), str):
        fail("GUIDE_RESPONSE_INVALID", "public Guide response is invalid")
    validated = validate_guide_response(response_file, raw["paintingId"])
    non_null_sections = sum(value is not None for value in validated["result"]["sections"].values())
    music_present = validated["result"]["sections"]["musicAssociation"] is not None
    if not music_present:
        fail("GUIDE_CONTENT_QUALITY_FAILED", "rich validation Painting produced no music association")
    return {
        "automatedScope": [
            "STRICT_PUBLIC_SCHEMA",
            "CHINESE_TEXT_PRESENCE",
            "MARKUP_AND_PROMPT_LEAKAGE_REJECTION",
            "DISTINCT_HIGHLIGHTS",
        ],
        "automatedStatus": "STRUCTURALLY_VALID",
        "distinctHighlights": len(validated["result"]["highlights"]),
        "factualGroundingStatus": "OPERATOR_REVIEW_REQUIRED",
        "knowledgeReferenceCount": len(validated["result"]["knowledgeReferences"]),
        "musicAssociationPresent": music_present,
        "nonNullSectionCount": non_null_sections,
        "subjectiveStatus": "OPERATOR_REVIEW_REQUIRED",
    }


def secret_scan(env_file: Path, token_file: Path | None, paths: Iterable[Path]) -> dict[str, Any]:
    _, values, _ = _parse_env(env_file)
    secrets_to_find = {
        value.encode("utf-8") for key, value in values.items()
        if (key in SECRET_ENV_KEYS or any(marker in key for marker in ("KEY", "TOKEN", "SECRET", "PASSWORD")))
        and len(value.encode("utf-8")) >= 6
    }
    if token_file is not None:
        token_path = _require_regular(token_file, "JWT token file", private=True)
        token = token_path.read_bytes()
        if len(token) >= 6:
            secrets_to_find.add(token)
    scanned = 0
    for path in paths:
        absolute = _absolute(path)
        candidates = [absolute]
        if absolute.is_dir():
            candidates = [item for item in absolute.rglob("*") if item.is_file() and not item.is_symlink()]
        for candidate in candidates:
            regular = _require_regular(candidate, "evidence file")
            if regular.stat().st_size > 16 * 1024 * 1024:
                fail("EVIDENCE_TOO_LARGE", "an evidence file exceeds the secret-scan size limit")
            content = regular.read_bytes()
            scanned += 1
            if any(secret in content for secret in secrets_to_find):
                fail("SECRET_LEAK_DETECTED", "private evidence contains a configured secret")
    return {"filesScanned": scanned, "result": "NO_SECRET_VALUES_FOUND"}


def write_manifest(phase: str, database: Path, destination: Path, painting_file: Path | None) -> None:
    if not re.fullmatch(r"[A-Z0-9_-]{1,64}", phase):
        fail("MANIFEST_PHASE_INVALID", "manifest phase is invalid")
    manifest: dict[str, Any] = {"database": inspect_database(database), "phase": phase}
    if painting_file is not None:
        painting = _read_json(painting_file, "Painting selection")
        allowed = {"annotationCoverageScore", "author", "dynasty", "paintingId", "selection", "title"}
        if not isinstance(painting, dict) or set(painting) != allowed:
            fail("PAINTING_SELECTION_INVALID", "Painting selection evidence is invalid")
        manifest["painting"] = painting
    _write_json(destination, manifest)


def _add_output(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--output", type=Path)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    preflight = sub.add_parser("preflight-env", help="validate private Guide/Qwen configuration")
    preflight.add_argument("--env-file", type=Path, required=True)
    _add_output(preflight)

    knowledge = sub.add_parser("preflight-knowledge", help="verify reviewed static Guide knowledge")
    knowledge.add_argument("--project-root", type=Path, required=True)
    _add_output(knowledge)

    inspect = sub.add_parser("inspect-db", help="read-only SQLite state inspection")
    inspect.add_argument("--database", type=Path, required=True)
    inspect.add_argument("--expect-production", action="store_true")
    inspect.add_argument("--expected-guides", type=int, default=0)
    _add_output(inspect)

    snapshot = sub.add_parser("snapshot-db", help="write a private read-only SQLite snapshot")
    snapshot.add_argument("--database", type=Path, required=True)
    snapshot.add_argument("--output", type=Path, required=True)

    select = sub.add_parser("select-painting", help="deterministically select one rich Painting")
    select.add_argument("--database", type=Path, required=True)
    select.add_argument("--painting-id")
    _add_output(select)

    backup = sub.add_parser("backup-db", help="create and logically verify a SQLite backup")
    backup.add_argument("--source", type=Path, required=True)
    backup.add_argument("--destination", type=Path, required=True)

    verify = sub.add_parser("verify-backup", help="verify logical SQLite backup equivalence")
    verify.add_argument("--source", type=Path, required=True)
    verify.add_argument("--backup", type=Path, required=True)

    failed = sub.add_parser("preserve-failed", help="preserve a private failed SQLite snapshot")
    failed.add_argument("--source", type=Path, required=True)
    failed.add_argument("--destination", type=Path, required=True)

    restore = sub.add_parser("restore-db", help="atomically restore a verified SQLite backup")
    restore.add_argument("--backup", type=Path, required=True)
    restore.add_argument("--database", type=Path, required=True)

    env_backup = sub.add_parser("backup-env", help="copy backend/.env privately")
    env_backup.add_argument("--source", type=Path, required=True)
    env_backup.add_argument("--destination", type=Path, required=True)

    env_restore = sub.add_parser("restore-env", help="atomically restore backend/.env")
    env_restore.add_argument("--backup", type=Path, required=True)
    env_restore.add_argument("--env-file", type=Path, required=True)

    token = sub.add_parser("generate-token", help="generate a missing internal token in backend/.env")
    token.add_argument("--env-file", type=Path, required=True)

    login = sub.add_parser("build-login-request", help="write login JSON only to a private pipe FD")
    login.add_argument("--username", required=True)
    login.add_argument("--output-fd", type=int, required=True)

    extract = sub.add_parser("extract-token", help="extract JWT from a private login response")
    extract.add_argument("--response", type=Path, required=True)
    extract.add_argument("--token-file", type=Path, required=True)

    auth = sub.add_parser("write-auth-config", help="create a private curl Authorization config")
    auth.add_argument("--token-file", type=Path, required=True)
    auth.add_argument("--output", type=Path, required=True)

    health = sub.add_parser("validate-health", help="validate a local health response")
    health.add_argument("--file", type=Path, required=True)
    health.add_argument("--service", choices=("guide", "spring"), required=True)

    guide = sub.add_parser("validate-guide-response", help="validate one strict public Guide response")
    guide.add_argument("--file", type=Path, required=True)
    guide.add_argument("--painting-id", required=True)
    guide.add_argument("--cache-status", choices=("GENERATED", "HIT"), required=True)
    _add_output(guide)

    compare = sub.add_parser("compare-guide-responses", help="compare GENERATED/HIT/GET responses")
    compare.add_argument("--generated", type=Path, required=True)
    compare.add_argument("--hit", type=Path, required=True)
    compare.add_argument("--get", type=Path, required=True)
    _add_output(compare)

    generated = sub.add_parser("verify-generated", help="verify exactly one Guide DB transition")
    generated.add_argument("--database", type=Path, required=True)
    generated.add_argument("--before", type=Path, required=True)
    generated.add_argument("--painting-id", required=True)
    generated.add_argument("--response", type=Path, required=True)
    _add_output(generated)

    hit = sub.add_parser("verify-hit", help="verify cache HIT performs no database write")
    hit.add_argument("--database", type=Path, required=True)
    hit.add_argument("--generated-state", type=Path, required=True)
    hit.add_argument("--painting-id", required=True)
    hit.add_argument("--response", type=Path, required=True)
    _add_output(hit)

    quality = sub.add_parser("content-quality", help="run deterministic content safety checks")
    quality.add_argument("--response", type=Path, required=True)
    _add_output(quality)

    scan = sub.add_parser("secret-scan", help="scan evidence without exposing secret values")
    scan.add_argument("--env-file", type=Path, required=True)
    scan.add_argument("--token-file", type=Path)
    scan.add_argument("--paths", type=Path, nargs="+", required=True)

    manifest = sub.add_parser("manifest", help="write a secret-safe validation manifest")
    manifest.add_argument("--phase", required=True)
    manifest.add_argument("--database", type=Path, required=True)
    manifest.add_argument("--destination", type=Path, required=True)
    manifest.add_argument("--painting-file", type=Path)
    return parser


def main() -> int:
    arguments = build_parser().parse_args()
    try:
        command = arguments.command
        if command == "preflight-env":
            _emit(preflight_env(arguments.env_file), arguments.output)
        elif command == "preflight-knowledge":
            _emit(verify_static_knowledge(arguments.project_root), arguments.output)
        elif command in {"inspect-db", "snapshot-db"}:
            snapshot = inspect_database(arguments.database)
            if command == "inspect-db" and arguments.expect_production:
                verify_production_state(snapshot, arguments.expected_guides)
            _emit(snapshot, arguments.output)
        elif command == "select-painting":
            _emit(select_painting(arguments.database, arguments.painting_id), arguments.output)
        elif command in {"backup-db", "preserve-failed"}:
            _emit(sqlite_backup(arguments.source, arguments.destination))
        elif command == "verify-backup":
            _emit(verify_backup(arguments.source, arguments.backup))
        elif command == "restore-db":
            _emit(restore_database(arguments.backup, arguments.database))
        elif command == "backup-env":
            backup_env(arguments.source, arguments.destination)
            print("ENV_BACKUP_VERIFIED")
        elif command == "restore-env":
            restore_env(arguments.backup, arguments.env_file)
            print("ENV_RESTORED")
        elif command == "generate-token":
            print(generate_internal_token(arguments.env_file))
        elif command == "build-login-request":
            build_login_request(arguments.username, arguments.output_fd)
        elif command == "extract-token":
            extract_token(arguments.response, arguments.token_file)
            print("JWT_EXTRACTED_PRIVATELY")
        elif command == "write-auth-config":
            write_auth_config(arguments.token_file, arguments.output)
            print("AUTH_CONFIG_WRITTEN_PRIVATELY")
        elif command == "validate-health":
            _emit(validate_health(arguments.file, arguments.service))
        elif command == "validate-guide-response":
            _emit(validate_guide_response(
                arguments.file, arguments.painting_id, arguments.cache_status
            ), arguments.output)
        elif command == "compare-guide-responses":
            _emit(compare_guide_responses(
                arguments.generated, arguments.hit, arguments.get
            ), arguments.output)
        elif command == "verify-generated":
            _emit(verify_generated(
                arguments.database, arguments.before, arguments.painting_id,
                arguments.response,
            ), arguments.output)
        elif command == "verify-hit":
            _emit(verify_hit(
                arguments.database, arguments.generated_state, arguments.painting_id,
                arguments.response,
            ), arguments.output)
        elif command == "content-quality":
            _emit(content_quality(arguments.response), arguments.output)
        elif command == "secret-scan":
            _emit(secret_scan(arguments.env_file, arguments.token_file, arguments.paths))
        elif command == "manifest":
            write_manifest(arguments.phase, arguments.database, arguments.destination, arguments.painting_file)
            print("MANIFEST_WRITTEN")
        else:
            raise AssertionError(command)
        return 0
    except Round61Error as exception:
        print(f"ROUND61_STATE_ERROR_CODE={exception.code}", file=sys.stderr)
        print(f"ROUND61_STATE_ERROR_SUMMARY={exception.summary}", file=sys.stderr)
        return 2
    except (OSError, sqlite3.Error, UnicodeError, ValueError):
        print("ROUND61_STATE_ERROR_CODE=LOCAL_OPERATION_FAILED", file=sys.stderr)
        print("ROUND61_STATE_ERROR_SUMMARY=local validation operation failed", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
