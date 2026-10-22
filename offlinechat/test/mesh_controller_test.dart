import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:offlinechat/mesh/mesh_controller.dart';
import 'package:offlinechat/mesh/mesh_models.dart';

import 'mesh_test_support.dart';

Future<void> settleController() => Future<void>.delayed(Duration.zero);

void main() {
  late TestMeshApi api;
  late MeshController controller;

  setUp(() {
    api = TestMeshApi(testSnapshot(contacts: [testContact]));
    controller = MeshController(api);
  });
  tearDown(() async {
    if (controller.mounted) controller.dispose();
    await api.dispose();
  });

  test('bootstraps authoritative native state and applies peer events',
      () async {
    await settleController();
    expect(controller.state.snapshot!.activity.running, isFalse);
    api.emit(testSnapshot(running: true, foreground: true, peers: [
      const MeshPeer(
          address: 'actual-address',
          id: null,
          name: 'Nearby mesh device',
          state: 'authenticating'),
    ]));
    expect(controller.state.snapshot!.peers.single.state, 'authenticating');
    expect(controller.state.snapshot!.activity.running, isTrue);
  });

  test('a slow bootstrap cannot overwrite a newer native event', () async {
    await settleController();
    final pending = Completer<MeshSnapshot>();
    api.onBootstrap = () => pending.future;
    final reload = controller.reload();
    api.emit(testSnapshot(running: true));
    pending.complete(testSnapshot(running: false));
    await reload;
    expect(controller.state.snapshot!.activity.running, isTrue);
  });

  test('a mutation response cannot overwrite a newer native event', () async {
    await settleController();
    final pending = Completer<MeshSnapshot>();
    api.onStart = () => pending.future;
    final start = controller.start();
    api.emit(testSnapshot(running: true, foreground: true));
    pending.complete(testSnapshot());
    expect(await start, isTrue);
    expect(controller.state.snapshot!.activity.running, isTrue);
    expect(controller.state.busy, isFalse);
  });

  test('UTF-8 limit allows 256 emoji and rejects 257 before native send',
      () async {
    await settleController();
    expect(meshTextBytes('😀' * 256), 1024);
    expect(await controller.send(testContact.id, '😀' * 256), isTrue);
    expect(await controller.send(testContact.id, '😀' * 257), isFalse);
    expect(api.calls.where((call) => call == 'send'), hasLength(1));
    expect(controller.state.error, contains('1024 UTF-8 bytes'));
  });

  test('native failures retain actual stopped state with useful error',
      () async {
    await settleController();
    api.onStart = () async => throw PlatformException(
        code: 'bluetooth_off',
        message: 'Enable Bluetooth, then start sharing.');
    expect(await controller.start(), isFalse);
    expect(controller.state.snapshot!.activity.running, isFalse);
    expect(controller.state.error, 'Enable Bluetooth, then start sharing.');
    expect(controller.state.busy, isFalse);
  });

  test('rejects invalid name and settings without invoking native mutation',
      () async {
    await settleController();
    expect(await controller.onboard('a' * 33), isFalse);
    expect(await controller.updateSettings(const MeshSettings(maxHops: 0)),
        isFalse);
    expect(await controller.updateSettings(const MeshSettings(ttlSeconds: 59)),
        isFalse);
    expect(
        await controller.updateSettings(const MeshSettings(queueLimitBytes: 1)),
        isFalse);
    expect(api.calls, ['bootstrap']);
  });

  test('late native completions after dispose are safely ignored', () async {
    await settleController();
    final pending = Completer<MeshSnapshot>();
    api.onStart = () => pending.future;
    final start = controller.start();
    controller.dispose();
    pending.complete(testSnapshot(running: true));
    expect(await start, isFalse);
  });

  test('missing message status is rejected rather than claiming Pending', () {
    expect(
        () => MeshMessage.fromMap({'id': 'envelope'}), throwsFormatException);
  });
}
