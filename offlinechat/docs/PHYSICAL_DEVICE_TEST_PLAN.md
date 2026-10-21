# Physical Android test plan

These are acceptance procedures, not a claim that they have been executed.
Record the APK hash, source revision, device models/OS builds, observations,
diagnostic exports and actual pass/fail for each run. Do not use old v0.2
Android or Windows binaries to test the Android v2 mesh.

## Device record

Complete this table before testing. Use at least three physical Android 10+
phones, with a mix of Android 10/11 and Android 12+ permission behavior when
available. All relay nodes need the BLE features required by the runtime.

| Device | Model/OS/security patch | BLE advertising supported | Concurrent scanner/server/client observed | Battery/optimization settings | Result |
| --- | --- | --- | --- | --- | --- |
| A, sender | Not tested | Not tested | Not tested | Record | Not tested |
| B, relay | Not tested | Not tested | Not tested | Record | Not tested |
| C, recipient | Not tested | Not tested | Not tested | Record | Not tested |

Keep Wi-Fi, hotspot, mobile data and any USB network tethering off. Bluetooth
must remain on. Use USB only for installation/log collection and prevent USB
networking. This verifies that no available internet path is helping delivery;
the app itself must not declare INTERNET permission in its merged mesh APK.

Set each device clock close to the others. Do not reduce the normal TTL for
the main relay test. Start each foreground service through its visible UI
control, grant the required permissions and record its actual notification
and Mesh screen state. Disable force-stop/background restrictions only for
the controlled first run; record those settings instead of implying a normal
phone will keep the process alive continuously.

Create distinct local identities named A, B and C. Physically exchange and
confirm A/C contact QR codes. Also exchange B's identity with the tester when
needed to check expected peer fingerprints; a name alone is not proof that a
scan result is the intended device. Never include private keys in test logs.

## Direct baseline

1. Place A and C nearby with B stopped. Observe a real authenticated peer
   connection and exchange a short text in both directions.
2. Send mixed text/emoji whose UTF-8 representation is near 1024 bytes. Verify
   exact content after fragmentation. Attempt 1025 bytes and verify a visible
   rejection without a queued/truncated message.
3. Confirm one history entry per accepted ID. A delivery claim must correspond
   to an original-recipient receipt, not only a relay/fragment acknowledgment.
4. Toggle Bluetooth during a transfer. Verify an explicit failure/pending
   state, no false **Delivered**, and eventual transfer repair after Bluetooth
   is enabled and the devices encounter each other again.
5. Restart both apps and inspect locally persisted history and queue state.

If this baseline fails, diagnose radio/subscription/permissions first. Do not
interpret the next scenario as a successful multi-hop test merely because an
in-memory router test passed.

## Three-phone A → B → C with A and C out of range

1. Choose separated locations for A and C. With B's mesh stopped, leave A/C
   running long enough to establish that they do not discover/authenticate
   with each other. Check both Nearby/Mesh peer lists and logs. Prefer a site
   arrangement with measured distance/barriers and independently repeat this
   isolation check; a one-time absent scan entry is not conclusive range proof.
2. Put B between them where it can communicate with A and C, while preserving
   the A/C isolation. Start B's relay participation and observe its two real
   peer connections. Do not put A and C side-by-side during this run.
3. On A, send a unique text to verified contact C. Record ID and time. Expect
   **Pending**, followed by **Relayed** after B has durable custody. That status
   alone is not a pass for end-to-end delivery.
4. Confirm B's queue/forwarding count changes. B must not gain a readable A/C
   chat bubble or plaintext history. Inspect only bounded metadata and, where
   lawful test tooling permits, a copy of B's app-private test database: stored
   third-party payloads must be ciphertext. Do not weaken production key
   protection to inspect them.
5. On C, verify the exact text once. Check that origin identity is A's verified
   identity and that the route is within the signed hop/TTL policy.
6. Keep B available for the return path. A must become **Delivered** only when
   C's signed receipt returns. Correlate the original message ID/digest with
   receipt handling diagnostics; a B custody event cannot satisfy this step.
7. Repeat with C sending to A and reverse the physical positions to expose
   asymmetric radio behavior. Restore the A/C isolation before each run.
8. Stop B and verify that a new A→C message does not magically arrive. This
   negative control is required to distinguish relay delivery from accidental
   direct radio range.

Record as separate outcomes: direct A/C isolation, A/B transfer, B/C transfer,
recipient authentication/decryption, B ciphertext-only storage, receipt return,
and no-relay negative control. A partial result is not a full mesh pass.

## Delayed encounter and persistent store-and-forward

1. Keep C absent/offline/out of range. Place A with B and send a new unique text
   to C. Wait for B's durable-custody evidence. A may show **Relayed**, never
   **Delivered**.
2. Separate A from B. Gracefully stop B's mesh, close/restart B's app, and
   inspect that its encrypted queue still contains the pending envelope. Do
   not clear data or reinstall B. Restart B's mesh through user action.
3. Move B to C while A remains absent and cannot reach C. Verify inventory
   reconciliation transfers the stored valid envelope and C displays it once.
4. Keep A absent while C's signed receipt is entrusted to B. Restart B again
   after durable receipt custody, then encounter A. Verify A's transition to
   **Delivered** only after receipt authentication.
5. Repeat with a process kill or OS task removal separately from graceful
   stop. Record any loss, duplicate transfer or retry. A force-stopped app
   cannot scan/relay until the user opens and starts it again; that gap is not
   a claim of continuous background operation.
6. Run the same delayed encounter after TTL expiry. The receiver must not
   display an expired new message and the sender must not gain a false
   **Delivered** state. Capture cleanup and expired status diagnostics.

## Policy and adversarial checks

Use a dedicated test build/tool for crafted packets. Do not add a production
UI switch that bypasses validation or simulates a Bluetooth success.

| Scenario | Required observation |
| --- | --- |
| Relay off on B | B refuses new third-party custody/forwarding according to policy; the UI does not claim delivery. Own contact messaging remains governed by the implemented policy. |
| Max hops 1 | A/B direct delivery can work; an A→B→C route exceeding the signed budget is rejected. A relay cannot reset the count or strip/alter proof edges. |
| Expired or future-dated envelope | Reject outside permitted clock/TTL bounds with bounded diagnostics; do not display or acknowledge unpersisted custody. |
| Queue near 25 MiB/default cap | Admission never exceeds the configured cap; expired cleanup is observable. A refused complete envelope receives no custody ACK. |
| Duplicate replay | Repeat an accepted envelope across reconnection/process restart: one message/history entry; same ID with a changed immutable digest rejected. |
| Forged delivery receipt | B signs a receipt for A's message intended for C: A remains Pending/Relayed. A receipt for the wrong ID/digest, wrong recipient or malformed payload is rejected. |
| Corrupt ciphertext/signature | Reject before readable message/history or receipt generation. |
| Unverified/mismatched contact | A spoofed display name or QR/key mismatch cannot establish the intended contact identity. |
| Fragment loss/reorder/duplicate | Bounded timeout/retry or later inventory repair; no truncated plaintext, interleaved message assembly or false Delivered. |
| Interrupted durable transfer | Restart during fragmentation and record whether journal recovery resumes fragments or retries a complete envelope; do not claim untested fine-grained resumption. |
| Permissions denied/revoked | Visible accurate capability/error state; no crash, fabricated peer or unsupported start. Test API 29/30 location and API 31+ Nearby Devices separately. |
| Bluetooth off/on | Disconnection is visible, queued messages persist and subsequent recovery is observed rather than assumed. |
| Low battery/Doze | Record actual mesh/service state and delivery delay. No promise of guaranteed continuous relaying. |
| Unsupported advertiser | Start is refused or accurately degraded; no success state solely from a foreground notification. |

## Sign-off

Store results with source revision/APK SHA-256 and label every item Passed,
Failed, Blocked or Not run. Record logs without private keys or unnecessary
message plaintext. A release must not describe real multi-hop, delivery while
backgrounded, or delayed encounter recovery as field-verified until these
procedures pass on the stated device matrix.
