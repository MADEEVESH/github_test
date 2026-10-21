# OfflineChat

An Android and Windows app for a foreground, two-device chat over Bluetooth
Low Energy. One device hosts; the other scans and joins. Text, display names
and delivery acknowledgments travel over BLE. Nicknames and the latest 200
messages are stored locally. No server or internet connection is used for chat.

## Android build

Use Flutter 3.35.7, Java 17 or 21 and Android SDK platform 36. The app requires
Android 7.0 (API 24) or newer. Dependency downloads need internet access on the
development machine.

```sh
flutter pub get
flutter analyze
flutter test
flutter build apk --release
```

The APK is written to `build/app/outputs/flutter-apk/app-release.apk`.
The generated Flutter release configuration uses the development signing key
for local installation. Configure your own release signing before distributing
through an app store; keep signing keys and passwords outside the source tree.

## Windows download and build

Use Windows 10 version 1809 or later, or Windows 11, x64, with a BLE-capable Bluetooth adapter. Host
mode additionally needs Bluetooth peripheral/advertising support from the
adapter and its driver. If hosting fails, host on the Android phone and use
**Find devices** on Windows instead.

Download `builds/OfflineChat-Windows-x64.zip` from the `offlinechat-build`
branch on GitHub. Extract the complete folder, then run `offlinechat.exe`.
Keep the executable, DLLs and `data` directory together. The ZIP includes
the Visual C++ runtime DLLs; Flutter and Visual Studio are not required to run
the packaged app. This portable test build is not code-signed.

To build locally, install Flutter 3.35.7 and Visual Studio 2022 with **Desktop
development with C++** and a Windows 10/11 SDK. Run from PowerShell:

```powershell
flutter doctor -v
.\scripts\build_windows.ps1 -SmokeTest
```

The script analyzes and tests the app, builds Windows x64, includes the Visual
C++ runtime, checks the complete package, and writes the ZIP and SHA-256 to
`dist`. The optional smoke test launches the packaged app without connecting
to Bluetooth hardware.

The repository's `Windows release` GitHub Actions workflow builds on Windows
and publishes the same ZIP under `builds` on `offlinechat-build`. A successful
workflow is required before treating a Windows ZIP as available. In a full
repository checkout the app is in `offlinechat/`; the active workflow is at
repository-root `.github/workflows/build.yml`.

## Try it on two devices

1. Install the APK on Android, or extract the Windows ZIP on a PC. Use two
   physical devices; Android-to-Windows uses the same BLE wire format as
   Android-to-Android. The host must support BLE advertising/GATT peripheral mode.
2. Enable Bluetooth on both devices. Android needs Nearby Devices permission;
   Android 11 and older may also need location permission and Location Services
   for scanning. Windows hosting uses the peripheral plugin's Location access
   permission check, so enable Location access for desktop apps if requested.
3. Open OfflineChat on both devices and use the pencil button to set names.
4. Tap **Host** on one device and **Find devices** on the other. Select the host.
5. Keep both apps visible. Send text in both directions and check that each
   outgoing message changes to **delivered** after the receiver acknowledges it.
6. Test emoji and a 200-character message, then Stop and reconnect. Restart the
   app to check that local history remains available.

Wi-Fi and mobile data may be disabled during these tests. BLE transport still
requires physical-device validation; a successful build and automated tests do
not establish radio compatibility or delivery on real phones.

## Scope

This is a single-peer prototype. It has no application-layer encryption,
authenticated pairing, automatic reconnect, background delivery, retry protocol
or per-contact histories. Local preferences are not encrypted. Use it for
non-sensitive test messages. The original upload's architecture and device
troubleshooting notes are preserved in `UPLOAD_README.md` as reference material.

## Code

- `lib/main.dart`: Material 3 chat interface.
- `lib/ble_chat_controller.dart`: BLE roles, lifecycle, receipts and persistence.
- `lib/ble_protocol.dart`: bounded JSON fragmentation and reassembly.
- `lib/chat_message.dart`: stored message model.
- `test/`: framing, controller and UI tests.

Generated Android scaffolding is included, so the app can be built directly
without running the setup scripts from the original ZIP. Windows runner and
CMake files are also included in `windows/`. `scripts/build_windows.ps1`
produces a complete portable Windows package.
