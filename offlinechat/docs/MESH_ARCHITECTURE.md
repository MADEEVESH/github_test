# Mesh v2 architecture

This document describes the v2 integration boundary and security design. The
executed-test record is separate in `../BUILD_REPORT.md`; physical behavior is
unverified unless a device-test record explicitly says otherwise.

## Components

| Component | Responsibility |
| --- | --- |
| Flutter `lib/mesh` | Riverpod state, local onboarding, contact QR UI, Chats/Nearby/Mesh/Settings, visible errors and diagnostics |
| Native bridge/runtime | Method/event channels, permission results, lifecycle, peer-session authentication, inventory coordination |
| Native identity/crypto | Key ownership, public identity binding, QR parsing, HPKE, signatures, envelope and route validation, local encryption |
| Native SQLite store | Verified contacts, encrypted history, ciphertext queue, deduplication, custody metadata, frame journal and cleanup |
| Native router | Direct-delivery priority, inventory offers, constrained relaying, receipt validation, TTL/hop/quota policy |
| Native BLE transport | Actual scanner/advertiser, GATT server/client, subscriptions, negotiated payloads, fragmentation, acknowledgment/retry |
| Connected-device foreground service | User-started service and notification, start/stop ownership; no guarantee against Android termination |

Android uses native Kotlin BLE APIs rather than fabricating Dart-level peers.
Windows retains the prior plugin-based direct-chat route and uses another
service/protocol. There is no mesh-compatible Windows implementation in v2.

## Boundaries

```text
Flutter UI -- MethodChannel/EventChannel -- native runtime
                                             |
              identity/crypto <---- router ----+---- peer session
                                      |                 |
                                  SQLite            framed BLE
                                      |                 |
                              durable ciphertext     GATT peers
```

The public method channel is `offlinechat/mesh`; updates arrive on
`offlinechat/mesh_events`. Snapshot data includes real peers, capability state,
foreground status, queue use, settings and diagnostics. Private signing keys,
HPKE private keys and wrapping keys are never sent to Flutter.

## Identity and encryption

A public identity binds an Ed25519 signing public key and an HPKE encryption
public key. Its fingerprint covers both keys. A self-signature binds its
display name and key material. Contact import verifies that binding and asks
the user to confirm the identity through the QR exchange. A display name is
not an authenticated human identity by itself.

The maintained Google Tink Android library supplies Ed25519 and HPKE using
X25519, HKDF-SHA256 and authenticated encryption. The exact Tink version and
selected HPKE template must be recorded with the executed build. The app does
not implement curve arithmetic, KDFs or AEAD primitives itself. This does not
make the custom mesh protocol audited.

Private Tink keysets are encrypted with an AndroidKeyStore-protected wrapping
key before persistence. Local message history is encrypted locally as well.
Relays persist ciphertext and routing metadata. Keystore hardware backing
depends on the device; it is not asserted solely because AndroidKeyStore is
used. Losing or invalidating the wrapping key is an error, not permission to
replace the identity silently.

HPKE authenticates the ciphertext against its immutable header context; an
Ed25519 signature authenticates the sender and the immutable envelope plus
ciphertext. A relay cannot decrypt text intended for another recipient. A
recipient accepts a contact's text only after origin validation and a verified
contact match.

## Envelope and signed route

The bounded, versioned binary envelope contains a unique message ID, sender
identity, recipient fingerprint, creation/expiry times, signed maximum hop
budget, message kind, encrypted body and sender signature. Strict length,
version and trailing-data checks are required before processing untrusted
bytes. IDs are not treated as sufficient proof of equivalent content: a
duplicate ID with another immutable digest is rejected.

A routed packet adds signed edges. Each edge binds the envelope digest,
previous edge digest, signer, intended next custodian and index. The first edge
must begin at the origin. Edges must join, target the receiving device and
identify the authenticated last peer; repeated nodes and an exhausted hop
budget are rejected. This design prevents an intermediary changing a plain
hop counter or stripping a route to deliver an unauthorized shorter path.

Signatures authenticate fields, not wall-clock truth. Each phone evaluates
expiry against its local clock. Material clock drift can reject valid traffic
or delay cleanup; the app does not use an internet time service. Test-device
clocks must be set consistently, and allowed future skew must be bounded.

## Sessions, transfer and custody

Peer session authentication proves possession of signing keys using fresh
challenges bound to the protocol and both identities. A valid self-signed peer
can be a relay without being a verified human contact. Application envelopes
and routing proofs still require independent validation.

BLE link readiness means the real GATT service/characteristics exist and the
notification subscription is established. It does not mean the remote person
is a verified contact. MTU sizing, serialized GATT operations, bounded
fragmentation, explicit acknowledgment, retry/timeouts and disconnect handling
belong to the native transport.

There are three distinct acknowledgment boundaries:

1. A fragment acknowledgment confirms transport progress. It is not custody
   of the complete message and is never delivery.
2. A custody acknowledgment follows durable admission of a valid complete
   envelope to SQLite. It can advance **Relayed** state.
3. An encrypted, signed receipt from the original intended recipient is bound
   to the original message ID and digest. Only this can advance **Delivered**.

Interrupted transfers remain pending. Inventory reconciliation must repair
missed transfers after a later authenticated encounter, without treating
disconnect as successful delivery. Durable queues do not imply guaranteed
Bluetooth reconnection or operation while force-stopped.

## Queue and forwarding policy

The default relay budget is 25 MiB, TTL is 24 hours, and the maximum route has
eight hops. Settings validate permitted bounds. Expired data is cleaned up;
valid direct deliveries and delivery receipts have priority over ordinary
relay traffic. Relay opt-out must stop taking on third-party custody while
allowing the user's own communications as documented by the runtime.

Inventories describe bounded batches of available valid messages; peer
inventory input is not permission to bypass validation or quotas. Duplicate
IDs, repeated routes, expired envelopes, excess hops and conflicting digests
must not create a new queue entry. Replay/deduplication records must be retained
long enough that an accepted envelope cannot become new again within its
allowed lifetime. Queue pressure and admission failure are visible errors;
no custody acknowledgment is sent for data that was not persisted.

## Threat limits

Content encryption does not hide sender/recipient identifiers, public keys,
timestamps, length, encountered peers, route proofs or local traffic volume.
A malicious relay can drop or delay traffic, refuse to forward or exhaust
radio airtime. There is no global admission authority, anonymous routing,
Sybil resistance or delivery guarantee. Quotas and backoff bound local work;
they do not solve radio jamming or denial of service.

The custom handshake, envelope, receipts, framing and routing require an
independent security review before sensitive use. A maintained cryptographic
library and passing unit tests do not constitute such a review.
