import 'dart:convert';
import 'dart:typed_data';

/// Custom UUID identifies OfflineChat hosts. TX/RX are the Nordic UART UUIDs
/// supplied by the flutter_ble_peripheral GATT server by default.
const String offlineChatServiceUuid = '79e17952-c4ab-4e3f-91a6-f4ad7b9fd822';
const String uartTxUuid = '6e400003-b5a3-f393-e0a9-e50e24dcca9e';
const String uartRxUuid = '6e400002-b5a3-f393-e0a9-e50e24dcca9e';

/// Use 1 header byte + 17 bytes of content (18 byte writes/notifications).
/// Works even at the minimum BLE ATT MTU (23 => 20 byte payload).
class BleFramer {
  static const int chunkSize = 17;
  static const int maxMessageBytes = 4096;
  static const int first = 0x80;
  static const int last = 0x40;

  static List<Uint8List> encode(Map<String, dynamic> message) {
    final data = utf8.encode(jsonEncode(message));
    if (data.isEmpty || data.length > maxMessageBytes) {
      throw StateError('Message exceeds the BLE MVP size limit.');
    }
    final frames = <Uint8List>[];
    for (var i = 0; i < data.length; i += chunkSize) {
      final end = (i + chunkSize < data.length) ? i + chunkSize : data.length;
      final header = (i == 0 ? first : 0) | (end == data.length ? last : 0);
      frames.add(Uint8List.fromList([header, ...data.sublist(i, end)]));
    }
    return frames;
  }
}

/// One assembly per BLE direction, suitable for one active connection.
class BleReassembler {
  BytesBuilder? _buffer;
  int _total = 0;

  void reset() {
    _buffer = null;
    _total = 0;
  }

  Map<String, dynamic>? accept(Uint8List packet) {
    if (packet.length < 2 || packet.length > 20) {
      reset();
      return null;
    }
    final header = packet[0];
    if ((header & BleFramer.first) != 0) {
      _buffer = BytesBuilder(copy: false);
      _total = 0;
    }
    if (_buffer == null) return null;
    final content = packet.sublist(1);
    _total += content.length;
    if (_total > BleFramer.maxMessageBytes) {
      reset();
      return null;
    }
    _buffer!.add(content);
    if ((header & BleFramer.last) == 0) return null;

    final bytes = _buffer!.takeBytes();
    reset();
    try {
      final value = jsonDecode(utf8.decode(bytes));
      if (value is Map<String, dynamic>) return value;
    } catch (_) {
      // A partial or corrupt frame is discarded; never execute its contents.
    }
    return null;
  }
}
