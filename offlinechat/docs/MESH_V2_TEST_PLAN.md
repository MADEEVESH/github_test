# Mesh v2 verification and physical-device test plan

Android Mesh v2 uses real BLE discovery, advertising, GATT and native routing.
Windows retains the existing direct-chat implementation and cannot join the v2
Android mesh. A passing desktop/native unit test does not verify a radio link.

**Physical-device checks below have NOT RUN in the cloud environment.** Record
device model, Android version, build commit, APK SHA-256, elapsed times and
diagnostics for every run. Do not label the APK hardware verified until the
three-phone and delayed-encounter cases pass on real devices.

## Automated checks

Run these commands from the Flutter project directory, `offlinechat`:

```sh
flutter pub get
dart format --output=none --set-exit-if-changed lib test
flutter analyze
flutter test
cd android
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Alternatively, build the installable debug APK from the Flutter directory:

```sh
flutter build apk --debug
```

The Flutter APK is `build/app/outputs/flutter-apk/app-debug.apk`. Native test
reports are under `build/app/reports/tests/testDebugUnitTest/` in this project
(Flutter redirects Android build output). Use the paths printed by Gradle if
the local build-directory configuration differs. A successful build alone is
not evidence that the BLE scenarios below pass.

Native router tests use real Tink HPKE and Ed25519, with ephemeral test keys;
the in-memory test store does not replace production SQLite. SQLite tests use
Robolectric's native SQLite engine, actual database files and close/reopen:

| Test area | Required result |
| --- | --- |
| A → B → C and return receipt | B holds ciphertext and cannot decrypt; A becomes Delivered only after C's signed receipt returns |
| Delayed encounter/restart | The same durable envelope reaches C after B's router/database restart |
| Forged receipts | A different signer, wrong original digest or out-of-lifetime delivery time cannot mark Delivered |
| Replay/dedup | The same ID and immutable digest creates one message/history/receipt; an ID reused for different ciphertext is rejected |
| TTL/hops/loops | Expired messages are not offered; exhausted routes and repeated peers are refused; signed hop history cannot be reset |
| Relay participation | Disabling forwarding retains existing ciphertext and still permits this device's own direct messages/receipts |
| Quotas/transactions | Both the byte budget and 4096-envelope budget include generated receipts; a failed recipient commit stores neither message nor receipt and sends no custody success |
| Durable fragments | Contiguous inbound fragments and outbound ACK progress survive reopen; conflicting chunks/ACK jumps are refused |
| Cleanup/migration | Live fragment prefixes survive cleanup; stale transfers are reclaimed; v1 → v2 migration preserves ciphertext/history and adds journal tables |
| Local confidentiality | Relay queue and history file contain no test message plaintext; history ciphertext binds displayed metadata/status |
| Clock skew/receipt lifetime | Small permitted skew does not invalidate delivery proof; a recipient's short default TTL does not prematurely expire the return receipt |

Default queue storage is 25 MiB of encoded envelopes. The independent hard
limit is 4096 queued envelopes, including receipts, so a complete compact
inventory fits a synchronization cycle. Raising the byte limit does not raise
this count limit. Expired queue records are reclaimed; history is independently
bounded to 10,000 rows, verified contacts to 500 and replay tombstones to
100,000. A bounded store refuses new custody when full instead of silently
discarding accepted ciphertext. Historical custody does not suppress a retry
when a peer's fresh authenticated inventory lacks the envelope.

## Prepare three Android phones

1. Use three physical Android 10+ phones, A, B and C. Confirm each supports BLE
   scanning, peripheral advertising and simultaneous central/peripheral roles.
   Install the same debug APK on all three. An emulator is not a substitute.
2. Put all phones in airplane mode, then turn Bluetooth back on. Leave Wi-Fi
   and mobile data off. On Android 10/11, enable system Location services for
   BLE scanning and grant the requested location permission. On Android 12+,
   grant Nearby devices. Grant notification permission when requested on
   Android 13+; record the behavior if it is denied.
3. Complete local onboarding with recognizable names: Alice (A), Relay (B)
   and Carol (C). Open **My QR code** on A and C. Each scans the other's QR
   through **Add contact**, previews it, compares the entire fingerprint in
   person, checks the confirmation and adds the verified contact. B does not
   import either contact. This proves B need not be trusted with plaintext.
4. Keep defaults: relay enabled, eight hops, 24-hour TTL, 25 MiB byte quota.
   Start the mesh through the app's explicit Start control on each phone.
   Confirm the foreground notification and Mesh service status agree.
5. First perform a direct A ↔ C sanity check nearby. Confirm both sides can
   exchange a message and a return receipt. Preserve diagnostics, then use
   fresh uniquely numbered messages for the relay cases.

## Prove A → B → C without an A ↔ C radio path

Use physical separation, walls or RF shielding. A missing Nearby entry alone
does not prove radio isolation. As a control, stop B and confirm a new A → C
message stays Pending for at least two minutes while A and C remain stationary.
Do not clear app data after contact verification.

1. Leave A and C in the isolated positions and start B between them, within
   usable BLE range of both. Verify Nearby/diagnostics show authenticated A–B
   and B–C connections and no A–C authenticated connection. If the control
   message delivers, preserve its full timestamps and use another new message
   for the measured run.
2. Send `mesh-live-001` from A's chat with C. A must first show Pending. After
   B durably accepts it, A may show Relayed. A must not show Delivered merely
   because B acknowledges custody or a BLE frame is ACKed.
3. C must display exactly one `mesh-live-001` from verified A. B must show no
   readable chat or notification body containing it. B's forwarding count
   should increase when downstream custody is confirmed; repeated custody
   must not count the same peer transfer again.
4. Allow C's encrypted, signed receipt to return C → B → A. Only then must A
   show Delivered. Record connection identities, queue sizes, status changes
   and receipt return time. Receipt traffic also uses the relay queue.
5. Repeat from C to A. Repeat with Unicode text and a message close to the
   1024-byte UTF-8 limit. Verify an oversized message is refused without a
   false Pending/Delivered chat entry.
6. Stop B and send another fresh message. It must remain Pending until B
   resumes and forwarding succeeds. Disabling B's relay participation must
   similarly prevent forwarding, while preserving ciphertext already queued.

If A reaches C directly at any stage, discard that run as evidence of multi-hop
radio delivery. The stricter delayed case below avoids simultaneous reachability.

## Prove delayed-contact store-and-forward and the return receipt

Keep A and C outside direct radio range for the whole test. B acts as a courier;
it does not require an internet connection or a verified chat contact.

1. Start A and B together, with C far away and not reachable. Send
   `mesh-delayed-001` from A to C. Wait until A shows Relayed and B's queue
   bytes increase. C must have no copy yet. Save diagnostics.
2. Stop the mesh on B, close its activity, and restart B's process without
   clearing its app data. Reopen it and confirm its identity fingerprint and
   nonzero queue are retained. Explicitly start the mesh again if necessary.
   This process restart is a persistence check, not a claim of operation while
   force-stopped.
3. Turn A's Bluetooth off and move B to C, outside A's range. Keep the original
   message within its TTL. Start B and C and wait for authenticated discovery.
   C must receive `mesh-delayed-001` exactly once. B must still be unable to
   display its plaintext. C's receipt must be queued for A.
4. Keep A unreachable while C receives the message. A must retain Relayed;
   C's local delivery cannot update A until the signed receipt reaches it.
5. Stop C's mesh or move away from C. Take B back to A, turn A's Bluetooth on
   and start A's mesh. A must become Delivered only when C's stored receipt
   returns through B. Repeated encounters must not duplicate C's chat bubble.
6. Repeat with a 60-second message TTL and wait beyond 60 seconds before the
   B–C encounter. C must receive no message. A should become Expired when its
   app next performs cleanup. Queue bytes must fall after expiry; a short
   replay tombstone remains temporarily for clock-skew protection.
7. Repeat with A's TTL at 24 hours and C's default TTL at 60 seconds. Deliver
   to C, wait longer than 60 seconds, then return B to A while the original
   message is valid. The receipt must still return and mark Delivered.

## Failure and platform checks

All rows require physical-device execution; none were run in the cloud.

| Action | Expected behavior |
| --- | --- |
| Deny Nearby devices/location | Start is blocked with a useful reason; no fabricated peer or successful mesh state |
| Disable Android 10/11 Location services | Explain scanning requirement and recover after re-enabling |
| Device lacks advertising | Capability warning; no promise that it can join the mesh |
| Toggle Bluetooth during a long transfer | Connection failure is visible; no false Delivered; durable envelope retries after reconnection |
| Background/lock a phone with mesh started | Foreground notification remains while service operates; record actual OEM behavior and duration |
| Enable battery saver/restrict the app | Record actual interruptions; no guarantee of continuous background operation |
| Stop through app/notification | BLE stops and queue remains; app state reflects stopped service |
| Force-stop the app | No continuous-delivery promise; reopening and explicitly restarting recovers retained queue/identity |
| Reboot a phone | Identity/history/queue survive; user action restarts mesh; no unsupported auto-start claim |
| Exhaust byte/count quota | Sender sees deferral/full queue; accepted history and receipts remain; retry succeeds after legitimate expiry/reclamation |
| Revoke camera permission | QR scanner explains failure; signed public QR text can still be pasted and verified |
| Rotate activity/reopen chat | Existing service and identity continue; no invented messages, duplicate service or duplicated bubbles |
| Two/three links, mixed Android releases | Observe GATT operation ordering, MTU negotiation, fragment ACK/retry and disconnect recovery |
| Clear app data/reinstall | A new identity is expected; old ciphertext/private keys are not restored by a backend |

Keep logs free of private keys and message plaintext. Record the actual result
as PASS, FAIL or NOT RUN with a linked issue for failures. Hardware advertising
support, vendor GATT behavior, radio range, Android foreground-service policy,
notification permission and battery restrictions remain real-device validation
items even when all automated checks pass.
