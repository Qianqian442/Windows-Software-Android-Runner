# Implementation Plan and Account Rules

## User-approved account behavior

- Do not poll the account server every 10 seconds.
- Validate only when needed: registration, sign-in, protected actions, device/session changes, token expiry, suspicious activity, or access to protected cloud resources.
- If registration cannot reach the network, create and store a local pending account record first.
- Require at least one contact method: QQ or WeChat. They are alternatives; both are not required.
- Keep the pending-sync state visible. Never label a local-only record as a completed cloud registration.
- When an HTTPS account service is connected, retry pending records after connectivity returns and handle duplicate/conflict responses without overwriting an existing account.
- Avatar selection should use the phone's image picker, not require a manually entered image URL.

## Current implementation status

The Android app now has a local-first registration/profile form and persists its pending state on the device. File imports are copied into app-private storage and recorded with size and SHA-256. Incoming single-file and multi-file Android shares are accepted.

**Cloud synchronization is not connected yet.** This repository does not currently contain an HTTPS account API, server endpoint, or paired administrator APK. The app therefore keeps accounts marked as pending and does not claim that they have synchronized.

## Planned milestones

1. **Import foundation:** accept shares from QQ, WeChat, and file managers; persist files locally; show metadata and hashes; never auto-execute unknown content.
2. **Package inspection:** identify PE/installer/archive/plugin file types, CPU architecture, dependencies, and relevant metadata.
3. **Administrator/account service:** define the HTTPS API and paired administrator APK; implement authenticated sync, duplicate/conflict handling, device management, and account status.
4. **Runtime integration:** evaluate and integrate a legally distributable Windows compatibility runtime and CPU translation layer. Android cannot natively execute Windows PE binaries. Compatibility will be tested per software category and device.
5. **Release pipeline:** build main and administrator APKs, publish artifacts, and verify the generated APK files before calling a release complete.

## Security and honesty constraints

- Never execute an imported file automatically.
- Keep user files on-device by default; do not upload them as part of account sync.
- Use HTTPS for any cloud account communication.
- Keep cloud validation demand-driven; do not add a fixed 10-second polling loop.
- Treat offline registration as pending until the server confirms synchronization.
- Do not promise that every Windows application or AE plugin will work on Android.
