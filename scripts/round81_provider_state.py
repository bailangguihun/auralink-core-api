#!/usr/bin/env python3
"""Secret-safe state and filesystem guard for ROUND 8.1 provider validation."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import shutil
import socket
import sqlite3
import stat
import struct
import subprocess
import sys
from pathlib import Path
from typing import Any
from urllib.parse import urlparse


SERVER_LOCAL_ROOT = Path("/root/autodl-tmp/auralink")
PRIVATE_RUN_ROOT = Path("/root/auralink_provider_validation_runs")
DETERMINISTIC_PAINTING_ID = "00074dee-e790-4cf3-a1d9-1e2e784364fb"
TEXT_SOURCE = "春雨初歇，远山含黛，一叶归舟穿过薄雾，江岸疏林以水墨留白构成宁静的中国山水画面，无文字与标志。"
POEM_SOURCE = "空山新雨后，天气晚来秋。明月松间照，清泉石上流。"
OPERATIONS = {
    "text-to-painting": {
        "code": "TEXT_TO_PAINTING",
        "provider": "seedream-5",
        "calls": {"seedream": 1, "qwen": 0, "vmm": 0},
        "image": False,
    },
    "image-to-painting": {
        "code": "IMAGE_TO_PAINTING",
        "provider": "seedream-5",
        "calls": {"seedream": 1, "qwen": 0, "vmm": 0},
        "image": True,
    },
    "poem-to-painting": {
        "code": "POEM_TO_PAINTING",
        "provider": "qwen3vl-seedream5",
        "calls": {"seedream": 1, "qwen": 1, "vmm": 0},
        "image": False,
    },
    "painting-to-poem": {
        "code": "PAINTING_TO_POEM",
        "provider": "qwen3-vl-plus",
        "calls": {"seedream": 0, "qwen": 1, "vmm": 0},
        "image": True,
    },
    "painting-to-music": {
        "code": "PAINTING_TO_MUSIC",
        "provider": "auralink-vmm",
        "calls": {"seedream": 0, "qwen": 0, "vmm": 1},
        "image": True,
    },
}
EXPECTED_COUNTS = {
    "users": 7,
    "generation_logs": 118,
    "paintings": 11067,
    "catalog_media_assets": 9067,
    "painting_guides": 1,
    "user_workflows": 0,
    "creations": 0,
    "creation_steps": 0,
    "creation_favorites": 0,
}
PROTECTED_TABLES = (
    "users",
    "generation_logs",
    "media_assets",
    "paintings",
    "catalog_import_runs",
    "painting_guides",
    "painting_favorites",
    "user_workflows",
    "creations",
    "creation_steps",
    "creation_favorites",
    "flyway_schema_history",
)
FORBIDDEN_EVIDENCE_KEYS = (
    "apikey",
    "api_key",
    "authorization",
    "token",
    "cookie",
    "password",
    "signedurl",
    "signed_url",
    "rawrequest",
    "rawresponse",
    "requestbody",
    "responsebody",
    "base64",
    "prompt",
    "baseurl",
    "endpoint",
    "providerurl",
    "localpath",
    "filesystempath",
    "reasoning",
    "modeldeployment",
    "model_id",
    "storagekey",
    "storage_key",
)
COMMON_CONFIGURATION_KEYS = {
    "AURALINK_PAINTING_PICTURE_DIR",
    "AURALINK_PROVIDER_STAGING_DIR",
    "AURALINK_PROVIDER_MAX_IMAGE_INPUT_BYTES",
    "AURALINK_PROVIDER_MAX_IMAGE_OUTPUT_BYTES",
    "AURALINK_PROVIDER_MAX_AUDIO_OUTPUT_BYTES",
    "AURALINK_PROVIDER_MAX_TEXT_CHARS",
    "AURALINK_PROVIDER_CONNECT_TIMEOUT_MS",
    "AURALINK_PROVIDER_QWEN_READ_TIMEOUT_MS",
    "AURALINK_PROVIDER_SEEDREAM_READ_TIMEOUT_MS",
    "AURALINK_PROVIDER_VMM_READ_TIMEOUT_MS",
    "AURALINK_PROVIDER_MAX_CONCURRENT_SEEDREAM",
    "AURALINK_PROVIDER_MAX_CONCURRENT_QWEN",
    "AURALINK_PROVIDER_MAX_CONCURRENT_VMM",
    "AURALINK_SEEDREAM_DEFAULT_SIZE",
    "AURALINK_SEEDREAM_OUTPUT_FORMAT",
    "AURALINK_SEEDREAM_WATERMARK",
}
VMM_CONFIGURATION_KEYS = COMMON_CONFIGURATION_KEYS | {
    "PAINTING_MUSIC_SERVICE_URL",
    "AURALINK_VMM_OUTPUT_DIR",
    "AURALINK_VMM_MUSICGEN_PATH",
    "AURALINK_VMM_CHECKPOINT_PATH",
    "AURALINK_VMM_CLIP_MODEL",
    "AURALINK_VMM_CLIP_CACHE",
    "CUDA_VISIBLE_DEVICES",
}


class StateError(RuntimeError):
    def __init__(self, code: str, message: str = "ROUND 8.1 state validation failed") -> None:
        super().__init__(message)
        self.code = code


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def json_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def write_private_json(path: Path, value: Any) -> None:
    write_private_bytes(path, json_bytes(value))


def write_private_bytes(path: Path, value: bytes) -> None:
    if path.exists() or path.is_symlink():
        raise StateError("PRIVATE_FILE_ALREADY_EXISTS")
    temporary = path.parent / f".{path.name}.{os.getpid()}.part"
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    descriptor = os.open(temporary, flags, 0o600)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(value)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, 0o600, follow_symlinks=False)
        os.replace(temporary, path)
        os.chmod(path, 0o600, follow_symlinks=False)
    finally:
        try:
            temporary.unlink(missing_ok=True)
        except OSError:
            pass


def parse_dotenv(path: Path, allowed_keys: set[str] | None = None) -> dict[str, str]:
    if path.is_symlink() or not path.is_file():
        raise StateError("BACKEND_ENV_REQUIRED")
    values: dict[str, str] = {}
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        stripped = raw.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if stripped.startswith("export "):
            stripped = stripped[7:].lstrip()
        if "=" not in stripped:
            raise StateError("ENV_FILE_INVALID", f"Invalid environment entry at line {number}")
        key, value = stripped.split("=", 1)
        key = key.strip()
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
            raise StateError("ENV_FILE_INVALID")
        if allowed_keys is not None and key not in allowed_keys:
            continue
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        if any(ord(character) < 32 and character not in "\t" for character in value):
            raise StateError("ENV_FILE_INVALID")
        values[key] = value
    return values


def merged_configuration(
    project_root: Path,
    allowed_keys: set[str] | None = None,
) -> dict[str, str]:
    values = parse_dotenv(project_root / "backend/.env", allowed_keys)
    for key, value in os.environ.items():
        if allowed_keys is None or key in allowed_keys:
            values[key] = value
    return values


def operation_configuration_keys(operation_token: str) -> set[str]:
    require_operation(operation_token)
    keys = set(COMMON_CONFIGURATION_KEYS)
    if operation_token in {"text-to-painting", "image-to-painting", "poem-to-painting"}:
        keys.update({"SEEDREAM_API_KEY", "SEEDREAM_BASE_URL", "SEEDREAM_MODEL"})
    if operation_token in {"poem-to-painting", "painting-to-poem"}:
        keys.update({"QWEN_API_KEY", "QWEN_BASE_URL", "QWEN_MODEL"})
    if operation_token == "painting-to-music":
        keys.update(VMM_CONFIGURATION_KEYS)
    return keys


def require_operation(token: str) -> dict[str, Any]:
    try:
        return OPERATIONS[token]
    except KeyError as error:
        raise StateError("UNSUPPORTED_OPERATION") from error


def require_regular(path: Path, code: str) -> Path:
    if path.is_symlink() or not path.is_file():
        raise StateError(code)
    return path.resolve(strict=True)


def require_directory(path: Path, code: str) -> Path:
    if path.is_symlink() or not path.is_dir():
        raise StateError(code)
    return path.resolve(strict=True)


def mode_bits(path: Path) -> int:
    return stat.S_IMODE(path.stat(follow_symlinks=False).st_mode)


def require_private_directory(path: Path, expected_root: Path | None = None) -> Path:
    real = require_directory(path, "PRIVATE_RUN_DIRECTORY_INVALID")
    if real != path.absolute() or mode_bits(path) != 0o700:
        raise StateError("PRIVATE_RUN_PERMISSIONS_INVALID")
    if expected_root is not None and real.parent != expected_root:
        raise StateError("PRIVATE_RUN_DIRECTORY_INVALID")
    if path.stat(follow_symlinks=False).st_uid != os.geteuid():
        raise StateError("PRIVATE_RUN_OWNERSHIP_INVALID")
    return real


def require_private_file(path: Path, parent: Path) -> Path:
    real = require_regular(path, "PRIVATE_RUN_FILE_INVALID")
    if real.parent != parent or mode_bits(path) != 0o600:
        raise StateError("PRIVATE_RUN_FILE_INVALID")
    return real


def subprocess_text(command: list[str], timeout: int = 30) -> str:
    try:
        completed = subprocess.run(
            command,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            timeout=timeout,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise StateError("LOCAL_COMMAND_FAILED") from error
    return completed.stdout


def git_text(root: Path, *arguments: str) -> str:
    return subprocess_text(["git", "-C", str(root), *arguments], timeout=20).strip()


def filesystem_type(path: Path) -> str:
    return subprocess_text(["findmnt", "-n", "-o", "FSTYPE", "-T", str(path)], timeout=10).strip().lower()


def verify_root_and_commit(
    project_root: Path,
    expected_commit: str,
    working_directory: Path | None = None,
    expected_root: Path = SERVER_LOCAL_ROOT,
    fs_type: str | None = None,
) -> str:
    if not re.fullmatch(r"[0-9a-f]{40}", expected_commit or ""):
        raise StateError("EXPECTED_COMMIT_REQUIRED")
    expected = require_directory(expected_root, "SERVER_LOCAL_ROOT_REQUIRED")
    actual = require_directory(project_root, "SERVER_LOCAL_ROOT_REQUIRED")
    cwd = (working_directory or Path.cwd()).resolve(strict=True)
    if expected != expected_root or actual != expected or cwd != expected:
        raise StateError("SERVER_LOCAL_ROOT_REQUIRED")
    current_fs = (fs_type if fs_type is not None else filesystem_type(actual)).lower()
    if "fuse" in current_fs or "sshfs" in current_fs:
        raise StateError("SSHFS_EXECUTION_REFUSED")
    actual_commit = git_text(actual, "rev-parse", "HEAD")
    if actual_commit != expected_commit:
        raise StateError("REVIEWED_COMMIT_MISMATCH")
    if git_text(actual, "status", "--porcelain=v1", "--untracked-files=normal"):
        raise StateError("WORKTREE_NOT_CLEAN")
    require_regular(actual / "backend/.env", "BACKEND_ENV_REQUIRED")
    return actual_commit


def schema_hash(database: Path) -> str:
    schema = subprocess.run(
        ["sqlite3", "-readonly", str(database), ".schema"],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        timeout=30,
    ).stdout
    return hashlib.sha256(schema).hexdigest()


def table_digest(database: Path, table: str) -> str:
    if table not in PROTECTED_TABLES:
        raise StateError("PROTECTED_TABLE_INVALID")
    dumped = subprocess.run(
        ["sqlite3", "-readonly", str(database), f".dump {table}"],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        timeout=60,
    ).stdout
    return hashlib.sha256(dumped).hexdigest()


def inspect_database(project_root: Path) -> dict[str, Any]:
    database = require_regular(project_root / "backend/auralink.db", "LIVE_DATABASE_REQUIRED")
    before = sha256_file(database)
    uri = f"file:{database}?mode=ro&immutable=1"
    connection = sqlite3.connect(uri, uri=True)
    try:
        connection.execute("PRAGMA query_only=ON")
        integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
        foreign_keys = connection.execute("PRAGMA foreign_key_check").fetchall()
        counts = {
            "users": connection.execute("SELECT COUNT(*) FROM users").fetchone()[0],
            "generation_logs": connection.execute("SELECT COUNT(*) FROM generation_logs").fetchone()[0],
            "paintings": connection.execute("SELECT COUNT(*) FROM paintings").fetchone()[0],
            "catalog_media_assets": connection.execute(
                "SELECT COUNT(*) FROM media_assets WHERE source_type='CATALOG_REFERENCE'"
            ).fetchone()[0],
            "painting_guides": connection.execute("SELECT COUNT(*) FROM painting_guides").fetchone()[0],
            "user_workflows": connection.execute("SELECT COUNT(*) FROM user_workflows").fetchone()[0],
            "creations": connection.execute("SELECT COUNT(*) FROM creations").fetchone()[0],
            "creation_steps": connection.execute("SELECT COUNT(*) FROM creation_steps").fetchone()[0],
            "creation_favorites": connection.execute("SELECT COUNT(*) FROM creation_favorites").fetchone()[0],
            "catalog_import_runs": connection.execute("SELECT COUNT(*) FROM catalog_import_runs").fetchone()[0],
        }
    finally:
        connection.close()
    after = sha256_file(database)
    if before != after:
        raise StateError("PRODUCTION_DATABASE_CHANGED")
    if integrity != "ok" or foreign_keys:
        raise StateError("PRODUCTION_DATABASE_INVALID")
    for name, expected in EXPECTED_COUNTS.items():
        if counts[name] != expected:
            raise StateError("PRODUCTION_DATABASE_COUNT_MISMATCH")
    document = {
        "databaseSha256": before,
        "schemaSha256": schema_hash(database),
        "counts": counts,
        "tableDigests": {table: table_digest(database, table) for table in PROTECTED_TABLES},
        "integrityCheck": "ok",
        "foreignKeyViolations": 0,
    }
    if sha256_file(database) != before:
        raise StateError("PRODUCTION_DATABASE_CHANGED")
    return document


def parse_image(path: Path) -> tuple[str, int, int]:
    size = path.stat().st_size
    if size < 24:
        raise StateError("CATALOG_IMAGE_INVALID")
    with path.open("rb") as source:
        prefix = source.read(24)
        if prefix.startswith(b"\x89PNG\r\n\x1a\n"):
            source.seek(-12, os.SEEK_END)
            if source.read(12) != b"\x00\x00\x00\x00IEND\xaeB`\x82":
                raise StateError("CATALOG_IMAGE_INVALID")
            width, height = struct.unpack(">II", prefix[16:24])
            return "image/png", width, height
        if prefix[:3] != b"\xff\xd8\xff":
            raise StateError("CATALOG_IMAGE_INVALID")
        source.seek(-2, os.SEEK_END)
        if source.read(2) != b"\xff\xd9":
            raise StateError("CATALOG_IMAGE_INVALID")
        source.seek(2)
        while source.tell() < size - 2:
            marker_start = source.read(1)
            if not marker_start:
                break
            if marker_start != b"\xff":
                continue
            marker = source.read(1)
            while marker == b"\xff":
                marker = source.read(1)
            if not marker or marker in {b"\xd8", b"\xd9"}:
                continue
            length_bytes = source.read(2)
            if len(length_bytes) != 2:
                break
            length = struct.unpack(">H", length_bytes)[0]
            if length < 2:
                raise StateError("CATALOG_IMAGE_INVALID")
            if marker[0] in {0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF}:
                data = source.read(length - 2)
                if len(data) < 5:
                    break
                height, width = struct.unpack(">HH", data[1:5])
                return "image/jpeg", width, height
            source.seek(length - 2, os.SEEK_CUR)
    raise StateError("CATALOG_IMAGE_INVALID")


def contained_catalog_file(root: Path, storage_key: str) -> Path:
    prefix = "catalog/"
    if not storage_key.startswith(prefix):
        raise StateError("CATALOG_STORAGE_KEY_INVALID")
    relative = Path(storage_key[len(prefix):])
    if relative.is_absolute() or not relative.parts or any(part in {"", ".", ".."} for part in relative.parts):
        raise StateError("CATALOG_STORAGE_KEY_INVALID")
    catalog_root = require_directory(root, "CATALOG_ROOT_INVALID")
    candidate = catalog_root.joinpath(relative)
    if candidate.is_symlink() or not candidate.is_file():
        raise StateError("CATALOG_IMAGE_INVALID")
    resolved = candidate.resolve(strict=True)
    if resolved.parent != catalog_root and catalog_root not in resolved.parents:
        raise StateError("CATALOG_IMAGE_OUTSIDE_ROOT")
    if resolved != candidate.absolute():
        raise StateError("CATALOG_IMAGE_SYMLINK_REFUSED")
    return resolved


def catalog_root(project_root: Path, configuration: dict[str, str]) -> Path:
    configured = configuration.get("AURALINK_PAINTING_PICTURE_DIR", "./picture").strip()
    if not configured or "\x00" in configured:
        raise StateError("CATALOG_ROOT_INVALID")
    path = Path(configured)
    if not path.is_absolute():
        path = project_root / "backend" / path
    absolute = Path(os.path.abspath(path))
    if path.is_symlink() or not path.is_dir():
        raise StateError("CATALOG_ROOT_INVALID")
    resolved = path.resolve(strict=True)
    if resolved != absolute:
        raise StateError("CATALOG_ROOT_INVALID")
    return require_directory(resolved, "CATALOG_ROOT_INVALID")


def resolve_painting_input(
    project_root: Path,
    configuration: dict[str, str],
    copy_to_run: Path | None = None,
) -> dict[str, Any]:
    database = require_regular(project_root / "backend/auralink.db", "LIVE_DATABASE_REQUIRED")
    before = sha256_file(database)
    connection = sqlite3.connect(f"file:{database}?mode=ro&immutable=1", uri=True)
    connection.row_factory = sqlite3.Row
    try:
        connection.execute("PRAGMA query_only=ON")
        row = connection.execute(
            """
            SELECT p.public_id AS painting_id, p.title, p.author_name AS author,
                   p.creation_dynasty_normalized AS dynasty, p.category, p.subject,
                   p.painting_school, p.style, p.composition, p.artistic_conception,
                   p.generated_text, p.music_scene_description,
                   p.status AS painting_status, p.image_available, p.visible_in_gallery,
                   m.storage_key, m.mime_type, m.file_size, m.sha256, m.width, m.height,
                   m.asset_type, m.semantic_type, m.source_type, m.visibility,
                   m.status AS asset_status
              FROM paintings p
              JOIN media_assets m ON m.id = p.image_asset_id
             WHERE p.public_id = ?
            """,
            (DETERMINISTIC_PAINTING_ID,),
        ).fetchone()
    finally:
        connection.close()
    if sha256_file(database) != before:
        raise StateError("PRODUCTION_DATABASE_CHANGED")
    if row is None:
        raise StateError("DETERMINISTIC_PAINTING_NOT_FOUND")
    if (
        row["painting_status"] != "ACTIVE"
        or row["image_available"] != 1
        or row["visible_in_gallery"] != 1
        or row["asset_type"] != "IMAGE"
        or row["semantic_type"] != "PAINTING"
        or row["source_type"] != "CATALOG_REFERENCE"
        or row["visibility"] != "PUBLIC"
        or row["asset_status"] != "ACTIVE"
    ):
        raise StateError("DETERMINISTIC_PAINTING_INELIGIBLE")
    source = contained_catalog_file(catalog_root(project_root, configuration), row["storage_key"])
    mime_type, width, height = parse_image(source)
    digest = sha256_file(source)
    if (
        mime_type != row["mime_type"]
        or width != row["width"]
        or height != row["height"]
        or source.stat().st_size != row["file_size"]
        or digest != row["sha256"]
    ):
        raise StateError("CATALOG_IMAGE_METADATA_MISMATCH")

    extension = "jpg" if mime_type == "image/jpeg" else "png"
    safe = {
        "paintingId": row["painting_id"],
        "title": row["title"],
        "author": row["author"],
        "dynasty": row["dynasty"],
        "category": row["category"],
        "subject": row["subject"],
        "paintingSchool": row["painting_school"],
        "style": row["style"],
        "composition": row["composition"],
        "artisticConception": row["artistic_conception"],
        "generatedText": row["generated_text"],
        "musicSceneDescription": row["music_scene_description"],
        "mimeType": mime_type,
        "width": width,
        "height": height,
        "sha256": digest,
        "inputFile": f"input-image.{extension}",
    }
    if copy_to_run is not None:
        private_run = require_private_directory(copy_to_run)
        target = private_run / safe["inputFile"]
        if source.is_symlink():
            raise StateError("CATALOG_IMAGE_SYMLINK_REFUSED")
        maximum = positive_integer(
            configuration.get("AURALINK_PROVIDER_MAX_IMAGE_INPUT_BYTES", "10485760"),
            "PROVIDER_LIMIT_CONFIGURATION_INVALID",
        )
        with source.open("rb") as input_stream:
            write_private_stream(target, input_stream, maximum, digest)
        write_private_json(private_run / "input-metadata.json", safe)
    if sha256_file(database) != before:
        raise StateError("PRODUCTION_DATABASE_CHANGED")
    return safe


def write_private_stream(path: Path, source: Any, maximum: int, expected_sha256: str) -> None:
    if path.exists() or path.is_symlink():
        raise StateError("PRIVATE_FILE_ALREADY_EXISTS")
    temporary = path.parent / f".{path.name}.{os.getpid()}.part"
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    digest = hashlib.sha256()
    total = 0
    try:
        with os.fdopen(descriptor, "wb") as output:
            while True:
                chunk = source.read(64 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > maximum:
                    raise StateError("CATALOG_IMAGE_TOO_LARGE")
                digest.update(chunk)
                output.write(chunk)
            output.flush()
            os.fsync(output.fileno())
        if total < 1 or digest.hexdigest() != expected_sha256:
            raise StateError("CATALOG_INPUT_COPY_MISMATCH")
        os.chmod(temporary, 0o600, follow_symlinks=False)
        os.replace(temporary, path)
        os.chmod(path, 0o600, follow_symlinks=False)
    finally:
        temporary.unlink(missing_ok=True)


def parsed_endpoint(raw: str, missing_code: str, invalid_code: str) -> Any:
    if not raw or not raw.strip():
        raise StateError(missing_code)
    if any(ord(character) < 33 for character in raw.strip()):
        raise StateError(invalid_code)
    try:
        parsed = urlparse(raw.strip())
        _ = parsed.port
    except ValueError as error:
        raise StateError(invalid_code) from error
    if parsed.username is not None or parsed.password is not None or parsed.query or parsed.fragment:
        raise StateError(invalid_code)
    return parsed


def validate_seedream_configuration(configuration: dict[str, str]) -> dict[str, Any]:
    if not configuration.get("SEEDREAM_API_KEY", "").strip():
        raise StateError("SEEDREAM_API_KEY_MISSING")
    model = configuration.get("SEEDREAM_MODEL", "").strip()
    if not model:
        raise StateError("SEEDREAM_MODEL_MISSING")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,159}", model):
        raise StateError("SEEDREAM_MODEL_INVALID")
    endpoint = parsed_endpoint(
        configuration.get("SEEDREAM_BASE_URL", ""),
        "SEEDREAM_BASE_URL_MISSING",
        "SEEDREAM_BASE_URL_INVALID",
    )
    normalized_path = endpoint.path.rstrip("/")
    if (
        endpoint.scheme.lower() != "https"
        or (endpoint.hostname or "").lower() != "ark.cn-beijing.volces.com"
        or endpoint.port is not None
        or normalized_path != "/api/v3"
    ):
        raise StateError("SEEDREAM_BASE_URL_INVALID")
    return {
        "providerFamily": "SEEDREAM",
        "apiKeyPresent": True,
        "configuredModelPresent": True,
        "hostClass": "VOLCENGINE_ARK_OFFICIAL",
        "region": "CN_BEIJING",
        "readinessCategory": "READY_FOR_CONTROLLED_EXECUTION",
        "modelIdentityHash": sha256_text(model),
    }


def validate_qwen_configuration(configuration: dict[str, str]) -> dict[str, Any]:
    if not configuration.get("QWEN_API_KEY", "").strip():
        raise StateError("QWEN_API_KEY_MISSING")
    model = configuration.get("QWEN_MODEL", "").strip()
    if not model:
        raise StateError("QWEN_MODEL_MISSING")
    if not re.fullmatch(r"qwen3-vl-plus(?:-[A-Za-z0-9._-]{1,96})?", model):
        raise StateError("QWEN_MODEL_INVALID")
    endpoint = parsed_endpoint(
        configuration.get("QWEN_BASE_URL", ""),
        "QWEN_BASE_URL_MISSING",
        "QWEN_BASE_URL_INVALID",
    )
    host = (endpoint.hostname or "").lower()
    if (
        endpoint.scheme.lower() != "https"
        or host not in {"dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com"}
        or endpoint.port is not None
        or endpoint.path.rstrip("/") != "/compatible-mode/v1"
    ):
        raise StateError("QWEN_BASE_URL_INVALID")
    return {
        "providerFamily": "QWEN",
        "apiKeyPresent": True,
        "configuredModelPresent": True,
        "hostClass": "ALIBABA_MODEL_STUDIO_OFFICIAL",
        "region": "CN" if host == "dashscope.aliyuncs.com" else "INTERNATIONAL",
        "readinessCategory": "READY_FOR_CONTROLLED_EXECUTION",
        "modelIdentityHash": sha256_text(model),
    }


def validate_vmm_configuration(configuration: dict[str, str]) -> dict[str, Any]:
    endpoint = parsed_endpoint(
        configuration.get("PAINTING_MUSIC_SERVICE_URL", ""),
        "VMM_CONFIGURATION_MISSING",
        "VMM_CONFIGURATION_INVALID",
    )
    host = (endpoint.hostname or "").lower()
    if (
        endpoint.scheme.lower() != "http"
        or host not in {"127.0.0.1", "localhost", "::1"}
        or endpoint.port != 5001
        or endpoint.path not in {"", "/"}
    ):
        raise StateError("VMM_CONFIGURATION_INVALID")
    output_raw = configuration.get("AURALINK_VMM_OUTPUT_DIR", "").strip()
    if not output_raw:
        raise StateError("VMM_CONFIGURATION_MISSING")
    output_root = Path(output_raw)
    if not output_root.is_absolute() or output_root.is_symlink() or not output_root.is_dir():
        raise StateError("VMM_OUTPUT_ROOT_INVALID")
    if output_root.resolve(strict=True) != output_root:
        raise StateError("VMM_OUTPUT_ROOT_INVALID")
    runtime_identity = "\n".join((
        str(output_root),
        configuration.get("AURALINK_VMM_MUSICGEN_PATH", "DEFAULT_MUSICGEN"),
        configuration.get("AURALINK_VMM_CHECKPOINT_PATH", "DEFAULT_CHECKPOINT"),
        configuration.get("AURALINK_VMM_CLIP_MODEL", "ViT-B/32"),
    ))
    return {
        "providerFamily": "VMM",
        "loopbackServiceConfigured": True,
        "outputRootConfigured": True,
        "hostClass": "LOOPBACK_INTERNAL",
        "readinessCategory": "INTERNAL_SERVICE_NOT_VALIDATED",
        "runtimeIdentityHash": sha256_text(runtime_identity),
    }


def staging_root(configuration: dict[str, str], require_empty: bool = True) -> Path:
    raw = configuration.get("AURALINK_PROVIDER_STAGING_DIR", "/tmp/auralink-provider-staging").strip()
    path = Path(raw)
    if not path.is_absolute() or path in {Path("/"), Path("/tmp"), Path("/root")}:
        raise StateError("STAGING_ROOT_INVALID")
    normalized = path.absolute()
    if normalized != path or path.is_symlink():
        raise StateError("STAGING_ROOT_INVALID")
    if path.exists():
        if not path.is_dir():
            raise StateError("STAGING_ROOT_INVALID")
        if path.resolve(strict=True) != path or mode_bits(path) != 0o700:
            raise StateError("STAGING_ROOT_INVALID")
        if path.stat(follow_symlinks=False).st_uid != os.geteuid():
            raise StateError("STAGING_ROOT_INVALID")
        if require_empty and any(path.iterdir()):
            raise StateError("STAGING_ROOT_NOT_EMPTY")
    else:
        parent = path.parent
        if parent.is_symlink() or not parent.is_dir() or not os.access(parent, os.W_OK):
            raise StateError("STAGING_ROOT_INVALID")
        if parent.resolve(strict=True) != Path(os.path.abspath(parent)):
            raise StateError("STAGING_ROOT_INVALID")
    return path


def positive_integer(raw: str, code: str) -> int:
    if not re.fullmatch(r"[1-9][0-9]{0,18}", str(raw).strip()):
        raise StateError(code)
    value = int(str(raw).strip())
    if value > 2**63 - 1:
        raise StateError(code)
    return value


def validated_safe_settings(
    configuration: dict[str, str],
    require_seedream_settings: bool = True,
) -> dict[str, Any]:
    settings = {
        "maxImageInputBytes": positive_integer(
            configuration.get("AURALINK_PROVIDER_MAX_IMAGE_INPUT_BYTES", "10485760"),
            "PROVIDER_LIMIT_CONFIGURATION_INVALID",
        ),
        "maxImageOutputBytes": positive_integer(
            configuration.get("AURALINK_PROVIDER_MAX_IMAGE_OUTPUT_BYTES", "26214400"),
            "PROVIDER_LIMIT_CONFIGURATION_INVALID",
        ),
        "maxAudioOutputBytes": positive_integer(
            configuration.get("AURALINK_PROVIDER_MAX_AUDIO_OUTPUT_BYTES", "268435456"),
            "PROVIDER_LIMIT_CONFIGURATION_INVALID",
        ),
        "maxTextChars": positive_integer(
            configuration.get("AURALINK_PROVIDER_MAX_TEXT_CHARS", "20000"),
            "PROVIDER_LIMIT_CONFIGURATION_INVALID",
        ),
        "connectTimeoutMs": positive_integer(
            configuration.get("AURALINK_PROVIDER_CONNECT_TIMEOUT_MS", "5000"),
            "PROVIDER_TIMEOUT_CONFIGURATION_INVALID",
        ),
        "qwenReadTimeoutMs": positive_integer(
            configuration.get("AURALINK_PROVIDER_QWEN_READ_TIMEOUT_MS", "180000"),
            "PROVIDER_TIMEOUT_CONFIGURATION_INVALID",
        ),
        "seedreamReadTimeoutMs": positive_integer(
            configuration.get("AURALINK_PROVIDER_SEEDREAM_READ_TIMEOUT_MS", "300000"),
            "PROVIDER_TIMEOUT_CONFIGURATION_INVALID",
        ),
        "vmmReadTimeoutMs": positive_integer(
            configuration.get("AURALINK_PROVIDER_VMM_READ_TIMEOUT_MS", "600000"),
            "PROVIDER_TIMEOUT_CONFIGURATION_INVALID",
        ),
        "seedreamConcurrency": positive_integer(
            configuration.get("AURALINK_PROVIDER_MAX_CONCURRENT_SEEDREAM", "2"),
            "PROVIDER_CONCURRENCY_CONFIGURATION_INVALID",
        ),
        "qwenConcurrency": positive_integer(
            configuration.get("AURALINK_PROVIDER_MAX_CONCURRENT_QWEN", "4"),
            "PROVIDER_CONCURRENCY_CONFIGURATION_INVALID",
        ),
        "vmmConcurrency": positive_integer(
            configuration.get("AURALINK_PROVIDER_MAX_CONCURRENT_VMM", "1"),
            "PROVIDER_CONCURRENCY_CONFIGURATION_INVALID",
        ),
    }
    if require_seedream_settings:
        size = configuration.get("AURALINK_SEEDREAM_DEFAULT_SIZE", "2K").strip()
        output_format = configuration.get("AURALINK_SEEDREAM_OUTPUT_FORMAT", "png").strip()
        watermark = configuration.get("AURALINK_SEEDREAM_WATERMARK", "true").strip().lower()
        if size not in {"1K", "2K", "4K"} or output_format not in {"png", "jpeg"}:
            raise StateError("SEEDREAM_GENERATION_CONFIGURATION_INVALID")
        if watermark not in {"true", "false"}:
            raise StateError("SEEDREAM_GENERATION_CONFIGURATION_INVALID")
        settings.update({
            "seedreamSize": size,
            "seedreamOutputFormat": output_format,
            "seedreamWatermark": watermark == "true",
        })
    return settings


def input_hash(operation_token: str, painting: dict[str, Any] | None) -> str:
    if operation_token == "text-to-painting":
        return sha256_text(TEXT_SOURCE)
    if operation_token == "poem-to-painting":
        return sha256_text(POEM_SOURCE)
    if painting is None:
        raise StateError("DETERMINISTIC_INPUT_REQUIRED")
    return painting["sha256"]


def operation_configuration(
    operation_token: str,
    configuration: dict[str, str],
) -> tuple[list[dict[str, Any]], str]:
    require_operation(operation_token)
    uses_seedream = operation_token in {
        "text-to-painting", "image-to-painting", "poem-to-painting"
    }
    settings = validated_safe_settings(configuration, require_seedream_settings=uses_seedream)
    readiness: list[dict[str, Any]] = []
    if operation_token in {"text-to-painting", "image-to-painting", "poem-to-painting"}:
        readiness.append(validate_seedream_configuration(configuration))
    if operation_token in {"poem-to-painting", "painting-to-poem"}:
        readiness.append(validate_qwen_configuration(configuration))
    if operation_token == "painting-to-music":
        readiness.append(validate_vmm_configuration(configuration))
    safe_identity = {
        "operation": operation_token,
        "provider": OPERATIONS[operation_token]["provider"],
        "readiness": readiness,
        "settings": settings,
    }
    return readiness, hashlib.sha256(json_bytes(safe_identity)).hexdigest()


def port_is_free(port: int, host: str = "127.0.0.1") -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            probe.bind((host, port))
        except OSError:
            return False
    return True


def assert_port_state(operation_token: str, mode: str) -> None:
    if operation_token != "painting-to-music":
        return
    free = port_is_free(5001)
    if mode in {"dry-run", "static"} and not free:
        raise StateError("VMM_PORT_OCCUPIED")
    if mode == "validate" and free:
        raise StateError("VMM_SERVICE_NOT_LISTENING")


def create_private_run(run_root: Path, operation_token: str) -> Path:
    if run_root != PRIVATE_RUN_ROOT:
        raise StateError("PRIVATE_RUN_ROOT_INVALID")
    if run_root.is_symlink():
        raise StateError("PRIVATE_RUN_ROOT_INVALID")
    if not run_root.exists():
        run_root.mkdir(mode=0o700, parents=False)
    os.chmod(run_root, 0o700, follow_symlinks=False)
    require_private_directory(run_root)
    timestamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    run = run_root / f"{timestamp}-{operation_token}"
    run.mkdir(mode=0o700)
    os.chmod(run, 0o700, follow_symlinks=False)
    return require_private_directory(run, run_root)


def preflight_document(
    project_root: Path,
    operation_token: str,
    commit: str,
    configuration: dict[str, str],
    include_database: bool = True,
) -> dict[str, Any]:
    operation = require_operation(operation_token)
    readiness, fingerprint = operation_configuration(operation_token, configuration)
    staging_root(configuration)
    painting = resolve_painting_input(project_root, configuration) if operation["image"] else None
    database = inspect_database(project_root) if include_database else None
    free_bytes = shutil.disk_usage(PRIVATE_RUN_ROOT.parent).free
    if free_bytes < 512 * 1024 * 1024:
        raise StateError("VALIDATION_RUN_SPACE_INSUFFICIENT")
    try:
        if PRIVATE_RUN_ROOT.exists():
            require_private_directory(PRIVATE_RUN_ROOT)
    except OSError as error:
        raise StateError("PRIVATE_RUN_ROOT_UNAVAILABLE") from error
    return {
        "manifestVersion": "1",
        "status": "PREFLIGHT_OK",
        "commit": commit,
        "operation": operation["code"],
        "providerCode": operation["provider"],
        "inputSha256": input_hash(operation_token, painting),
        "configurationFingerprint": fingerprint,
        "providerReadiness": readiness,
        "safeSettings": validated_safe_settings(
            configuration,
            require_seedream_settings=operation_token in {
                "text-to-painting", "image-to-painting", "poem-to-painting"
            },
        ),
        "expectedCalls": operation["calls"],
        "database": database,
        "painting": None if painting is None else {
            "paintingId": painting["paintingId"],
            "title": painting["title"],
            "author": painting["author"],
            "dynasty": painting["dynasty"],
            "mimeType": painting["mimeType"],
            "width": painting["width"],
            "height": painting["height"],
            "sha256Prefix": painting["sha256"][:12],
        },
        "zeroMutation": True,
    }


def create_run_state(
    project_root: Path,
    operation_token: str,
    commit: str,
    configuration: dict[str, str],
) -> Path:
    document = preflight_document(project_root, operation_token, commit, configuration)
    healthy = find_healthy_run(PRIVATE_RUN_ROOT, document)
    if healthy is not None:
        raise StateError("ALREADY_VALIDATED_AND_HEALTHY")
    run = create_private_run(PRIVATE_RUN_ROOT, operation_token)
    try:
        if OPERATIONS[operation_token]["image"]:
            resolve_painting_input(project_root, configuration, run)
        write_private_json(run / "preflight-manifest.json", document)
        write_private_json(run / "database-before.json", document["database"])
        write_private_json(run / "operator-review-checklist.json", {
            "operation": OPERATIONS[operation_token]["code"],
            "structuralValidationRequired": True,
            "operatorReviewRequired": True,
            "operatorAudioReviewRequired": operation_token == "painting-to-music",
            "reviewCompleted": False,
        })
        return run
    except Exception:
        shutil.rmtree(run, ignore_errors=True)
        raise


def forbidden_evidence(value: Any, key: str = "") -> bool:
    normalized = re.sub(r"[^a-z0-9]", "", key.lower())
    if normalized == "apikeypresent" and isinstance(value, bool):
        return False
    if normalized == "modelidentityhash" and isinstance(value, str):
        return re.fullmatch(r"[0-9a-f]{64}", value) is None
    if any(re.sub(r"[^a-z0-9]", "", forbidden) in normalized for forbidden in FORBIDDEN_EVIDENCE_KEYS):
        return True
    if isinstance(value, dict):
        return any(forbidden_evidence(child, str(name)) for name, child in value.items())
    if isinstance(value, list):
        return any(forbidden_evidence(child) for child in value)
    if isinstance(value, str):
        lowered = value.lower()
        return "authorization: bearer" in lowered or "data:image/" in lowered or "x-amz-signature=" in lowered
    return False


def read_private_json(path: Path, parent: Path) -> Any:
    require_private_file(path, parent)
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise StateError("PRIVATE_MANIFEST_INVALID") from error
    if forbidden_evidence(value):
        raise StateError("PRIVATE_MANIFEST_SECRET_BOUNDARY_FAILED")
    return value


def validate_poem_file(path: Path, parent: Path) -> str:
    poem = read_private_json(path, parent)
    if not isinstance(poem, dict) or set(poem) != {"schemaVersion", "title", "lines", "text"}:
        raise StateError("VALIDATED_POEM_INVALID")
    lines = poem.get("lines")
    if poem.get("schemaVersion") != "1" or not isinstance(lines, list) or len(lines) != 4:
        raise StateError("VALIDATED_POEM_INVALID")
    if any(not isinstance(line, str) or not line.strip() for line in lines):
        raise StateError("VALIDATED_POEM_INVALID")
    if not isinstance(poem.get("text"), str) or not poem["text"].strip():
        raise StateError("VALIDATED_POEM_INVALID")
    return sha256_file(path)


def validate_wave(path: Path, maximum: int) -> None:
    size = path.stat().st_size
    if size < 44 or size > maximum:
        raise StateError("VALIDATED_WAV_INVALID")
    with path.open("rb") as source:
        header = source.read(12)
    if header[:4] != b"RIFF" or header[8:12] != b"WAVE":
        raise StateError("VALIDATED_WAV_INVALID")


def validate_retained_result(run: Path, metadata: dict[str, Any]) -> str:
    required = {
        "resultFile", "mimeType", "byteLength", "sha256", "width", "height",
        "structuralState", "reviewState",
    }
    if not isinstance(metadata, dict) or set(metadata) != required:
        raise StateError("RESULT_METADATA_INVALID")
    name = metadata.get("resultFile")
    if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", name):
        raise StateError("RESULT_METADATA_INVALID")
    result = require_private_file(run / name, run)
    digest = sha256_file(result)
    if digest != metadata.get("sha256") or result.stat().st_size != metadata.get("byteLength"):
        raise StateError("RESULT_METADATA_INVALID")
    mime = metadata.get("mimeType")
    if mime in {"image/jpeg", "image/png"}:
        detected, width, height = parse_image(result)
        if (detected, width, height) != (mime, metadata.get("width"), metadata.get("height")):
            raise StateError("RESULT_METADATA_INVALID")
    elif mime == "audio/wav":
        validate_wave(result, 268435456)
    elif mime == "application/json":
        validate_poem_file(result, run)
    else:
        raise StateError("RESULT_METADATA_INVALID")
    if metadata.get("structuralState") != "STRUCTURALLY_VALID":
        raise StateError("RESULT_METADATA_INVALID")
    return digest


def find_healthy_run(run_root: Path, expected: dict[str, Any]) -> Path | None:
    if not run_root.exists():
        return None
    require_private_directory(run_root)
    matches: list[Path] = []
    for candidate in sorted(run_root.iterdir()):
        if candidate.is_symlink() or not candidate.is_dir():
            continue
        manifest_path = candidate / "validation-manifest.json"
        if not manifest_path.is_file() or manifest_path.is_symlink():
            continue
        try:
            run = require_private_directory(candidate, run_root)
            manifest = read_private_json(manifest_path, run)
            comparable = (
                manifest.get("status") == "SUCCESS"
                and manifest.get("commit") == expected.get("commit")
                and manifest.get("operation") == expected.get("operation")
                and manifest.get("providerCode") == expected.get("providerCode")
                and manifest.get("inputSha256") == expected.get("inputSha256")
                and manifest.get("configurationFingerprint") == expected.get("configurationFingerprint")
                and manifest.get("calls") == expected.get("expectedCalls")
                and manifest.get("cleanupComplete") is True
            )
            if not comparable:
                continue
            metadata = read_private_json(run / "result-metadata.json", run)
            digest = validate_retained_result(run, metadata)
            if digest != manifest.get("outputSha256"):
                continue
            matches.append(run)
        except StateError:
            continue
    if len(matches) > 1:
        raise StateError("AMBIGUOUS_VALIDATION_RUNS")
    return matches[0] if matches else None


def finalize_run(project_root: Path, run: Path) -> dict[str, Any]:
    private_run = require_private_directory(run, PRIVATE_RUN_ROOT)
    preflight = read_private_json(private_run / "preflight-manifest.json", private_run)
    before = read_private_json(private_run / "database-before.json", private_run)
    after = inspect_database(project_root)
    if before != after:
        raise StateError("PRODUCTION_DATABASE_CHANGED")
    call_counts = read_private_json(private_run / "call-counts.json", private_run)
    expected_calls = preflight.get("expectedCalls")
    if (
        call_counts.get("calls") != expected_calls
        or call_counts.get("executionEntered") is not True
        or call_counts.get("retryHandlerInvoked") is not False
        or call_counts.get("outputCount") != 1
    ):
        raise StateError("PROVIDER_CALL_COUNT_MISMATCH")
    cleanup = read_private_json(private_run / "cleanup-result.json", private_run)
    if cleanup.get("cleanupComplete") is not True or cleanup.get("stagingEmpty") is not True:
        raise StateError("PROVIDER_CLEANUP_INCOMPLETE")
    result_metadata = read_private_json(private_run / "result-metadata.json", private_run)
    output_sha = validate_retained_result(private_run, result_metadata)
    execution_result = read_private_json(private_run / "execution-result.json", private_run)
    elapsed_millis = execution_result.get("elapsedMillis")
    validated_at = execution_result.get("validatedAt")
    if not isinstance(elapsed_millis, int) or elapsed_millis < 0:
        raise StateError("EXECUTION_RESULT_INVALID")
    if not isinstance(validated_at, str) or not re.fullmatch(
        r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z", validated_at
    ):
        raise StateError("EXECUTION_RESULT_INVALID")
    manifest = {
        "manifestVersion": "1",
        "status": "SUCCESS",
        "commit": preflight["commit"],
        "operation": preflight["operation"],
        "providerCode": preflight["providerCode"],
        "inputSha256": preflight["inputSha256"],
        "configurationFingerprint": preflight["configurationFingerprint"],
        "outputSha256": output_sha,
        "validatedAt": validated_at,
        "elapsedMillis": elapsed_millis,
        "structuralState": "STRUCTURALLY_VALID",
        "reviewState": result_metadata["reviewState"],
        "cleanupComplete": True,
        "calls": expected_calls,
    }
    for source_name in ("input-image.jpg", "input-image.png", "input-metadata.json"):
        source = private_run / source_name
        if source.exists():
            require_private_file(source, private_run).unlink()
    write_private_json(private_run / "database-after.json", after)
    write_private_json(private_run / "validation-manifest.json", manifest)
    return manifest


def cleanup_failed_run(project_root: Path, run: Path) -> dict[str, Any]:
    private_run = require_private_directory(run, PRIVATE_RUN_ROOT)
    configuration = merged_configuration(
        project_root, {"AURALINK_PROVIDER_STAGING_DIR"}
    )
    staging = staging_root(configuration, require_empty=False)
    removed = 0
    if staging.exists():
        if staging.resolve(strict=True) != staging or staging.stat(follow_symlinks=False).st_uid != os.geteuid():
            raise StateError("STAGING_ROOT_INVALID")
        for entry in list(staging.iterdir()):
            if entry.parent != staging or entry.is_dir() and not entry.is_symlink():
                raise StateError("STAGING_CLEANUP_REFUSED")
            entry.unlink()
            removed += 1
    if not (private_run / "validation-manifest.json").exists():
        for name in (
            "validated-result.png", "validated-result.jpg", "validated-result.jpeg",
            "validated-result.wav", "validated-poem.json", "result-metadata.json",
            "execution-result.json", "call-counts.json", "input-image.png",
            "input-image.jpg", "input-metadata.json",
        ):
            candidate = private_run / name
            if candidate.exists() or candidate.is_symlink():
                if candidate.parent != private_run or candidate.is_dir() and not candidate.is_symlink():
                    raise StateError("PRIVATE_FAILURE_CLEANUP_REFUSED")
                candidate.unlink()
    database_after = inspect_database(project_root)
    before_path = private_run / "database-before.json"
    if before_path.exists():
        database_before = read_private_json(before_path, private_run)
        if database_before != database_after:
            raise StateError("PRODUCTION_DATABASE_CHANGED")
    document = {
        "failureCleanupComplete": True,
        "stagingEmpty": not staging.exists() or not any(staging.iterdir()),
        "stagingEntriesRemoved": removed,
        "databaseUnchanged": True,
    }
    target = private_run / "failure-cleanup.json"
    if not target.exists():
        write_private_json(target, document)
    return document


def static_vmm_preflight(project_root: Path, configuration: dict[str, str]) -> dict[str, Any]:
    checks: dict[str, str] = {}
    root = project_root.resolve(strict=True)
    python = root / "micromamba/envs/auralink-ai/bin/python"
    app = root / "VMM/app.py"
    audiocraft = root / "VMM/audiocraft"
    clip_source = root / "CLIP"
    musicgen = Path(configuration.get("AURALINK_VMM_MUSICGEN_PATH", str(root / "VMM/models/musicgen-small")))
    checkpoint = Path(configuration.get("AURALINK_VMM_CHECKPOINT_PATH", str(root / "VMM/models/final_model.pth")))

    checks["pythonEnvironment"] = "PASS" if python.is_file() and os.access(python, os.X_OK) else "VMM_PYTHON_MISSING"
    checks["vmmApplication"] = "PASS" if app.is_file() and not app.is_symlink() else "VMM_PACKAGE_PATH_INVALID"
    checks["audiocraftSource"] = "PASS" if (audiocraft / "audiocraft").is_dir() and not audiocraft.is_symlink() else "VMM_PACKAGE_PATH_INVALID"
    checks["clipSource"] = "PASS" if (clip_source / "clip").is_dir() and not clip_source.is_symlink() else "VMM_PACKAGE_PATH_INVALID"
    checks["packageSourceResolution"] = "PASS" if (
        (audiocraft / "audiocraft.egg-info").is_dir()
        and (clip_source / "clip.egg-info").is_dir()
    ) else "VMM_PACKAGE_PATH_INVALID"
    music_files = (
        "compression_state_dict.bin", "state_dict.bin", "config.json", "spiece.model", "tokenizer.json"
    )
    checks["musicGenFiles"] = "PASS" if musicgen.is_dir() and all((musicgen / name).is_file() for name in music_files) else "VMM_MUSICGEN_ASSET_MISSING"
    checks["textEncoderCache"] = "PASS" if musicgen.is_dir() and all((musicgen / name).is_file() for name in ("spiece.model", "tokenizer.json", "tokenizer_config.json")) else "VMM_TEXT_ENCODER_ASSET_MISSING"
    checks["checkpoint"] = "PASS" if checkpoint.is_file() and checkpoint.stat().st_size > 0 else "VMM_CHECKPOINT_MISSING"

    clip_cache_candidates = [
        Path(configuration.get("AURALINK_VMM_CLIP_CACHE", "")) if configuration.get("AURALINK_VMM_CLIP_CACHE") else None,
        Path("/root/.cache/clip/ViT-B-32.pt"),
    ]
    def safe_regular(candidate: Path | None) -> bool:
        if candidate is None:
            return False
        try:
            return candidate.is_file() and not candidate.is_symlink()
        except OSError:
            return False

    clip_ready = any(safe_regular(path) for path in clip_cache_candidates)
    checks["clipAssetCache"] = "PASS" if clip_ready else "VMM_CLIP_ASSET_MISSING"

    try:
        validate_vmm_configuration(configuration)
        checks["configuration"] = "PASS"
        checks["outputRoot"] = "PASS"
    except StateError as error:
        checks["configuration"] = error.code
        checks["outputRoot"] = "VMM_OUTPUT_ROOT_INVALID" if error.code == "VMM_OUTPUT_ROOT_INVALID" else "NOT_CHECKED"
    checks["gpuVisibilityCommand"] = "PASS" if gpu_visible() else "VMM_GPU_NOT_VISIBLE"
    blockers = sorted({value for value in checks.values() if value not in {"PASS", "NOT_CHECKED"}})
    return {
        "state": "VMM_STATIC_PREFLIGHT_READY" if not blockers else "VMM_STATIC_PREFLIGHT_BLOCKED",
        "checks": checks,
        "reasonCodes": blockers,
        "modelImported": False,
        "checkpointLoaded": False,
        "networkUsed": False,
    }


def gpu_visible() -> bool:
    if not shutil.which("nvidia-smi"):
        return False
    try:
        completed = subprocess.run(
            ["nvidia-smi", "-L"],
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            timeout=10,
        )
        return completed.returncode == 0 and bool(completed.stdout.strip())
    except (OSError, subprocess.SubprocessError):
        return False


def exec_vmm(project_root: Path) -> None:
    configuration = merged_configuration(project_root, VMM_CONFIGURATION_KEYS)
    preflight = static_vmm_preflight(project_root, configuration)
    if preflight["state"] != "VMM_STATIC_PREFLIGHT_READY":
        raise StateError("VMM_STATIC_PREFLIGHT_BLOCKED")
    root = project_root.resolve(strict=True)
    python = require_regular(root / "micromamba/envs/auralink-ai/bin/python", "VMM_PYTHON_MISSING")
    application = require_regular(root / "VMM/app.py", "VMM_PACKAGE_PATH_INVALID")
    output_root = require_directory(
        Path(configuration["AURALINK_VMM_OUTPUT_DIR"]), "VMM_OUTPUT_ROOT_INVALID"
    )
    runtime_environment = {
        "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
        "HOME": os.environ.get("HOME", "/root"),
        "LANG": os.environ.get("LANG", "C.UTF-8"),
        "PYTHONUNBUFFERED": "1",
        "PYTHONPATH": os.pathsep.join((str(root / "VMM/audiocraft"), str(root / "CLIP"))),
        "HF_HUB_OFFLINE": "1",
        "TRANSFORMERS_OFFLINE": "1",
        "HF_DATASETS_OFFLINE": "1",
        "AURALINK_HF_ENDPOINT": "",
        "NO_PROXY": "127.0.0.1,localhost,::1",
        "no_proxy": "127.0.0.1,localhost,::1",
        "AURALINK_VMM_SERVICE_HOST": "127.0.0.1",
        "AURALINK_VMM_SERVICE_PORT": "5001",
        "AURALINK_VMM_OUTPUT_DIR": str(output_root),
        "AURALINK_VMM_UPLOAD_DIR": str(output_root),
        "AURALINK_VMM_MUSICGEN_PATH": configuration.get(
            "AURALINK_VMM_MUSICGEN_PATH", str(root / "VMM/models/musicgen-small")
        ),
        "AURALINK_VMM_CHECKPOINT_PATH": configuration.get(
            "AURALINK_VMM_CHECKPOINT_PATH", str(root / "VMM/models/final_model.pth")
        ),
        "AURALINK_VMM_CLIP_MODEL": configuration.get("AURALINK_VMM_CLIP_MODEL", "ViT-B/32"),
    }
    if "CUDA_VISIBLE_DEVICES" in configuration:
        runtime_environment["CUDA_VISIBLE_DEVICES"] = configuration["CUDA_VISIBLE_DEVICES"]
    os.chdir(root / "VMM")
    os.execve(str(python), [str(python), str(application)], runtime_environment)


def safe_print(value: Any) -> None:
    print(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="ROUND 8.1 provider validation state guard")
    subparsers = parser.add_subparsers(dest="command", required=True)

    inspect = subparsers.add_parser("inspect-database")
    inspect.add_argument("--project-root", type=Path, required=True)

    painting = subparsers.add_parser("inspect-painting")
    painting.add_argument("--project-root", type=Path, required=True)

    configuration = subparsers.add_parser("configuration-status")
    configuration.add_argument("--project-root", type=Path, required=True)
    configuration.add_argument("--operation", choices=sorted(OPERATIONS), required=True)

    preflight = subparsers.add_parser("preflight")
    preflight.add_argument("--project-root", type=Path, required=True)
    preflight.add_argument("--operation", choices=sorted(OPERATIONS), required=True)
    preflight.add_argument("--expected-commit", required=True)
    preflight.add_argument("--mode", choices=("dry-run", "validate"), required=True)

    create = subparsers.add_parser("create-run")
    create.add_argument("--project-root", type=Path, required=True)
    create.add_argument("--operation", choices=sorted(OPERATIONS), required=True)
    create.add_argument("--expected-commit", required=True)

    finalize = subparsers.add_parser("finalize-run")
    finalize.add_argument("--project-root", type=Path, required=True)
    finalize.add_argument("--run-dir", type=Path, required=True)

    cleanup = subparsers.add_parser("cleanup-failure")
    cleanup.add_argument("--project-root", type=Path, required=True)
    cleanup.add_argument("--run-dir", type=Path, required=True)

    healthy = subparsers.add_parser("healthy-run")
    healthy.add_argument("--project-root", type=Path, required=True)
    healthy.add_argument("--operation", choices=sorted(OPERATIONS), required=True)
    healthy.add_argument("--expected-commit", required=True)

    vmm = subparsers.add_parser("vmm-static-preflight")
    vmm.add_argument("--project-root", type=Path, required=True)

    execute_vmm = subparsers.add_parser("exec-vmm")
    execute_vmm.add_argument("--project-root", type=Path, required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    arguments = parser.parse_args(argv)
    try:
        root = require_directory(arguments.project_root, "SERVER_LOCAL_ROOT_REQUIRED")
        if arguments.command == "inspect-database":
            safe_print(inspect_database(root))
        elif arguments.command == "inspect-painting":
            configuration = merged_configuration(root, {"AURALINK_PAINTING_PICTURE_DIR"})
            painting = resolve_painting_input(root, configuration)
            safe_print({
                "paintingId": painting["paintingId"],
                "title": painting["title"],
                "author": painting["author"],
                "dynasty": painting["dynasty"],
                "mimeType": painting["mimeType"],
                "width": painting["width"],
                "height": painting["height"],
                "sha256Prefix": painting["sha256"][:12],
            })
        elif arguments.command == "configuration-status":
            configuration = merged_configuration(
                root, operation_configuration_keys(arguments.operation)
            )
            readiness, fingerprint = operation_configuration(arguments.operation, configuration)
            safe_print({
                "operation": OPERATIONS[arguments.operation]["code"],
                "providerCode": OPERATIONS[arguments.operation]["provider"],
                "providerReadiness": readiness,
                "configurationFingerprint": fingerprint,
            })
        elif arguments.command == "preflight":
            configuration = merged_configuration(
                root, operation_configuration_keys(arguments.operation)
            )
            assert_port_state(arguments.operation, arguments.mode)
            safe_print(preflight_document(
                root, arguments.operation, arguments.expected_commit, configuration
            ))
        elif arguments.command == "create-run":
            configuration = merged_configuration(
                root, operation_configuration_keys(arguments.operation)
            )
            run = create_run_state(root, arguments.operation, arguments.expected_commit, configuration)
            print(str(run))
        elif arguments.command == "finalize-run":
            safe_print(finalize_run(root, arguments.run_dir))
        elif arguments.command == "cleanup-failure":
            safe_print(cleanup_failed_run(root, arguments.run_dir))
        elif arguments.command == "healthy-run":
            configuration = merged_configuration(
                root, operation_configuration_keys(arguments.operation)
            )
            expected = preflight_document(root, arguments.operation, arguments.expected_commit, configuration)
            healthy = find_healthy_run(PRIVATE_RUN_ROOT, expected)
            if healthy is None:
                print("NOT_PREVIOUSLY_VALIDATED")
                return 1
            print("ALREADY_VALIDATED_AND_HEALTHY")
        elif arguments.command == "vmm-static-preflight":
            configuration = merged_configuration(root, VMM_CONFIGURATION_KEYS)
            safe_print(static_vmm_preflight(root, configuration))
        elif arguments.command == "exec-vmm":
            exec_vmm(root)
        return 0
    except StateError as error:
        print(f"ROUND81_STATE_ERROR={error.code}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("ROUND81_STATE_ERROR=STATE_TOOL_INTERRUPTED", file=sys.stderr)
        return 130
    except Exception:
        print("ROUND81_STATE_ERROR=STATE_TOOL_INTERNAL_FAILURE", file=sys.stderr)
        return 3


if __name__ == "__main__":
    raise SystemExit(main())
