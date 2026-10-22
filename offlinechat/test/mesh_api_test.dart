import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:offlinechat/mesh/mesh_api.dart';
import 'package:offlinechat/mesh/mesh_models.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('mesh-test-rpc');
  const api = ChannelMeshApi(methodChannel: channel);
  final calls = <MethodCall>[];
  final snapshot = <String, Object?>{
    'onboarded': true,
    'identity': {'id': 'local-id', 'name': 'Alice', 'qr': 'public-qr'},
    'contacts': <Object?>[],
    'chats': <Object?>[],
    'peers': <Object?>[],
    'settings': const MeshSettings().toMap(),
    'capabilities': {
      'supported': true,
      'advertising': true,
      'bluetoothOn': true,
      'permissionsGranted': true,
    },
    'mesh': {
      'running': false,
      'foreground': false,
      'connections': 0,
      'forwarded': 0,
      'queueBytes': 0,
      'status': 'Stopped by user',
    },
    'diagnostics': <Object?>[],
  };

  setUp(() {
    calls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      switch (call.method) {
        case 'validateContact':
          return {'id': 'bob-id', 'name': 'Bob', 'verified': false};
        case 'messages':
          return [
            {
              'id': 'envelope-id',
              'contactId': 'bob-id',
              'text': 'Native history',
              'outgoing': true,
              'time': 1730000000000,
              'status': 'relayed',
            },
          ];
        case 'diagnostics':
          return [
            {
              'time': 1730000000000,
              'level': 'info',
              'message': 'Radio stopped'
            },
          ];
        default:
          return snapshot;
      }
    });
  });
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test(
      'channel matches native contract including human-verified import and units',
      () async {
    expect((await api.bootstrap()).identity!.qr, 'public-qr');
    await api.onboard('Alice');
    expect((await api.validateContact('contact-qr')).verified, isFalse);
    await api.addContact('contact-qr');
    final history = await api.messages('bob-id');
    expect(history.single.status, 'relayed');
    await api.send('bob-id', 'Secret message');
    await api.start();
    await api.stop();
    await api.updateSettings(const MeshSettings());
    expect((await api.diagnostics()).single.message, 'Radio stopped');
    expect(calls.map((call) => call.method), [
      'bootstrap',
      'onboard',
      'validateContact',
      'addContact',
      'messages',
      'send',
      'start',
      'stop',
      'updateSettings',
      'diagnostics',
    ]);
    expect(calls[1].arguments, {'name': 'Alice'});
    expect(calls[2].arguments, {'qr': 'contact-qr'});
    expect(calls[3].arguments, {'qr': 'contact-qr', 'verified': true});
    expect(calls[4].arguments, {'contactId': 'bob-id'});
    expect(
        calls[5].arguments, {'contactId': 'bob-id', 'text': 'Secret message'});
    expect(calls[8].arguments, {
      'relayEnabled': true,
      'maxHops': 8,
      'ttlSeconds': 86400,
      'queueLimitBytes': 25 * 1024 * 1024,
    });
  });

  test('preserves actionable platform errors instead of returning fake state',
      () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
            channel,
            (call) async => throw PlatformException(
                code: 'unsupported',
                message: 'BLE advertising is unavailable.'));
    await expectLater(
        api.start(),
        throwsA(isA<PlatformException>().having((error) => error.message,
            'message', 'BLE advertising is unavailable.')));
  });
}
