# OfflineChat — BLE-only Android-first Flutter MVP

**What it does:** one phone hosts a BLE GATT chat service; a second phone scans and joins. Either person can send text in both directions with no Wi-Fi, internet, mobile data, backend, account, or router. Includes on-device persisted chat history, short-message fragmentation/reassembly, names, and receiver ACKs.

**Important scope:** two foreground Android phones, one client per host. This proof-of-concept has no application-layer encryption, no authenticated pairing or invitation, no background delivery, no mesh routing, and no reliable resumption after interruption. Not ready for public distribution or sensitive messages.

## 1. Prerequisites

- Flutter SDK + Android toolchain and `flutter` available in your terminal.
- Android Studio SDK tools or VS Code with Flutter tooling.
- Two physical Android phones supporting Bluetooth Low Energy; one must support BLE advertising/GATT peripheral mode.
- USB debugging enabled on the phone used to build/test via `flutter run`.
- Android 7.0 / API 24 or later (configured in setup script).
- Internet **only for your development computer** when running `flutter pub get`; the installed application communicates fully offline.

## 2. One-command project setup

On Windows PowerShell, extract ZIP and run from its top-level directory:

```powershell
powershell -ExecutionPolicy Bypass -File .\setup_windows.ps1
cd offlinechat
flutter doctor
flutter pub get
flutter test
flutter run
```

On macOS/Linux:

```bash
bash setup_unix.sh
cd offlinechat
flutter pub get
flutter test
flutter run
```

The setup script runs `flutter create` to generate official Android Gradle, Kotlin, launcher, and wrapper files. Then it copies all custom Dart files, manifest, package manifest, and tests into that new Flutter project and sets minimum Android SDK to 24. This is intentional: Flutter's generated platform scaffolding is tied to your installed Flutter version.

### Manual setup, if the script does not work

```bash
flutter create --platforms=android --org dev.offlinechat offlinechat
```

Copy `source/pubspec.yaml`, `source/lib/`, `source/test/`, `source/analysis_options.yaml`, and `source/android/app/src/main/AndroidManifest.xml` to the same paths in `offlinechat`. Delete the generated `offlinechat/test/widget_test.dart` (it tests the default Flutter counter app). In `android/app/build.gradle.kts`, set `minSdk = 24` (if using Groovy `build.gradle`, set `minSdkVersion 24`). Run `flutter pub get` then `flutter run`.

## 3. Two-phone procedure

1. Turn **Bluetooth ON** on both Android phones. Turn **Wi-Fi OFF** and **Mobile Data OFF** if you want to verify that the app uses only Bluetooth. On Android 10/11 and earlier, location services may also need to be ON for BLE discovery (the app never uses that location).
2. Install the same app on both phones. Easiest: run `flutter build apk --release` and copy `build/app/outputs/flutter-apk/app-release.apk` to both phones. Android may ask you to allow app installs from your files app. You can also install using `flutter install` with one USB device at a time.
3. Launch OfflineChat on **Phone A**. Tap pencil and change its name to **Alice**. Tap **Host**. Grant the Android Nearby Devices permission. Screen says *Hosting. Waiting for someone to join...*.
4. Launch OfflineChat on **Phone B**. Rename to **Bob**. Tap **Find phones**. Grant Nearby Devices permission. Wait for `OfflineChat host` in the list. Tap it.
5. Keep both apps in the foreground until both show *Connected*. A will show Bob and B will show Alice after the hello handshake.
6. On **Phone B**, send `Hello Alice`. Phone A should display it. A sends an automatic ACK, causing B's status to change from `sent` to `delivered`.
7. On **Phone A**, reply `Hello Bob`. B should receive it and A should show `delivered`.
8. Send an emoji and a longer message (100–200 characters) to exercise packet fragmentation.
9. On either phone tap **Stop**, then try reconnecting. Chat history should remain saved locally across restarts.

The host might not be discoverable if its Bluetooth radio cannot advertise. Some devices cannot scan and advertise simultaneously reliably. This MVP explicitly separates host and client roles.

## 4. Project architecture

```
Phone A (BLE Peripheral / Host)        Phone B (BLE Central / Join)
BLE advertisement [service UUID]  -->  Scan filtered to service UUID
GATT RX (write characteristic)    <--  writeCharacteristic()
GATT TX (notify characteristic)   -->  subscribe to notifications
            Text + hello + ACK frames sent in both directions
```

Files:

- `lib/main.dart`: Material 3 UI, local chat view, host/scan/join/stop buttons.
- `lib/ble_chat_controller.dart`: BLE roles, permissions, GATT connection, messaging, ACKs, local persistence.
- `lib/ble_protocol.dart`: custom service UUID, Nordic UART TX/RX, JSON framing into chunks no larger than 18 bytes, reassembly.
- `lib/chat_message.dart`: data model.
- `android/app/src/main/AndroidManifest.xml`: Android Bluetooth / legacy location permissions.
- `test/ble_protocol_test.dart`: round-trip tests for the framing format.

Dependencies are pinned for reproducibility:

- `flutter_ble_central: 1.1.0` (central scanning, GATT connect and write)
- `flutter_ble_peripheral: 3.1.0` (GATT server, advertising and notifications)
- `shared_preferences: ^2.5.3` (on-device nickname and chat history)

The peripheral's default Nordic UART characteristic UUIDs are used. Messages are JSON maps, framed in 18-byte packets using first/last flags; a FIFO queue avoids interleaving on each device. ACKs are **application-level receipts**, not encrypted identities or read receipts. Chat history in this MVP is a single local timeline; it is not per-peer partitioned.

## 5. Troubleshooting

- **No host in scan list:** restart Bluetooth; bring phones within 1–2 meters; both in foreground; host is on Phone A and scan on B; grant Nearby Devices; if Android 10/11, enable Location Services; wait a few seconds. Try a different Phone A if advertising is unsupported.
- **Permission denied:** Settings → Apps → OfflineChat → Permissions → Nearby devices → Allow, then reopen the app. For older Android, enable Location.
- **GATT error 133/disconnect:** Stop on both, toggle Bluetooth, relaunch, reverse the host/client phones, and re-test.
- **Shows connected but cannot send:** connect again so central subscribes to TX; some Android vendors aggressively suspend background processes, so keep both apps open.
- **No response:** use `flutter run -v` or `adb logcat` for native Bluetooth diagnostics. Confirm the `serviceUuid`, `uartTxUuid`, and `uartRxUuid` are identical on both phones.
- **Package API changed:** keep versions pinned to those in `pubspec.yaml`; run `flutter clean && flutter pub get`.
- **Gradle, Java, or SDK errors:** run `flutter doctor -v` and install its reported missing Android SDK/Java components.

## 6. Security and limitations

- **Anyone in Bluetooth range with compatible client code may try to connect.** Do not send passwords, private files, or secrets in this prototype.
- It does NOT implement end-to-end encryption, authenticated keys, or connection admission rules.
- BLE range is hardware/obstacle dependent and often short indoors.
- It does not automatically reconnect, retry failed messages, provide delivery while devices are offline, create a mesh, or receive while the process is killed.
- Only test one connecting phone per host. The peripheral package can notify multiple subscribed centrals, but this single-conversation protocol has no per-user routing.
- Local history is stored in the app's preferences (not an encrypted database).

Before publishing: authenticated key exchange (e.g. reviewed crypto protocol with identity verification), E2EE, bonded/approved peers, per-contact storage, packet sequence numbers/timeouts/retries, robust connection lifecycle, background services/notifications under Android policy, automated end-to-end physical-device tests, Play Store policies, and privacy disclosures.

## 7. Source documentation

- https://pub.dev/packages/flutter_ble_central
- https://pub.dev/packages/flutter_ble_peripheral
- https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview

**Verification note:** This archive contains the full source overlay and scripts, but the app has not been compiled or field-tested in this environment because the Flutter SDK and two Android test phones are not available here. Run `flutter test`, `flutter analyze`, and the physical-device test to verify it on your toolchain.
