import 'dart:async';

import 'package:offlinechat/mesh/mesh_api.dart';
import 'package:offlinechat/mesh/mesh_models.dart';

const testFingerprint =
    '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef';
const testIdentity = MeshIdentity(
    id: testFingerprint, name: 'Alice', qr: 'test-public-identity-qr');
const testContact =
    MeshContact(id: 'bob-fingerprint', name: 'Bob', verified: true);

MeshSnapshot testSnapshot({
  bool onboarded = true,
  bool running = false,
  bool foreground = false,
  bool supported = true,
  List<MeshContact> contacts = const [],
  List<MeshChat> chats = const [],
  List<MeshPeer> peers = const [],
  MeshSettings settings = const MeshSettings(),
  List<MeshDiagnostic> diagnostics = const [],
}) =>
    MeshSnapshot(
      onboarded: onboarded,
      identity: onboarded ? testIdentity : null,
      contacts: contacts,
      chats: chats,
      peers: peers,
      settings: settings,
      capabilities: MeshCapabilities(
          supported: supported,
          advertising: supported,
          bluetoothOn: true,
          permissionsGranted: true),
      activity: MeshActivity(
          running: running,
          foreground: foreground,
          connections: peers
              .where((peer) =>
                  peer.state == 'authenticated' || peer.state == 'connected')
              .length,
          forwarded: 0,
          queueBytes: 0,
          status: running ? 'Scanning and advertising' : 'Stopped by user'),
      diagnostics: diagnostics,
    );

class TestMeshApi implements MeshApi {
  TestMeshApi(this.current);
  MeshSnapshot current;
  final events = StreamController<MeshSnapshot>.broadcast(sync: true);
  final calls = <String>[];
  final history = <String, List<MeshMessage>>{};
  Future<MeshSnapshot> Function()? onBootstrap;
  Future<MeshSnapshot> Function()? onStart;
  Future<MeshSnapshot> Function(String, String)? onSend;
  String? onboardedName;
  String? importedQr;
  MeshSettings? savedSettings;

  void emit(MeshSnapshot snapshot) {
    current = snapshot;
    events.add(snapshot);
  }

  @override
  Stream<MeshSnapshot> get snapshots => events.stream;
  @override
  Future<MeshSnapshot> bootstrap() async {
    calls.add('bootstrap');
    return onBootstrap == null ? current : await onBootstrap!();
  }

  @override
  Future<MeshSnapshot> onboard(String name) async {
    calls.add('onboard');
    onboardedName = name;
    return current = testSnapshot();
  }

  @override
  Future<MeshContact> validateContact(String qr) async {
    calls.add('validateContact');
    return testContact;
  }

  @override
  Future<MeshSnapshot> addContact(String qr) async {
    calls.add('addContact');
    importedQr = qr;
    return current = testSnapshot(contacts: [testContact]);
  }

  @override
  Future<List<MeshMessage>> messages(String contactId) async {
    calls.add('messages');
    return history[contactId] ?? const [];
  }

  @override
  Future<MeshSnapshot> send(String contactId, String text) async {
    calls.add('send');
    if (onSend != null) return onSend!(contactId, text);
    return current;
  }

  @override
  Future<MeshSnapshot> start() async {
    calls.add('start');
    if (onStart != null) return onStart!();
    return current = testSnapshot(running: true, foreground: true);
  }

  @override
  Future<MeshSnapshot> stop() async {
    calls.add('stop');
    return current = testSnapshot();
  }

  @override
  Future<MeshSnapshot> updateSettings(MeshSettings settings) async {
    calls.add('updateSettings');
    savedSettings = settings;
    return current = testSnapshot(settings: settings);
  }

  @override
  Future<List<MeshDiagnostic>> diagnostics() async {
    calls.add('diagnostics');
    return current.diagnostics;
  }

  Future<void> dispose() => events.close();
}
