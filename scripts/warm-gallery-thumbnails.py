#!/usr/bin/env python3
"""Request public gallery derivatives in bounded, restartable batches."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import re
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

PREVIEW = re.compile(r"/api/v1/assets/public-catalog-thumbnails/(v1|v2|v2/480)/([0-9a-f-]{36})/[0-9a-f]{64}\.jpg")
MAX_PREVIEW_BYTES = 192 * 1024


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


OPENER = build_opener(NoRedirect())


def get(origin, path, timeout, limit, content_type):
    request = Request(origin + path, headers={"Accept": content_type})
    for attempt in range(3):
        try:
            with OPENER.open(request, timeout=timeout) as response:
                data = response.read(limit + 1)
                if response.headers.get_content_type() != content_type or len(data) > limit:
                    raise ValueError(f"Unexpected response for {path}")
                return data
        except HTTPError as error:
            if error.code not in (429, 502, 503, 504) or attempt == 2:
                raise
        except (URLError, TimeoutError):
            if attempt == 2:
                raise
        time.sleep(2 ** attempt)


def fetch_preview(args, path):
    try:
        data = get(args.origin, path, args.timeout, MAX_PREVIEW_BYTES, "image/jpeg")
        if not data.startswith(b"\xff\xd8") or not data.endswith(b"\xff\xd9"):
            raise ValueError("Invalid JPEG preview")
        return None
    except (URLError, TimeoutError, ValueError) as error:
        return f"{path}: {error}"
    finally:
        time.sleep(args.delay)


def warm_batch(args, paintings, pool, seen, totals):
    paths = []
    for painting in paintings:
        image = painting.get("image")
        if not image:
            continue
        variants = []
        if args.variant in ("standard", "both"):
            variants.append((image.get("previewUrl") or image.get("thumbnailUrl"), ("v1", "v2")))
        if args.variant in ("small", "both"):
            variants.append((image.get("smallPreviewUrl"), ("v2/480",)))
        for path, versions in variants:
            match = PREVIEW.fullmatch(path or "")
            if match is None or match[1] not in versions or match[2] != image.get("assetId"):
                totals["missing"] += 1
                print(f"No valid {versions[-1]} preview URL for {painting.get('paintingId')}", file=sys.stderr)
            elif path not in seen:
                seen.add(path)
                paths.append(path)
    for error in pool.map(lambda path: fetch_preview(args, path), paths):
        if error:
            totals["failed"] += 1
            print(error, file=sys.stderr)
        else:
            totals["ready"] += 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("origin", help="Core API HTTP(S) origin")
    parser.add_argument("--variant", choices=("standard", "small", "both"), default="standard")
    parser.add_argument("--start-page", type=int, default=0)
    parser.add_argument("--pages", type=int, default=1, help="Number of pages, 0 to continue to the last page")
    parser.add_argument("--size", type=int, default=48)
    parser.add_argument("--delay", type=float, default=0.15, help="Seconds between image requests")
    parser.add_argument("--workers", type=int, default=1, choices=range(1, 5))
    parser.add_argument("--timeout", type=float, default=12)
    args = parser.parse_args()
    parsed = urlsplit(args.origin)
    if (parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username is not None
            or parsed.path not in ("", "/") or parsed.query or parsed.fragment):
        parser.error("provide an HTTP(S) origin without credentials, query or path")
    if args.start_page < 0 or args.pages < 0 or not 1 <= args.size <= 100 or args.delay < 0 or args.timeout <= 0:
        parser.error("invalid batch bounds, delay or timeout")
    args.origin = args.origin.rstrip("/")

    def read_json(path):
        return json.loads(get(args.origin, path, args.timeout, 4 * 1024 * 1024, "application/json"))

    seen = set()
    totals = {"ready": 0, "missing": 0, "failed": 0}
    page = args.start_page
    try:
        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            if page == 0:
                daily = read_json("/api/v1/paintings/daily")
                if daily:
                    warm_batch(args, [daily], pool, seen, totals)
                first = read_json("/api/v1/paintings?page=0&size=12&sort=daily")
                warm_batch(args, first["items"], pool, seen, totals)
            while args.pages == 0 or page < args.start_page + args.pages:
                query = urlencode({"page": page, "size": args.size, "sort": "source"})
                result = read_json("/api/v1/paintings?" + query)
                if result.get("page") != page:
                    raise ValueError(f"Unexpected gallery page {result.get('page')} for {page}")
                warm_batch(args, result["items"], pool, seen, totals)
                page += 1
                print(json.dumps({"nextPage": page, **totals}), flush=True)
                if not result["items"] or page * args.size >= result["totalElements"]:
                    break
    except (URLError, TimeoutError, ValueError, KeyError) as error:
        print(f"Batch stopped on page {page}: {error}", file=sys.stderr)
        return 1
    return int(totals["missing"] > 0 or totals["failed"] > 0)


if __name__ == "__main__":
    sys.exit(main())
