# TDLib Instagram Bulk Downloader

Android app prototype combining Python/Chaquopy + yt-dlp for downloading and official TDLib Java/JNI for Telegram uploads.

## Architecture
- **Java + TDLib 1.8.70:** Telegram account authorization, channel resolution, media sending and upload progress.
- **Python + Chaquopy 17 / Python 3.11:** yt-dlp extraction and downloads.
- **Python sqlite3:** persistent URL queue, per-post status, retry count, and per-album chunk checkpoints.
- Media groups are planned in chunks of at most 10 items; already-sent chunks can be skipped after restart.

## Current development stage
This branch introduces the Chaquopy runtime and Python downloader/database layer. The existing TDLib sign-in and single-file upload screen is retained while the bulk queue UI and Java-to-Python/TDLib orchestration are added in subsequent steps.

## Build
GitHub Actions downloads the pinned Android TDLib 1.8.70 build, verifies its SHA-256, then builds a debug APK with Gradle 8.9, Android Gradle Plugin 8.7.3, and Chaquopy 17.0.0.

## Security
Keep Telegram API credentials, login codes, passwords, session files, and private cookies out of source control. Use only destination channels you control.
