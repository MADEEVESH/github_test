# Mesh v2 limitations and verification boundaries

The Android implementation now includes native BLE transport, encrypted
identities, signed sessions and routes, persistent forwarding, receipt
validation and Flutter screens. This is an experimental custom protocol.
Read [BUILD_REPORT.md](../BUILD_REPORT.md) for exact executed commands/results;
source files, UI controls and successful packaging are not evidence of radio
delivery. Historical v0.2 results do not validate v2.

## Security and trust

- Google Tink Android 1.24.0 supplies raw Ed25519 signatures, HPKE
  X25519/HKDF-SHA256/AES-128-GCM and AES-256-GCM local history encryption. The
  app's identity binding, handshake, codec, signed routes and receipt protocol
  have not received an independent security audit. Passing tests does not make
  the whole system audited E2EE.
- A signed QR document binds keys, name and fingerprint; it does not establish
  the intended person's identity. Contact import requires explicit human
  confirmation through an in-person or trusted-channel QR/fingerprint check.
  A valid authenticated relay is not automatically a verified contact.
- End-to-end encryption protects message content from intermediate relays.
  Sender/recipient fingerprints, public identities, timestamps, lengths,
  routes, encounters and traffic patterns remain exposed. Session controls
  are signed with fresh transcript/sequence binding; inventories and routing
  metadata are not independently encrypted. SQLite contains public metadata
  alongside encrypted history and envelopes.
- There is no message-key ratchet or post-compromise recovery. Compromise of
  a recipient's long-term HPKE private key can expose captured envelopes.
  There is no implemented key rotation/revocation distribution, private
  identity export, cross-device recovery or lost-key recovery workflow.
- AndroidKeyStore wrapping is mandatory with no cleartext fallback. Corrupt or
  partial vault state, a missing/invalidated master key or provider failure
  stops loading. Normal loads also reject an entirely missing vault; only
  explicit initial onboarding can create keysets. Hardware backing depends
  on the device. Clearing app data, reinstalling or losing the master key may
  make identity/history unrecoverable; backup/device-transfer exclusions avoid
  restoring ciphertext without its wrapping key. Real KeyStore instrumentation
  remains unexecuted.
- TTL uses offline wall clocks, permitting at most five minutes of future
  creation skew. Significant clock drift can reject valid packets or affect
  expiry and receipt handling. No Internet time source is used.
- A malicious relay can discard/delay traffic, acknowledge custody dishonestly
  or refuse to forward. Custody never proves recipient delivery. There is no
  anonymous routing, global admission authority, general Sybil resistance,
  radio-jamming protection or delivery guarantee. Local quotas, rate limits
  and backoff cannot eliminate denial of service.

## Capacity, state and recovery

- The relay queue defaults to 25 MiB and is also capped at 4,096 complete
  envelopes, including receipts. Raising the byte setting does not raise that
  fixed count cap. Quota pressure can reject text or its atomic receipt batch;
  the receiver sends no custody acknowledgment for failed admission.
- Queue bytes measure serialized queued packets, not the whole SQLite file.
  Encrypted history, replay bindings, contacts, journals, indexes and SQLite
  overhead consume additional space. Fixed limits include 500 contacts,
  10,000 history records and 100,000 retained ID/digest bindings. History is
  retained after message TTL, and there is no implemented history pruning or
  contact-deletion UI; reaching those fixed bounds can block new admission.
- Fragment ACKs confirm transport progress only. Full payload handling is
  asynchronous, so a final fragment ACK can precede successful full-envelope
  admission. A process interruption between those steps is repaired by
  envelope inventory reconciliation, not by assuming custody. Reconnect uses
  a fresh session and full-envelope retransmission rather than a guaranteed
  continuation at the prior fragment offset. Old fragment journals expire
  after 30 minutes.
- Receipt delivery needs a return path to the original sender. A recipient
  may read text while the sender still shows Pending/Relayed until the signed
  receipt returns. Expiry or queue pressure can prevent that return. Receipt
  authentication cannot guarantee a route exists.
- Inventories are bounded to the admitted queue and exchanged after
  authentication. Protocol/transport failures retain pending envelopes for
  later reconciliation. There is no guarantee of reconnection, fairness,
  throughput or latency in a congested/changing topology; slow-radio and
  interruption behavior still require physical measurement.
- The native mesh v1-to-v2 SQLite migration preserves mesh records and adds
  frame journals. Existing v0.2 direct-chat history remains plaintext in its
  original preferences and is not auto-migrated, re-encrypted or imported as
  verified contacts. Installing v2 does not retroactively encrypt old data.

## Platform and user-visible boundaries

- Encrypted mesh targets Android 10/API 29+. BLE advertising/peripheral
  support, simultaneous central/server operation and practical MTU/connection
  behavior vary by radio, driver, OS and manufacturer. The source checks
  capabilities and caps active links at three. No supported-device model list
  or measured range/throughput is established.
- Camera permission is needed for scanning a contact QR. BLE runtime
  permissions differ by Android version; Android 10/11 also needs location
  permission and Location services for scanning. Notification permission can
  be denied independently. Denial, Bluetooth toggles and radio failures must
  be validated on real devices rather than inferred from permission code.
- The user starts a `connectedDevice` foreground service. Its notification
  reports service participation, not successful peer authentication or
  delivery. The service is non-sticky: Android force-stop, process termination,
  Doze, battery rules and manufacturer restrictions can interrupt operation,
  and restart requires user action. Continuous background delivery is not
  promised.
- Notifications use generic text without message plaintext. The runtime
  currently reports zero unread counts; unread/read tracking is not
  implemented. Receiving a snapshot or notification does not validate the
  entire BLE path.
- Windows retains the existing unencrypted v0.2 direct-chat application. It
  cannot join the Android v2 mesh, exchange v2 encrypted envelopes or relay
  them. V2 uses a distinct BLE service and is not wire-compatible with v0.2.
  An older published Android APK does not acquire these features until a new
  v2 artifact is built and installed. Windows mesh support requires a separate
  native transport/protocol port and verification.
- No mesh Internet, Wi-Fi, mobile-data, cloud or backend fallback is
  implemented. Linux builds/JVM tests cannot verify BLE range, concurrent
  roles, packet timing or actual nearby encounters. Emulator tests can cover
  KeyStore/database integration but do not replace physical radios.

## Evidence by requirement

Automated test source exists for the groups below. Results are intentionally
referenced to [BUILD_REPORT.md](../BUILD_REPORT.md), which records the actual
selected test suites and commands; do not infer a pass from this table.

| Requirement | Implemented mechanism and automated coverage | Remaining evidence |
| --- | --- | --- |
| Identity, QR and E2EE | Tink primitives; both-key fingerprint/self-signature; recipient, tamper, parameter and parser tests | Independent protocol review; physical QR/decryption checks |
| Private keys and history at rest | Explicit KeyStore-encrypted keysets; separate local AEAD; corruption/reload instrumentation tests | AndroidKeyStore instrumentation not run; device storage inspection |
| Authenticated sessions | Fresh mutual nonce/identity transcript; exact-byte signed sequential controls; tamper/replay/fresh-session tests | Actual BLE session/lifecycle exercise |
| Reliable transport | Frame boundaries, fragmentation, ACK/retry, durable journal, quotas and failure tests | Physical GATT client/server, MTU, subscription and disconnect matrix |
| Persistent custody | SQLite transactions, migration/quota/dedup/journal tests; receipt batch admission | Real process/power interruption and restart; hardware custody timing |
| Multi-hop/store-and-forward | Signed edge chain, TTL/hop/loop rules, bounded inventories and router topology tests | Isolated physical A→B→C and delayed encounters not run |
| Delivery receipts | Original-recipient signatures and original ID/digest/time binding; rejection tests | Physical return path while original sender is absent |
| Foreground service and permissions | Native service, manifest, permission bridge, generic notifications and diagnostics | Device background/Doze/force-stop/battery/denial matrix not run |
| Flutter screens and build | Riverpod mesh UI, bridge models and automated tests | Executed analyzer/tests/build results in BUILD_REPORT; device UI smoke |
| Windows preservation | Existing legacy direct route remains separate | New Windows build/smoke result where recorded; v2 mesh unsupported |

## Required next validation

1. Record selected-source analyzer, Dart/native test counts, Android build and
   artifact checks in [BUILD_REPORT.md](../BUILD_REPORT.md). Instrumentation
   compilation must be distinguished from execution.
2. Run `connectedDebugAndroidTest` on a supported Android target for the real
   KeyStore vault tests. Investigate device-specific provider/invalidation
   behavior rather than adding a cleartext fallback.
3. Execute [PHYSICAL_DEVICE_TEST_PLAN.md](PHYSICAL_DEVICE_TEST_PLAN.md), including
   isolated three-phone routing, delayed contact, sender absence, no-relay
   negative controls, queue pressure, interrupted custody and signed receipt
   return. Record phone models/OS versions, clocks and observed outcomes.
4. Complete independent security review and deliberate malformed-traffic,
   replay, connection-churn and resource-exhaustion testing before making a
   sensitive-use or field-readiness claim.
