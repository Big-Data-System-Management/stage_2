import argparse
import csv
import gzip
import hashlib
import os
import random
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

BENCHMARK_DIR = Path(__file__).resolve().parent
BOOKS_CSV = Path(os.environ.get("BENCH_BOOKS_CSV", BENCHMARK_DIR / "books.csv"))
CACHE_DIR = Path(os.environ.get("BENCH_CACHE_DIR", BENCHMARK_DIR / "cache"))
RAW_DIR = CACHE_DIR / "books"
SPLIT_DIR = CACHE_DIR / "split"
CANDIDATES_CSV = CACHE_DIR / "candidates.csv"

BASE_URL = os.environ.get("GUTENBERG_BASE_URL", "https://gutenberg.pglaf.org").rstrip("/")
DELAY_SECONDS = float(os.environ.get("GUTENBERG_DELAY_S", "2"))
USER_AGENT = "stage2-benchmark/1.0 (ULPGC Big Data course project)"

SEED = 20261030
MAX_BOOK_ID = 76000
BATCH_SIZE = 20
BASE_SIZE = 2000
BATCH_MIN_BODY_BYTES = 100_000
BATCH_MAX_BODY_BYTES = 1_000_000
MIN_BODY_BYTES = 1_000
MAX_ATTEMPTS = 4

START_MARKER = re.compile(r"\*\*\* ?START OF (THE|THIS) PROJECT GUTENBERG E-?BOOK[^\r\n]*", re.IGNORECASE)
END_MARKER = re.compile(r"\*\*\* ?END OF (THE|THIS) PROJECT GUTENBERG E-?BOOK", re.IGNORECASE)

last_request = 0.0


def fetch(book_id):
    global last_request
    url = f"{BASE_URL}/cache/epub/{book_id}/pg{book_id}.txt"
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    for attempt in range(1, MAX_ATTEMPTS + 1):
        wait = last_request + DELAY_SECONDS - time.monotonic()
        if wait > 0:
            time.sleep(wait)
        last_request = time.monotonic()
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                content = response.read()
            return gzip.decompress(content) if content[:2] == b"\x1f\x8b" else content
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return None
            failure = f"HTTP {error.code}"
        except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
            failure = str(error)
        print(f"[dataset] Book {book_id}: {failure} (attempt {attempt}/{MAX_ATTEMPTS})", flush=True)
        time.sleep(DELAY_SECONDS * 2 ** attempt)
    raise SystemExit(f"[dataset] Giving up on book {book_id}; run the script again to resume")


def split(text):
    start = START_MARKER.search(text)
    if start is None:
        return None
    end = END_MARKER.search(text, start.end())
    if end is None:
        return None
    return text[: start.start()].strip(), text[start.end() : end.start()].strip()


def store(book_id, content):
    try:
        text = content.decode("utf-8")
    except UnicodeDecodeError:
        return None
    parts = split(text)
    if parts is None:
        return None
    header, body = parts
    body_bytes = len(body.encode("utf-8"))
    if body_bytes < MIN_BODY_BYTES:
        return None
    (RAW_DIR / f"pg{book_id}.txt").write_bytes(content)
    (SPLIT_DIR / f"{book_id}.header.txt").write_text(header, encoding="utf-8", newline="")
    (SPLIT_DIR / f"{book_id}.body.txt").write_text(body, encoding="utf-8", newline="")
    return body_bytes


def load_candidates():
    if not CANDIDATES_CSV.exists():
        return {}
    with CANDIDATES_CSV.open(encoding="utf-8", newline="") as file:
        return {int(row["book_id"]): int(row["body_bytes"]) for row in csv.DictReader(file)}


def append_candidate(book_id, body_bytes):
    is_new = not CANDIDATES_CSV.exists()
    with CANDIDATES_CSV.open("a", encoding="utf-8", newline="") as file:
        writer = csv.writer(file, lineterminator="\n")
        if is_new:
            writer.writerow(["book_id", "body_bytes"])
        writer.writerow([book_id, body_bytes])


def select_books():
    candidates = load_candidates()
    order = list(range(1, MAX_BOOK_ID + 1))
    random.Random(SEED).shuffle(order)
    batch, base = [], []
    for book_id in order:
        if len(batch) == BATCH_SIZE and len(base) == BASE_SIZE:
            break
        if book_id not in candidates:
            content = fetch(book_id)
            body_bytes = store(book_id, content) if content is not None else None
            candidates[book_id] = body_bytes or 0
            append_candidate(book_id, candidates[book_id])
            done = sum(1 for value in candidates.values() if value > 0)
            if len(candidates) % 25 == 0:
                print(f"[dataset] Tried {len(candidates)} ids, {done} usable", flush=True)
        body_bytes = candidates[book_id]
        if body_bytes == 0:
            continue
        fits_batch = BATCH_MIN_BODY_BYTES <= body_bytes <= BATCH_MAX_BODY_BYTES
        if len(batch) < BATCH_SIZE and fits_batch:
            batch.append(book_id)
        elif len(base) < BASE_SIZE:
            base.append(book_id)
    if len(batch) < BATCH_SIZE or len(base) < BASE_SIZE:
        raise SystemExit("[dataset] Not enough usable books in the id range")
    return batch, base


def digest(book_id):
    return hashlib.sha256((RAW_DIR / f"pg{book_id}.txt").read_bytes()).hexdigest()[:16]


def write_books_csv(batch, base):
    with BOOKS_CSV.open("w", encoding="utf-8", newline="") as file:
        writer = csv.writer(file, lineterminator="\n")
        writer.writerow(["book_id", "role", "position", "sha256_16"])
        for role, books in (("batch", batch), ("base", base)):
            for position, book_id in enumerate(books):
                writer.writerow([book_id, role, position, digest(book_id)])


def read_books_csv():
    with BOOKS_CSV.open(encoding="utf-8", newline="") as file:
        return list(csv.DictReader(file))


def download_listed(rows):
    missing = [row for row in rows if not (SPLIT_DIR / f"{row['book_id']}.body.txt").exists()]
    print(f"[dataset] {len(rows) - len(missing)} books cached, {len(missing)} to download", flush=True)
    for done, row in enumerate(missing, start=1):
        book_id = int(row["book_id"])
        content = fetch(book_id)
        if content is None or store(book_id, content) is None:
            raise SystemExit(f"[dataset] Listed book {book_id} is no longer usable")
        if done % 25 == 0:
            print(f"[dataset] Downloaded {done}/{len(missing)}", flush=True)


def verify(rows):
    roles = {"batch": set(), "base": set()}
    for row in rows:
        roles[row["role"]].add(row["book_id"])
    if roles["batch"] & roles["base"]:
        raise SystemExit("[dataset] The reserved batch overlaps the base books")
    changed = [row["book_id"] for row in rows if digest(row["book_id"]) != row["sha256_16"]]
    if changed:
        print(f"[dataset] Warning: {len(changed)} books differ from books.csv: {' '.join(changed[:10])}", flush=True)
    size = sum((RAW_DIR / f"pg{row['book_id']}.txt").stat().st_size for row in rows)
    print(f"[dataset] Ready: {len(roles['batch'])} batch books, {len(roles['base'])} base books, "
          f"{size / 1024 ** 3:.2f} GB in {CACHE_DIR}", flush=True)


def main():
    parser = argparse.ArgumentParser(description="Download the benchmark books once into a local cache")
    parser.add_argument("--check", action="store_true", help="exit with code 1 if the cache is incomplete")
    args = parser.parse_args()
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    SPLIT_DIR.mkdir(parents=True, exist_ok=True)
    if args.check:
        rows = read_books_csv() if BOOKS_CSV.exists() else []
        complete = rows and all((SPLIT_DIR / f"{row['book_id']}.body.txt").exists() for row in rows)
        sys.exit(0 if complete else 1)
    if not BOOKS_CSV.exists():
        batch, base = select_books()
        write_books_csv(batch, base)
    rows = read_books_csv()
    download_listed(rows)
    verify(rows)


if __name__ == "__main__":
    main()
