# OfflineChat downloads

- [Windows x64 ZIP](OfflineChat-Windows-x64.zip): Windows 10 version 1809 or newer / Windows 11. Extract the entire folder and run offlinechat.exe with its DLLs and data. Published after the Windows release workflow succeeds.
- [Android APK 0.2.0](OfflineChat-0.2.0.apk): Android 7.0 or newer; development-signed for sideload testing.
- [Complete Android and Windows source](OfflineChat-source.zip).
- [SHA-256 checksums](SHA256SUMS). Windows ZIP has its own .sha256 file after publication.

On GitHub, open a binary file and use **Download raw file**. The older Android 0.1.0 APK remains available for existing links.

Source is in ../offlinechat. The repository-root Windows release workflow analyzes, tests, compiles and packages on Windows 2022, then records its exact source commit in OfflineChat-Windows-build.txt.

Local analyzer is clean and 31 tests pass; Android 0.2.0 release APK is rebuilt. Mocked tests and hosted-runner launch checks do not validate physical Bluetooth discovery or Android/Windows delivery. Test with two BLE devices.
