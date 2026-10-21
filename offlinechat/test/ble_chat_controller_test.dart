import 'dart:async';
import 'dart:convert';
import 'dart:io' show Platform;

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:offlinechat/ble_chat_controller.dart';
import 'package:offlinechat/ble_protocol.dart';
import 'package:shared_preferences/shared_preferences.dart';

const _address = 'AA:BB:CC:DD:EE:FF';
const _centralMethod = 'dev.steenbakker.flutter_ble_central/method';
const _peripheralMethod = 'dev.steenbakker.flutter_ble_peripheral/ble_state';
const _scanEvent = 'dev.steenbakker.flutter_ble_central/scan_result';
const _connectionEvent = 'dev.steenbakker.flutter_ble_central/connection_state';
const _notificationEvent =
    'dev.steenbakker.flutter_ble_central/characteristic_value';
const _receiveEvent =
    'dev.steenbakker.flutter_ble_peripheral/ble_data_received';
const _subscriptionEvent =
    'dev.steenbakker.flutter_ble_peripheral/ble_subscription_changed';

Future<void> _flush() async {
  // Let platform responses, stream callbacks, the transmit queue, and preference
  // writes finish. These are unit tests with no physical Bluetooth dependency.
  for (var i = 0; i < 4; i++) {
    await Future<void>.delayed(Duration.zero);
  }
}

class _BlePlatform {
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final centralCalls = <MethodCall>[];
  final peripheralCalls = <MethodCall>[];
  final centralFrames = <Uint8List>[];
  final peripheralFrames = <Uint8List>[];
  final listening = <String>{};
  Future<Object?> Function(MethodCall)? centralOverride;
  Future<Object?> Function(MethodCall)? peripheralOverride;

  static const eventChannels = [
    _scanEvent,
    _connectionEvent,
    _notificationEvent,
    _receiveEvent,
    _subscriptionEvent,
  ];

  void install() {
    messenger.setMockMethodCallHandler(const MethodChannel(_centralMethod),
        (call) async {
      centralCalls.add(call);
      if (call.method == 'writeCharacteristic') {
        final args = call.arguments as Map;
        centralFrames
            .add(Uint8List.fromList((args['value'] as List).cast<int>()));
      }
      if (centralOverride != null) return centralOverride!(call);
      return centralResponse(call);
    });
    messenger.setMockMethodCallHandler(const MethodChannel(_peripheralMethod),
        (call) async {
      peripheralCalls.add(call);
      if (call.method == 'sendData') {
        final args = call.arguments as Map;
        peripheralFrames
            .add(Uint8List.fromList((args['data'] as List).cast<int>()));
      }
      if (peripheralOverride != null) return peripheralOverride!(call);
      return peripheralResponse(call);
    });
    for (final name in eventChannels) {
      messenger.setMockMethodCallHandler(MethodChannel(name), (call) async {
        if (call.method == 'listen') listening.add(name);
        if (call.method == 'cancel') listening.remove(name);
        return null;
      });
    }
  }

  Object? centralResponse(MethodCall call) {
    switch (call.method) {
      case 'isSupported':
      case 'isBluetoothOn':
      case 'enableBluetooth':
        return true;
      case 'requestPermission':
        return 0; // CentralBluetoothState.granted
      case 'start':
        return 8; // CentralBluetoothState.ready
      case 'stop':
        // The plugin uses invokeMethod<bool> for this call on Windows, where
        // the native implementation actually returns null.
        return Platform.isWindows ? null : 8;
      case 'getConnectionState':
        return 2; // GattConnectionState.connected
      case 'discoverServices':
        return [
          {
            'uuid': offlineChatServiceUuid,
            'isPrimary': true,
            'characteristics': <Object>[]
          },
        ];
      case 'connect':
      case 'disconnect':
      case 'setCharacteristicNotification':
      case 'writeCharacteristic':
        return null;
      default:
        throw StateError('Unexpected central method: ${call.method}');
    }
  }

  Object? peripheralResponse(MethodCall call) {
    switch (call.method) {
      case 'isSupported':
      case 'isAdvertising':
      case 'isBluetoothOn':
      case 'enableBluetooth':
      case 'requestLocationPermission':
      case 'hasLocationPermission':
        return true;
      case 'isSubscribed':
        return false;
      case 'requestPermission':
        return 0; // PeripheralBluetoothState.granted
      case 'start':
      case 'stop':
        return 8; // PeripheralBluetoothState.ready
      case 'sendData':
        return null;
      default:
        throw StateError('Unexpected peripheral method: ${call.method}');
    }
  }

  Future<void> emit(String channel, Object value) async {
    await messenger.handlePlatformMessage(
      channel,
      const StandardMethodCodec().encodeSuccessEnvelope(value),
      (_) {},
    );
    await _flush();
  }

  Future<void> subscription(bool value) =>
      emit(_subscriptionEvent, {'anySubscribed': value});

  Future<void> receive(Map<String, dynamic> message) async {
    for (final frame in BleFramer.encode(message)) {
      await emit(_receiveEvent, {'data': frame});
    }
  }

  Future<void> notify(
    Map<String, dynamic> message, {
    String address = _address,
    String serviceUuid = offlineChatServiceUuid,
    String characteristicUuid = uartTxUuid,
  }) async {
    for (final frame in BleFramer.encode(message)) {
      await emit(_notificationEvent, {
        'address': address,
        'serviceUuid': serviceUuid,
        'characteristicUuid': characteristicUuid,
        'value': frame,
      });
    }
  }

  void uninstall() {
    messenger.setMockMethodCallHandler(
        const MethodChannel(_centralMethod), null);
    messenger.setMockMethodCallHandler(
        const MethodChannel(_peripheralMethod), null);
    for (final name in eventChannels) {
      messenger.setMockMethodCallHandler(MethodChannel(name), null);
    }
  }
}

List<Map<String, dynamic>> _decode(List<Uint8List> frames) {
  final reader = BleReassembler();
  final messages = <Map<String, dynamic>>[];
  for (final frame in frames) {
    final message = reader.accept(frame);
    if (message != null) messages.add(message);
  }
  return messages;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late _BlePlatform platform;
  late BleChatController chat;

  setUp(() {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    SharedPreferences.setMockInitialValues({});
    platform = _BlePlatform()..install();
    chat = BleChatController();
  });

  tearDown(() async {
    chat.dispose();
    await _flush();
    platform.uninstall();
    debugDefaultTargetPlatformOverride = null;
  });

  test('restores nickname/history and persists incoming text with an ACK',
      () async {
    SharedPreferences.setMockInitialValues({
      'nickname': 'Alice',
      'messages': jsonEncode([
        {
          'id': 'saved',
          'text': 'Earlier',
          'outgoing': false,
          'time': '2026-01-01T00:00:00.000Z',
          'status': 'received',
        },
      ]),
    });
    await chat.restore();
    expect(chat.nickname, 'Alice');
    expect(chat.messages.single.text, 'Earlier');
    await chat.startHost();
    await platform.subscription(true);
    platform.peripheralFrames.clear();

    await platform
        .receive({'kind': 'text', 'id': 'incoming', 'text': 'Hello 🌍'});
    // A repeated frame is acknowledged again without duplicating history.
    await platform
        .receive({'kind': 'text', 'id': 'incoming', 'text': 'Hello 🌍'});

    expect(chat.messages.map((m) => m.text), ['Earlier', 'Hello 🌍']);
    expect(_decode(platform.peripheralFrames), [
      {'kind': 'ack', 'id': 'incoming'},
      {'kind': 'ack', 'id': 'incoming'},
    ]);
    final prefs = await SharedPreferences.getInstance();
    final saved = jsonDecode(prefs.getString('messages')!) as List;
    expect(saved.length, 2);
    expect((saved.last as Map)['text'], 'Hello 🌍');
  });

  test(
      'joins the advertised service and rejects notifications from other routes',
      () async {
    await chat.restore();
    await chat.startScan();
    expect(chat.scanning, isTrue);
    final start = platform.centralCalls.firstWhere((c) => c.method == 'start');
    expect((start.arguments as Map)['serviceUuids'], [offlineChatServiceUuid]);
    await chat.join(_address);
    expect(chat.canSend, isTrue);
    final subscribe = platform.centralCalls
        .firstWhere((c) => c.method == 'setCharacteristicNotification');
    expect(subscribe.arguments, {
      'address': _address,
      'serviceUuid': offlineChatServiceUuid,
      'characteristicUuid': uartTxUuid,
      'enable': true,
    });
    platform.centralFrames.clear();
    final incoming = {'kind': 'text', 'id': 'one', 'text': 'Valid'};
    await platform.notify(incoming, address: 'another phone');
    await platform.notify(incoming, serviceUuid: 'another service');
    await platform.notify(incoming, characteristicUuid: uartRxUuid);
    expect(chat.messages, isEmpty);
    expect(platform.centralFrames, isEmpty);

    await platform.notify(incoming,
        serviceUuid: offlineChatServiceUuid.toUpperCase());
    expect(chat.messages.single.text, 'Valid');
    expect(
        _decode(platform.centralFrames).single, {'kind': 'ack', 'id': 'one'});
  });

  test('joining failure releases the native connection and subscriptions',
      () async {
    platform.centralOverride = (call) async {
      if (call.method == 'discoverServices') return <Object>[];
      return platform.centralResponse(call);
    };
    await chat.join(_address);
    expect(chat.mode, 'idle');
    expect(chat.connected, isFalse);
    expect(chat.busy, isFalse);
    expect(chat.status, contains('No GATT services discovered'));
    expect(
        platform.centralCalls
            .where((c) => c.method == 'disconnect')
            .single
            .arguments,
        {'address': _address});
    expect(platform.listening, isEmpty);
  });

  test('disconnect resets the peer and invalidates queued host fragments',
      () async {
    await chat.startHost();
    await platform.subscription(true);
    await platform.receive({'kind': 'hello', 'name': 'Bob'});
    expect(chat.peerName, 'Bob');
    platform.peripheralFrames.clear();
    final entered = Completer<void>();
    final release = Completer<Object?>();
    var holdNext = true;
    platform.peripheralOverride = (call) async {
      if (call.method == 'sendData' && holdNext) {
        holdNext = false;
        entered.complete();
        return release.future;
      }
      return platform.peripheralResponse(call);
    };
    final sending = chat.sendText('A' * 200);
    await entered.future;
    await platform.subscription(false);
    expect(chat.peerName, 'Nearby device');
    expect(chat.connected, isFalse);
    await platform.subscription(true);
    release.complete(null);
    await sending;
    await _flush();

    expect(chat.messages.single.status, 'failed');
    // Exactly the one in-flight old packet precedes the replacement greeting;
    // no remaining old-message fragments reach the replacement connection.
    expect(platform.peripheralFrames.length, greaterThan(1));
    expect(platform.peripheralFrames[1][0] & BleFramer.first, isNonZero);
    expect(_decode(platform.peripheralFrames), [
      {'kind': 'hello', 'name': 'Android User'},
    ]);
    expect(chat.status, 'Device connected. You can chat.');
  });

  test('client link loss resets the displayed peer', () async {
    await chat.join(_address);
    await platform.notify({'kind': 'hello', 'name': 'Bob'});
    expect(chat.peerName, 'Bob');
    await platform.emit(_connectionEvent, {'address': _address, 'state': 0});
    expect(chat.connected, isFalse);
    expect(chat.canSend, isFalse);
    expect(chat.peerName, 'Nearby device');
    expect(chat.status, contains('Connection lost'));
  });

  test('dispose stops hosting and cancels all platform event subscriptions',
      () async {
    await chat.startHost();
    await platform.subscription(true);
    var notifications = 0;
    chat.addListener(() => notifications++);
    chat.dispose();
    final atDisposal = notifications;
    await _flush();
    expect(platform.peripheralCalls.where((c) => c.method == 'stop'),
        hasLength(1));
    expect(platform.listening, isEmpty);
    expect(chat.canSend, isFalse);
    await platform.receive({'kind': 'text', 'id': 'late', 'text': 'Too late'});
    expect(chat.messages, isEmpty);
    expect(notifications, atDisposal);
  });

  test('dispose disconnects a joined client and stops scanning', () async {
    await chat.join(_address);
    final stopsBefore =
        platform.centralCalls.where((c) => c.method == 'stop').length;
    chat.dispose();
    await _flush();
    expect(platform.centralCalls.where((c) => c.method == 'stop').length,
        stopsBefore + 1);
    expect(
        platform.centralCalls
            .where((c) => c.method == 'disconnect')
            .single
            .arguments,
        {'address': _address});
    expect(platform.listening, isEmpty);
  });

  test('dispose during permission lookup prevents a later native start',
      () async {
    final entered = Completer<void>();
    final permission = Completer<Object?>();
    platform.peripheralOverride = (call) async {
      if (call.method == 'requestPermission' ||
          call.method == 'requestLocationPermission') {
        entered.complete();
        return permission.future;
      }
      return platform.peripheralResponse(call);
    };
    var notifications = 0;
    chat.addListener(() => notifications++);
    final starting = chat.startHost();
    await entered.future;
    chat.dispose();
    final atDisposal = notifications;
    permission.complete(Platform.isWindows ? true : 0);
    await starting;
    await _flush();
    expect(platform.peripheralCalls.where((c) => c.method == 'start'), isEmpty);
    expect(chat.mode, 'idle');
    expect(chat.connected, isFalse);
    expect(notifications, atDisposal);
  });

  test('dispose waits out an in-flight native start before stopping it',
      () async {
    final entered = Completer<void>();
    final startResult = Completer<Object?>();
    platform.peripheralOverride = (call) async {
      if (call.method == 'start') {
        entered.complete();
        return startResult.future;
      }
      return platform.peripheralResponse(call);
    };
    final starting = chat.startHost();
    await entered.future;
    chat.dispose();
    startResult.complete(8);
    await starting;
    await _flush();
    final methods = platform.peripheralCalls.map((c) => c.method).toList();
    expect(methods.last, 'stop');
    expect(methods.where((m) => m == 'isAdvertising'), isEmpty);
    expect(chat.mode, 'idle');
    expect(platform.listening, isEmpty);
  });

  test('pending restore does not notify or mutate a disposed controller',
      () async {
    SharedPreferences.setMockInitialValues({'nickname': 'Persisted name'});
    var notifications = 0;
    chat.addListener(() => notifications++);
    final restoring = chat.restore();
    chat.dispose();
    await restoring;
    expect(chat.nickname, 'Android User');
    expect(notifications, 0);
  });

  void useWindows() {
    chat.dispose();
    debugDefaultTargetPlatformOverride = TargetPlatform.windows;
    chat = BleChatController();
  }

  test('Windows uses its own default nickname and keeps a saved display name',
      () async {
    useWindows();
    expect(chat.nickname, 'Windows User');
    await chat.restore();
    expect(chat.nickname, 'Windows User');
    await chat.changeName('Desktop Alice');
    chat.dispose();
    chat = BleChatController();
    await chat.restore();
    expect(chat.nickname, 'Desktop Alice');
  });

  test('Windows discovery checks the radio even when permission is granted',
      () async {
    useWindows();
    platform.centralOverride = (call) async {
      if (call.method == 'isBluetoothOn' || call.method == 'enableBluetooth') {
        return false;
      }
      return platform.centralResponse(call);
    };
    await chat.startScan();
    expect(chat.mode, 'idle');
    expect(chat.scanning, isFalse);
    expect(chat.busy, isFalse);
    expect(chat.status, contains('Windows Settings > Bluetooth & devices'));
    expect(chat.status, isNot(contains('Nearby Devices')));
    expect(platform.centralCalls.where((c) => c.method == 'start'), isEmpty);
    expect(platform.centralCalls.where((c) => c.method == 'enableBluetooth'),
        hasLength(1));
  });

  test('Windows discovery rechecks the radio after enabling Bluetooth',
      () async {
    useWindows();
    var enabled = false;
    platform.centralOverride = (call) async {
      if (call.method == 'isBluetoothOn') return enabled;
      if (call.method == 'enableBluetooth') {
        enabled = true;
        return true;
      }
      return platform.centralResponse(call);
    };
    await chat.startScan();
    expect(chat.scanning, isTrue);
    expect(platform.centralCalls.where((c) => c.method == 'isBluetoothOn'),
        hasLength(2));
    expect(platform.centralCalls.where((c) => c.method == 'enableBluetooth'),
        hasLength(1));
    expect(
        platform.centralCalls.where((c) => c.method == 'start'), hasLength(1));
  });

  test('Windows host permission denial names the Windows location settings',
      () async {
    useWindows();
    platform.peripheralOverride = (call) async {
      if (call.method == 'requestLocationPermission') return false;
      if (call.method == 'requestPermission') return 1;
      return platform.peripheralResponse(call);
    };
    await chat.startHost();
    expect(chat.mode, 'idle');
    expect(chat.busy, isFalse);
    expect(chat.status,
        contains('Windows Settings > Privacy & security > Location'));
    expect(chat.status, isNot(contains('Nearby Devices')));
    expect(platform.peripheralCalls.where((c) => c.method == 'start'), isEmpty);
    expect(platform.listening, isEmpty);
  });

  test('Windows hosting checks its own radio before advertising', () async {
    useWindows();
    platform.peripheralOverride = (call) async {
      if (call.method == 'isBluetoothOn' || call.method == 'enableBluetooth') {
        return false;
      }
      return platform.peripheralResponse(call);
    };
    await chat.startHost();
    expect(chat.status, contains('Windows Settings > Bluetooth & devices'));
    expect(platform.peripheralCalls.where((c) => c.method == 'start'), isEmpty);
    // The peripheral plugin deliberately returns false without a native enable
    // call on Linux; both Windows and Linux must still recheck the radio.
    expect(platform.peripheralCalls.where((c) => c.method == 'isBluetoothOn'),
        hasLength(2));
    expect(chat.mode, 'idle');
  });

  test(
      'Windows hosting failure explains the hardware requirement and join option',
      () async {
    useWindows();
    platform.peripheralOverride = (call) async {
      if (call.method == 'start') {
        throw PlatformException(
            code: 'start_failed', message: 'GATT unavailable');
      }
      return platform.peripheralResponse(call);
    };
    await chat.startHost();
    expect(chat.status, contains('BLE peripheral support'));
    expect(chat.status, contains('host on Android'));
    expect(chat.mode, 'idle');
    expect(chat.connected, isFalse);
    expect(platform.listening, isEmpty);
    expect(platform.peripheralCalls.where((c) => c.method == 'stop'),
        hasLength(1));
  });

  test('Windows hosting retains the Android-compatible framing and receipts',
      () async {
    useWindows();
    await chat.restore();
    await chat.startHost();
    await platform.subscription(true);
    expect(chat.canSend, isTrue);
    expect(_decode(platform.peripheralFrames), [
      {'kind': 'hello', 'name': 'Windows User'},
    ]);
    platform.peripheralFrames.clear();
    await platform
        .receive({'kind': 'text', 'id': 'android', 'text': 'Hello PC 🌍'});
    expect(chat.messages.single.text, 'Hello PC 🌍');
    expect(_decode(platform.peripheralFrames), [
      {'kind': 'ack', 'id': 'android'},
    ]);
    await chat.sendText('Hello Android');
    final outgoing = chat.messages.last;
    expect(outgoing.status, 'sent');
    await platform.receive({'kind': 'ack', 'id': outgoing.id});
    expect(outgoing.status, 'delivered');
  });
}
