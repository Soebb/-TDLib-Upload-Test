# TDLib Upload Test

A minimal Android test app using Telegram's official TDLib Java/JNI interface.

## What it tests
- Sign in to a personal Telegram account with phone number, login code, and optional 2-step verification password.
- Connect directly through TDLib/MTProto (no Bot API and no proxy configured by the app).
- Resolve a public channel by username and upload one selected video.
- Show TDLib authorization and upload status messages.

## Build
GitHub Actions downloads a pinned Android TDLib build (TDLib 1.8.70, Java JNI, arm64-v8a / armeabi-v7a / x86_64), verifies its SHA-256, and builds the debug APK.

Open Actions > Build TDLib Upload Test APK > Artifacts to download the APK.

## Setup
1. Create an API ID and API Hash at https://my.telegram.org.
2. Install the APK and enter those values.
3. Enter your phone number in international format and tap Connect / Send login code.
4. Enter the Telegram login code; if prompted, enter your 2-step verification password.
5. Enter a public channel username (for example @mychannel), choose a file, and upload.

The account must already be an administrator of the destination channel with permission to post.

## Security
- API credentials and account session are stored only in the app's private storage; do not share app data or device backups.
- Never publish API credentials, login codes, passwords, or TDLib session files.
- This is a test client, not a production Telegram client. It does not yet implement a remote logout/session wipe control.
- Use only a channel you control for testing.

## TDLib
The app uses TDLib's Java/JNI interface. Native Android binaries are from a pinned TDLib 1.8.70 Android build and are checked against its published SHA-256.
