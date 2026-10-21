# Mesh v2 limitations and requirement status

This is an experimental custom protocol. A feature is not verified merely
because a source file or UI control exists. Use `../BUILD_REPORT.md` for exact
executed commands/results and `PHYSICAL_DEVICE_TEST_PLAN.md` for acceptance.

## Security and trust limits

- Google Tink supplies maintained cryptographic primitives. The app's identity
  binding, session transcript, binary codec, routing and receipt protocol have
  not received an independent security audit. Do not label the whole system
  audited, reviewed E2EE solely because it calls Tink.
- QR self-signatures prove consistency and key ownership. Trust in the intended
  person depends on an in-person/trusted-channel QR/fingerprint confirmation.
  Unknown authenticated relays are not automatically verified contacts.
- Relay content is encrypted, but identities, timing, lengths, route/encounter
  metadata and traffic patterns are exposed. There is no anonymous routing or
  general Sybil resistance. A malicious relay can drop/delay traffic and BLE
  can be jammed; no protocol guarantees delivery.
- AndroidKeyStore does not imply every phone has a hardware-backed key. Loss,
  invalidation, reinstall, clear-data or some device migrations can make local
  history/keysets unrecoverable. There is no implemented cross-device private
  identity backup/recovery unless an explicit tested mechanism is added.
- Wall-clock expiry assumes reasonably synchronized offline device clocks.
  There is no internet time source. Bounded clock skew, replay retention and
  expiry cleanup must be tested with actual clocks and process restarts.
- SQLite queue quotas bound retained relay data, not radio airtime or every
  form of denial of service. Malicious inventory floods, disconnect churn and
  storage exhaustion require monitoring and rate-limit validation.

## Platform and operation limits

- The encrypted mesh target is Android 10/API 29+. BLE advertising/peripheral
  support and useful simultaneous central/server operation depend on the
  actual radio, driver, Android build and manufacturer policy. A model list is
  not established without recorded physical tests.
- Android may suspend or terminate the process despite a connected-device
  foreground service. Force-stop prevents operation until the user restarts.
  Battery optimization, Doze and permission changes affect discovery/delivery.
  No continuous-background or guaranteed reconnect claim is made.
- A foreground notification proves service lifecycle only. It does not prove
  advertising, scanning, authenticated peers, decryption or delivery.
- Version 2 is not wire-compatible with the retained unencrypted v0.2 direct
  app. Windows remains that legacy direct app, not an encrypted mesh peer.
  An older published Android APK does not silently gain mesh features.
- Native Android builds and unit tests in Linux cannot establish BLE range,
  concurrent roles, packet timing, peer encounters or field delivery. An
  Android emulator does not replace the required physical phones.
- Legacy v0.2 history used plaintext preferences and has no verified contact
  identity. Any migration must keep it explicitly unverified or archived; it
  must not turn old unverified names into verified encrypted contacts. Existing
  legacy data must not be called encrypted without an actual migration.

## Acceptance matrix

Status at initial documentation draft is **pending v2 implementation
validation**. Historical v0.2 checks are not v2 evidence. Update this table only
after the named checks run; never infer a pass from build completion alone.

| Requirement group | Required evidence | Initial status |
| --- | --- | --- |
| Local identity, QR verification and E2EE | Key-vault/code review; crypto tests for key binding, wrong recipient, tamper and parser bounds | Pending v2 validation |
| No clear private keys at rest | Production storage-path audit and AndroidKeyStore integration test/physical inspection | Pending v2 validation |
| Authenticated sessions | Fresh transcript tests; replay/identity/role mismatch rejection | Pending v2 validation |
| Reliable native BLE transport | Framing/retry unit tests and actual GATT transfer on physical radios | Pending v2 validation; hardware not run |
| Persistent relay store and migrations | SQLite migration/atomic custody/quota/restart tests | Pending v2 validation |
| Multi-hop and store-and-forward | Router tests plus isolated A→B→C and delayed-encounter physical procedures | Pending v2 validation; hardware not run |
| Receipt authentication | Wrong signer/recipient/ID/digest/replay tests; real receipt return path | Pending v2 validation; hardware not run |
| Foreground service, notifications and recovery | Manifest/build checks and device lifecycle/permission/low-battery procedures | Pending v2 validation; hardware not run |
| Flutter/Riverpod screens | Widget/bridge tests and Android UI smoke run | Pending v2 validation |
| Buildable APK | Analyzer, Dart/native tests and debug APK command with nonzero failures preserved | Pending v2 validation |
| Windows preservation | Legacy tests/build compatibility for unchanged Windows UI | Historical v0.2 success; mesh not supported |

## Open verification work

1. Finish implementation integration and record the actual Dart/native test
   counts and Android APK result in `BUILD_REPORT.md`.
2. Run native Android instrumentation on a supported device or emulator for
   database/bridge/lifecycle behavior; label compilation-only separately.
3. Complete the physical three-phone and delayed-contact plans, including the
   no-relay negative control, persisted custody before power interruption, and
   receipt return path while the sender is absent.
4. Independently review the security protocol and operate it under deliberate
   malformed traffic, storage pressure, replay and connection churn before any
   sensitive or public deployment claim.
