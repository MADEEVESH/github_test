import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:offlinechat/mesh/mesh_app.dart';
import 'package:offlinechat/mesh/mesh_controller.dart';
import 'package:offlinechat/mesh/mesh_models.dart';
import 'package:qr_flutter/qr_flutter.dart';

import 'mesh_test_support.dart';

Future<void> mountMesh(WidgetTester tester, TestMeshApi api,
    {Size size = const Size(500, 900)}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(ProviderScope(
      overrides: [meshApiProvider.overrideWithValue(api)],
      child: const MeshApp()));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('onboarding creates identity through native API with local name',
      (tester) async {
    final api = TestMeshApi(testSnapshot(onboarded: false));
    addTearDown(api.dispose);
    await mountMesh(tester, api);
    expect(find.text('Create secure identity'), findsOneWidget);
    await tester.tap(find.text('Create secure identity'));
    await tester.pumpAndSettle();
    expect(api.calls, ['bootstrap']);
    await tester.enterText(
        find.byKey(const Key('mesh-onboarding-name')), '  Alice  ');
    await tester.tap(find.text('Create secure identity'));
    await tester.pumpAndSettle();
    expect(api.onboardedName, 'Alice');
    expect(find.byType(NavigationBar), findsOneWidget);
    expect(find.text('Start with a contact'), findsOneWidget);
  });

  testWidgets('contact preview requires explicit trusted-channel confirmation',
      (tester) async {
    final api = TestMeshApi(testSnapshot());
    addTearDown(api.dispose);
    await mountMesh(tester, api);
    await tester.tap(find.byTooltip('Add contact'));
    await tester.pumpAndSettle();
    await tester.enterText(
        find.byKey(const Key('mesh-contact-qr')), 'public-contact-qr');
    await tester.tap(find.text('Preview contact'));
    await tester.pumpAndSettle();
    expect(find.text('Bob'), findsOneWidget);
    expect(api.calls.where((call) => call == 'addContact'), isEmpty);
    final save = find.byKey(const Key('mesh-contact-save'));
    expect(tester.widget<FilledButton>(save).onPressed, isNull);
    await tester.ensureVisible(find.byKey(const Key('mesh-contact-verified')));
    await tester.tap(find.byKey(const Key('mesh-contact-verified')));
    await tester.pumpAndSettle();
    expect(tester.widget<FilledButton>(save).onPressed, isNotNull);
    await tester.tap(save);
    await tester.pumpAndSettle();
    expect(api.importedQr, 'public-contact-qr');
    expect(find.text('Bob'), findsOneWidget);
    expect(find.text('Verify a contact'), findsNothing);
  });

  testWidgets('changing QR invalidates fingerprint confirmation',
      (tester) async {
    final api = TestMeshApi(testSnapshot());
    addTearDown(api.dispose);
    await mountMesh(tester, api);
    await tester.tap(find.byTooltip('Add contact'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('mesh-contact-qr')), 'first');
    await tester.tap(find.text('Preview contact'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const Key('mesh-contact-verified')));
    await tester.tap(find.byKey(const Key('mesh-contact-verified')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('mesh-contact-qr')), 'second');
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('mesh-contact-verified')), findsNothing);
    expect(
        tester
            .widget<FilledButton>(find.byKey(const Key('mesh-contact-save')))
            .onPressed,
        isNull);
    expect(api.importedQr, isNull);
  });

  testWidgets('own QR displays public identity and full fingerprint',
      (tester) async {
    final api = TestMeshApi(testSnapshot());
    addTearDown(api.dispose);
    await mountMesh(tester, api);
    await tester.tap(find.byTooltip('My QR code'));
    await tester.pumpAndSettle();
    expect(find.byType(QrImageView), findsOneWidget);
    expect(find.text(meshFingerprint(testFingerprint)), findsOneWidget);
  });

  testWidgets(
      'failed native start reports error and retains real stopped state',
      (tester) async {
    final api = TestMeshApi(testSnapshot());
    api.onStart = () async => throw PlatformException(
        code: 'bluetooth_off',
        message: 'Enable Bluetooth to start this device.');
    addTearDown(api.dispose);
    await mountMesh(tester, api);
    await tester.tap(find.text('Nearby'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('mesh-toggle')));
    await tester.pumpAndSettle();
    expect(find.text('Enable Bluetooth to start this device.'), findsOneWidget);
    expect(find.text('Mesh sharing is stopped'), findsOneWidget);
    expect(find.text('Start mesh sharing'), findsOneWidget);
    expect(find.text('Mesh sharing is running'), findsNothing);
  });

  testWidgets(
      'conversation shows native status and preserves failed-send draft',
      (tester) async {
    final api = TestMeshApi(testSnapshot(contacts: [testContact]));
    api.history[testContact.id] = [
      for (final status in ['pending', 'relayed', 'delivered', 'expired'])
        MeshMessage(
            id: status,
            contactId: testContact.id,
            text: 'Native $status text',
            outgoing: true,
            time: 1730000000000,
            status: status),
    ];
    api.onSend = (contactId, text) async => throw PlatformException(
        code: 'queue_full', message: 'Encrypted queue is full.');
    addTearDown(api.dispose);
    await mountMesh(tester, api);
    await tester.tap(find.text('Bob'));
    await tester.pumpAndSettle();
    for (final status in ['Pending', 'Relayed', 'Delivered', 'Expired']) {
      expect(find.textContaining('· $status'), findsOneWidget);
    }
    await tester.enterText(
        find.byKey(const Key('mesh-message-input')), 'Keep this draft');
    await tester.pump();
    await tester.tap(find.byKey(const Key('mesh-send')));
    await tester.pumpAndSettle();
    expect(find.text('Encrypted queue is full.'), findsOneWidget);
    expect(
        tester
            .widget<TextField>(find.byKey(const Key('mesh-message-input')))
            .controller!
            .text,
        'Keep this draft');
    expect(find.text('Keep this draft'), findsOneWidget);
  });

  testWidgets('composer counts UTF-8 bytes and prevents oversized send',
      (tester) async {
    final api = TestMeshApi(testSnapshot(contacts: [testContact]));
    addTearDown(api.dispose);
    await mountMesh(tester, api);
    await tester.tap(find.text('Bob'));
    await tester.pumpAndSettle();
    await tester.enterText(
        find.byKey(const Key('mesh-message-input')), '😀' * 257);
    await tester.pumpAndSettle();
    expect(find.text('1028 / 1024 UTF-8 bytes'), findsOneWidget);
    expect(
        tester.widget<IconButton>(find.byKey(const Key('mesh-send'))).onPressed,
        isNull);
    expect(api.calls.where((call) => call == 'send'), isEmpty);
  });

  testWidgets('settings expose defaults and save validated native units',
      (tester) async {
    final api = TestMeshApi(testSnapshot());
    addTearDown(api.dispose);
    await mountMesh(tester, api);
    await tester.tap(find.text('Settings'));
    await tester.pumpAndSettle();
    String value(String key) =>
        tester.widget<TextFormField>(find.byKey(Key(key))).controller!.text;
    expect(value('mesh-hops-setting'), '8');
    expect(value('mesh-ttl-setting'), '1440');
    expect(value('mesh-storage-setting'), '25');
    await tester.enterText(find.byKey(const Key('mesh-hops-setting')), '4');
    await tester.enterText(find.byKey(const Key('mesh-ttl-setting')), '60');
    await tester.ensureVisible(find.byKey(const Key('mesh-storage-setting')));
    await tester.enterText(find.byKey(const Key('mesh-storage-setting')), '10');
    await tester.ensureVisible(find.text('Save settings'));
    await tester.tap(find.text('Save settings'));
    await tester.pumpAndSettle();
    expect(api.savedSettings!.maxHops, 4);
    expect(api.savedSettings!.ttlSeconds, 3600);
    expect(api.savedSettings!.queueLimitBytes, 10 * 1024 * 1024);
  });

  testWidgets('large screens use rail and render actual peer states',
      (tester) async {
    final api =
        TestMeshApi(testSnapshot(running: true, foreground: true, peers: [
      const MeshPeer(
          address: 'native-address',
          id: 'native-peer-fingerprint',
          name: 'Real nearby device',
          state: 'authenticating',
          rssi: -65),
      const MeshPeer(
          address: 'authenticated-native-address',
          id: 'authenticated-peer-fingerprint',
          name: 'Authenticated nearby device',
          state: 'authenticated',
          rssi: -50),
      const MeshPeer(
          address: 'discovered-native-address',
          id: null,
          name: 'Discovered nearby device',
          state: 'nearby'),
    ]));
    addTearDown(api.dispose);
    await mountMesh(tester, api, size: const Size(1300, 900));
    expect(find.byType(NavigationRail), findsOneWidget);
    await tester.tap(find.text('Nearby'));
    await tester.pumpAndSettle();
    expect(find.text('Real nearby device'), findsOneWidget);
    expect(find.textContaining('Authenticating · -65 dBm'), findsOneWidget);
    expect(find.text('Authenticated nearby device'), findsOneWidget);
    expect(find.textContaining('Connected · authenticated · -50 dBm'),
        findsOneWidget);
    expect(find.byIcon(Icons.link), findsOneWidget);
    expect(find.text('Compatible · nearby'), findsOneWidget);
    expect(find.text('Stop mesh sharing'), findsOneWidget);
  });
}
