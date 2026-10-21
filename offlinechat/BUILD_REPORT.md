# Build validation

OfflineChat 0.2.0+2 targets Android and Windows x64 and retains the original
BLE framing format so both platforms can communicate.

## Local validation

- Flutter 3.35.7 / Dart 3.9.2.
- `flutter analyze`: no issues found.
- `flutter test`: 31 passed, none failed or skipped (11 protocol, 17 controller,
  3 widget tests). Includes Windows radio/permission behavior and Windows UI.
- `flutter build apk --release`: passed with Temurin JDK 21.0.12.1+1,
  Gradle 8.12, Android Gradle plugin 8.9.1, Kotlin 2.1.0, target SDK 36,
  minimum SDK 24. Android remains supported.
- Windows packaging script and workflow PowerShell blocks parsed successfully.

The Android APK is development-signed for sideload testing. The source archive
does not include signing keys. App-store distribution needs a release key.

## Native Windows validation

Linux cannot compile Flutter's Windows runner. The `Windows release` workflow
on the `offlinechat-build` branch builds with Windows 2022 / Visual Studio 2022,
runs analysis and tests, checks the x64 PE executable, bundles the complete
Flutter output and Microsoft VC143 runtime, and runs a process launch smoke
test before publishing `builds/OfflineChat-Windows-x64.zip`.

Only a successful workflow establishes a Windows build. Its generated
`builds/OfflineChat-Windows-build.txt` records the exact source revision and run.
The archive's SHA-256 is saved alongside it. The Windows executable is an
unsigned portable test build; extract the whole folder and retain DLLs/data.

Windows 10 version 1809 or newer, or Windows 11 x64, is required. BLE host mode
also depends on the adapter's peripheral-role support. Hosting on Android and
joining from Windows is the fallback for adapters without advertising support.

Automated tests and the Windows process launch check do not verify real radio
discovery, delivery or interoperability. Use the two-device procedure in
README.md for Android/Windows testing with physical Bluetooth hardware.
