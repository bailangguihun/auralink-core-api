#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import shutil
import stat
import struct
import subprocess
import tempfile
import unittest
import zlib
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[3] / "scripts/round81_provider_state.py"
SPEC = importlib.util.spec_from_file_location("round81_provider_state", SCRIPT)
assert SPEC and SPEC.loader
state = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(state)


def png_bytes() -> bytes:
    def chunk(kind: bytes, value: bytes) -> bytes:
        return struct.pack(">I", len(value)) + kind + value + struct.pack(">I", zlib.crc32(kind + value))

    header = struct.pack(">IIBBBBB", 3, 2, 8, 2, 0, 0, 0)
    pixels = b"\x00" + b"\x10\x20\x30" * 3 + b"\x00" + b"\x40\x50\x60" * 3
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(pixels)) + chunk(b"IEND", b"")


class Round81StateToolTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="round81-state-")
        self.root = Path(self.temporary.name)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def private_dir(self, name: str) -> Path:
        path = self.root / name
        path.mkdir(mode=0o700)
        os.chmod(path, 0o700)
        return path

    def test_environment_precedence_and_configuration_fingerprint_excludes_key_bytes(self) -> None:
        project = self.private_dir("project")
        backend = self.private_dir("project/backend")
        env = backend / ".env"
        env.write_text(
            "SEEDREAM_API_KEY=file-key\n"
            "SEEDREAM_BASE_URL=https://ark.cn-beijing.volces.com/api/v3\n"
            "SEEDREAM_MODEL=reviewed-model\n",
            encoding="utf-8",
        )
        with mock.patch.dict(os.environ, {"SEEDREAM_API_KEY": "process-key"}, clear=False):
            merged = state.merged_configuration(project)
        self.assertEqual("process-key", merged["SEEDREAM_API_KEY"])
        _, first = state.operation_configuration("text-to-painting", merged)
        changed = dict(merged, SEEDREAM_API_KEY="different-key-bytes")
        _, second = state.operation_configuration("text-to-painting", changed)
        self.assertEqual(first, second)
        rendered = json.dumps(state.validate_seedream_configuration(merged), sort_keys=True)
        self.assertNotIn("process-key", rendered)
        self.assertNotIn("reviewed-model", rendered)
        self.assertFalse(state.forbidden_evidence({"apiKeyPresent": True}))
        self.assertFalse(state.forbidden_evidence({"modelIdentityHash": "a" * 64}))
        self.assertTrue(state.forbidden_evidence({"apiKey": "not-allowed"}))
        self.assertTrue(state.forbidden_evidence({"modelIdentityHash": "reviewed-model"}))

    def test_endpoint_and_missing_configuration_rejections_are_fixed_codes(self) -> None:
        seedream = {
            "SEEDREAM_API_KEY": "present",
            "SEEDREAM_MODEL": "model",
            "SEEDREAM_BASE_URL": "http://127.0.0.1/api/v3",
        }
        with self.assertRaisesRegex(state.StateError, "ROUND 8.1") as error:
            state.validate_seedream_configuration(seedream)
        self.assertEqual("SEEDREAM_BASE_URL_INVALID", error.exception.code)

        qwen = {
            "QWEN_API_KEY": "present",
            "QWEN_MODEL": "qwen3-vl-plus",
            "QWEN_BASE_URL": "https://example.invalid/compatible-mode/v1",
        }
        with self.assertRaises(state.StateError) as error:
            state.validate_qwen_configuration(qwen)
        self.assertEqual("QWEN_BASE_URL_INVALID", error.exception.code)

        with self.assertRaises(state.StateError) as error:
            state.validate_qwen_configuration({})
        self.assertEqual("QWEN_API_KEY_MISSING", error.exception.code)
        with self.assertRaises(state.StateError) as error:
            state.validate_qwen_configuration({
                "QWEN_API_KEY": "present",
                "QWEN_BASE_URL": "https://dashscope.aliyuncs.com/compatible-mode/v1",
            })
        self.assertEqual("QWEN_MODEL_MISSING", error.exception.code)
        with self.assertRaises(state.StateError) as error:
            state.validate_seedream_configuration({
                "SEEDREAM_API_KEY": "present",
                "SEEDREAM_BASE_URL": "https://ark.cn-beijing.volces.com/api/v3",
                "SEEDREAM_MODEL": "",
            })
        self.assertEqual("SEEDREAM_MODEL_MISSING", error.exception.code)
        with self.assertRaises(state.StateError) as error:
            state.validated_safe_settings({"AURALINK_PROVIDER_MAX_IMAGE_INPUT_BYTES": "0"})
        self.assertEqual("PROVIDER_LIMIT_CONFIGURATION_INVALID", error.exception.code)

    def test_root_filesystem_commit_and_clean_tree_guards(self) -> None:
        project = self.private_dir("git-project")
        (project / "backend").mkdir(mode=0o700)
        (project / ".gitignore").write_text("backend/.env\n", encoding="utf-8")
        (project / "backend/.env").write_text("SAFE=value\n", encoding="utf-8")
        subprocess.run(["git", "init", "-q", str(project)], check=True)
        subprocess.run(["git", "-C", str(project), "config", "user.email", "round81@example.invalid"], check=True)
        subprocess.run(["git", "-C", str(project), "config", "user.name", "Round81 Test"], check=True)
        subprocess.run(["git", "-C", str(project), "add", ".gitignore"], check=True)
        subprocess.run(["git", "-C", str(project), "commit", "-qm", "fixture"], check=True)
        commit = subprocess.check_output(["git", "-C", str(project), "rev-parse", "HEAD"], text=True).strip()

        self.assertEqual(commit, state.verify_root_and_commit(
            project, commit, working_directory=project, expected_root=project, fs_type="ext4"
        ))
        with self.assertRaises(state.StateError) as error:
            state.verify_root_and_commit(
                project, commit, working_directory=project, expected_root=project, fs_type="fuse.sshfs"
            )
        self.assertEqual("SSHFS_EXECUTION_REFUSED", error.exception.code)
        with self.assertRaises(state.StateError) as error:
            state.verify_root_and_commit(
                project, "0" * 40, working_directory=project, expected_root=project, fs_type="ext4"
            )
        self.assertEqual("REVIEWED_COMMIT_MISMATCH", error.exception.code)
        (project / "dirty").write_text("dirty", encoding="utf-8")
        with self.assertRaises(state.StateError) as error:
            state.verify_root_and_commit(
                project, commit, working_directory=project, expected_root=project, fs_type="ext4"
            )
        self.assertEqual("WORKTREE_NOT_CLEAN", error.exception.code)
        (project / "dirty").unlink()
        (project / "backend/.env").unlink()
        with self.assertRaises(state.StateError) as error:
            state.verify_root_and_commit(
                project, commit, working_directory=project, expected_root=project, fs_type="ext4"
            )
        self.assertEqual("BACKEND_ENV_REQUIRED", error.exception.code)

    def test_private_permissions_symlink_and_bounded_copy(self) -> None:
        run = self.private_dir("run")
        source = self.root / "source.png"
        source.write_bytes(png_bytes())
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        with source.open("rb") as stream:
            state.write_private_stream(run / "input.png", stream, 1024 * 1024, digest)
        self.assertEqual(0o600, stat.S_IMODE((run / "input.png").stat().st_mode))
        symlink = run / "link"
        symlink.symlink_to(source)
        with self.assertRaises(state.StateError):
            state.require_private_file(symlink, run)
        with source.open("rb") as stream:
            with self.assertRaises(state.StateError) as error:
                state.write_private_stream(run / "oversize.png", stream, 4, digest)
        self.assertEqual("CATALOG_IMAGE_TOO_LARGE", error.exception.code)
        self.assertFalse((run / "oversize.png").exists())

        catalog = self.private_dir("catalog")
        catalog_image = catalog / "safe.png"
        catalog_image.write_bytes(png_bytes())
        self.assertEqual(catalog_image, state.contained_catalog_file(catalog, "catalog/safe.png"))
        catalog_image.unlink()
        catalog_image.symlink_to(source)
        with self.assertRaises(state.StateError) as error:
            state.contained_catalog_file(catalog, "catalog/safe.png")
        self.assertEqual("CATALOG_IMAGE_INVALID", error.exception.code)
        with self.assertRaises(state.StateError):
            state.contained_catalog_file(catalog, "catalog/../source.png")

    def write_healthy_run(self, run_root: Path, name: str, expected: dict[str, object]) -> Path:
        run = run_root / name
        run.mkdir(mode=0o700)
        os.chmod(run, 0o700)
        image = png_bytes()
        result = run / "validated-result.png"
        state.write_private_bytes(result, image)
        digest = hashlib.sha256(image).hexdigest()
        metadata = {
            "resultFile": result.name,
            "mimeType": "image/png",
            "byteLength": len(image),
            "sha256": digest,
            "width": 3,
            "height": 2,
            "structuralState": "STRUCTURALLY_VALID",
            "reviewState": "OPERATOR_REVIEW_REQUIRED",
        }
        state.write_private_json(run / "result-metadata.json", metadata)
        manifest = {
            "status": "SUCCESS",
            "commit": expected["commit"],
            "operation": expected["operation"],
            "providerCode": expected["providerCode"],
            "inputSha256": expected["inputSha256"],
            "configurationFingerprint": expected["configurationFingerprint"],
            "outputSha256": digest,
            "cleanupComplete": True,
            "calls": expected["expectedCalls"],
        }
        state.write_private_json(run / "validation-manifest.json", manifest)
        return run

    def test_already_validated_exact_match_and_ambiguous_refusal(self) -> None:
        run_root = self.private_dir("runs")
        expected = {
            "commit": "a" * 40,
            "operation": "TEXT_TO_PAINTING",
            "providerCode": "seedream-5",
            "inputSha256": "b" * 64,
            "configurationFingerprint": "c" * 64,
            "expectedCalls": {"seedream": 1, "qwen": 0, "vmm": 0},
        }
        first = self.write_healthy_run(run_root, "one", expected)
        self.assertEqual(first, state.find_healthy_run(run_root, expected))
        self.write_healthy_run(run_root, "two", expected)
        with self.assertRaises(state.StateError) as error:
            state.find_healthy_run(run_root, expected)
        self.assertEqual("AMBIGUOUS_VALIDATION_RUNS", error.exception.code)

    def test_finalize_enforces_counts_database_and_removes_private_source_copy(self) -> None:
        run_root = self.private_dir("final-runs")
        run = run_root / "one"
        run.mkdir(mode=0o700)
        os.chmod(run, 0o700)
        database_state = {"databaseSha256": "a" * 64, "schemaSha256": "b" * 64}
        image = png_bytes()
        digest = hashlib.sha256(image).hexdigest()
        state.write_private_bytes(run / "validated-result.png", image)
        state.write_private_bytes(run / "input-image.png", image)
        state.write_private_json(run / "input-metadata.json", {"fixture": True})
        state.write_private_json(run / "preflight-manifest.json", {
            "commit": "c" * 40,
            "operation": "TEXT_TO_PAINTING",
            "providerCode": "seedream-5",
            "inputSha256": "d" * 64,
            "configurationFingerprint": "e" * 64,
            "expectedCalls": {"seedream": 1, "qwen": 0, "vmm": 0},
        })
        state.write_private_json(run / "database-before.json", database_state)
        state.write_private_json(run / "call-counts.json", {
            "calls": {"seedream": 1, "qwen": 0, "vmm": 0},
            "executionEntered": True,
            "retryHandlerInvoked": False,
            "outputCount": 1,
        })
        state.write_private_json(run / "cleanup-result.json", {
            "cleanupComplete": True, "stagingEmpty": True, "providerArtifactClosed": True,
        })
        result_metadata = {
            "resultFile": "validated-result.png",
            "mimeType": "image/png",
            "byteLength": len(image),
            "sha256": digest,
            "width": 3,
            "height": 2,
            "structuralState": "STRUCTURALLY_VALID",
            "reviewState": "OPERATOR_REVIEW_REQUIRED",
        }
        state.write_private_json(run / "result-metadata.json", result_metadata)
        state.write_private_json(run / "execution-result.json", {
            "validatedAt": "2026-08-16T16:00:00.123Z",
            "elapsedMillis": 42,
            "result": result_metadata,
        })
        with mock.patch.object(state, "PRIVATE_RUN_ROOT", run_root), \
                mock.patch.object(state, "inspect_database", return_value=database_state):
            manifest = state.finalize_run(self.root, run)
        self.assertEqual("SUCCESS", manifest["status"])
        self.assertEqual(42, manifest["elapsedMillis"])
        self.assertFalse((run / "input-image.png").exists())
        self.assertFalse((run / "input-metadata.json").exists())
        self.assertEqual(0o600, stat.S_IMODE((run / "validation-manifest.json").stat().st_mode))

    def test_port_state_and_failure_cleanup_are_bounded(self) -> None:
        with mock.patch.object(state, "port_is_free", return_value=False):
            with self.assertRaises(state.StateError) as error:
                state.assert_port_state("painting-to-music", "dry-run")
        self.assertEqual("VMM_PORT_OCCUPIED", error.exception.code)
        with mock.patch.object(state, "port_is_free", return_value=True):
            with self.assertRaises(state.StateError) as error:
                state.assert_port_state("painting-to-music", "validate")
        self.assertEqual("VMM_SERVICE_NOT_LISTENING", error.exception.code)

        run_root = self.private_dir("failure-runs")
        run = run_root / "one"
        run.mkdir(mode=0o700)
        os.chmod(run, 0o700)
        staging = self.private_dir("failure-staging")
        (staging / ".provider-incoming-fixture.part").write_bytes(b"fixture")
        database_state = {"databaseSha256": "f" * 64}
        state.write_private_json(run / "database-before.json", database_state)
        with mock.patch.object(state, "PRIVATE_RUN_ROOT", run_root), \
                mock.patch.object(state, "merged_configuration", return_value={
                    "AURALINK_PROVIDER_STAGING_DIR": str(staging)
                }), mock.patch.object(state, "inspect_database", return_value=database_state):
            cleanup = state.cleanup_failed_run(self.root, run)
        self.assertTrue(cleanup["failureCleanupComplete"])
        self.assertFalse(any(staging.iterdir()))

    def make_vmm_fixture(self) -> tuple[Path, dict[str, str]]:
        project = self.private_dir("vmm-project")
        paths = [
            "micromamba/envs/auralink-ai/bin",
            "VMM/audiocraft",
            "VMM/models/musicgen-small",
            "CLIP",
            "vmm-output",
            "clip-cache",
        ]
        for relative in paths:
            (project / relative).mkdir(parents=True, exist_ok=True)
        (project / "VMM/audiocraft/audiocraft").mkdir()
        (project / "VMM/audiocraft/audiocraft.egg-info").mkdir()
        (project / "CLIP/clip").mkdir()
        (project / "CLIP/clip.egg-info").mkdir()
        python = project / "micromamba/envs/auralink-ai/bin/python"
        python.write_text("#!/bin/sh\n", encoding="utf-8")
        python.chmod(0o700)
        (project / "VMM/app.py").write_text("# fixture\n", encoding="utf-8")
        musicgen = project / "VMM/models/musicgen-small"
        for name in (
            "compression_state_dict.bin", "state_dict.bin", "config.json", "spiece.model",
            "tokenizer.json", "tokenizer_config.json",
        ):
            (musicgen / name).write_bytes(b"fixture")
        checkpoint = project / "VMM/models/final_model.pth"
        checkpoint.write_bytes(b"fixture")
        clip_cache = project / "clip-cache/ViT-B-32.pt"
        clip_cache.write_bytes(b"fixture")
        configuration = {
            "PAINTING_MUSIC_SERVICE_URL": "http://127.0.0.1:5001",
            "AURALINK_VMM_OUTPUT_DIR": str(project / "vmm-output"),
            "AURALINK_VMM_MUSICGEN_PATH": str(musicgen),
            "AURALINK_VMM_CHECKPOINT_PATH": str(checkpoint),
            "AURALINK_VMM_CLIP_CACHE": str(clip_cache),
        }
        return project, configuration

    def test_vmm_static_ready_fixture_and_every_fixed_blocker_family(self) -> None:
        project, configuration = self.make_vmm_fixture()
        with mock.patch.object(state, "gpu_visible", return_value=True):
            ready = state.static_vmm_preflight(project, configuration)
        self.assertEqual("VMM_STATIC_PREFLIGHT_READY", ready["state"])
        self.assertFalse(ready["modelImported"])
        self.assertFalse(ready["checkpointLoaded"])

        cases = [
            ("micromamba/envs/auralink-ai/bin/python", "VMM_PYTHON_MISSING"),
            ("VMM/app.py", "VMM_PACKAGE_PATH_INVALID"),
            ("clip-cache/ViT-B-32.pt", "VMM_CLIP_ASSET_MISSING"),
            ("VMM/models/musicgen-small/tokenizer_config.json", "VMM_TEXT_ENCODER_ASSET_MISSING"),
            ("VMM/models/musicgen-small/state_dict.bin", "VMM_MUSICGEN_ASSET_MISSING"),
            ("VMM/models/final_model.pth", "VMM_CHECKPOINT_MISSING"),
        ]
        for relative, code in cases:
            target = project / relative
            backup = target.read_bytes()
            target.unlink()
            with mock.patch.object(state, "gpu_visible", return_value=True):
                blocked = state.static_vmm_preflight(project, configuration)
            self.assertIn(code, blocked["reasonCodes"])
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(backup)
            if relative.endswith("/python"):
                target.chmod(0o700)

        with mock.patch.object(state, "gpu_visible", return_value=False):
            blocked = state.static_vmm_preflight(project, configuration)
        self.assertIn("VMM_GPU_NOT_VISIBLE", blocked["reasonCodes"])
        missing_configuration = dict(configuration, PAINTING_MUSIC_SERVICE_URL="")
        with mock.patch.object(state, "gpu_visible", return_value=True):
            blocked = state.static_vmm_preflight(project, missing_configuration)
        self.assertIn("VMM_CONFIGURATION_MISSING", blocked["reasonCodes"])
        bad_output = dict(configuration, AURALINK_VMM_OUTPUT_DIR=str(project / "missing-output"))
        with mock.patch.object(state, "gpu_visible", return_value=True):
            blocked = state.static_vmm_preflight(project, bad_output)
        self.assertIn("VMM_OUTPUT_ROOT_INVALID", blocked["reasonCodes"])


if __name__ == "__main__":
    unittest.main()
