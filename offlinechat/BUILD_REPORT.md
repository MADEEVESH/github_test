> **Unfinished Mesh v2 checkpoint.** This snapshot does not build and has no verified mesh features. Read [CHECKPOINT_STATUS.md](CHECKPOINT_STATUS.md) before resuming.

# Build and test evidence

## Mesh v2 upgrade

The v2 implementation is being integrated. The commands below are required
checks, not completed results at this initial documentation stage. Replace
Pending only after the selected source executes the command successfully.

| Check | Result | What it establishes |
| --- | --- | --- |
| `flutter analyze` | Pending v2 run | Dart static analysis |
| `flutter test` | Pending v2 run | Executed Dart/widget suite, with counts recorded separately |
| `./gradlew :app:testDebugUnitTest` | Pending v2 run | Native JVM tests actually selected by Gradle |
| `flutter build apk --debug` | Pending v2 run | Android Kotlin/Dart/manifest/resources compile and APK packaging |
| APK manifest/signature/ZIP checks | Pending v2 run | Packaged permissions, signing and artifact integrity |
| `./gradlew :app:assembleDebugAndroidTest` | Pending v2 run | Instrumentation test APK compilation only |
| `./gradlew :app:connectedDebugAndroidTest` | Not run | Requires a supported connected Android target; not inferred from compilation |
| Three-phone A→B→C | Not run | Real isolated multi-hop, recipient decryption and receipt return |
| Delayed-contact restart test | Not run | Real persisted custody across disconnected encounters/restarts |
| Background/permission/battery matrix | Not run | Actual Android service and hardware behavior |

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
