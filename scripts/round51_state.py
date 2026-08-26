#!/usr/bin/env python3
"""Local-only state and backup primitives for the Round 5.1 activation tool.

This helper deliberately contains no production-root override and no activation
or migration logic.  The shell guard owns the fixed server-local root and calls
these narrowly-scoped operations only after its process and path checks pass.
"""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
import fcntl
import hashlib
import hmac
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
import time
from typing import Any
import uuid


FOUNDATION_TABLES = (
    "media_assets",
    "paintings",
    "catalog_import_runs",
    "painting_guides",
    "painting_favorites",
    "user_workflows",
    "creations",
    "creation_steps",
    "creation_favorites",
)
LEGACY_TABLES = ("users", "generation_logs")
CONTROLLED_ENV = {
    "AURALINK_FLYWAY_ENABLED": "false",
    "AURALINK_JPA_DDL_AUTO": "none",
    "AURALINK_PAINTING_CATALOG_IMPORT_ENABLED": "true",
    "AURALINK_PAINTING_CATALOG_IMPORT_FAIL_ON_ERROR": "true",
}

EXPECTED_HEADERS = (
    "序号", "图像存储名称", "画作名称", "作者姓名", "作者出生年份", "作者出生地", "作者流派",
    "创作年代", "创作朝代", "实际尺寸", "收藏机构", "分类", "题材", "画作流派", "风格", "色彩",
    "构图", "意境", "笔法", "墨法", "绘画材料", "颜料", "印章", "文化符号", "文本生成",
    "音乐情境生成", "收集平台",
)

LEGACY_COLUMNS = {
    "users": (
        ("id", "INTEGER", 0, None, 1),
        ("account_non_expired", "BOOLEAN", 1, None, 0),
        ("account_non_locked", "BOOLEAN", 1, None, 0),
        ("created_at", "TIMESTAMP", 0, None, 0),
        ("credentials_non_expired", "BOOLEAN", 1, None, 0),
        ("email", "VARCHAR(255)", 1, None, 0),
        ("enabled", "BOOLEAN", 1, None, 0),
        ("full_name", "VARCHAR(255)", 1, None, 0),
        ("password", "VARCHAR(255)", 1, None, 0),
        ("role", "VARCHAR(255)", 1, None, 0),
        ("updated_at", "TIMESTAMP", 0, None, 0),
        ("username", "VARCHAR(255)", 1, None, 0),
    ),
    "generation_logs": (
        ("id", "INTEGER", 0, None, 1),
        ("api_provider", "VARCHAR(255)", 0, None, 0),
        ("api_source", "VARCHAR(255)", 1, None, 0),
        ("created_at", "TIMESTAMP", 1, None, 0),
        ("description", "VARCHAR(1024)", 0, None, 0),
        ("duration", "INTEGER", 0, None, 0),
        ("error_message", "VARCHAR(1024)", 0, None, 0),
        ("image_url", "VARCHAR(1024)", 0, None, 0),
        ("input_data", "TEXT", 0, None, 0),
        ("metadata", "TEXT", 0, None, 0),
        ("model_size", "VARCHAR(255)", 1, None, 0),
        ("output_data", "TEXT", 0, None, 0),
        ("processing_time_ms", "BIGINT", 0, None, 0),
        ("result_url", "VARCHAR(1024)", 0, None, 0),
        ("success", "BOOLEAN", 1, None, 0),
        ("task_type", "VARCHAR(255)", 1, None, 0),
        ("use_fast_generate", "BOOLEAN", 1, None, 0),
        ("user_id", "BIGINT", 1, None, 0),
    ),
}
LEGACY_UNIQUE_COLUMNS = {
    "users": {("email",), ("username",)},
    "generation_logs": set(),
}


class StateError(RuntimeError):
    pass


def _readonly(database: Path) -> sqlite3.Connection:
    resolved = database.resolve(strict=True)
    connection = sqlite3.connect(f"{resolved.as_uri()}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    return connection


def _tables(connection: sqlite3.Connection) -> set[str]:
    return {
        row[0]
        for row in connection.execute(
            "SELECT name FROM sqlite_master "
            "WHERE type='table' AND name NOT LIKE 'sqlite_%'"
        )
    }


def _single(connection: sqlite3.Connection, sql: str) -> Any:
    row = connection.execute(sql).fetchone()
    if row is None:
        raise StateError(f"query returned no row: {sql}")
    return row[0]


def _schema_hash(connection: sqlite3.Connection) -> str:
    rows = connection.execute(
        "SELECT type, name, tbl_name, COALESCE(sql, '') "
        "FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' "
        "ORDER BY type, name"
    ).fetchall()
    digest = hashlib.sha256()
    for row in rows:
        digest.update("\x1f".join(str(value) for value in row).encode("utf-8"))
        digest.update(b"\n")
    return digest.hexdigest()


def _quoted_identifier(value: str) -> str:
    return '"' + value.replace('"', '""') + '"'


def _table_structure(connection: sqlite3.Connection, table: str) -> tuple[Any, ...]:
    escaped = table.replace("'", "''")
    columns = tuple(
        (
            row[1],
            (row[2] or "").strip().upper(),
            int(row[3]),
            row[4],
            int(row[5]),
        )
        for row in connection.execute(f"PRAGMA table_info('{escaped}')")
    )
    unique_columns: set[tuple[str, ...]] = set()
    for index in connection.execute(f"PRAGMA index_list('{escaped}')"):
        if int(index[2]) != 1:
            continue
        index_name = str(index[1]).replace("'", "''")
        unique_columns.add(tuple(
            str(row[2]) for row in connection.execute(f"PRAGMA index_info('{index_name}')")
        ))
    foreign_keys = tuple(tuple(row) for row in connection.execute(
        f"PRAGMA foreign_key_list('{escaped}')"
    ))
    return columns, frozenset(unique_columns), foreign_keys


def _verify_legacy_structure(connection: sqlite3.Connection) -> None:
    for table in LEGACY_TABLES:
        columns, unique_columns, foreign_keys = _table_structure(connection, table)
        if columns != LEGACY_COLUMNS[table]:
            raise StateError(f"legacy table structure differs for {table}")
        if unique_columns != LEGACY_UNIQUE_COLUMNS[table]:
            raise StateError(f"legacy unique constraints differ for {table}")
        if foreign_keys:
            raise StateError(f"legacy foreign-key structure differs for {table}")


def _update_digest_value(digest: Any, value: Any) -> None:
    if value is None:
        digest.update(b"N\0")
    elif isinstance(value, bytes):
        digest.update(b"B")
        digest.update(value.hex().encode("ascii"))
        digest.update(b"\0")
    else:
        digest.update(type(value).__name__.encode("ascii"))
        digest.update(b":")
        digest.update(str(value).encode("utf-8"))
        digest.update(b"\0")


def _data_hash(connection: sqlite3.Connection, tables: list[str]) -> str:
    digest = hashlib.sha256()
    for table in sorted(tables):
        digest.update(table.encode("utf-8"))
        digest.update(b"\n")
        query = f"SELECT * FROM {_quoted_identifier(table)} ORDER BY rowid"
        for row in connection.execute(query):
            for value in row:
                _update_digest_value(digest, value)
            digest.update(b"\n")
    return digest.hexdigest()


def _legacy_structure_hash(connection: sqlite3.Connection) -> str:
    digest = hashlib.sha256()
    for table in LEGACY_TABLES:
        columns, unique_columns, foreign_keys = _table_structure(connection, table)
        stable_structure = (columns, tuple(sorted(unique_columns)), foreign_keys)
        digest.update(repr(stable_structure).encode("utf-8"))
        digest.update(b"\n")
    return digest.hexdigest()


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _pinned_identity(metadata: os.stat_result) -> tuple[int, int, int, int, int, int]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_size,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
        stat.S_IMODE(metadata.st_mode),
    )


@dataclass
class _PinnedFile:
    path: Path
    parent_descriptor: int
    descriptor: int
    identity: tuple[int, int, int, int, int, int]

    def assert_current(self, label: str) -> None:
        descriptor_identity = _pinned_identity(os.fstat(self.descriptor))
        try:
            path_identity = _pinned_identity(os.stat(
                self.path.name,
                dir_fd=self.parent_descriptor,
                follow_symlinks=False,
            ))
        except FileNotFoundError as exception:
            raise StateError(f"{label} path became missing or unsafe") from exception
        if descriptor_identity != self.identity or path_identity != self.identity:
            raise StateError(f"{label} changed during final release validation")

    def close(self) -> None:
        os.close(self.descriptor)
        os.close(self.parent_descriptor)


def _open_pinned_regular_file(
        path: Path,
        label: str,
        private: bool = False) -> _PinnedFile:
    absolute = Path(os.path.abspath(os.fspath(path)))
    lexical_parent = absolute.parent
    if lexical_parent.is_symlink() or not lexical_parent.is_dir():
        raise StateError(f"{label} parent must be a non-symlink directory")
    resolved_parent = lexical_parent.resolve(strict=True)
    if resolved_parent != lexical_parent:
        raise StateError(f"{label} parent path must not contain symlinks")
    parent_flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
    parent_descriptor = os.open(resolved_parent, parent_flags)
    descriptor = -1
    try:
        flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
        descriptor = os.open(absolute.name, flags, dir_fd=parent_descriptor)
        descriptor_metadata = os.fstat(descriptor)
        path_metadata = os.stat(
            absolute.name,
            dir_fd=parent_descriptor,
            follow_symlinks=False,
        )
        if not stat.S_ISREG(descriptor_metadata.st_mode):
            raise StateError(f"{label} must be a regular file")
        if ((descriptor_metadata.st_dev, descriptor_metadata.st_ino)
                != (path_metadata.st_dev, path_metadata.st_ino)):
            raise StateError(f"{label} changed while it was opened")
        if (private and (
                descriptor_metadata.st_uid != os.getuid()
                or descriptor_metadata.st_mode & 0o077)):
            raise StateError(f"{label} must be private and owned by the current operator")
        return _PinnedFile(
            resolved_parent / absolute.name,
            parent_descriptor,
            descriptor,
            _pinned_identity(descriptor_metadata),
        )
    except BaseException:
        if descriptor >= 0:
            os.close(descriptor)
        os.close(parent_descriptor)
        raise


def _read_pinned_bytes(descriptor: int, label: str, maximum_bytes: int) -> bytes:
    content = os.pread(descriptor, maximum_bytes + 1, 0)
    if len(content) > maximum_bytes:
        raise StateError(f"{label} is unexpectedly large")
    return content


def inspect_database(database: Path) -> dict[str, Any]:
    with _readonly(database) as connection:
        tables = _tables(connection)
        integrity_rows = [row[0] for row in connection.execute("PRAGMA integrity_check")]
        foreign_key_rows = [tuple(row) for row in connection.execute("PRAGMA foreign_key_check")]
        users = _single(connection, "SELECT COUNT(*) FROM users") if "users" in tables else None
        logs = (
            _single(connection, "SELECT COUNT(*) FROM generation_logs")
            if "generation_logs" in tables
            else None
        )
        if integrity_rows != ["ok"]:
            state = "CORRUPT"
        elif not set(LEGACY_TABLES).issubset(tables):
            state = "PARTIALLY_ACTIVATED_UNKNOWN"
        else:
            has_history = "flyway_schema_history" in tables
            present_foundation = set(FOUNDATION_TABLES).intersection(tables)
            if not has_history and not present_foundation and tables == set(LEGACY_TABLES):
                state = "INHERITED_READY" if users == 7 and logs == 118 else "LEGACY_COUNT_MISMATCH"
            elif (has_history and present_foundation == set(FOUNDATION_TABLES)
                  and tables == set(LEGACY_TABLES) | set(FOUNDATION_TABLES) | {"flyway_schema_history"}):
                state = "ACTIVATED_CANDIDATE"
            else:
                state = "PARTIALLY_ACTIVATED_UNKNOWN"
        legacy_structure_hash = None
        legacy_data_hash = None
        if set(LEGACY_TABLES).issubset(tables):
            legacy_structure_hash = _legacy_structure_hash(connection)
            legacy_data_hash = _data_hash(connection, list(LEGACY_TABLES))
        logical_tables = [
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"
            )
        ]
        return {
            "state": state,
            "tables": sorted(tables),
            "users": users,
            "generationLogs": logs,
            "integrityCheck": integrity_rows,
            "foreignKeyViolations": len(foreign_key_rows),
            "schemaSha256": _schema_hash(connection),
            "legacyStructureSha256": legacy_structure_hash,
            "legacyDataSha256": legacy_data_hash,
            "logicalDataSha256": _data_hash(connection, logical_tables),
            "databaseSha256": _sha256(database),
        }


def verify_inherited(
        database: Path,
        expected_legacy_data_sha256: str | None = None) -> dict[str, Any]:
    result = inspect_database(database)
    if result["state"] != "INHERITED_READY":
        raise StateError(f"expected inherited database, found {result['state']}")
    with _readonly(database) as connection:
        _verify_legacy_structure(connection)
    if expected_legacy_data_sha256 and result["legacyDataSha256"] != expected_legacy_data_sha256:
        raise StateError("inherited legacy data digest differs from the reviewed baseline")
    return result


def catalog_fingerprint(csv_path: Path, picture_dir: Path) -> str:
    digest = hashlib.sha256()
    digest.update(b"auralink-official-painting-snapshot-v1\n")
    digest.update(_sha256(csv_path).encode("utf-8"))
    digest.update(b"\0")
    entries: list[tuple[str, int, int]] = []
    for path in picture_dir.resolve(strict=True).iterdir():
        if path.is_symlink() or not path.is_file() or path.suffix.casefold() not in {".jpg", ".jpeg"}:
            continue
        metadata = path.stat(follow_symlinks=False)
        entries.append((path.name, metadata.st_size, metadata.st_mtime_ns // 1_000_000))
    for name, size, modified_millis in sorted(entries, key=lambda entry: entry[0]):
        for value in (name, str(size), str(modified_millis)):
            digest.update(value.encode("utf-8"))
            digest.update(b"\0")
    return digest.hexdigest()


def _canonical_uuid_count(connection: sqlite3.Connection, sql: str) -> int:
    invalid = 0
    for row in connection.execute(sql):
        value = row[0]
        try:
            if not isinstance(value, str) or str(uuid.UUID(value)) != value:
                invalid += 1
        except (ValueError, AttributeError):
            invalid += 1
    return invalid


def verify_activated(
        database: Path,
        csv_path: Path,
        picture_dir: Path,
        expected: dict[str, Any]) -> dict[str, Any]:
    result = inspect_database(database)
    if result["state"] != "ACTIVATED_CANDIDATE":
        raise StateError(f"expected activated database, found {result['state']}")
    if result["users"] != 7 or result["generationLogs"] != 118:
        raise StateError("activated database legacy row counts changed")
    if result["legacyDataSha256"] != expected["legacyDataSha256"]:
        raise StateError("activated database legacy data differs from the reviewed baseline")
    if result["integrityCheck"] != ["ok"] or result["foreignKeyViolations"] != 0:
        raise StateError("activated database integrity or foreign keys are invalid")
    with _readonly(database) as connection:
        _verify_legacy_structure(connection)
        baseline = connection.execute(
            "SELECT COUNT(*) FROM flyway_schema_history "
            "WHERE version = '1' AND type = 'BASELINE' AND success = 1"
        ).fetchone()[0]
        migration = connection.execute(
            "SELECT COUNT(*) FROM flyway_schema_history "
            "WHERE version = '2' AND type = 'SQL' AND success = 1"
        ).fetchone()[0]
        checks = {
            "flywaySchemaRows": _single(
                connection, "SELECT COUNT(*) FROM flyway_schema_history"
            ),
            "flywayBaselineV1": baseline,
            "flywayMigrationV2": migration,
            "paintings": _single(connection, "SELECT COUNT(*) FROM paintings"),
            "paintingPublicIds": _single(
                connection, "SELECT COUNT(DISTINCT public_id) FROM paintings"
            ),
            "paintingSourceKeys": _single(
                connection, "SELECT COUNT(DISTINCT source_key) FROM paintings"
            ),
            "catalogMediaAssets": _single(
                connection,
                "SELECT COUNT(*) FROM media_assets "
                "WHERE source_type='CATALOG_REFERENCE'",
            ),
            "catalogMediaPublicIds": _single(
                connection,
                "SELECT COUNT(DISTINCT public_id) FROM media_assets "
                "WHERE source_type='CATALOG_REFERENCE'",
            ),
            "catalogMediaStorageKeys": _single(
                connection,
                "SELECT COUNT(DISTINCT storage_key) FROM media_assets "
                "WHERE source_type='CATALOG_REFERENCE'",
            ),
            "galleryEligible": _single(
                connection,
                "SELECT COUNT(*) FROM paintings WHERE status='ACTIVE' "
                "AND image_available=1 AND visible_in_gallery=1",
            ),
            "missingImages": _single(
                connection, "SELECT COUNT(*) FROM paintings WHERE image_available=0"
            ),
            "generatedTextPopulated": _single(
                connection,
                "SELECT COUNT(*) FROM paintings "
                "WHERE generated_text IS NOT NULL AND TRIM(generated_text) <> ''",
            ),
            "musicScenePopulated": _single(
                connection,
                "SELECT COUNT(*) FROM paintings WHERE music_scene_description IS NOT NULL "
                "AND TRIM(music_scene_description) <> ''",
            ),
            "linkedCatalogMediaInvalid": _single(
                connection,
                "SELECT COUNT(*) FROM paintings p JOIN media_assets m "
                "ON m.id=p.image_asset_id WHERE m.source_type<>'CATALOG_REFERENCE' "
                "OR m.visibility<>'PUBLIC' OR m.status<>'ACTIVE' "
                "OR m.owner_user_id IS NOT NULL OR m.asset_type<>'IMAGE' "
                "OR m.semantic_type<>'PAINTING' "
                "OR m.storage_key NOT LIKE 'catalog/%'",
            ),
            "paintingPolicyInvalid": _single(
                connection,
                "SELECT COUNT(*) FROM paintings WHERE status<>'ACTIVE' OR "
                "(image_available=1 AND (image_asset_id IS NULL OR visible_in_gallery<>1)) OR "
                "(image_available=0 AND (image_asset_id IS NOT NULL OR visible_in_gallery<>0))",
            ),
            "linkedImageMismatch": _single(
                connection,
                "SELECT COUNT(*) FROM paintings WHERE "
                "(image_available=1 AND image_asset_id IS NULL) OR "
                "(image_available=0 AND image_asset_id IS NOT NULL)",
            ),
            "catalogLinkCount": _single(
                connection,
                "SELECT COUNT(DISTINCT image_asset_id) FROM paintings "
                "WHERE image_asset_id IS NOT NULL",
            ),
            "unlinkedCatalogMedia": _single(
                connection,
                "SELECT COUNT(*) FROM media_assets m WHERE m.source_type='CATALOG_REFERENCE' "
                "AND NOT EXISTS (SELECT 1 FROM paintings p WHERE p.image_asset_id=m.id)",
            ),
            "invalidPaintingUuids": _canonical_uuid_count(
                connection, "SELECT public_id FROM paintings"
            ),
            "invalidCatalogMediaUuids": _canonical_uuid_count(
                connection,
                "SELECT public_id FROM media_assets WHERE source_type='CATALOG_REFERENCE'",
            ),
        }
        expected_checks = {
            "flywaySchemaRows": 2,
            "flywayBaselineV1": 1,
            "flywayMigrationV2": 1,
            "paintings": expected["paintings"],
            "paintingPublicIds": expected["paintings"],
            "paintingSourceKeys": expected["paintings"],
            "catalogMediaAssets": expected["catalogMediaAssets"],
            "catalogMediaPublicIds": expected["catalogMediaAssets"],
            "catalogMediaStorageKeys": expected["catalogMediaAssets"],
            "galleryEligible": expected["visibleInGallery"],
            "missingImages": expected["missingImages"],
            "generatedTextPopulated": expected["generatedTextPopulated"],
            "musicScenePopulated": expected["musicScenePopulated"],
            "linkedCatalogMediaInvalid": 0,
            "paintingPolicyInvalid": 0,
            "linkedImageMismatch": 0,
            "catalogLinkCount": expected["catalogMediaAssets"],
            "unlinkedCatalogMedia": 0,
            "invalidPaintingUuids": 0,
            "invalidCatalogMediaUuids": 0,
        }
        for key, expected_value in expected_checks.items():
            if checks[key] != expected_value:
                raise StateError(
                    f"activated database {key} expected {expected_value}, found {checks[key]}"
                )
        successful_import = connection.execute(
            "SELECT source_sha256, total_rows, matched_images, missing_images, orphan_images "
            "FROM catalog_import_runs WHERE status='SUCCESS' "
            "ORDER BY finished_at DESC LIMIT 1"
        ).fetchone()
        if successful_import is None:
            raise StateError("activated database has no successful catalog import audit")
        if tuple(successful_import[1:]) != (
                expected["paintings"],
                expected["catalogMediaAssets"],
                expected["missingImages"],
                expected["orphanImages"]):
            raise StateError("successful catalog import audit counts are invalid")
        latest_import = connection.execute(
            "SELECT source_sha256, status, total_rows, inserted_rows, updated_rows, "
            "unchanged_rows, matched_images, missing_images, orphan_images, finished_at "
            "FROM catalog_import_runs "
            "ORDER BY started_at DESC, id DESC LIMIT 1"
        ).fetchone()
        if latest_import is None or latest_import[1] != "SKIPPED":
            raise StateError("latest catalog import audit is not the required unchanged SKIPPED run")
        if latest_import[0] != successful_import[0]:
            raise StateError("latest catalog import fingerprint differs from successful import")
        expected_skip = (
            expected["paintings"], 0, 0, expected["paintings"],
            expected["catalogMediaAssets"], expected["missingImages"],
            expected["orphanImages"],
        )
        if tuple(latest_import[2:9]) != expected_skip or latest_import[9] is None:
            raise StateError("latest skipped catalog import audit counts are invalid")
        current_fingerprint = catalog_fingerprint(csv_path, picture_dir)
        if successful_import[0] != current_fingerprint:
            raise StateError("official catalog source fingerprint differs from activated import")
        if current_fingerprint != expected["catalogFingerprint"]:
            raise StateError("official catalog source fingerprint differs from the reviewed source")
        result.update(checks)
        result["catalogFingerprint"] = successful_import[0]
    return result


def verify_preflight(
        database: Path,
        csv_path: Path,
        picture_dir: Path,
        expected: dict[str, Any]) -> dict[str, Any]:
    profile = catalog_profile(csv_path, picture_dir)
    verify_catalog_profile(profile, expected)
    result = inspect_database(database)
    if result["state"] == "INHERITED_READY":
        verified = verify_inherited(
            database,
            expected["legacyDataSha256"],
        )
        verified["catalogProfile"] = profile
        verified["catalogFingerprintCurrent"] = profile["catalogFingerprint"]
        return verified
    if result["state"] == "ACTIVATED_CANDIDATE":
        return verify_activated(database, csv_path, picture_dir, expected)
    raise StateError(f"database state is not safe for activation: {result['state']}")


def verify_logical_backup(database: Path) -> dict[str, Any]:
    result = inspect_database(database)
    if result["integrityCheck"] != ["ok"]:
        raise StateError("backup integrity_check failed")
    if result["users"] != 7 or result["generationLogs"] != 118:
        raise StateError("backup legacy row counts do not match 7 users / 118 generation_logs")
    return result


def verify_database_equivalence(source: Path, candidate: Path) -> dict[str, Any]:
    source_result = verify_logical_backup(source)
    candidate_result = verify_logical_backup(candidate)
    for key in (
            "state",
            "tables",
            "users",
            "generationLogs",
            "schemaSha256",
            "legacyStructureSha256",
            "legacyDataSha256",
            "logicalDataSha256"):
        if source_result[key] != candidate_result[key]:
            raise StateError(f"database backup is not logically equivalent for {key}")
    return {
        "sourceDatabaseSha256": source_result["databaseSha256"],
        "backupDatabaseSha256": candidate_result["databaseSha256"],
        "schemaSha256": candidate_result["schemaSha256"],
        "logicalDataSha256": candidate_result["logicalDataSha256"],
        "state": candidate_result["state"],
        "users": candidate_result["users"],
        "generationLogs": candidate_result["generationLogs"],
        "integrityCheck": candidate_result["integrityCheck"],
    }


def sqlite_backup(source: Path, destination: Path) -> dict[str, Any]:
    source = source.resolve(strict=True)
    destination = destination.resolve(strict=False)
    if destination.exists():
        raise StateError(f"refusing to overwrite backup: {destination}")
    destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with _readonly(source) as source_connection:
        destination_connection = sqlite3.connect(destination)
        try:
            source_connection.backup(destination_connection)
            destination_connection.commit()
        finally:
            destination_connection.close()
    os.chmod(destination, 0o600)
    with destination.open("rb") as backup_file:
        os.fsync(backup_file.fileno())
    return verify_database_equivalence(source, destination)


def backup_env(source: Path, destination: Path) -> None:
    if source.is_symlink():
        raise StateError("backend/.env must be a regular non-symlink file")
    source = source.resolve(strict=True)
    destination = destination.resolve(strict=False)
    if source.is_symlink() or not source.is_file():
        raise StateError("backend/.env must be a regular non-symlink file")
    if destination.exists():
        raise StateError(f"refusing to overwrite env backup: {destination}")
    shutil.copyfile(source, destination)
    os.chmod(destination, 0o600)
    with destination.open("rb") as backup_file:
        os.fsync(backup_file.fileno())


def fsync_directory(directory: Path) -> None:
    if directory.is_symlink() or not directory.is_dir():
        raise StateError("directory fsync target must be a non-symlink directory")
    resolved = directory.resolve(strict=True)
    flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0)
    descriptor = os.open(resolved, flags)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def fsync_file(path: Path) -> None:
    if path.is_symlink() or not path.is_file():
        raise StateError("file fsync target must be a regular non-symlink file")
    resolved = path.resolve(strict=True)
    with resolved.open("rb") as source:
        os.fsync(source.fileno())


def _process_start_time(pid: int) -> str | None:
    try:
        fields = Path(f"/proc/{pid}/stat").read_text(encoding="ascii").split()
    except (OSError, UnicodeError):
        return None
    return fields[21] if len(fields) > 21 else None


def hold_startup_gate(
        gate: Path,
        ready: Path,
        parent_pid: int,
        parent_start_time: str,
        orphan_fence: Path | None = None) -> None:
    """Retain a POSIX write lock shared with Spring's FileChannel guard."""
    if parent_pid <= 1 or not re.fullmatch(r"[0-9]+", parent_start_time):
        raise StateError("startup-gate parent identity is invalid")
    if _process_start_time(parent_pid) != parent_start_time:
        raise StateError("startup-gate coordinator is not alive")
    absolute = Path(os.path.abspath(os.fspath(gate)))
    parent = absolute.parent
    if parent.is_symlink() or not parent.is_dir() or parent.resolve(strict=True) != parent:
        raise StateError("startup-gate parent must be a real non-symlink directory")
    parent_metadata = parent.stat()
    if parent_metadata.st_uid != os.getuid() or parent_metadata.st_mode & 0o077:
        raise StateError("startup-gate parent must be private and operator-owned")
    parent_descriptor = os.open(
        parent,
        os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0),
    )
    descriptor = -1
    try:
        descriptor = os.open(
            absolute.name,
            os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0),
            0o600,
            dir_fd=parent_descriptor,
        )
        metadata = os.fstat(descriptor)
        path_metadata = os.stat(
            absolute.name, dir_fd=parent_descriptor, follow_symlinks=False
        )
        if (not stat.S_ISREG(metadata.st_mode)
                or (metadata.st_dev, metadata.st_ino)
                != (path_metadata.st_dev, path_metadata.st_ino)
                or metadata.st_uid != os.getuid()
                or metadata.st_mode & 0o077):
            raise StateError("startup gate is not a safe private regular file")
        fcntl.lockf(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        os.fsync(descriptor)
        fsync_directory(parent)
        _write_private_exclusive(ready, b"STARTUP_GATE_HELD\n")
        while _process_start_time(parent_pid) == parent_start_time:
            time.sleep(0.25)
        if orphan_fence is not None and orphan_fence.exists():
            # Deliberately remain alive after coordinator loss. This is the
            # last-resort fail-closed state when durable marker restoration
            # could not be proved. The operator must repair/recreate the marker
            # and remove the private orphan-fence file before this gate exits.
            while orphan_fence.exists():
                time.sleep(1)
    finally:
        if descriptor >= 0:
            os.close(descriptor)
        os.close(parent_descriptor)


def _private_regular_file(path: Path, label: str) -> Path:
    if path.is_symlink() or not path.is_file():
        raise StateError(f"{label} must be a regular non-symlink file")
    resolved = path.resolve(strict=True)
    stat_result = resolved.stat()
    if stat_result.st_uid != os.getuid() or stat_result.st_mode & 0o077:
        raise StateError(f"{label} must be private and owned by the current operator")
    return resolved


def _write_private_exclusive(path: Path, payload: bytes) -> None:
    if path.exists() or path.is_symlink():
        raise StateError(f"refusing to overwrite private evidence: {path.name}")
    parent = path.parent
    if parent.is_symlink() or not parent.is_dir():
        raise StateError("private evidence parent must be a non-symlink directory")
    parent = parent.resolve(strict=True)
    destination = parent / path.name
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.round51-", dir=parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(payload)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, 0o600, follow_symlinks=False)
        # A hard link publishes the fully written inode atomically while
        # preserving no-overwrite semantics. The private temporary name is
        # unlinked immediately after successful publication.
        os.link(temporary, destination, follow_symlinks=False)
        fsync_directory(parent)
    except BaseException:
        raise
    finally:
        temporary.unlink(missing_ok=True)
        fsync_directory(parent)


def _create_maintenance_marker_with_token(marker: Path, token: str) -> None:
    if not re.fullmatch(r"[0-9a-f]{64}", token):
        raise StateError("maintenance ownership token is invalid")
    _write_private_exclusive(marker, (token + "\n").encode("ascii"))


def create_maintenance_marker(marker: Path) -> str:
    token = secrets.token_hex(32)
    try:
        _create_maintenance_marker_with_token(marker, token)
    except FileExistsError as exception:
        raise StateError(
            "a maintenance marker already exists; review the stale activation state"
        ) from exception
    return token


def create_recovery_binding(
        marker: Path,
        binding: Path,
        database_backup: Path,
        environment_backup: Path,
        pre_activation_manifest: Path,
        database_verification: Path,
        reviewed_commit: str) -> str:
    """Durably bind one backup directory to the global maintenance marker.

    The binding is written and fsynced before the global marker. Therefore a
    crash can leave an unused binding without a marker, but it cannot leave a
    marker that lacks complete recovery provenance.
    """
    if not re.fullmatch(r"[0-9a-f]{40}", reviewed_commit):
        raise StateError("reviewed commit must be a full lowercase Git object ID")
    binding_parent = binding.parent.resolve(strict=True)
    evidence = {
        "databaseBackup": _private_regular_file(
            database_backup, "database backup").resolve(strict=True),
        "environmentBackup": _private_regular_file(
            environment_backup, "environment backup").resolve(strict=True),
        "preActivationManifest": _private_regular_file(
            pre_activation_manifest, "pre-activation manifest").resolve(strict=True),
        "databaseVerification": _private_regular_file(
            database_verification, "database backup verification").resolve(strict=True),
    }
    if any(path.parent != binding_parent for path in evidence.values()):
        raise StateError("all recovery evidence must belong to the selected backup directory")
    token = secrets.token_hex(32)
    payload: dict[str, Any] = {
        "formatVersion": 1,
        "reviewedCommit": reviewed_commit,
        "backupDirectory": str(binding_parent),
        "maintenanceTokenSha256": hashlib.sha256(token.encode("ascii")).hexdigest(),
    }
    for key, path in evidence.items():
        payload[key] = path.name
        payload[f"{key}Sha256"] = _sha256(path)
    _write_private_exclusive(
        binding,
        (json.dumps(payload, ensure_ascii=True, indent=2, sort_keys=True) + "\n")
        .encode("utf-8"),
    )
    try:
        _create_maintenance_marker_with_token(marker, token)
    except BaseException:
        # The private binding is useful evidence and harmless without the
        # global marker; leave it in place for operator review.
        raise
    return token


def _read_small_private_text(path: Path, label: str, maximum_bytes: int) -> str:
    resolved = _private_regular_file(path, label)
    if resolved.stat().st_size > maximum_bytes:
        raise StateError(f"{label} is unexpectedly large")
    return resolved.read_text(encoding="utf-8")


def _verify_recovery_binding_evidence(
        binding: Path,
        database_backup: Path,
        environment_backup: Path,
        pre_activation_manifest: Path,
        database_verification: Path,
        reviewed_commit: str) -> dict[str, Any]:
    if not re.fullmatch(r"[0-9a-f]{40}", reviewed_commit):
        raise StateError("reviewed commit must be a full lowercase Git object ID")
    binding = _private_regular_file(binding, "recovery binding")
    backup_directory = binding.parent
    files = {
        "databaseBackup": _private_regular_file(database_backup, "database backup"),
        "environmentBackup": _private_regular_file(environment_backup, "environment backup"),
        "preActivationManifest": _private_regular_file(
            pre_activation_manifest, "pre-activation manifest"),
        "databaseVerification": _private_regular_file(
            database_verification, "database backup verification"),
    }
    if any(path.parent != backup_directory for path in files.values()):
        raise StateError("recovery evidence is not from one selected backup directory")
    try:
        payload = json.loads(_read_small_private_text(
            binding, "recovery binding", 64 * 1024))
    except json.JSONDecodeError as exception:
        raise StateError("recovery binding is not valid JSON") from exception
    if not isinstance(payload, dict) or payload.get("formatVersion") != 1:
        raise StateError("recovery binding format is unsupported")
    if payload.get("reviewedCommit") != reviewed_commit:
        raise StateError("recovery binding belongs to a different reviewed commit")
    if payload.get("backupDirectory") != str(backup_directory):
        raise StateError("recovery binding belongs to a different backup directory")
    for key, path in files.items():
        if payload.get(key) != path.name or payload.get(f"{key}Sha256") != _sha256(path):
            raise StateError(f"recovery binding does not match {key}")
    return payload


def _bound_orphan_fence_payload(
        binding: Path,
        database_backup: Path,
        environment_backup: Path,
        pre_activation_manifest: Path,
        database_verification: Path,
        reviewed_commit: str) -> dict[str, Any]:
    binding_payload = _verify_recovery_binding_evidence(
        binding,
        database_backup,
        environment_backup,
        pre_activation_manifest,
        database_verification,
        reviewed_commit,
    )
    binding = _private_regular_file(binding, "recovery binding")
    return {
        "formatVersion": 1,
        "backupDirectory": str(binding.parent),
        "recoveryBinding": binding.name,
        "recoveryBindingSha256": _sha256(binding),
        "reviewedCommit": reviewed_commit,
        "maintenanceTokenSha256": binding_payload["maintenanceTokenSha256"],
    }


def create_bound_orphan_fence(
        fence: Path,
        binding: Path,
        database_backup: Path,
        environment_backup: Path,
        pre_activation_manifest: Path,
        database_verification: Path,
        reviewed_commit: str) -> None:
    """Publish a durable startup fence bound to exactly one recovery run."""
    payload = _bound_orphan_fence_payload(
        binding,
        database_backup,
        environment_backup,
        pre_activation_manifest,
        database_verification,
        reviewed_commit,
    )
    backup_root = binding.parent.resolve(strict=True).parent
    fence_parent = fence.parent.resolve(strict=True)
    if fence_parent != backup_root:
        raise StateError("startup orphan fence is outside the selected backup root")
    _write_private_exclusive(
        fence,
        (json.dumps(payload, ensure_ascii=True, indent=2, sort_keys=True) + "\n")
        .encode("utf-8"),
    )


def verify_bound_orphan_fence(
        fence: Path,
        binding: Path,
        database_backup: Path,
        environment_backup: Path,
        pre_activation_manifest: Path,
        database_verification: Path,
        reviewed_commit: str) -> dict[str, Any]:
    """Authenticate durable orphan evidence against the selected backup run."""
    expected = _bound_orphan_fence_payload(
        binding,
        database_backup,
        environment_backup,
        pre_activation_manifest,
        database_verification,
        reviewed_commit,
    )
    fence = _private_regular_file(fence, "startup orphan fence")
    if fence.parent != binding.parent.resolve(strict=True).parent:
        raise StateError("startup orphan fence is outside the selected backup root")
    try:
        actual = json.loads(_read_small_private_text(
            fence, "startup orphan fence", 64 * 1024))
    except json.JSONDecodeError as exception:
        raise StateError("startup orphan fence is not valid JSON") from exception
    if actual != expected:
        raise StateError("startup orphan fence belongs to a different recovery run")
    return actual


def verify_recovery_binding(
        marker: Path,
        binding: Path,
        database_backup: Path,
        environment_backup: Path,
        pre_activation_manifest: Path,
        database_verification: Path,
        reviewed_commit: str) -> dict[str, Any]:
    payload = _verify_recovery_binding_evidence(
        binding,
        database_backup,
        environment_backup,
        pre_activation_manifest,
        database_verification,
        reviewed_commit,
    )
    marker_text = _read_small_private_text(marker, "maintenance marker", 129).strip()
    if not re.fullmatch(r"[0-9a-f]{64}", marker_text):
        raise StateError("maintenance marker token is invalid")
    if payload.get("maintenanceTokenSha256") != hashlib.sha256(
            marker_text.encode("ascii")).hexdigest():
        raise StateError("selected backup directory is not bound to the maintenance marker")
    return payload


def retain_verified_recovery_token(
        marker: Path,
        binding: Path,
        database_backup: Path,
        environment_backup: Path,
        pre_activation_manifest: Path,
        database_verification: Path,
        reviewed_commit: str) -> str:
    """Return the nonce read and verified in the same helper invocation."""
    # Retain the no-follow marker value first. If it disappears while the
    # potentially longer evidence hashes are checked, the caller already has
    # the exact nonce required to re-establish the fence on failure.
    marker_text = _read_small_private_text(marker, "maintenance marker", 129).strip()
    if not re.fullmatch(r"[0-9a-f]{64}", marker_text):
        raise StateError("maintenance marker token is invalid")
    payload = _verify_recovery_binding_evidence(
        binding,
        database_backup,
        environment_backup,
        pre_activation_manifest,
        database_verification,
        reviewed_commit,
    )
    if payload.get("maintenanceTokenSha256") != hashlib.sha256(
            marker_text.encode("ascii")).hexdigest():
        raise StateError("selected backup directory is not bound to the maintenance marker")
    return marker_text


def recreate_maintenance_marker(
        marker: Path,
        binding: Path,
        database_backup: Path,
        environment_backup: Path,
        pre_activation_manifest: Path,
        database_verification: Path,
        reviewed_commit: str) -> None:
    """Restore a lost activation fence from its durably bound evidence.

    This is deliberately narrower than stale recovery: it requires the live
    coordinator's secret nonce and is used only before a verified rollback.
    """
    payload = _verify_recovery_binding_evidence(
        binding,
        database_backup,
        environment_backup,
        pre_activation_manifest,
        database_verification,
        reviewed_commit,
    )
    token = os.environ.get("AURALINK_ROUND51_MAINTENANCE_TOKEN", "")
    if not re.fullmatch(r"[0-9a-f]{64}", token):
        raise StateError("valid maintenance ownership token is required")
    if payload.get("maintenanceTokenSha256") != hashlib.sha256(
            token.encode("ascii")).hexdigest():
        raise StateError("maintenance token does not match the recovery binding")
    marker_parent = marker.parent.resolve(strict=True)
    binding_directory = binding.parent.resolve(strict=True)
    if binding_directory.parent != marker_parent:
        raise StateError("recovery binding is not beneath the maintenance marker root")
    if marker.exists() or marker.is_symlink():
        raise StateError("maintenance marker unexpectedly exists")
    _create_maintenance_marker_with_token(marker, token)


def remove_maintenance_marker(marker: Path) -> None:
    token = os.environ.get("AURALINK_ROUND51_MAINTENANCE_TOKEN", "")
    if not re.fullmatch(r"[0-9a-f]{64}", token):
        raise StateError("valid maintenance ownership token is required")
    parent = marker.parent
    if parent.is_symlink() or not parent.is_dir():
        raise StateError("maintenance marker parent must be a non-symlink directory")
    parent = parent.resolve(strict=True)
    marker = parent / marker.name
    if marker.is_symlink() or not marker.is_file():
        raise StateError("maintenance marker is missing or unsafe")
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(marker, flags)
    with os.fdopen(descriptor, "r", encoding="ascii") as source:
        actual = source.read(129).strip()
    if not hmac.compare_digest(actual, token):
        raise StateError("maintenance marker ownership token does not match")
    marker.unlink()
    fsync_directory(parent)


def remove_stale_maintenance_marker(
        marker: Path,
        released_marker: Path,
        binding: Path,
        verified_backup: Path,
        environment_backup: Path,
        pre_activation_manifest: Path,
        database_verification: Path,
        current_database: Path,
        expected_legacy_data_sha256: str,
        reviewed_commit: str,
        release_intent: Path,
        allow_activated_current: bool = False,
        env_file: Path | None = None,
        csv_path: Path | None = None,
        picture_dir: Path | None = None,
        expected_activated: dict[str, Any] | None = None) -> None:
    """Release a crash-stale lease only with the reviewed inherited backup present."""
    binding_payload = verify_recovery_binding(
        marker,
        binding,
        verified_backup,
        environment_backup,
        pre_activation_manifest,
        database_verification,
        reviewed_commit,
    )
    verify_inherited(verified_backup, expected_legacy_data_sha256)
    if env_file is None:
        raise StateError("current environment verification is required")
    if allow_activated_current and (
            csv_path is None or picture_dir is None or expected_activated is None):
        raise StateError("full activated-state release evidence is required")
    parent = marker.parent
    if parent.is_symlink() or not parent.is_dir():
        raise StateError("maintenance marker parent must be a non-symlink directory")
    parent = parent.resolve(strict=True)
    marker = parent / marker.name
    released_parent = released_marker.parent.resolve(strict=True)
    if released_parent != binding.parent.resolve(strict=True):
        raise StateError("released marker evidence must belong to the selected backup directory")
    if released_marker.exists() or released_marker.is_symlink():
        raise StateError("released marker evidence already exists")
    database_file: _PinnedFile | None = None
    env_file_handle: _PinnedFile | None = None
    marker_file: _PinnedFile | None = None
    lock_connection: sqlite3.Connection | None = None
    try:
        database_file = _open_pinned_regular_file(
            current_database, "current database"
        )
        env_file_handle = _open_pinned_regular_file(
            env_file, "current environment", private=True
        )
        marker_file = _open_pinned_regular_file(
            marker, "maintenance marker", private=True
        )
        marker_token = _read_pinned_bytes(
            marker_file.descriptor, "maintenance marker", 129
        ).decode("ascii").strip()
        if not re.fullmatch(r"[0-9a-f]{64}", marker_token):
            raise StateError("maintenance marker token is invalid")
        if not hmac.compare_digest(
                binding_payload["maintenanceTokenSha256"],
                hashlib.sha256(marker_token.encode("ascii")).hexdigest()):
            raise StateError("maintenance marker no longer matches its recovery binding")

        # Keep an advisory lock on the exact environment and marker inodes for
        # cooperating operators. SQLite needs a real database lock: an open
        # read descriptor alone does not exclude a short writer that could
        # otherwise commit between verification and marker removal.
        fcntl.flock(env_file_handle.descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        fcntl.flock(marker_file.descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        lock_connection = sqlite3.connect(
            f"{database_file.path.as_uri()}?mode=rw",
            uri=True,
            timeout=0,
            isolation_level=None,
        )
        lock_connection.execute("PRAGMA busy_timeout=0")
        lock_connection.execute("BEGIN IMMEDIATE")

        database_file.assert_current("current database")
        env_file_handle.assert_current("current environment")
        marker_file.assert_current("maintenance marker")

        env_content = _read_pinned_bytes(
            env_file_handle.descriptor, "current environment", 1024 * 1024
        )
        if allow_activated_current:
            verify_activated(
                database_file.path, csv_path, picture_dir, expected_activated
            )
            verify_activation_env_text(env_content.decode("utf-8"))
        else:
            verify_inherited(database_file.path, expected_legacy_data_sha256)
            backup_env_path = _private_regular_file(
                environment_backup, "environment backup"
            )
            if not hmac.compare_digest(
                    hashlib.sha256(env_content).hexdigest(), _sha256(backup_env_path)):
                raise StateError("restored environment does not match its named backup")

        # Immediately before publishing release intent, prove that each path
        # still names the exact inode and contents pinned before validation.
        # BEGIN IMMEDIATE remains held until after the marker is durably gone.
        database_file.assert_current("current database")
        env_file_handle.assert_current("current environment")
        if allow_activated_current:
            current_catalog_fingerprint = catalog_fingerprint(csv_path, picture_dir)
            if current_catalog_fingerprint != expected_activated["catalogFingerprint"]:
                raise StateError("official catalog source changed during final release")
        marker_file.assert_current("maintenance marker")
        released_parent_descriptor = os.open(
            released_parent,
            os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0),
        )
        try:
            # Publish a durable, no-overwrite hard link to the exact nonce inode
            # before detaching the global marker. Crash recovery can therefore
            # authenticate the selected binding even if the host resets in the
            # release handoff window.
            os.link(
                marker_file.path.name,
                released_marker.name,
                src_dir_fd=marker_file.parent_descriptor,
                dst_dir_fd=released_parent_descriptor,
                follow_symlinks=False,
            )
            os.fsync(released_parent_descriptor)
            released_metadata = os.stat(
                released_marker.name,
                dir_fd=released_parent_descriptor,
                follow_symlinks=False,
            )
            if ((released_metadata.st_dev, released_metadata.st_ino)
                    != (os.fstat(marker_file.descriptor).st_dev,
                        os.fstat(marker_file.descriptor).st_ino)):
                raise StateError("released marker evidence does not pin the maintenance marker")
        finally:
            os.close(released_parent_descriptor)
        _write_private_exclusive(release_intent, b"verified-release\n")
        os.unlink(marker_file.path.name, dir_fd=marker_file.parent_descriptor)
        fsync_directory(parent)
        # Verify the exact marker inode was detached and that neither protected
        # input path changed across the unlink. The server-local startup gate is
        # still held exclusively by the coordinator until this helper returns.
        if os.fstat(marker_file.descriptor).st_nlink != 1:
            raise StateError("maintenance marker inode was not safely transferred")
        try:
            os.stat(
                marker_file.path.name,
                dir_fd=marker_file.parent_descriptor,
                follow_symlinks=False,
            )
        except FileNotFoundError:
            pass
        else:
            raise StateError("maintenance marker path was replaced during release")
        database_file.assert_current("current database")
        env_file_handle.assert_current("current environment")
        if allow_activated_current:
            current_catalog_fingerprint = catalog_fingerprint(csv_path, picture_dir)
            if current_catalog_fingerprint != expected_activated["catalogFingerprint"]:
                raise StateError("official catalog source changed during final release")
    finally:
        if lock_connection is not None:
            try:
                if lock_connection.in_transaction:
                    lock_connection.execute("ROLLBACK")
            finally:
                lock_connection.close()
        for pinned_file in (marker_file, env_file_handle, database_file):
            if pinned_file is not None:
                pinned_file.close()


def _atomic_copy(source: Path, destination: Path, mode: int = 0o600) -> None:
    destination_parent = destination.parent.resolve(strict=True)
    file_descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{destination.name}.round51-", dir=destination_parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(file_descriptor, "wb") as output, source.open("rb") as input_file:
            shutil.copyfileobj(input_file, output, length=1024 * 1024)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, mode)
        os.replace(temporary, destination)
        directory_fd = os.open(destination_parent, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        temporary.unlink(missing_ok=True)


def restore_database(backup: Path, database: Path) -> dict[str, Any]:
    backup_state = verify_logical_backup(backup)
    database = database.resolve(strict=False)
    temporary = database.parent / f".{database.name}.round51-restore-{os.getpid()}"
    temporary.unlink(missing_ok=True)
    try:
        sqlite_backup(backup, temporary)
        # The inherited database currently uses DELETE journal mode, while a
        # future deployment could use WAL. With the service proven stopped,
        # remove every known SQLite sidecar before atomically installing the
        # verified pre-activation database so no stale journal can replay.
        for suffix in ("-journal", "-wal", "-shm"):
            Path(f"{database}{suffix}").unlink(missing_ok=True)
        os.replace(temporary, database)
        os.chmod(database, 0o600)
        directory_fd = os.open(database.parent, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        temporary.unlink(missing_ok=True)
    restored_state = verify_logical_backup(database)
    verify_database_equivalence(backup, database)
    return restored_state


def restore_env(backup: Path, env_file: Path) -> None:
    if not backup.is_file():
        raise StateError("verified .env backup is missing")
    _atomic_copy(backup, env_file, 0o600)


def update_env(env_file: Path) -> None:
    if env_file.is_symlink():
        raise StateError("backend/.env must be a regular non-symlink file")
    env_file = env_file.resolve(strict=True)
    if env_file.is_symlink() or not env_file.is_file():
        raise StateError("backend/.env must be a regular non-symlink file")
    original = env_file.read_text(encoding="utf-8")
    lines = original.splitlines(keepends=True)
    positions: dict[str, list[int]] = {key: [] for key in CONTROLLED_ENV}
    patterns = {
        key: re.compile(rf"^\s*{re.escape(key)}\s*=") for key in CONTROLLED_ENV
    }
    for index, line in enumerate(lines):
        for key, pattern in patterns.items():
            if pattern.match(line):
                positions[key].append(index)
    duplicates = [key for key, indexes in positions.items() if len(indexes) > 1]
    if duplicates:
        raise StateError("duplicate controlled .env keys: " + ", ".join(sorted(duplicates)))
    newline = "\r\n" if "\r\n" in original else "\n"
    for key, value in CONTROLLED_ENV.items():
        replacement = f"{key}={value}{newline}"
        if positions[key]:
            lines[positions[key][0]] = replacement
        else:
            if lines and not lines[-1].endswith(("\n", "\r")):
                lines[-1] += newline
            lines.append(replacement)
    updated = "".join(lines).encode("utf-8")
    destination_parent = env_file.parent
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{env_file.name}.round51-", dir=destination_parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(updated)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, env_file)
        directory_fd = os.open(destination_parent, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        temporary.unlink(missing_ok=True)


def verify_activation_env(env_file: Path) -> None:
    verify_activation_env_text(env_file.read_text(encoding="utf-8"))


def verify_activation_env_text(content: str) -> None:
    for key, expected in CONTROLLED_ENV.items():
        actual = env_value_text(content, key, "")
        if actual != expected:
            raise StateError(f"activation setting {key} does not have its required value")


def preserve_failed_snapshot(database: Path, destination_prefix: Path) -> list[str]:
    copied: list[str] = []
    for suffix in ("", "-journal", "-wal", "-shm"):
        source = Path(f"{database}{suffix}")
        if source.is_file():
            target = Path(f"{destination_prefix}{suffix}")
            shutil.copyfile(source, target)
            os.chmod(target, 0o600)
            with target.open("rb") as snapshot:
                os.fsync(snapshot.fileno())
            copied.append(target.name)
    if not copied or destination_prefix.name not in copied:
        raise StateError("failed database snapshot could not be preserved")
    return copied


def write_manifest(database: Path, destination: Path, phase: str) -> None:
    result = inspect_database(database)
    manifest = {
        "phase": phase,
        "database": str(database.resolve(strict=True)),
        **result,
    }
    destination.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    os.chmod(destination, 0o600)
    fsync_file(destination)
    fsync_directory(destination.parent)


def _image_candidates(raw_name: str) -> list[str]:
    sanitized = raw_name.strip()
    if (not sanitized
            or any(ord(character) < 32 or ord(character) == 127 for character in sanitized)
            or "/" in sanitized
            or "\\" in sanitized
            or re.match(r"^[A-Za-z]:", sanitized)
            or sanitized in {".", ".."}):
        raise StateError("official painting image storage name is unsafe or blank")
    values = [sanitized] if sanitized.lower().endswith((".jpg", ".jpeg")) else [
        sanitized + ".jpg", sanitized + ".jpeg"
    ]
    candidates: list[str] = []
    for value in values:
        normalized = re.sub(r"\s+", " ", value.replace("（", "(").replace("）", ")")).strip()
        variants = (
            value,
            normalized,
            re.sub(r"(?<=\d)\(", " (", normalized),
            re.sub(r"\s+\(", "(", normalized),
        )
        for candidate in variants:
            if candidate not in candidates:
                candidates.append(candidate)
    return candidates


def catalog_profile(csv_path: Path, picture_dir: Path) -> dict[str, Any]:
    csv_path = csv_path.resolve(strict=True)
    picture_dir = picture_dir.resolve(strict=True)
    with csv_path.open("r", encoding="utf-8-sig", newline="") as source:
        reader = csv.reader(source)
        header = tuple(next(reader, ()))
        if header != EXPECTED_HEADERS:
            raise StateError("official Painting CSV headers differ from the required 27-field order")
        records = list(reader)
    if any(len(record) != len(EXPECTED_HEADERS) for record in records):
        raise StateError("official Painting CSV contains a malformed row")
    image_files = sorted([
        path
        for path in picture_dir.iterdir()
        if not path.is_symlink()
        and path.is_file()
        and path.suffix.casefold() in {".jpg", ".jpeg"}
    ], key=lambda path: path.name)
    by_lower_name: dict[str, str] = {}
    for path in image_files:
        lowered = path.name.lower()
        if lowered in by_lower_name:
            raise StateError("catalog image corpus contains case-insensitive filename ambiguity")
        by_lower_name[lowered] = path.name

    matched_names: set[str] = set()
    source_keys: set[str] = set()
    matched_rows = 0
    generated_text_populated = 0
    music_scene_populated = 0
    for record in records:
        image_storage_name = record[1].strip()
        source_key = "painting-dataset:" + image_storage_name
        if source_key in source_keys:
            raise StateError("official Painting CSV contains a duplicate source key")
        source_keys.add(source_key)
        matches = {
            by_lower_name[candidate.lower()]
            for candidate in _image_candidates(image_storage_name)
            if candidate.lower() in by_lower_name
        }
        if len(matches) > 1:
            raise StateError("official Painting image storage name resolves ambiguously")
        if matches:
            matched_rows += 1
            matched_names.update(matches)
        if record[24].strip():
            generated_text_populated += 1
        if record[25].strip():
            music_scene_populated += 1
    return {
        "csvRows": len(records),
        "csvColumns": len(header),
        "imageFiles": len(image_files),
        "matchedImages": matched_rows,
        "missingImages": len(records) - matched_rows,
        "orphanImages": len(image_files) - len(matched_names),
        "generatedTextPopulated": generated_text_populated,
        "musicScenePopulated": music_scene_populated,
        "catalogFingerprint": catalog_fingerprint(csv_path, picture_dir),
    }


def verify_catalog_profile(profile: dict[str, Any], expected: dict[str, Any]) -> None:
    expected_profile = {
        "csvRows": expected["paintings"],
        "csvColumns": 27,
        "imageFiles": expected["imageFiles"],
        "matchedImages": expected["catalogMediaAssets"],
        "missingImages": expected["missingImages"],
        "orphanImages": expected["orphanImages"],
        "generatedTextPopulated": expected["generatedTextPopulated"],
        "musicScenePopulated": expected["musicScenePopulated"],
        "catalogFingerprint": expected["catalogFingerprint"],
    }
    for key, expected_value in expected_profile.items():
        if profile.get(key) != expected_value:
            raise StateError(
                f"official catalog {key} expected {expected_value}, found {profile.get(key)}"
            )


def env_value(env_file: Path, key: str, default: str) -> str:
    return env_value_text(env_file.read_text(encoding="utf-8"), key, default)


def env_value_text(content: str, key: str, default: str) -> str:
    if not re.fullmatch(r"[A-Z][A-Z0-9_]*", key):
        raise StateError("invalid environment key")
    matches: list[str] = []
    pattern = re.compile(rf"^\s*{re.escape(key)}\s*=\s*(.*?)\s*$")
    for line in content.splitlines():
        match = pattern.match(line)
        if match:
            matches.append(match.group(1).strip("'\""))
    if len(matches) > 1:
        raise StateError(f"duplicate configuration key: {key}")
    return matches[0] if matches else default


def validate_smoke(health_path: Path, gallery_path: Path, daily_path: Path) -> None:
    for path in (health_path, gallery_path, daily_path):
        json.loads(path.read_text(encoding="utf-8"))
    health = json.loads(health_path.read_text(encoding="utf-8"))
    gallery = json.loads(gallery_path.read_text(encoding="utf-8"))
    daily = json.loads(daily_path.read_text(encoding="utf-8"))
    if health.get("status") != "UP":
        raise StateError("health endpoint did not report UP")
    items = gallery.get("items")
    if not isinstance(items, list) or not items:
        raise StateError("gallery smoke response contains no Painting")

    def walk(value: Any) -> None:
        if isinstance(value, dict):
            for child in value.values():
                walk(child)
        elif isinstance(value, list):
            for child in value:
                walk(child)
        elif isinstance(value, str):
            if value.startswith(("/root/", "/home/", "file:")):
                raise StateError("smoke response exposed a local filesystem path")

    walk(gallery)
    walk(daily)
    serialized = json.dumps([gallery, daily], ensure_ascii=False)
    if "/api/v1/assets/" not in serialized or "/content" not in serialized:
        raise StateError("Painting response did not expose a MediaAsset content URL")


def expectations_from_arguments(arguments: argparse.Namespace) -> dict[str, Any]:
    return {
        "legacyDataSha256": arguments.expected_legacy_data_sha256,
        "catalogFingerprint": arguments.expected_catalog_fingerprint,
        "paintings": arguments.expected_paintings,
        "imageFiles": arguments.expected_image_files,
        "catalogMediaAssets": arguments.expected_catalog_assets,
        "missingImages": arguments.expected_missing_images,
        "orphanImages": arguments.expected_orphan_images,
        "generatedTextPopulated": arguments.expected_generated_text,
        "musicScenePopulated": arguments.expected_music_scene,
        "visibleInGallery": arguments.expected_gallery_visible,
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    inspect = subparsers.add_parser("inspect")
    inspect.add_argument("--database", type=Path, required=True)

    verify_legacy = subparsers.add_parser("verify-inherited")
    verify_legacy.add_argument("--database", type=Path, required=True)
    verify_legacy.add_argument("--expected-legacy-data-sha256")

    verify_backup = subparsers.add_parser("verify-backup")
    verify_backup.add_argument("--source", type=Path, required=True)
    verify_backup.add_argument("--database", type=Path, required=True)

    for name in ("verify-activated", "verify-preflight"):
        child = subparsers.add_parser(name)
        child.add_argument("--database", type=Path, required=True)
        child.add_argument("--csv", type=Path, required=True)
        child.add_argument("--pictures", type=Path, required=True)
        child.add_argument("--expected-legacy-data-sha256", required=True)
        child.add_argument("--expected-catalog-fingerprint", required=True)
        child.add_argument("--expected-paintings", type=int, required=True)
        child.add_argument("--expected-image-files", type=int, required=True)
        child.add_argument("--expected-catalog-assets", type=int, required=True)
        child.add_argument("--expected-missing-images", type=int, required=True)
        child.add_argument("--expected-orphan-images", type=int, required=True)
        child.add_argument("--expected-generated-text", type=int, required=True)
        child.add_argument("--expected-music-scene", type=int, required=True)
        child.add_argument("--expected-gallery-visible", type=int, required=True)

    backup = subparsers.add_parser("backup-db")
    backup.add_argument("--source", type=Path, required=True)
    backup.add_argument("--destination", type=Path, required=True)

    restore = subparsers.add_parser("restore-db")
    restore.add_argument("--backup", type=Path, required=True)
    restore.add_argument("--database", type=Path, required=True)

    env_backup = subparsers.add_parser("backup-env")
    env_backup.add_argument("--source", type=Path, required=True)
    env_backup.add_argument("--destination", type=Path, required=True)

    fsync_dir = subparsers.add_parser("fsync-dir")
    fsync_dir.add_argument("--directory", type=Path, required=True)

    fsync_file_parser = subparsers.add_parser("fsync-file")
    fsync_file_parser.add_argument("--file", type=Path, required=True)

    startup_gate = subparsers.add_parser("hold-startup-gate")
    startup_gate.add_argument("--gate", type=Path, required=True)
    startup_gate.add_argument("--ready", type=Path, required=True)
    startup_gate.add_argument("--parent-pid", type=int, required=True)
    startup_gate.add_argument("--parent-start-time", required=True)
    startup_gate.add_argument("--orphan-fence", type=Path)

    create_marker = subparsers.add_parser("create-maintenance-marker")
    create_marker.add_argument("--marker", type=Path, required=True)

    create_binding = subparsers.add_parser("create-recovery-binding")
    create_binding.add_argument("--marker", type=Path, required=True)
    create_binding.add_argument("--binding", type=Path, required=True)
    create_binding.add_argument("--database-backup", type=Path, required=True)
    create_binding.add_argument("--environment-backup", type=Path, required=True)
    create_binding.add_argument("--pre-activation-manifest", type=Path, required=True)
    create_binding.add_argument("--database-verification", type=Path, required=True)
    create_binding.add_argument("--reviewed-commit", required=True)

    verify_binding = subparsers.add_parser("verify-recovery-binding")
    verify_binding.add_argument("--marker", type=Path, required=True)
    verify_binding.add_argument("--binding", type=Path, required=True)
    verify_binding.add_argument("--database-backup", type=Path, required=True)
    verify_binding.add_argument("--environment-backup", type=Path, required=True)
    verify_binding.add_argument("--pre-activation-manifest", type=Path, required=True)
    verify_binding.add_argument("--database-verification", type=Path, required=True)
    verify_binding.add_argument("--reviewed-commit", required=True)

    create_orphan = subparsers.add_parser("create-bound-orphan-fence")
    create_orphan.add_argument("--fence", type=Path, required=True)
    create_orphan.add_argument("--binding", type=Path, required=True)
    create_orphan.add_argument("--database-backup", type=Path, required=True)
    create_orphan.add_argument("--environment-backup", type=Path, required=True)
    create_orphan.add_argument("--pre-activation-manifest", type=Path, required=True)
    create_orphan.add_argument("--database-verification", type=Path, required=True)
    create_orphan.add_argument("--reviewed-commit", required=True)

    verify_orphan = subparsers.add_parser("verify-bound-orphan-fence")
    verify_orphan.add_argument("--fence", type=Path, required=True)
    verify_orphan.add_argument("--binding", type=Path, required=True)
    verify_orphan.add_argument("--database-backup", type=Path, required=True)
    verify_orphan.add_argument("--environment-backup", type=Path, required=True)
    verify_orphan.add_argument("--pre-activation-manifest", type=Path, required=True)
    verify_orphan.add_argument("--database-verification", type=Path, required=True)
    verify_orphan.add_argument("--reviewed-commit", required=True)

    retain_token = subparsers.add_parser("retain-verified-recovery-token")
    retain_token.add_argument("--marker", type=Path, required=True)
    retain_token.add_argument("--binding", type=Path, required=True)
    retain_token.add_argument("--database-backup", type=Path, required=True)
    retain_token.add_argument("--environment-backup", type=Path, required=True)
    retain_token.add_argument("--pre-activation-manifest", type=Path, required=True)
    retain_token.add_argument("--database-verification", type=Path, required=True)
    retain_token.add_argument("--reviewed-commit", required=True)

    recreate_marker = subparsers.add_parser("recreate-maintenance-marker")
    recreate_marker.add_argument("--marker", type=Path, required=True)
    recreate_marker.add_argument("--binding", type=Path, required=True)
    recreate_marker.add_argument("--database-backup", type=Path, required=True)
    recreate_marker.add_argument("--environment-backup", type=Path, required=True)
    recreate_marker.add_argument("--pre-activation-manifest", type=Path, required=True)
    recreate_marker.add_argument("--database-verification", type=Path, required=True)
    recreate_marker.add_argument("--reviewed-commit", required=True)

    remove_marker = subparsers.add_parser("remove-maintenance-marker")
    remove_marker.add_argument("--marker", type=Path, required=True)

    remove_stale_marker = subparsers.add_parser("remove-stale-maintenance-marker")
    remove_stale_marker.add_argument("--marker", type=Path, required=True)
    remove_stale_marker.add_argument("--released-marker", type=Path, required=True)
    remove_stale_marker.add_argument("--binding", type=Path, required=True)
    remove_stale_marker.add_argument("--verified-backup", type=Path, required=True)
    remove_stale_marker.add_argument("--environment-backup", type=Path, required=True)
    remove_stale_marker.add_argument("--pre-activation-manifest", type=Path, required=True)
    remove_stale_marker.add_argument("--database-verification", type=Path, required=True)
    remove_stale_marker.add_argument("--current-database", type=Path, required=True)
    remove_stale_marker.add_argument("--expected-legacy-data-sha256", required=True)
    remove_stale_marker.add_argument("--reviewed-commit", required=True)
    remove_stale_marker.add_argument("--release-intent", type=Path, required=True)
    remove_stale_marker.add_argument("--allow-activated-current", action="store_true")
    remove_stale_marker.add_argument("--env-file", type=Path, required=True)
    remove_stale_marker.add_argument("--csv", type=Path)
    remove_stale_marker.add_argument("--pictures", type=Path)
    remove_stale_marker.add_argument("--expected-catalog-fingerprint")
    remove_stale_marker.add_argument("--expected-paintings", type=int)
    remove_stale_marker.add_argument("--expected-image-files", type=int)
    remove_stale_marker.add_argument("--expected-catalog-assets", type=int)
    remove_stale_marker.add_argument("--expected-missing-images", type=int)
    remove_stale_marker.add_argument("--expected-orphan-images", type=int)
    remove_stale_marker.add_argument("--expected-generated-text", type=int)
    remove_stale_marker.add_argument("--expected-music-scene", type=int)
    remove_stale_marker.add_argument("--expected-gallery-visible", type=int)

    env_restore = subparsers.add_parser("restore-env")
    env_restore.add_argument("--backup", type=Path, required=True)
    env_restore.add_argument("--env-file", type=Path, required=True)

    env_update = subparsers.add_parser("update-env")
    env_update.add_argument("--env-file", type=Path, required=True)

    env_verify = subparsers.add_parser("verify-activation-env")
    env_verify.add_argument("--env-file", type=Path, required=True)

    failed = subparsers.add_parser("preserve-failed")
    failed.add_argument("--database", type=Path, required=True)
    failed.add_argument("--destination-prefix", type=Path, required=True)

    manifest = subparsers.add_parser("manifest")
    manifest.add_argument("--database", type=Path, required=True)
    manifest.add_argument("--destination", type=Path, required=True)
    manifest.add_argument("--phase", required=True)

    profile = subparsers.add_parser("catalog-profile")
    profile.add_argument("--csv", type=Path, required=True)
    profile.add_argument("--pictures", type=Path, required=True)

    value = subparsers.add_parser("env-value")
    value.add_argument("--env-file", type=Path, required=True)
    value.add_argument("--key", required=True)
    value.add_argument("--default", required=True)

    smoke = subparsers.add_parser("validate-smoke")
    smoke.add_argument("--health", type=Path, required=True)
    smoke.add_argument("--gallery", type=Path, required=True)
    smoke.add_argument("--daily", type=Path, required=True)
    return parser


def main() -> int:
    arguments = build_parser().parse_args()
    try:
        if arguments.command == "inspect":
            print(json.dumps(inspect_database(arguments.database), sort_keys=True))
        elif arguments.command == "verify-inherited":
            print(json.dumps(verify_inherited(
                arguments.database,
                arguments.expected_legacy_data_sha256,
            ), sort_keys=True))
        elif arguments.command == "verify-activated":
            print(json.dumps(verify_activated(
                arguments.database,
                arguments.csv,
                arguments.pictures,
                expectations_from_arguments(arguments),
            ), sort_keys=True))
        elif arguments.command == "verify-preflight":
            print(json.dumps(verify_preflight(
                arguments.database,
                arguments.csv,
                arguments.pictures,
                expectations_from_arguments(arguments),
            ), sort_keys=True))
        elif arguments.command == "verify-backup":
            print(json.dumps(verify_database_equivalence(
                arguments.source, arguments.database
            ), sort_keys=True))
        elif arguments.command == "backup-db":
            print(json.dumps(sqlite_backup(arguments.source, arguments.destination), sort_keys=True))
        elif arguments.command == "restore-db":
            print(json.dumps(restore_database(arguments.backup, arguments.database), sort_keys=True))
        elif arguments.command == "backup-env":
            backup_env(arguments.source, arguments.destination)
            print("ENV_BACKUP_VERIFIED")
        elif arguments.command == "fsync-dir":
            fsync_directory(arguments.directory)
            print("DIRECTORY_FSYNC_VERIFIED")
        elif arguments.command == "fsync-file":
            fsync_file(arguments.file)
            print("FILE_FSYNC_VERIFIED")
        elif arguments.command == "hold-startup-gate":
            hold_startup_gate(
                arguments.gate,
                arguments.ready,
                arguments.parent_pid,
                arguments.parent_start_time,
                arguments.orphan_fence,
            )
        elif arguments.command == "create-maintenance-marker":
            print(create_maintenance_marker(arguments.marker))
        elif arguments.command == "create-recovery-binding":
            print(create_recovery_binding(
                arguments.marker,
                arguments.binding,
                arguments.database_backup,
                arguments.environment_backup,
                arguments.pre_activation_manifest,
                arguments.database_verification,
                arguments.reviewed_commit,
            ))
        elif arguments.command == "verify-recovery-binding":
            print(json.dumps(verify_recovery_binding(
                arguments.marker,
                arguments.binding,
                arguments.database_backup,
                arguments.environment_backup,
                arguments.pre_activation_manifest,
                arguments.database_verification,
                arguments.reviewed_commit,
            ), sort_keys=True))
        elif arguments.command == "create-bound-orphan-fence":
            create_bound_orphan_fence(
                arguments.fence,
                arguments.binding,
                arguments.database_backup,
                arguments.environment_backup,
                arguments.pre_activation_manifest,
                arguments.database_verification,
                arguments.reviewed_commit,
            )
            print("BOUND_ORPHAN_FENCE_CREATED")
        elif arguments.command == "verify-bound-orphan-fence":
            print(json.dumps(verify_bound_orphan_fence(
                arguments.fence,
                arguments.binding,
                arguments.database_backup,
                arguments.environment_backup,
                arguments.pre_activation_manifest,
                arguments.database_verification,
                arguments.reviewed_commit,
            ), sort_keys=True))
        elif arguments.command == "retain-verified-recovery-token":
            print(retain_verified_recovery_token(
                arguments.marker,
                arguments.binding,
                arguments.database_backup,
                arguments.environment_backup,
                arguments.pre_activation_manifest,
                arguments.database_verification,
                arguments.reviewed_commit,
            ))
        elif arguments.command == "recreate-maintenance-marker":
            recreate_maintenance_marker(
                arguments.marker,
                arguments.binding,
                arguments.database_backup,
                arguments.environment_backup,
                arguments.pre_activation_manifest,
                arguments.database_verification,
                arguments.reviewed_commit,
            )
            print("MAINTENANCE_MARKER_RECREATED")
        elif arguments.command == "remove-maintenance-marker":
            remove_maintenance_marker(arguments.marker)
            print("MAINTENANCE_MARKER_RELEASED")
        elif arguments.command == "remove-stale-maintenance-marker":
            activated_expected = None
            if arguments.allow_activated_current:
                required_activated_values = (
                    arguments.expected_catalog_fingerprint,
                    arguments.expected_paintings,
                    arguments.expected_image_files,
                    arguments.expected_catalog_assets,
                    arguments.expected_missing_images,
                    arguments.expected_orphan_images,
                    arguments.expected_generated_text,
                    arguments.expected_music_scene,
                    arguments.expected_gallery_visible,
                )
                if any(value is None for value in required_activated_values):
                    raise StateError("all activated release expectations are required")
                activated_expected = {
                    "legacyDataSha256": arguments.expected_legacy_data_sha256,
                    "catalogFingerprint": arguments.expected_catalog_fingerprint,
                    "paintings": arguments.expected_paintings,
                    "imageFiles": arguments.expected_image_files,
                    "catalogMediaAssets": arguments.expected_catalog_assets,
                    "missingImages": arguments.expected_missing_images,
                    "orphanImages": arguments.expected_orphan_images,
                    "generatedTextPopulated": arguments.expected_generated_text,
                    "musicScenePopulated": arguments.expected_music_scene,
                    "visibleInGallery": arguments.expected_gallery_visible,
                }
            remove_stale_maintenance_marker(
                arguments.marker,
                arguments.released_marker,
                arguments.binding,
                arguments.verified_backup,
                arguments.environment_backup,
                arguments.pre_activation_manifest,
                arguments.database_verification,
                arguments.current_database,
                arguments.expected_legacy_data_sha256,
                arguments.reviewed_commit,
                arguments.release_intent,
                arguments.allow_activated_current,
                arguments.env_file,
                arguments.csv,
                arguments.pictures,
                activated_expected,
            )
            print("STALE_MAINTENANCE_MARKER_RELEASED")
        elif arguments.command == "restore-env":
            restore_env(arguments.backup, arguments.env_file)
            print("ENV_RESTORED")
        elif arguments.command == "update-env":
            update_env(arguments.env_file)
            print("ENV_ACTIVATION_SETTINGS_UPDATED")
        elif arguments.command == "verify-activation-env":
            verify_activation_env(arguments.env_file)
            print("ENV_ACTIVATION_SETTINGS_VERIFIED")
        elif arguments.command == "preserve-failed":
            print(json.dumps(preserve_failed_snapshot(
                arguments.database, arguments.destination_prefix
            )))
        elif arguments.command == "manifest":
            write_manifest(arguments.database, arguments.destination, arguments.phase)
            print("MANIFEST_WRITTEN")
        elif arguments.command == "catalog-profile":
            print(json.dumps(catalog_profile(arguments.csv, arguments.pictures), sort_keys=True))
        elif arguments.command == "env-value":
            print(env_value(arguments.env_file, arguments.key, arguments.default))
        elif arguments.command == "validate-smoke":
            validate_smoke(arguments.health, arguments.gallery, arguments.daily)
            print("SMOKE_RESPONSES_VERIFIED")
        else:
            raise AssertionError(arguments.command)
        return 0
    except (OSError, sqlite3.Error, StateError, UnicodeError, ValueError) as exception:
        print(f"ROUND51_STATE_ERROR: {exception}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
