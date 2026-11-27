# TDLib Instagram Bulk Uploader

An Android app for downloading Instagram posts and sending their photos and videos to a Telegram channel in batches. It combines **yt-dlp** for Instagram media extraction, **Chaquopy/Python** for downloading and queue management, and the official **Telegram TDLib** for Telegram account authorization and uploads.

> This is a development project. Instagram access and Telegram upload behavior can vary by account, network, and platform changes.

## How it works

1. Paste Instagram post/reel URLs into the app or import a `.txt` file containing URLs. A separate SQLite database is created for each imported batch.
2. The app processes queued URLs one by one. **yt-dlp** extracts the post's media and downloads the available photos/videos to the app's private storage.
3. Java passes the downloaded media to TDLib. Photos are sent as Telegram **photos** and videos as Telegram **videos** (not as documents).
4. Media from a post is sent in Telegram media groups of up to **10 items** per group. The Instagram URL is placed on the first caption line, followed by the extracted post description. A manually entered caption can override the extracted description.
5. The app waits for TDLib's upload result and records progress and completion in SQLite. Successfully sent groups are checkpointed so completed groups can be skipped when a post is resumed.

## Main features

- Telegram personal-account authorization through TDLib.
- Instagram URL extraction from pasted text and TXT files.
- Single local-file upload and queued bulk Instagram uploads.
- Photo/video detection and the matching Telegram media type.
- Per-batch SQLite databases with URL status, attempt count, errors, and sent-group checkpoints.
- Queue recovery after app restart: interrupted work can be resumed; failed items can be retried.
- Separate statuses for pending, processing, completed, failed, and uncertain uploads.
- **Uncertain upload protection:** if Telegram's result cannot be confirmed (for example, an upload stalls), the app does not automatically delete Telegram messages or retry the post. It marks the item for review.
- **Review uncertain uploads:** inspect the destination channel, then mark the post as uploaded or explicitly choose to retry. Retrying may create duplicates if some media had already reached Telegram.
- Completed databases can be deleted; databases with unfinished or uncertain items are protected from deletion.

## Architecture

| Component | Responsibility |
|---|---|
| Java + TDLib 1.8.70 | Telegram authorization, channel handling, sending photos/videos, and upload-state updates |
| Python 3.11 + Chaquopy 17 | yt-dlp extraction/downloads and bulk workflow logic |
| Python `sqlite3` | Persistent batches, URL statuses, retry information, and media-group checkpoints |
| Android app | Login, URL/TXT input, destination channel, captions, progress, logs, and review controls |

## Build

The repository includes a GitHub Actions workflow that builds a **debug APK** when changes are pushed to the `instagram-bulk-tdlib-chaquopy` branch (for relevant project files), or when the workflow is started manually.

The workflow uses Java 17, Python 3.11, Android SDK 35, Gradle 8.13, Chaquopy 17, and a pinned TDLib 1.8.70 Android build whose SHA-256 is verified before compilation.

To build locally, open the project in Android Studio with the required Android SDK installed and run the Gradle `assembleDebug` task. GitHub Actions artifacts are available from the repository's **Actions** tab.

## Telegram setup

The app requires Telegram API credentials (**API ID** and **API Hash**) from [my.telegram.org](https://my.telegram.org). Sign in with the Telegram account that will upload the media, then provide the destination channel username. The account must have permission to post in that channel.

Keep API credentials, verification codes, session data, and any private cookies out of source control. Use only accounts and destination channels you are authorized to access.

## Important notes

- Download availability and quality depend on Instagram's access rules and the source post. Private, restricted, removed, or otherwise inaccessible posts may fail.
- A timeout means the upload result may be unknown; it does **not** prove that Telegram rejected the media. Always inspect the channel before manually retrying an item marked for review.
- The app stores its queue databases and downloaded media in its private app storage. Uninstalling the app or clearing its data may remove that local state.
