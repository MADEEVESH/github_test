# OfflineChat downloads

## Android Mesh 2.0.0 (experimental)

- [Installable Android APK](OfflineChat-Mesh-2.0.0.apk): Android 10/API29 or later; includes ARM32, ARM64 and x86_64. Development-signed release build.
- [Mesh source ZIP](OfflineChat-Mesh-2.0.0-source.zip): extract, open the `offlinechat` subdirectory, and follow its README.
- [Source revision, validation and limitations](OfflineChat-Mesh-build.txt).
- [Mesh SHA-256 checksums](OfflineChat-Mesh-SHA256SUMS).

50 Flutter tests and 85 native tests pass; debug/release APKs build. The release APK was checked for no Internet permission, ZIP integrity, signing and 16 KiB ELF/ZIP alignment. Physical BLE A→B→C, delayed encounters, camera/background behavior and four AndroidKeyStore device tests remain unrun. The custom protocol has not received an independent security audit. See [the device test plan](../offlinechat/docs/MESH_V2_TEST_PLAN.md).

On GitHub, open an APK/ZIP and select **Download raw file**. The same APK must be installed on every Android mesh phone; create identities, verify contact QR fingerprints, and start mesh sharing through the visible control.

## Retained v0.2 direct-chat downloads

- [Windows x64 ZIP](https://github.com/MADEEVESH/github_test/raw/refs/heads/offlinechat-build/builds/OfflineChat-Windows-x64.zip): Windows 10 version1809+ or Windows11. Extract the entire folder and keep `offlinechat.exe`, DLLs and `data` together. It does not join the Android encrypted mesh.
- [Android v0.2 APK](OfflineChat-0.2.0.apk): Android7+; legacy unencrypted direct messaging, compatible with that Windows app.
- [Legacy source](OfflineChat-source.zip) and [legacy checksums](SHA256SUMS).

The v0.2 Windows workflow previously passed its build and launch checks; those results do not establish real BLE delivery or validate Mesh v2. The older v0.1 APK and unfinished Mesh checkpoint ZIP are retained for existing links/history. Use the new Mesh2.0.0 files above for this implementation.
