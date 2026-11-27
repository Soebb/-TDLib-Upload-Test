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

## Build and releases

The repository includes a GitHub Actions workflow that builds a **debug APK** when relevant project files are pushed to the `instagram-bulk-tdlib-chaquopy` branch, or when the workflow is started manually.

After a successful build, the workflow automatically creates a **GitHub Release** and attaches the debug APK to it. Each workflow run/attempt gets its own release tag. You can download the APK from the repository's **Releases** page. The workflow also uploads the APK as a normal Actions artifact.

The workflow uses Java 17, Python 3.11, Android SDK 35, Gradle 8.13, Chaquopy 17, and a pinned TDLib 1.8.70 Android build whose SHA-256 is verified before compilation.

To build locally, open the project in Android Studio with the required Android SDK installed and run the Gradle `assembleDebug` task.

## Telegram setup and required information

To use the app, prepare all of the following:

1. **API ID and API Hash:** create/get these credentials at [my.telegram.org](https://my.telegram.org).
2. **Phone number:** enter the mobile number of the Telegram account you want the app to use, including the international country calling code (for example, `+1...` or `+49...`).
3. **Login verification code:** after submitting the phone number, Telegram sends a login code to an authorized Telegram session or through the delivery method Telegram provides. Enter that code in the app. If Telegram requests an additional two-step verification password, that will also be needed.
4. **Destination channel address:** enter the username or address of the Telegram channel where the media should be posted (for example, `@your_channel` or its supported channel link). The Telegram account you sign in with must already be a member of that channel and have permission to post messages/media there (typically administrator posting permission).

The app signs in as your **Telegram user account** through TDLib; this is not a bot-token login. Never share your login code or two-step verification password.

Keep API credentials, verification codes, session data, and any private cookies out of source control. Use only accounts and destination channels you are authorized to access.

## Important notes

- Download availability and quality depend on Instagram's access rules and the source post. Private, restricted, removed, or otherwise inaccessible posts may fail.
- A timeout means the upload result may be unknown; it does **not** prove that Telegram rejected the media. Always inspect the channel before manually retrying an item marked for review.
- The app stores its queue databases and downloaded media in its private app storage. Uninstalling the app or clearing its data may remove that local state.
