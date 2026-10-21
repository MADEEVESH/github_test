# Mesh v2 integration contract

Android implementation lives in `dev.offlinechat.offlinechat.mesh`. Root owns
`MeshContracts.kt`, runtime/coordinator, session authentication, bridge, build
configuration and MainActivity. Agents must preserve these contracts and report
necessary changes. Existing Windows v0.2 direct app stays in its existing files.

Crypto: `IdentityCrypto.forAndroid(context): MeshCrypto`. Test factory may use
ephemeral keys in memory. Use maintained Google Tink Android for HPKE
X25519/HKDF-SHA256/AEAD plus Ed25519 signatures. AndroidKeyStore encrypts private
keysets and local history; never persist clear private keysets. Verify every
identity self-signature and bind both public keys into the fingerprint. QR is
a bounded versioned string, no network links. Envelope signatures cover all
immutable fields and ciphertext. Each hop signs the previous route digest,
immutable envelope digest, from/to identity and index. First edge starts with
the origin sender; all edges chain, forbid repeated nodes and exceed no signed
hop budget. Receiver checks final edge targets itself and last signer matches
the authenticated transport peer. This prevents relays resetting a hop counter.

Store/router agent owns `SqliteMeshStore`, `MeshRouter` and their row types.
Required callable operations: settings/updateSettings, contacts/contact/addContact,
messages(contactId), conversations, queueBytes, forwardedCount, diagnostics/log,
sendText(recipientId,text), receive(packet,authenticatedPeerId), inventory(),
offers(peerId,remoteIds,limit). Constructor router(store,crypto,clock) may vary
but report exact API to root early. SQLite implements FrameJournal. Persist
before custody ACK, enforce expiry/hops/quotas, dedup by id AND immutable digest,
and prioritize direct messages and encrypted signed receipts. Message text is
encrypted locally in history. Only a receipt signed by the original recipient,
bound to original envelope id/digest, can mark Delivered. Router receive result
must indicate accepted/duplicate/delivered/rejected and enough id/digest info
for the coordinator to send custody control. No BLE or Android service in router.

Transport agent owns `BleMeshTransport(context,journal): MeshTransport`, reliable
frame codec/state machine, and `MeshForegroundService`. Service invokes
`MeshRuntime.get(context).startTransport()` / `.stopTransport()` and
`.setForeground(Boolean)`. Runtime will exist in root implementation. Service
public constants ACTION_START, ACTION_STOP and companion start(context),
stop(context) for user-triggered use. Bluetooth runs through actual Android
APIs: simultaneous advertiser/GATT server/scanner, maximum 3 links, address-
specific notifications, serialized GATT ops, MTU sizing, ACK/retry/timeouts,
bounded journal and useful failures. A per-start random advertised short tag
can break central/peripheral connection races. Use a v2 service UUID distinct
from v0.2. Links become ready only after TX notification subscription. Root
performs authenticated session handshake over send/onPayload. No custody or
Delivered inferred from frame ACK. Frame state/journal survives interruption;
full envelope inventory reconciliation repairs interrupted sessions.

Flutter bridge MethodChannel `offlinechat/mesh`, EventChannel
`offlinechat/mesh_events`. Each event and every state-changing call returns
the full snapshot below. UI uses Riverpod 2.6.1, Material3 dark-first, and
separate Chats, Nearby, Mesh, Settings navigation.

Methods: bootstrap; onboard{name}; validateContact{qr}; addContact{qr,verified:true};
messages{contactId}; send{contactId,text}; start; stop; updateSettings{relayEnabled,
maxHops,ttlSeconds,queueLimitBytes}; diagnostics. validateContact returns a contact
preview; messages returns list; diagnostics returns list; other calls snapshot.
Errors are PlatformException with a useful message. UI handles missing native
platform clearly and does not manufacture peers, receipts or messages.

Snapshot:
```
{onboarded:bool, identity:{id,name,qr}?, contacts:[{id,name,verified}],
 chats:[{contactId,name,lastText,lastAt,unread}],
 peers:[{address,id?,name,state,rssi?}],
 settings:{relayEnabled,maxHops,ttlSeconds,queueLimitBytes},
 capabilities:{supported,advertising,bluetoothOn,permissionsGranted,reason?},
 mesh:{running,foreground,connections,forwarded,queueBytes,status},
 diagnostics:[{time,level,message}]}
```
Messages `{id,contactId,text,outgoing,time,status}` use milliseconds and lower-case
pending/relayed/delivered/expired. TTL is seconds in settings. Plaintext max is
1024 UTF8 bytes. Default8hops/24hours/25MiB, minAndroid29. Native requests BLE,
legacy location when API29/30, and optional notification permission through
MainActivity before user-started foreground service. QR camera is managed by
mobile_scanner. Native background events refresh snapshot and Flutter displays
only real state. Windows retains existing direct protocol, no mesh claim there.
