"""Instagram bulk downloader with one SQLite database per imported link batch."""
import hashlib
import json
import os
import re
import shutil
import sqlite3
import time
import uuid
from pathlib import Path

import yt_dlp

FORMAT = (
    "best[height=360]/best[height=480]/best[height=720]/"
    "best[height<=360]/best[height<=480]/best[height<=720]/best"
)
URL_PATTERN = re.compile(r"https?://(?:www\.)?(?:instagram\.com|instagr\.am)/[^\s<>]+", re.IGNORECASE)


def extract_links(text):
    urls, seen = [], set()
    for match in URL_PATTERN.findall(text or ""):
        url = match.rstrip(".,;)]}\\\"'")
        if url and url not in seen:
            urls.append(url)
            seen.add(url)
    return urls


def _connect(database_path):
    conn = sqlite3.connect(str(database_path), timeout=30)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    conn.execute("""
        CREATE TABLE IF NOT EXISTS batch_info (
            id INTEGER PRIMARY KEY CHECK(id=1),
            name TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        )
    """)
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


def create_batch(database_directory, text, display_name=None):
    urls = extract_links(text)
    if not urls:
        return {"created": False, "reason": "no_links", "received": 0}
    root = Path(database_directory)
    root.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d_%H%M%S")
    batch_id = uuid.uuid4().hex[:8]
    safe_name = (display_name or "Links batch").strip() or "Links batch"
    filename = f"batch_{stamp}_{batch_id}.sqlite3"
    path = root / filename
    conn = _connect(path)
    try:
        conn.execute("INSERT INTO batch_info(id, name) VALUES(1, ?)", (safe_name,))
        conn.executemany(
            "INSERT INTO posts(url, status) VALUES(?, 'pending')",
            [(url,) for url in urls],
        )
        conn.commit()
    finally:
        conn.close()
    return {
        "created": True, "database": str(path), "filename": filename,
        "name": safe_name, "received": len(urls), "inserted": len(urls),
    }


def _counts(database_path):
    conn = _connect(database_path)
    try:
        result = {name: 0 for name in ("pending", "processing", "done", "failed")}
        for status, amount in conn.execute("SELECT status, COUNT(*) FROM posts GROUP BY status"):
            result[status] = amount
        result["total"] = sum(result.values())
        row = conn.execute("SELECT name FROM batch_info WHERE id=1").fetchone()
        result["name"] = row[0] if row else Path(database_path).stem
        result["processed"] = result["total"] > 0 and result["pending"] == 0 and result["processing"] == 0
        result["complete"] = result["total"] > 0 and result["done"] == result["total"]
        return result
    finally:
        conn.close()


def list_batches_json(database_directory):
    root = Path(database_directory)
    batches = []
    if root.exists():
        for path in sorted(root.glob("batch_*.sqlite3"), key=lambda p: p.stat().st_mtime, reverse=True):
            try:
                info = _counts(path)
                info["path"] = str(path)
                info["filename"] = path.name
                batches.append(info)
            except sqlite3.Error:
                continue
    return json.dumps(batches, ensure_ascii=False)


def counts_json(database_path):
    return json.dumps(_counts(database_path), ensure_ascii=False)


def delete_batch(database_path):
    path = Path(database_path)
    if not path.is_file():
        return False
    info = _counts(path)
    if not info["processed"]:
        return False
    for suffix in ("", "-wal", "-shm"):
        try:
            Path(str(path) + suffix).unlink(missing_ok=True)
        except OSError:
            return False
    return True


def initialize(database_path):
    conn = _connect(database_path)
    try:
        conn.execute("UPDATE posts SET status='pending', updated_at=CURRENT_TIMESTAMP WHERE status='processing'")
        conn.execute("UPDATE posts SET status='pending', attempts=0, last_error=NULL, updated_at=CURRENT_TIMESTAMP WHERE status='failed'")
        conn.commit()
        return _counts(database_path)
    finally:
        conn.close()


def next_pending(database_path):
    conn = _connect(database_path)
    try:
        row = conn.execute(
            "SELECT url FROM posts WHERE status='pending' ORDER BY rowid LIMIT 1"
        ).fetchone()
        return row[0] if row else None
    finally:
        conn.close()


def mark_processing(database_path, url):
    conn = _connect(database_path)
    try:
        conn.execute("""
            UPDATE posts SET status='processing', attempts=attempts+1,
                last_error=NULL, updated_at=CURRENT_TIMESTAMP WHERE url=?
        """, (url,))
        conn.commit()
        row = conn.execute("SELECT attempts FROM posts WHERE url=?", (url,)).fetchone()
        return int(row[0]) if row else 0
    finally:
        conn.close()


def attempts_for(database_path, url):
    conn = _connect(database_path)
    try:
        row = conn.execute("SELECT attempts FROM posts WHERE url=?", (url,)).fetchone()
        return int(row[0]) if row else 0
    finally:
        conn.close()


def release_processing(database_path, url):
    """Return a manually stopped in-progress link to pending without consuming an attempt."""
    conn = _connect(database_path)
    try:
        conn.execute("""
            UPDATE posts
            SET status='pending',
                attempts=MAX(0, attempts-1),
                updated_at=CURRENT_TIMESTAMP
            WHERE url=? AND status='processing'
        """, (url,))
        conn.commit()
    finally:
        conn.close()


def retry_or_fail(database_path, url, error, max_attempts=4):
    conn = _connect(database_path)
    try:
        row = conn.execute("SELECT attempts FROM posts WHERE url=?", (url,)).fetchone()
        attempts = int(row[0]) if row else max_attempts
        status = "pending" if attempts < max_attempts else "failed"
        conn.execute(
            "UPDATE posts SET status=?, last_error=?, updated_at=CURRENT_TIMESTAMP WHERE url=?",
            (status, str(error)[:2000], url),
        )
        conn.commit()
        return {"status": status, "attempts": attempts, "remaining": max(0, max_attempts-attempts)}
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
        "noplaylist": False, "retries": 2, "extract_flat": False,
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
        "title": info.get("title") or "", "description": description or "",
        "uploader": info.get("uploader") or "", "media": media,
        "caption": (description or "").strip(),
    }


def _chunk_signature(items):
    return hashlib.sha256("|".join(item["id"] for item in items).encode()).hexdigest()


def chunk_plan(media_items, sent_chunks_json="{}"):
    sent = {int(key): value for key, value in json.loads(sent_chunks_json or "{}").items()}
    plan = []
    for start in range(0, len(media_items), 10):
        number = start // 10
        part = media_items[start:start + 10]
        signature = _chunk_signature(part)
        if sent.get(number) != signature:
            plan.append({"number": number, "signature": signature, "media": part})
    return plan


def download_post(url, download_root, progress_listener=None):
    key = hashlib.sha1(url.encode()).hexdigest()[:16]
    folder = Path(download_root) / key
    folder.mkdir(parents=True, exist_ok=True)
    progress_steps = {}

    def progress_hook(data):
        if progress_listener is None:
            return
        status = data.get("status")
        info = data.get("info_dict") or {}
        filename = Path(data.get("filename") or data.get("tmpfilename") or info.get("id") or "media").name
        key = str(info.get("id") or filename)
        if status == "downloading":
            total = data.get("total_bytes") or data.get("total_bytes_estimate")
            downloaded = data.get("downloaded_bytes") or 0
            if total:
                percent = int(min(100, downloaded * 100 / max(1, total)))
            else:
                fragment_index = data.get("fragment_index")
                fragment_count = data.get("fragment_count")
                if fragment_index and fragment_count:
                    percent = int(min(100, fragment_index * 100 / max(1, fragment_count)))
                else:
                    return
            step = percent // 10
            previous_step = progress_steps.get(key, 0)
            for completed_step in range(previous_step + 1, step + 1):
                progress_listener.onProgress(filename, min(100, completed_step * 10))
            if step > previous_step:
                progress_steps[key] = step
        elif status == "finished" and progress_steps.get(key, 0) < 10:
            progress_steps[key] = 10
            progress_listener.onProgress(filename, 100)

    options = {
        "outtmpl": str(folder / "%(id)s.%(ext)s"), "format": FORMAT,
        "noplaylist": False, "ignoreerrors": False, "quiet": True,
        "no_warnings": True, "retries": 2, "fragment_retries": 2,
        "socket_timeout": 30, "merge_output_format": "mp4",
        "progress_hooks": [progress_hook] if progress_listener is not None else [],
    }
    with yt_dlp.YoutubeDL(options) as ydl:
        info = ydl.extract_info(url, download=True)
        if not info:
            raise RuntimeError("yt-dlp returned no downloaded media")
        entries = _flatten(info)
        paths = []
        for entry in entries:
            candidates = [
                Path(item["filepath"]) for item in entry.get("requested_downloads", [])
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
                "is_video": path.suffix.lower() in (
                    ".mp4", ".m4v", ".mov", ".webm", ".mkv", ".avi",
                    ".3gp", ".mpeg", ".mpg", ".ts", ".mts", ".m2ts",
                    ".ogv", ".vob", ".flv", ".wmv",
                ),
                "width": 0, "height": 0, "duration": 0, "ext": path.suffix.lstrip("."),
            })
    description = info.get("description") or next(
        (entry.get("description") for entry in entries if entry.get("description")), ""
    )
    return json.dumps({
        "files": [str(path) for path in paths], "media": media,
        "caption": (description or "").strip(),
    }, ensure_ascii=False)


def cleanup_post(url, download_root):
    key = hashlib.sha1(url.encode()).hexdigest()[:16]
    shutil.rmtree(Path(download_root) / key, ignore_errors=True)


def create_batch_json(database_directory, text, display_name=None):
    return json.dumps(create_batch(database_directory, text, display_name), ensure_ascii=False)


def list_batches(database_directory):
    return list_batches_json(database_directory)


def delete_batch_json(database_path):
    return json.dumps({"deleted": delete_batch(database_path)})


def sent_chunks_json(database_path, url):
    return json.dumps({str(k): v for k, v in get_sent_chunks(database_path, url).items()})


def chunk_plan_json(media_json, sent_chunks_json_value="{}"):
    return json.dumps(chunk_plan(json.loads(media_json), sent_chunks_json_value), ensure_ascii=False)
