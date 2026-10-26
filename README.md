# Bro

Bro is a personal assistant with chat, tasks and reminders, a nutrition diary,
personal metrics, and support for compatible devices. Visit [bro.chromvoid.com](https://bro.chromvoid.com).

This repository is a public source mirror of released Bro clients. Development
and CI run in Forgejo. Each successful release publishes a snapshot commit and a
`release-<source-sha>` tag to GitHub. The `Source-Commit` field in the commit message
identifies the source revision. Internal development history is not mirrored.

## Download

[Latest Android release](https://github.com/chromvoid/bro/releases/latest) ·
[Download APK](https://github.com/chromvoid/bro/releases/latest/download/Bro-Android.apk)

Requires Android 8.0 or later and an ARM64 device. Download the APK and follow the
Android installation prompt. Releases include a SHA-256 checksum and public build
metadata. The Android application ID is `com.chromvoid.bro`. Updates with this ID
use the same signing certificate and install over earlier versions of this package.
The former `dev.assistant.shell` package is a separate installation: sign in again
to the same server account to access your server data. Android does not transfer
local data between different application IDs.

## Source code

- `mobile/` — Kotlin Multiplatform core and native Android client built with Jetpack Compose.
- `web/` — Vue/TypeScript web client; `plugins/*/web` — client integrations.
- `shell/ios/` — iOS client; `shell/ring/` — Rust ring core.
- `firmware/` — ESP-IDF desktop display client.
- `sdk/`, `contracts/`, `plugins/*/package/` — SDKs and integration contracts.
- `tools/android_ci.py`, `tools/android_release.py` — Android build tools.

The clients require an account on a compatible Bro server. The backend,
server-side plugins, and infrastructure remain commercial/proprietary and are not
included in this mirror. Build Android from the Gradle root in `mobile/`; tool and
dependency versions are pinned in the project. For iOS, provide your own
`shell/ios/Firebase/GoogleService-Info.plist`. Signing keys and credentials are not
included in the source; use your own installation settings.

## Licensing

Clients and product UI: **AGPL-3.0-only**. Standalone SDKs and contracts: **MIT**.
See [LICENSING.md](LICENSING.md) for the full license matrix and [LICENSES/](LICENSES/)
for the license texts. Third-party components retain their own terms; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Support: [support@chromvoid.com](mailto:support@chromvoid.com).
