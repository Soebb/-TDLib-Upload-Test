"""Instagram bulk downloader engine.

Runs inside Chaquopy. SQLite is deliberately owned by Python (sqlite3);
Java/TDLib is responsible only for Telegram authorization and media delivery.
"""
import hashlib
import json
import os
import shutil
import sqlite3
import re
from pathlib import Path

import yt_dlp

FORMAT = (
    "best[height=360]/best[height=480]/best[height=720]/"
    "best[height<=360]/best[height<=480]/best[height<=720]/best"
)


def _connect(database_path):
    path = Path(database_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(path), timeout=30)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    conn.execute("""
        CREATE TABLE IF NOT EXISTS posts (
            url TEXT PRIMARY KEY,
            status TEXT NOT NULL DEFAULT 'pending',
            attempts INTEGER NOT NULL DEFAULT 0,
            last_error TEXT,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        )
    """)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS chunks (
            url TEXT NOT NULL,
            chunk_no INTEGER NOT NULL,
            signature TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'pending',
            updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (url, chunk_no),
            FOREIGN KEY (url) REFERENCES posts(url) ON DELETE CASCADE
        )
    """)
    return conn


def initialize(database_path):
    conn = _connect(database_path)
    try:
        conn.commit()
        return counts(database_path)
    finally:
        conn.close()


def import_links(database_path, text):
    """Import newline-separated URLs; duplicate URLs are ignored."""
    urls = []
    seen = set()
    # Extract Instagram URLs even when surrounded by captions or separators.
    pattern = re.compile(r'https?://(?:www\\.)?(?:instagram\\.com|instagr\\.am)/[^\\s<>\\\"\\']+', re.IGNORECASE)
    for match in pattern.findall(text or ""):
        url = match.rstrip(".,;)]}")
        if url and url not in seen:
            urls.append(url)
            seen.add(url)
    conn = _connect(database_path)
    try:
        before = conn.total_changes
        conn.executemany(
            "INSERT OR IGNORE INTO posts(url, status) VALUES(?, 'pending')",
            [(url,) for url in urls],
        )
        inserted = conn.total_changes - before
        conn.commit()
        return {"received": len(urls), "inserted": inserted, "duplicates": len(urls) - inserted}
    finally:
        conn.close()


def counts(database_path):
    conn = _connect(database_path)
    try:
        result = {name: 0 for name in ("pending", "processing", "done", "failed")}
        for status, amount in conn.execute(
            "SELECT status, COUNT(*) FROM posts GROUP BY status"
        ):
            result[status] = amount
        result["total"] = sum(result.values())
        return result
    finally:
        conn.close()


def next_pending(database_path):
    conn = _connect(database_path)
    try:
        row = conn.execute(
            "SELECT url FROM posts WHERE status IN ('pending','failed') ORDER BY rowid LIMIT 1"
        ).fetchone()
        return row[0] if row else None
    finally:
        conn.close()


def mark_processing(database_path, url):
    conn = _connect(database_path)
    try:
        conn.execute("""
            INSERT INTO posts(url, status, attempts) VALUES(?, 'processing', 1)
            ON CONFLICT(url) DO UPDATE SET
                status='processing', attempts=attempts+1,
                last_error=NULL, updated_at=CURRENT_TIMESTAMP
        """, (url,))
        conn.commit()
    finally:
        conn.close()


def mark_done(database_path, url):
    conn = _connect(database_path)
    try:
        conn.execute(
            "UPDATE posts SET status='done', last_error=NULL, updated_at=CURRENT_TIMESTAMP WHERE url=?",
            (url,),
        )
        conn.commit()
    finally:
        conn.close()


def mark_failed(database_path, url, error):
    conn = _connect(database_path)
    try:
        conn.execute(
            "UPDATE posts SET status='failed', last_error=?, updated_at=CURRENT_TIMESTAMP WHERE url=?",
            (str(error)[:2000], url),
        )
        conn.commit()
    finally:
        conn.close()


def _flatten(info):
    entries = info.get("entries")
    if not entries:
        return [info]
    result = []
    for entry in entries:
        if entry:
            result.extend(_flatten(entry) if entry.get("entries") else [entry])
    return result


def _is_video(item):
    codec = item.get("vcodec")
    ext = (item.get("ext") or "").lower()
    return (codec and codec != "none") or ext in {
        "mp4", "m4v", "mov", "webm", "mkv", "avi", "3gp"
    }


def _best_format(entry):
    formats = [item for item in entry.get("formats", []) if item.get("url")]
    if not formats:
        return entry
    videos = [item for item in formats if _is_video(item)]
    candidates = videos or formats
    if not videos:
        return max(candidates, key=lambda item: (item.get("width") or 0) * (item.get("height") or 0))
    for height in (360, 480, 720):
        exact = [item for item in candidates if item.get("height") == height]
        if exact:
            return max(exact, key=lambda item: item.get("tbr") or 0)
    for limit in (360, 480, 720):
        lower = [item for item in candidates if (item.get("height") or 0) <= limit]
        if lower:
            return max(lower, key=lambda item: (item.get("height") or 0, item.get("tbr") or 0))
    return max(candidates, key=lambda item: (item.get("height") or 0, item.get("tbr") or 0))


def _media_items(info):
    result = []
    for entry in _flatten(info):
        selected = _best_format(entry)
        media_url = selected.get("url") or entry.get("url")
        if not media_url:
            continue
        media_id = str(entry.get("id") or hashlib.sha1(media_url.encode()).hexdigest()[:12])
        result.append({
            "id": media_id,
            "is_video": bool(_is_video(selected) or _is_video(entry)),
            "width": int(selected.get("width") or entry.get("width") or 0),
            "height": int(selected.get("height") or entry.get("height") or 0),
            "duration": int(entry.get("duration") or 0),
            "ext": selected.get("ext") or entry.get("ext") or "",
        })
    return result


def inspect_post(url):
    options = {
        "quiet": True, "no_warnings": True, "skip_download": True,
        "noplaylist": False, "retries": 5, "extract_flat": False,
    }
    with yt_dlp.YoutubeDL(options) as ydl:
        info = ydl.extract_info(url, download=False)
    if not info:
        raise RuntimeError("yt-dlp returned no post information")
    entries = _flatten(info)
    media = _media_items(info)
    if not media:
        raise RuntimeError("No supported photo/video media was found")
    description = info.get("description") or next(
        (entry.get("description") for entry in entries if entry.get("description")), ""
    )
    return {
        "title": info.get("title") or "",
        "description": description or "",
        "uploader": info.get("uploader") or "",
        "media": media,
        "caption": (description or "").strip(),
    }


def _chunk_signature(items):
    return hashlib.sha256("|".join(item["id"] for item in items).encode()).hexdigest()


def get_sent_chunks(database_path, url):
    conn = _connect(database_path)
    try:
        return {
            int(number): signature
            for number, signature in conn.execute(
                "SELECT chunk_no, signature FROM chunks WHERE url=? AND status='sent'",
                (url,),
            )
        }
    finally:
        conn.close()


def mark_chunk_sent(database_path, url, chunk_no, signature):
    conn = _connect(database_path)
    try:
        conn.execute("""
            INSERT INTO chunks(url, chunk_no, signature, status)
            VALUES(?, ?, ?, 'sent')
            ON CONFLICT(url, chunk_no) DO UPDATE SET
                signature=excluded.signature, status='sent',
                updated_at=CURRENT_TIMESTAMP
        """, (url, int(chunk_no), signature))
        conn.commit()
    finally:
        conn.close()


def chunk_plan(media_items, sent_chunks_json="{}"):
    sent = {int(key): value for key, value in json.loads(sent_chunks_json or "{}").items()}
    plan = []
    for start in range(0, len(media_items), 10):
        number = start // 10
        part = media_items[start:start + 10]
        signature = _chunk_signature(part)
        if sent.get(number) != signature:
            plan.append({
                "number": number,
                "signature": signature,
                "media": part,
            })
    return plan


def download_post(url, download_root):
    key = hashlib.sha1(url.encode()).hexdigest()[:16]
    folder = Path(download_root) / key
    folder.mkdir(parents=True, exist_ok=True)
    options = {
        "outtmpl": str(folder / "%(id)s.%(ext)s"),
        "format": FORMAT,
        "noplaylist": False,
        "ignoreerrors": False,
        "quiet": True,
        "no_warnings": True,
        "retries": 8,
        "fragment_retries": 8,
        "socket_timeout": 30,
        "merge_output_format": "mp4",
    }
    with yt_dlp.YoutubeDL(options) as ydl:
        info = ydl.extract_info(url, download=True)
        if not info:
            raise RuntimeError("yt-dlp returned no downloaded media")
        entries = _flatten(info)
        paths = []
        for entry in entries:
            candidates = [
                Path(item["filepath"])
                for item in entry.get("requested_downloads", [])
                if item.get("filepath")
            ]
            if entry.get("filepath"):
                candidates.append(Path(entry["filepath"]))
            candidates.extend(folder.glob(f"{entry.get('id', '')}.*"))
            found = next((path for path in candidates if path.is_file()), None)
            if found and found not in paths:
                paths.append(found)
        if len(paths) != len(entries):
            remaining = [
                path for path in sorted(folder.iterdir())
                if path.is_file() and path not in paths
                and not path.name.endswith((".part", ".ytdl"))
            ]
            paths.extend(remaining[:len(entries) - len(paths)])
    if not paths:
        raise RuntimeError("No downloaded media files were found")
    media = _media_items(info)
    if len(media) != len(paths):
        media = media[:len(paths)]
        while len(media) < len(paths):
            path = paths[len(media)]
            media.append({
                "id": path.stem,
                "is_video": path.suffix.lower() not in (".jpg", ".jpeg", ".png", ".webp", ".heic"),
                "width": 0, "height": 0, "duration": 0,
                "ext": path.suffix.lstrip(".")
            })
    description = info.get("description") or next(
        (entry.get("description") for entry in entries if entry.get("description")), ""
    )
    return json.dumps({
        "files": [str(path) for path in paths],
        "media": media,
        "caption": (description or "").strip()
    }, ensure_ascii=False)


def cleanup_post(url, download_root):
    key = hashlib.sha1(url.encode()).hexdigest()[:16]
    shutil.rmtree(Path(download_root) / key, ignore_errors=True)


# JSON wrappers keep the Chaquopy bridge simple and version-independent.
def initialize_json(database_path):
    return json.dumps(initialize(database_path))


def import_links_json(database_path, text):
    return json.dumps(import_links(database_path, text))


def counts_json(database_path):
    return json.dumps(counts(database_path))


def inspect_post_json(url):
    return json.dumps(inspect_post(url), ensure_ascii=False)


def chunk_plan_json(media_json, sent_chunks_json="{}"):
    return json.dumps(chunk_plan(json.loads(media_json), sent_chunks_json), ensure_ascii=False)


def get_sent_chunks_json(database_path, url):
    return json.dumps({str(key): value for key, value in get_sent_chunks(database_path, url).items()})
