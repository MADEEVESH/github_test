# Build and test evidence

## Mesh v2 upgrade

The checkpoint has been resumed and the Android implementation builds. The
checks below executed in the cloud workspace against the implementation in
this branch. They establish automated behavior and packaging, not physical
Bluetooth operation or an independent security audit. The exact source
revision and download hashes are recorded in
[`../builds/OfflineChat-Mesh-build.txt`](../builds/OfflineChat-Mesh-build.txt).

| Check | Result | What it establishes |
| --- | --- | --- |
| `dart format --output=none --set-exit-if-changed lib test` | Passed; 18 files, zero changes | Dart formatting |
| `flutter analyze --no-pub` | Passed; no issues | Dart static analysis |
| `flutter test --no-pub --reporter expanded` | Passed; 50 tests, none skipped | 31 retained direct-app tests and 19 mesh bridge/controller/widget tests |
| `./gradlew :app:testDebugUnitTest` | Passed; 85 tests, zero failures/errors/skips | Native crypto, sessions, framing, routing and SQLite tests |
| `flutter build apk --debug --target-platform android-arm64 --split-per-abi --no-pub` | Passed | Installable arm64 debug APK; local/CI artifact |
| `flutter build apk --release --no-pub` | Passed; approximately 66.3 MB | Universal Android APK for armeabi-v7a, arm64-v8a and x86_64 |
| `python3 scripts/check_android_apk.py build/app/outputs/flutter-apk/app-release.apk` | Passed | ZIP integrity, package/minimum SDK, permissions, v2 signature, 16 KiB ELF and ZIP alignment |
| `./gradlew :app:assembleDebugAndroidTest` | Passed | Instrumentation test APK compilation only; four vault device tests are not executed |
| `./gradlew :app:connectedDebugAndroidTest` | Not run | Requires a supported connected Android target; not inferred from compilation |
| Three-phone A→B→C | Not run | Real isolated multi-hop, recipient decryption and receipt return |
| Delayed-contact restart test | Not run | Real persisted custody across disconnected encounters/restarts |
| Background/permission/battery matrix | Not run | Actual Android service and hardware behavior |

Native test breakdown: 17 IdentityCrypto, 10 PeerSession, 22 ReliableFrames,
27 MeshRouter and 9 SqliteMeshStore tests. Router/session tests use real Tink
cryptography with ephemeral test keys. SQLite tests use Robolectric's native
SQLite engine and real database files, including close/reopen and migration.

Toolchain: Flutter 3.35.7, Dart 3.9.2, Temurin Java 21, Gradle 8.12, Android
Gradle plugin 8.9.1, Kotlin 2.1.0, SDK 36 and NDK 27.0.12077973. The APK is
`dev.offlinechat.offlinechat`, version `2.0.0`/code `3`, minimum SDK 29 and
target SDK 36. Google Tink Android 1.24.0 supplies the cryptographic primitives.

The cloud host has a read-only home directory. The executed native command
used `-I /workspace/toolchains/mesh-test-env.gradle` to give test JVMs a writable
`user.home` and configure the environment's Maven proxy. This is an environment
adapter, not a replacement test implementation or a skipped check. A normal
developer/GitHub runner with a writable home uses the command in the table.

The release manifest explicitly removes Internet permission contributed by a
transitive QR-scanner dependency. The packaged APK check confirms that
`android.permission.INTERNET` is absent. Flutter debug/profile variants retain
development-tooling permission; production message transport is native BLE.
Backup and device transfer of the identity/database are excluded.

The downloadable APK uses a development signing key. `apksigner` verified its
APK Signature Scheme v2 signature. Signing certificate SHA-256:
`2775b5057a0a695c64143d61413ddb7d30b21d08948f982e16b32d2dd7501af2`.
Use a separately managed release key for public distribution. The oversized
debug APK is kept out of Git; the release APK is the direct download.

The new Android Mesh v2 workflow repeats analysis, tests, device-test
compilation and APK checks on GitHub. Local results above do not claim that a
GitHub workflow or an attached-device run has completed.

Crypto/router unit tests may simulate a topology using in-memory transport or
repositories. Those are tests of the implementation; production code must use
real BLE callbacks. Such tests must never be reported as physical Bluetooth
delivery. Device records belong in `docs/PHYSICAL_DEVICE_TEST_PLAN.md`.

## Historical v0.2 baseline

The previous direct-chat application used Flutter 3.35.7 / Dart 3.9.2. Its
recorded baseline was a clean analyzer, 31 Dart tests passing with none skipped,
and an Android release APK built with Temurin 21.0.12.1+1, Gradle 8.12, Android
Gradle plugin 8.9.1, Kotlin 2.1.0, target SDK 36 and minimum SDK 24. That APK was
development-signed and its package/signature/alignment were verified.

The Windows v0.2 workflow for source
`f248528edc200033ad225ada9727cd4101e918cc` completed successfully at
https://github.com/MADEEVESH/github_test/actions/runs/39563321553. It ran its
31 tests, release compile, package checks and launch smoke test. The published
x64 ZIP had SHA-256
`eaf44b9955b4deae87d2e47f617113ecc4ff5dd0a2757b71461b43fe6e443708`.

Those results validate the historical unencrypted direct app. They are not
evidence of v2 encrypted sessions, relaying, SQLite custody or field operation.
Windows remains the legacy direct app unless a separate mesh port is built and
tested. No physical BLE compatibility claim is inherited from either build.
