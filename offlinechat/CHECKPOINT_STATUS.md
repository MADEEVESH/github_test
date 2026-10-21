# Mesh v2 unfinished checkpoint

This is an **incomplete development snapshot**, not a buildable or verified
Mesh v2 release. Worker agents stopped at an account usage limit. No Mesh v2
APK was built, and no secure multi-hop or BLE hardware test passed.

The working Android and Windows v0.2 downloads remain on `offlinechat-build`.
The local working project at `/workspace/offlinechat` was restored from release
commit `1e817d38fb1be4b40a2c1cceef9061101dba8b62`. This separate checkpoint
preserves the upgrade work so it can be resumed without breaking that release.

## Saved work, not verified as features

- Repository audit and shared native/Flutter integration contract.
- Native Kotlin contracts, bounded envelope codec, frame reliability code,
  router and store interface drafts.
- Flutter bridge, state models and controller drafts.
- Draft Android min-SDK/dependency/manifest changes and native Tink selection.
- Architecture, limitations and three-phone/delayed-contact test-plan drafts.
- Updated dependency lockfile for the planned Riverpod/QR/scanner UI.

## Missing before this can build or be called Mesh v2

- Real Tink cryptography implementation and AndroidKeyStore key vault.
- SQLite store, migrations, durable frame journal and associated tests.
- Native scanner/advertiser/GATT implementation and foreground service.
- Authenticated peer-session handshake and runtime/router orchestration.
- Android method/event channel bridge and permission integration.
- MeshApp and complete onboarding/chats/nearby/mesh/settings screens.
- Native and Flutter tests for the new implementation, security review,
  analysis, Android builds, and physical A–B–C/delayed-contact validation.

The draft `main.dart` references a MeshApp file that has not been written.
The draft manifest references a foreground service that has not been written.
Preserve existing working Windows direct messaging and keep it separate from
the new Android-only v2 protocol. Custom mesh protocol security has not been
audited, and self-signed QR key binding alone is not human contact verification.

## Resume

When execution quota is available, use this checkpoint as the upgrade source
and begin by reconciling it with `docs/MESH_IMPLEMENTATION_CONTRACT.md`.
Finish and test the missing production implementations; do not replace them
with simulated messaging or label this snapshot as a working encrypted mesh.
