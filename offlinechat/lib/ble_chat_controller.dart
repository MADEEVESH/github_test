import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_ble_central/flutter_ble_central.dart';
import 'package:flutter_ble_peripheral/flutter_ble_peripheral.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'ble_protocol.dart';
import 'chat_message.dart';

class BleChatController extends ChangeNotifier {
  final FlutterBleCentral _central = FlutterBleCentral();
  final FlutterBlePeripheral _peripheral = FlutterBlePeripheral();
  final BleReassembler _inbound = BleReassembler();

  StreamSubscription? _scanSub;
  StreamSubscription? _connectionSub;
  StreamSubscription? _notificationSub;
  StreamSubscription? _receiveSub;
  StreamSubscription? _subscribedSub;
  SharedPreferences? _prefs;
  Future<void> _txTail = Future<void>.value();
  Future<Object?>? _pendingActivation;
  int _sequence = 0;
  int _session = 0;
  int _operation = 0;
  bool _disposed = false;
  String? _remoteAddress;

  String nickname = defaultTargetPlatform == TargetPlatform.windows
      ? 'Windows User'
      : 'Android User';
  String peerName = 'Nearby device';
  String mode = 'idle'; // idle | host | scan | client
  String status = 'Ready. Start hosting or search for a nearby device.';
  bool busy = false;
  bool connected = false;
  bool scanning = false;
  final List<ChatMessage> messages = [];
  final Map<String, NearbyPeer> peers = {};

  bool get canSend => !_disposed && connected && !busy;

  bool get _isWindows => defaultTargetPlatform == TargetPlatform.windows;

  String get _defaultNickname => _isWindows ? 'Windows User' : 'Android User';

  String get _permissionHint => _isWindows
      ? 'Turn on Bluetooth in Windows Settings > Bluetooth & devices.'
      : 'Grant Nearby Devices / Bluetooth permission and turn on Bluetooth.';

  bool _active(int operation) => !_disposed && operation == _operation;

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  Future<void> restore() async {
    if (_disposed) return;
    final prefs = await SharedPreferences.getInstance();
    if (_disposed) return;
    _prefs = prefs;
    nickname = prefs.getString('nickname') ?? _defaultNickname;
    final saved = prefs.getString('messages');
    if (saved != null) {
      try {
        final decoded = jsonDecode(saved) as List<dynamic>;
        messages.clear();
        for (final value in decoded) {
          messages.add(
              ChatMessage.fromJson(Map<String, dynamic>.from(value as Map)));
        }
        if (messages.length > 200) {
          messages.removeRange(0, messages.length - 200);
        }
      } catch (_) {
        messages.clear();
      }
    }
    _notify();
  }

  Future<void> changeName(String name) async {
    if (_disposed) return;
    final trimmed = name.trim();
    if (trimmed.isEmpty) return;
    nickname = trimmed.substring(0, trimmed.length > 30 ? 30 : trimmed.length);
    await _prefs?.setString('nickname', nickname);
    if (_disposed) return;
    _notify();
    if (connected) _sendGreeting();
  }

  Future<void> _persist() async {
    if (_disposed) return;
    if (messages.length > 200) messages.removeRange(0, messages.length - 200);
    await _prefs?.setString(
        'messages', jsonEncode(messages.map((m) => m.toJson()).toList()));
  }

  void _state(String value) {
    if (_disposed) return;
    status = value;
    _notify();
  }

  Future<bool> _ensureCentralReady(int operation) async {
    final supported = await _central.isSupported;
    if (!_active(operation)) return false;
    if (!supported) throw StateError('BLE central mode is not supported.');
    if (!await _ensureWindowsRadio(
      operation,
      () => _central.isBluetoothOn,
      () => _central.enableBluetooth(),
    )) {
      return false;
    }
    var state = await _central.requestPermission();
    if (!_active(operation)) return false;
    if (state == CentralBluetoothState.turnedOff) {
      await _central.enableBluetooth();
      if (!_active(operation)) return false;
      state = await _central.requestPermission();
      if (!_active(operation)) return false;
    }
    if (state != CentralBluetoothState.granted &&
        state != CentralBluetoothState.ready) {
      throw StateError('$_permissionHint ($state)');
    }
    return true;
  }

  Future<bool> _ensurePeripheralReady(int operation) async {
    final supported = await _peripheral.isSupported;
    if (!_active(operation)) return false;
    if (!supported) {
      throw StateError(
          'This device cannot advertise as a BLE peripheral. Try another host device.');
    }
    if (!await _ensureWindowsRadio(
      operation,
      () => _peripheral.isBluetoothOn,
      () => _peripheral.enableBluetooth(),
    )) {
      return false;
    }
    var state = await _peripheral.requestPermission();
    if (!_active(operation)) return false;
    if (state == PeripheralBluetoothState.turnedOff) {
      await _peripheral.enableBluetooth();
      if (!_active(operation)) return false;
      state = await _peripheral.requestPermission();
      if (!_active(operation)) return false;
    }
    if (state != PeripheralBluetoothState.granted &&
        state != PeripheralBluetoothState.ready) {
      if (_isWindows) {
        // The Windows plugin gates hosting on its location-consent API rather
        // than Android's Nearby Devices permission. No location is read here.
        throw StateError(
          'Allow Location services and desktop app location access in '
          'Windows Settings > Privacy & security > Location, then try hosting again. '
          'You can also host on Android and connect from this PC. ($state)',
        );
      }
      throw StateError('$_permissionHint ($state)');
    }
    return true;
  }

  Future<bool> _ensureWindowsRadio(
    int operation,
    Future<bool> Function() isOn,
    Future<bool> Function() enable,
  ) async {
    if (!_isWindows) return true;
    // Windows permission checks can report granted while the radio is off.
    // Verify the radio itself before attempting GATT hosting or discovery.
    var enabled = await isOn();
    if (!_active(operation)) return false;
    if (!enabled) {
      await enable();
      if (!_active(operation)) return false;
      enabled = await isOn();
      if (!_active(operation)) return false;
    }
    if (!enabled) throw StateError(_permissionHint);
    return true;
  }

  // A stop waits for an in-flight native start/connect before releasing it.
  // Otherwise a late platform response could restart BLE after disposal.
  Future<T> _activate<T>(Future<T> Function() start) async {
    final pending = start();
    _pendingActivation = pending;
    try {
      return await pending;
    } finally {
      if (identical(_pendingActivation, pending)) _pendingActivation = null;
    }
  }

  void _resetConnection() {
    _session++;
    connected = false;
    peerName = 'Nearby device';
    _inbound.reset();
  }

  List<StreamSubscription?> _takeSubscriptions() {
    final subscriptions = [
      _scanSub,
      _connectionSub,
      _notificationSub,
      _receiveSub,
      _subscribedSub
    ];
    _scanSub = null;
    _connectionSub = null;
    _notificationSub = null;
    _receiveSub = null;
    _subscribedSub = null;
    return subscriptions;
  }

  Future<void> _shutdown(
    String oldMode,
    String? address,
    List<StreamSubscription?> subscriptions,
    Future<Object?>? pendingActivation,
  ) async {
    for (final subscription in subscriptions) {
      try {
        await subscription?.cancel();
      } catch (_) {}
    }
    try {
      await pendingActivation;
    } catch (_) {}
    try {
      if (oldMode == 'scan' || oldMode == 'client') await _central.stop();
    } catch (_) {}
    try {
      if (address != null && oldMode == 'client') {
        await _central.disconnect(address);
      }
    } catch (_) {}
    try {
      if (oldMode == 'host') await _peripheral.stop();
    } catch (_) {}
  }

  Future<void> stop() async {
    if (_disposed) return;
    final operation = ++_operation;
    final oldMode = mode;
    final address = _remoteAddress;
    final subscriptions = _takeSubscriptions();
    final pendingActivation = _pendingActivation;
    busy = true;
    _resetConnection();
    scanning = false;
    _remoteAddress = null;
    mode = 'idle';
    _notify();
    await _shutdown(oldMode, address, subscriptions, pendingActivation);
    if (!_active(operation)) return;
    busy = false;
    _state('Disconnected. You can host or search again.');
  }

  void _sendGreeting() {
    final session = _session;
    unawaited(_queueFrame({'kind': 'hello', 'name': nickname}, session: session)
        .catchError((Object error) {
      if (!_disposed && session == _session) _state('Greeting failed: $error');
    }));
  }

  void _hostSubscription(bool subscribed, int operation) {
    if (!_active(operation) || mode != 'host' || connected == subscribed) {
      return;
    }
    _resetConnection();
    connected = subscribed;
    _state(subscribed
        ? 'Device connected. You can chat.'
        : 'Hosting. Waiting for someone to join...');
    if (subscribed) _sendGreeting();
  }

  Future<void> startHost() async {
    if (_disposed || busy) return;
    await stop();
    if (_disposed) return;
    final operation = ++_operation;
    busy = true;
    mode = 'host';
    _notify();
    try {
      if (!await _ensurePeripheralReady(operation)) return;
      _receiveSub = _peripheral.onDataReceived.listen((bytes) {
        if (_active(operation) && mode == 'host' && connected) {
          _receivePacket(bytes);
        }
      });
      _subscribedSub = _peripheral.onSubscriptionChanged.listen(
        (subscribed) => _hostSubscription(subscribed, operation),
      );
      final result = await _activate(() => _peripheral.start(
            advertiseData:
                const AdvertiseDataCore(serviceUuid: offlineChatServiceUuid),
            gattServer: const GattServerSettings(),
          ));
      if (!_active(operation)) return;
      final advertising = await _peripheral.isAdvertising;
      if (!_active(operation)) return;
      if (!advertising) {
        throw StateError('Could not start advertising ($result)');
      }
      if (!connected) _state('Hosting. Waiting for someone to join...');
      // Recover a subscription that arrived before the platform start returned.
      final subscribed = await _peripheral.isSubscribed;
      if (!_active(operation)) return;
      _hostSubscription(subscribed, operation);
    } catch (e) {
      if (!_active(operation)) return;
      await stop();
      if (!_disposed && mode == 'idle') {
        final guidance = _isWindows
            ? ' Windows hosting needs a Bluetooth adapter with BLE peripheral '
                'support. If hosting is unavailable, host on Android and use '
                'Find devices on this PC.'
            : '';
        _state('Could not host: $e$guidance');
      }
    } finally {
      if (_active(operation)) {
        busy = false;
        _notify();
      }
    }
  }

  Future<void> startScan() async {
    if (_disposed || busy) return;
    await stop();
    if (_disposed) return;
    final operation = ++_operation;
    peers.clear();
    busy = true;
    mode = 'scan';
    _notify();
    try {
      if (!await _ensureCentralReady(operation)) return;
      _scanSub = _central.onScanResult.listen((result) {
        if (!_active(operation) || mode != 'scan') return;
        final address = result.device?.address;
        if (address == null || address.isEmpty) return;
        peers[address] =
            NearbyPeer(address, int.tryParse('${result.rssi}') ?? -127);
        _notify();
      });
      final result = await _activate(
          () => _central.start(serviceUuids: [offlineChatServiceUuid]));
      if (!_active(operation)) return;
      if (result != CentralBluetoothState.granted &&
          result != CentralBluetoothState.ready) {
        throw StateError('Scanning failed ($result)');
      }
      scanning = true;
      _state('Searching for OfflineChat hosts nearby...');
    } catch (e) {
      if (!_active(operation)) return;
      await stop();
      if (!_disposed && mode == 'idle') _state('Could not scan: $e');
    } finally {
      if (_active(operation)) {
        busy = false;
        _notify();
      }
    }
  }

  Future<void> join(String address) async {
    if (_disposed || busy) return;
    final operation = ++_operation;
    busy = true;
    _resetConnection();
    scanning = false;
    _remoteAddress = address;
    mode = 'client';
    final scanSub = _scanSub;
    _scanSub = null;
    _state('Connecting to $address...');
    try {
      await scanSub?.cancel();
      if (!_active(operation)) return;
      await _central.stop();
      if (!_active(operation)) return;
      _notificationSub = _central.onCharacteristicValueChanged.listen((event) {
        if (!_active(operation) ||
            mode != 'client' ||
            !connected ||
            event.address != _remoteAddress ||
            event.serviceUuid.toLowerCase() != offlineChatServiceUuid ||
            event.characteristicUuid.toLowerCase() != uartTxUuid) {
          return;
        }
        _receivePacket(event.value);
      });
      _connectionSub = _central.onConnectionStateChanged.listen((event) {
        if (!_active(operation) ||
            mode != 'client' ||
            event.address != _remoteAddress ||
            !connected) {
          return;
        }
        if (event.state == GattConnectionState.disconnected) {
          _resetConnection();
          _state('Connection lost. Tap Stop and search again.');
        }
      });
      await _activate(() => _central.connect(address: address));
      if (!_active(operation)) return;
      var linked = false;
      for (var i = 0; i < 50; i++) {
        final state = await _central.getConnectionState(address);
        if (!_active(operation)) return;
        if (state == GattConnectionState.connected) {
          linked = true;
          break;
        }
        await Future<void>.delayed(const Duration(milliseconds: 250));
        if (!_active(operation)) return;
      }
      if (!linked) {
        throw StateError(
            'Connection timed out. Retry with devices close together.');
      }
      final services = await _central.discoverServices(address);
      if (!_active(operation)) return;
      if (services.isEmpty) throw StateError('No GATT services discovered.');
      // The host can notify its greeting before the subscription call returns.
      connected = true;
      final session = _session;
      await _central.setCharacteristicNotification(
        address: address,
        serviceUuid: offlineChatServiceUuid,
        characteristicUuid: uartTxUuid,
        enable: true,
      );
      if (!_active(operation)) return;
      if (session != _session || !connected) {
        throw StateError('Connection lost while subscribing.');
      }
      _state('Connected. You can chat.');
      await _queueFrame({'kind': 'hello', 'name': nickname}, session: session);
    } catch (e) {
      if (!_active(operation)) return;
      await stop();
      if (!_disposed && mode == 'idle') _state('Could not join: $e');
    } finally {
      if (_active(operation)) {
        busy = false;
        _notify();
      }
    }
  }

  /// Serializes concurrent frames; messages and ACKs cannot interleave.
  Future<void> _queueFrame(Map<String, dynamic> content, {int? session}) {
    final targetSession = session ?? _session;
    final task = _txTail.then((_) => _writeMessage(content, targetSession));
    _txTail = task.catchError((Object _) {});
    return task;
  }

  Future<void> _writeMessage(Map<String, dynamic> content, int session) async {
    for (final frame in BleFramer.encode(content)) {
      if (_disposed || session != _session) {
        throw StateError('Connection changed; message cancelled.');
      }
      if (mode == 'host' && connected) {
        await _peripheral.sendData(frame);
      } else if (mode == 'client' && connected && _remoteAddress != null) {
        await _central.writeCharacteristic(
          address: _remoteAddress!,
          serviceUuid: offlineChatServiceUuid,
          characteristicUuid: uartRxUuid,
          value: frame,
        );
      } else {
        throw StateError('Not connected to a peer.');
      }
    }
    if (_disposed || session != _session) {
      throw StateError('Connection changed; message cancelled.');
    }
  }

  void _receivePacket(Uint8List bytes) {
    if (_disposed) return;
    final content = _inbound.accept(bytes);
    if (content == null) return;
    final kind = content['kind'];
    if (kind == 'hello') {
      final name = content['name'];
      if (name is String && name.isNotEmpty) {
        peerName = name.substring(0, name.length > 30 ? 30 : name.length);
        _notify();
      }
    } else if (kind == 'text') {
      final id = content['id'];
      final text = content['text'];
      if (id is! String ||
          text is! String ||
          text.isEmpty ||
          text.length > 400) {
        return;
      }
      if (!messages.any((m) => m.id == id && !m.outgoing)) {
        messages.add(ChatMessage(
            id: id, text: text, outgoing: false, time: DateTime.now()));
        unawaited(_persist());
        _notify();
      }
      final session = _session;
      unawaited(_queueFrame({'kind': 'ack', 'id': id}, session: session)
          .catchError((Object e) {
        if (!_disposed && session == _session) {
          _state('Acknowledgment failed: $e');
        }
      }));
    } else if (kind == 'ack') {
      final id = content['id'];
      for (final msg in messages) {
        if (msg.outgoing && msg.id == id) {
          msg.status = 'delivered';
          unawaited(_persist());
          _notify();
          break;
        }
      }
    }
  }

  Future<void> sendText(String text) async {
    if (_disposed) return;
    final trimmed = text.trim();
    if (trimmed.isEmpty || !connected) return;
    if (trimmed.runes.length > 200) {
      _state('Maximum message length: 200 characters.');
      return;
    }
    final session = _session;
    final id = '${DateTime.now().microsecondsSinceEpoch}-${_sequence++}';
    final message = ChatMessage(
      id: id,
      text: trimmed,
      outgoing: true,
      time: DateTime.now(),
      status: 'sending',
    );
    messages.add(message);
    _notify();
    await _persist();
    if (_disposed) return;
    try {
      await _queueFrame({'kind': 'text', 'id': id, 'text': trimmed},
          session: session);
      if (_disposed) return;
      if (message.status == 'sending') message.status = 'sent';
    } catch (e) {
      if (_disposed) return;
      message.status = 'failed';
      if (session == _session) _state('Send failed: $e');
    }
    await _persist();
    _notify();
  }

  Future<void> clearHistory() async {
    if (_disposed) return;
    messages.clear();
    await _persist();
    _notify();
  }

  @override
  void dispose() {
    if (_disposed) return;
    _disposed = true;
    _operation++;
    final oldMode = mode;
    final address = _remoteAddress;
    final subscriptions = _takeSubscriptions();
    _resetConnection();
    scanning = false;
    busy = false;
    mode = 'idle';
    _remoteAddress = null;
    unawaited(_shutdown(oldMode, address, subscriptions, _pendingActivation));
    super.dispose();
  }
}
