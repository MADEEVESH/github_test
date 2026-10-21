> **Unfinished Mesh v2 checkpoint.** This snapshot does not build and has no verified mesh features. Read [CHECKPOINT_STATUS.md](CHECKPOINT_STATUS.md) before resuming.

# OfflineChat Mesh v2

Android-first, BLE-only messaging with local cryptographic identities,
verified contacts and persistent relay queues. The v2 upgrade is experimental.
Read [BUILD_REPORT.md](BUILD_REPORT.md) for checks actually executed and
[limitations](docs/MESH_LIMITATIONS.md) for unresolved and hardware-only checks.
A successful build is not evidence that three real phones can relay messages.

The Android mesh and the retained Windows v0.2 direct-chat app are different
protocols. The Windows app remains available for its original direct-chat
workflow; it does not join the encrypted Android v2 mesh. Earlier v0.1/v0.2
Android APKs also do not implement the v2 protocol.

## Android setup

The v2 Android target is Android 10 / API 29 or later, compile/target SDK 36.
Use Flutter 3.35.7, Dart 3.9.2, Java 17 or 21, and the Android SDK/NDK reported
by `flutter doctor -v`. Generated Android scaffolding and the dependency lockfile
are included. Development dependency downloads need internet access; installed
mesh messaging uses Bluetooth only and has no backend, account or cloud service.

From this project's directory:

```sh
flutter pub get --enforce-lockfile
flutter analyze
flutter test
cd android
./gradlew :app:testDebugUnitTest
cd ..
flutter build apk --debug
```

On Windows use `gradlew.bat` instead of `./gradlew`. The debug APK is
`build/app/outputs/flutter-apk/app-debug.apk`. Install that same APK on each
test phone. `flutter run` is also available with a USB-debugging device attached.
Configure a separate release signing key before distribution; do not commit
private keys or signing passwords.

Native instrumentation tests, when a compatible Android device or emulator is
attached, run with:

```sh
cd android
./gradlew :app:connectedDebugAndroidTest
```

An emulator can exercise Android database/bridge behavior. It does not establish
BLE advertising, concurrent central/peripheral operation or radio range.

## First use on Android

1. Create a local identity with a display name. Keep the same installation for
   continuity; loss of the AndroidKeyStore key can make encrypted local data
   unrecoverable.
2. Exchange identity QR codes with the intended contact in person, or through
   another trusted channel. Inspect the displayed fingerprint and explicitly
   confirm the contact. A QR self-signature proves key ownership, not a person's
   real-world identity.
3. Open **Mesh** and start the mesh through the user-visible control. Grant the
   Bluetooth permissions required by that Android version. API 29/30 also need
   fine-location permission and Location Services for discovery. API 31+ uses
   Nearby Devices permissions. Notification permission is separate on API 33+.
4. Check real capability, advertising, scanning and foreground-service state.
   A foreground notification alone is not proof of a working BLE connection.
5. Use **Nearby** for compatible peers and **Chats** for verified contacts.
   **Settings** controls relay participation, TTL, hop budget and queue storage.

Default limits are eight hops, a 24-hour message TTL, 1024 UTF-8 plaintext bytes
per message and a 25 MiB relay queue. A multibyte emoji can use more than one
byte. Custody acknowledgment can advance a message to **Relayed**; only a valid
receipt authenticated as the intended recipient can advance it to **Delivered**.
**Expired** means the message's permitted lifetime has ended.

Keep the phone awake during initial diagnostics. Android may restrict or kill
work despite a connected-device foreground service. Force-stop terminates the
app, and the app does not promise delivery while force-stopped. Restart through
the UI before resuming an interrupted physical test.

## Architecture and tests

- [Architecture and wire/security boundaries](docs/MESH_ARCHITECTURE.md).
- [Three-phone and delayed-encounter procedures](docs/PHYSICAL_DEVICE_TEST_PLAN.md).
- [Limitations and requirement status](docs/MESH_LIMITATIONS.md).
- [Executed build/test evidence](BUILD_REPORT.md).

Flutter/Riverpod provides Chats, Nearby, Mesh and Settings. The Android method
and event channels connect the UI to native Kotlin crypto, routing, SQLite and
BLE components. The older Dart BLE controller and plugin dependencies remain
for the Windows direct-chat workflow.

## Retained Windows v0.2 app

The existing portable Windows x64 ZIP is the unencrypted, foreground,
single-peer direct-chat prototype. It can communicate with the old Android
v0.2 application; it cannot exchange v2 encrypted mesh envelopes. It must not
be described as providing end-to-end encryption, routing or store-and-forward.

For that legacy workflow use Windows 10 version 1809 or later or Windows 11,
with a BLE-capable adapter. Extract the complete ZIP and run `offlinechat.exe`
with its DLLs and `data` folder. Building it requires Visual Studio 2022 with
Desktop development with C++, a Windows SDK, and Flutter 3.35.7:

```powershell
.\scripts\build_windows.ps1 -SmokeTest
```

Only a successful Windows build for the selected source revision establishes a
new Windows package. The earlier successful Windows v0.2 build does not validate
new Android mesh code. The original upload's notes are retained as historical
reference in `UPLOAD_README.md`; this README describes the current target.
