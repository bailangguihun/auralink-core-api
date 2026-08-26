#!/usr/bin/env python3
"""Loopback-only synthetic provider used by the packaged ROUND 8.1A harness."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import signal
import struct
import sys
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Optional


OPERATIONS = {
    "text-to-painting": {"seedream": 1, "qwen": 0, "vmm": 0},
    "image-to-painting": {"seedream": 1, "qwen": 0, "vmm": 0},
    "poem-to-painting": {"seedream": 1, "qwen": 1, "vmm": 0},
    "painting-to-poem": {"seedream": 0, "qwen": 1, "vmm": 0},
    "painting-to-music": {"seedream": 0, "qwen": 0, "vmm": 1},
}
TEXT_SEEDREAM_KEYS = {
    "model", "prompt", "response_format", "size", "stream", "watermark",
}
IMAGE_SEEDREAM_KEYS = {
    "model", "prompt", "image", "response_format", "size", "stream", "watermark",
}
ARK_REJECTIONS = {
    "seedream-http-400": (400, "InvalidParameter"),
    "seedream-http-401": (401, "AuthenticationError"),
    "seedream-http-403": (403, "AccessDenied"),
}


def png_bytes() -> bytes:
    def chunk(name: bytes, payload: bytes) -> bytes:
        return struct.pack(">I", len(payload)) + name + payload + struct.pack(">I", zlib.crc32(name + payload))

    header = struct.pack(">IIBBBBB", 3, 2, 8, 2, 0, 0, 0)
    rows = b"\x00\x10\x20\x30\x40\x50\x60\x70\x80\x90\x00\x90\x80\x70\x60\x50\x40\x30\x20\x10"
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")


def wave_bytes() -> bytes:
    samples = b"\x00\x00\x00\x00"
    header = (
        b"RIFF" + struct.pack("<I", 36 + len(samples)) + b"WAVE"
        + b"fmt " + struct.pack("<IHHIIHH", 16, 1, 1, 8000, 16000, 2, 16)
        + b"data" + struct.pack("<I", len(samples))
    )
    return header + samples


def write_private(path: Path, data: bytes) -> None:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        output.write(data)
    os.chmod(path, 0o600)


def write_json(path: Path, value: Any) -> None:
    write_private(path, json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode())


def prepare_fixture(run_directory: Path) -> None:
    if run_directory.is_symlink() or not run_directory.is_dir():
        raise ValueError("run directory must already exist")
    os.chmod(run_directory, 0o700)
    image = png_bytes()
    image_name = "input-image.png"
    write_private(run_directory / image_name, image)
    manifest = {
        "paintingId": "00074dee-e790-4cf3-a1d9-1e2e784364fb",
        "title": "共饮一江水",
        "author": "叶浅予",
        "dynasty": "现代",
        "category": "山水",
        "subject": "江上归舟",
        "paintingSchool": None,
        "style": "水墨",
        "composition": "远山近水",
        "artisticConception": "清润宁静",
        "generatedText": "江上薄雾与归舟。",
        "musicSceneDescription": "宁静舒展的江南水色。",
        "mimeType": "image/png",
        "width": 3,
        "height": 2,
        "sha256": hashlib.sha256(image).hexdigest(),
        "inputFile": image_name,
    }
    write_json(run_directory / "input-metadata.json", manifest)


class MockState:
    def __init__(self, operation: str, state_file: Path, vmm_output: Path, failure: str) -> None:
        self.operation = operation
        self.state_file = state_file
        self.vmm_output = vmm_output
        self.failure = failure
        self.counts = {"seedream": 0, "qwen": 0, "vmm": 0}
        self.write()

    def record(self, provider: str) -> None:
        self.counts[provider] += 1
        self.write()

    def write(self) -> None:
        temporary = self.state_file.with_suffix(".part")
        if temporary.exists():
            temporary.unlink()
        write_json(temporary, {"operation": self.operation, "calls": self.counts})
        os.replace(temporary, self.state_file)
        os.chmod(self.state_file, 0o600)


def handler_for(state: MockState, base_url: list[str]) -> type[BaseHTTPRequestHandler]:
    image = png_bytes()

    class Handler(BaseHTTPRequestHandler):
        server_version = "Round81Mock/1"

        def log_message(self, _format: str, *_args: object) -> None:
            return

        def do_GET(self) -> None:  # noqa: N802
            if self.path == "/mock/generated.png":
                self.respond(200, "image/png", image)
            elif self.path == "/health":
                self.respond_json(200, {"status": "ok", "model_ready": True})
            else:
                self.respond_json(404, {"error": "not_found"})

        def do_POST(self) -> None:  # noqa: N802
            raw_length = self.headers.get("Content-Length", "0")
            if not raw_length.isdigit() or int(raw_length) > 25 * 1024 * 1024:
                self.respond_json(413, {"error": "too_large"})
                return
            body = self.rfile.read(int(raw_length))
            try:
                request = json.loads(body)
            except json.JSONDecodeError:
                self.respond_json(400, {"error": "invalid_json"})
                return
            if self.path == "/images/generations":
                state.record("seedream")
                expected_keys = (
                    IMAGE_SEEDREAM_KEYS
                    if state.operation == "image-to-painting"
                    else TEXT_SEEDREAM_KEYS
                )
                base_contract_valid = (
                    set(request) == expected_keys
                    and isinstance(request.get("model"), str)
                    and bool(request["model"].strip())
                    and isinstance(request.get("prompt"), str)
                    and bool(request["prompt"].strip())
                    and request.get("response_format") == "url"
                    and request.get("size") == "2K"
                    and request.get("stream") is False
                    and isinstance(request.get("watermark"), bool)
                )
                image_contract_valid = state.operation != "image-to-painting" or (
                    isinstance(request.get("image"), str)
                    and request["image"].startswith((
                        "data:image/jpeg;base64,", "data:image/png;base64,"
                    ))
                    and not any(character.isspace() for character in request["image"])
                    and all(control in request["prompt"] for control in (
                        "中国画（国画）", "保持主要主体身份", "主要主体数量", "核心构图",
                        "空间关系", "不添加无关对象", "不添加文字", "徽标", "界面边框",
                        "不执行",
                    ))
                )
                if not base_contract_valid or not image_contract_valid:
                    self.respond_json(400, {"error": "contract"})
                    return
                if state.failure in ARK_REJECTIONS:
                    status, code = ARK_REJECTIONS[state.failure]
                    self.respond_json(
                        status,
                        {
                            "error": {
                                "code": code,
                                "message": "RAW_PROVIDER_MESSAGE_MUST_NOT_ESCAPE",
                                "request_id": "RAW_BODY_REQUEST_ID_MUST_NOT_ESCAPE",
                            },
                            "model": "PRIVATE_MODEL_MUST_NOT_ESCAPE",
                            "prompt": "PRIVATE_PROMPT_MUST_NOT_ESCAPE",
                            "authorization": "PRIVATE_API_KEY_MUST_NOT_ESCAPE",
                        },
                        {"X-Request-Id": "RAW_HEADER_REQUEST_ID_MUST_NOT_ESCAPE"},
                    )
                    return
                results = [{"url": base_url[0] + "/mock/generated.png"}]
                if state.failure == "seedream-multiple":
                    results.append({"url": base_url[0] + "/mock/generated.png"})
                self.respond_json(200, {"data": results})
            elif self.path == "/chat/completions":
                state.record("qwen")
                if request.get("enable_thinking") is not False or request.get("stream") is not False:
                    self.respond_json(400, {"error": "contract"})
                    return
                content = poem_content() if state.operation == "painting-to-poem" else plan_content()
                if state.failure == "qwen-invalid":
                    content = "not-json"
                elif state.operation == "painting-to-poem" and state.failure.startswith("qwen-poem-"):
                    content = invalid_poem_content(state.failure)
                message: dict[str, Any] = {"content": content}
                if state.operation == "painting-to-poem":
                    reasoning_values: dict[str, Any] = {
                        "qwen-poem-reasoning-null": None,
                        "qwen-poem-reasoning-empty": "",
                        "qwen-poem-reasoning-whitespace": "\u3000\t ",
                        "qwen-poem-reasoning-nonblank": "ROUND81_PRIVATE_REASONING_MARKER",
                        "qwen-poem-reasoning-object": {"private": "ROUND81_PRIVATE_REASONING_OBJECT"},
                    }
                    if state.failure in reasoning_values:
                        message["reasoning_content"] = reasoning_values[state.failure]
                self.respond_json(200, {"choices": [{"message": message}]})
            elif self.path == "/api/generate_with_image":
                state.record("vmm")
                if request.get("duration") != 30 or not str(request.get("image", "")).startswith("data:image/"):
                    self.respond_json(400, {"error": "contract"})
                    return
                output = state.vmm_output / "mock-result.wav"
                if output.exists():
                    output.unlink()
                write_private(output, wave_bytes())
                self.respond_json(200, {
                    "success": True,
                    "fileName": output.name,
                    "full_path": "/ignored/mock/provider/path.wav",
                    "message": "synthetic",
                })
            else:
                self.respond_json(404, {"error": "not_found"})

        def respond_json(
            self,
            status: int,
            value: Any,
            headers: Optional[dict[str, str]] = None,
        ) -> None:
            self.respond(
                status,
                "application/json",
                json.dumps(value, ensure_ascii=False).encode(),
                headers,
            )

        def respond(
            self,
            status: int,
            content_type: str,
            body: bytes,
            headers: Optional[dict[str, str]] = None,
        ) -> None:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            for name, value in (headers or {}).items():
                self.send_header(name, value)
            self.end_headers()
            self.wfile.write(body)

    return Handler


def plan_content() -> str:
    return json.dumps({
        "schemaVersion": "1",
        "subject": "空山清泉与松间明月",
        "scene": "新雨后的秋山清幽澄澈",
        "composition": "远山留白近景松石相映",
        "colorPalette": "淡墨青绿与月色冷白",
        "brushwork": "湿笔皴染兼以细线勾勒",
        "artisticConception": "清寂明净而含归隐诗意",
        "finalPrompt": "中国画山水，秋雨初霁，松间明月照见石上清泉，远山留白，淡墨青绿，构图清寂，无文字标志。",
    }, ensure_ascii=False, separators=(",", ":"))


def poem_content() -> str:
    lines = ["远岫含烟入晚晴", "孤舟一叶过江汀", "松风不语随云去", "月照清泉石上明"]
    return json.dumps({
        "schemaVersion": "1",
        "title": "江山清韵",
        "lines": lines,
        "text": "\n".join(lines),
    }, ensure_ascii=False, separators=(",", ":"))


def invalid_poem_content(failure: str) -> str:
    lines = ["私密诗句甲", "私密诗句乙", "私密诗句丙", "私密诗句丁"]
    document: dict[str, Any] = {
        "schemaVersion": "1",
        "title": "私密题名ROUND81_PRIVATE_POEM_MARKER_8A4",
        "lines": lines,
        "text": "\n".join(lines),
    }
    if failure == "qwen-poem-numeric-schema":
        document["schemaVersion"] = 1
    elif failure == "qwen-poem-five-lines":
        document["lines"] = lines + ["私密诗句戊"]
        document["text"] = "\n".join(document["lines"])
    elif failure == "qwen-poem-text-mismatch":
        document["text"] = "私密正文不匹配"
    elif failure not in (
        "qwen-poem-fenced",
        "qwen-poem-reasoning-null",
        "qwen-poem-reasoning-empty",
        "qwen-poem-reasoning-whitespace",
        "qwen-poem-reasoning-nonblank",
        "qwen-poem-reasoning-object",
    ):
        raise ValueError("unsupported synthetic poem failure")
    rendered = json.dumps(document, ensure_ascii=False, separators=(",", ":"))
    return f"```json\n{rendered}\n```" if failure == "qwen-poem-fenced" else rendered


def serve(arguments: argparse.Namespace) -> int:
    if arguments.operation not in OPERATIONS:
        return 2
    arguments.vmm_output.mkdir(mode=0o700, exist_ok=True)
    os.chmod(arguments.vmm_output, 0o700)
    state = MockState(arguments.operation, arguments.state_file, arguments.vmm_output, arguments.failure)
    base_url = [""]
    server = ThreadingHTTPServer(("127.0.0.1", 0), handler_for(state, base_url))
    server.timeout = 0.2
    base_url[0] = f"http://127.0.0.1:{server.server_port}"
    write_private(arguments.port_file, str(server.server_port).encode())
    stopped = False

    def stop(_signum: int, _frame: object) -> None:
        nonlocal stopped
        stopped = True

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    while not stopped:
        server.handle_request()
    server.server_close()
    return 0


def assert_counts(operation: str, state_file: Path) -> int:
    document = json.loads(state_file.read_text(encoding="utf-8"))
    if document != {"operation": operation, "calls": OPERATIONS[operation]}:
        print("MOCK_CALL_COUNT_MISMATCH", file=sys.stderr)
        return 2
    print("MOCK_CALL_COUNTS_VERIFIED")
    return 0


def assert_custom_counts(state_file: Path, seedream: int, qwen: int, vmm: int) -> int:
    document = json.loads(state_file.read_text(encoding="utf-8"))
    expected = {"seedream": seedream, "qwen": qwen, "vmm": vmm}
    if document.get("calls") != expected:
        print("MOCK_CALL_COUNT_MISMATCH", file=sys.stderr)
        return 2
    print("MOCK_CALL_COUNTS_VERIFIED")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    fixture = subparsers.add_parser("prepare-fixture")
    fixture.add_argument("--run-dir", type=Path, required=True)
    server = subparsers.add_parser("serve")
    server.add_argument("--operation", choices=sorted(OPERATIONS), required=True)
    server.add_argument("--state-file", type=Path, required=True)
    server.add_argument("--port-file", type=Path, required=True)
    server.add_argument("--vmm-output", type=Path, required=True)
    server.add_argument(
        "--failure",
        choices=(
            "none", "qwen-invalid", "seedream-multiple",
            "qwen-poem-numeric-schema", "qwen-poem-five-lines",
            "qwen-poem-fenced", "qwen-poem-text-mismatch",
            "qwen-poem-reasoning-null", "qwen-poem-reasoning-empty",
            "qwen-poem-reasoning-whitespace", "qwen-poem-reasoning-nonblank",
            "qwen-poem-reasoning-object",
            "seedream-http-400", "seedream-http-401", "seedream-http-403",
        ),
        default="none",
    )
    counts = subparsers.add_parser("assert-counts")
    counts.add_argument("--operation", choices=sorted(OPERATIONS), required=True)
    counts.add_argument("--state-file", type=Path, required=True)
    custom = subparsers.add_parser("assert-custom-counts")
    custom.add_argument("--state-file", type=Path, required=True)
    custom.add_argument("--seedream", type=int, required=True)
    custom.add_argument("--qwen", type=int, required=True)
    custom.add_argument("--vmm", type=int, required=True)
    arguments = parser.parse_args()
    if arguments.command == "prepare-fixture":
        prepare_fixture(arguments.run_dir)
        return 0
    if arguments.command == "assert-counts":
        return assert_counts(arguments.operation, arguments.state_file)
    if arguments.command == "assert-custom-counts":
        return assert_custom_counts(
            arguments.state_file, arguments.seedream, arguments.qwen, arguments.vmm
        )
    return serve(arguments)


if __name__ == "__main__":
    raise SystemExit(main())
