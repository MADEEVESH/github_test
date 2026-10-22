# Mesh v2 checkpoint resumed

The unfinished checkpoint has been replaced by an integrated Android Mesh v2
implementation. It builds and passes 50 Flutter tests and 85 native tests.
An installable development-signed release APK and a source ZIP are provided
in [builds](../builds). See [the app README](README.md),
[executed checks](BUILD_REPORT.md) and
[limitations](docs/MESH_LIMITATIONS.md).

Implemented source includes native BLE scanner/advertiser/GATT client/server,
reliable framing, authenticated sessions, Tink encryption/signatures, an
AndroidKeyStore vault, SQLite queue/history/journals, routing and recipient
receipts, a user-started foreground service, and the Flutter/Riverpod UI.

Physical Android BLE transfer, isolated A→B→C, delayed encounters, camera and
background behavior have NOT been verified on real phones. Four AndroidKeyStore
instrumentation tests compile but have NOT run on a device/emulator. The custom
protocol has not received an independent security audit. Passing builds and
unit tests do not establish those properties.

Follow [the device test plan](docs/MESH_V2_TEST_PLAN.md) next.
Windows remains the separate v0.2 direct-chat application. Its existing download
and the earlier Android v0.2 release remain on the `offlinechat-build` branch.
The old unfinished ZIP is retained only as a historical archive; use the new
Mesh 2.0.0 APK/source downloads for this implementation.
