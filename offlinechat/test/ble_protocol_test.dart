import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:offlinechat/ble_protocol.dart';

void main() {
  test('fragments and reassembles a Unicode chat message', () {
    final payload = {
      'kind': 'text',
      'id': 'abc',
      'text': 'Hello! 🌍 Hello! 🌍 Hello! 🌍',
    };
    final frames = BleFramer.encode(payload);
    expect(frames.length, greaterThan(1));
    for (final frame in frames) {
      expect(frame.length, lessThanOrEqualTo(20));
    }
    final reader = BleReassembler();
    Map<String, dynamic>? reconstructed;
    for (final frame in frames) {
      reconstructed = reader.accept(frame) ?? reconstructed;
    }
    expect(reconstructed, payload);
  });

  test('ignores a continuation without a start', () {
    final reader = BleReassembler();
    expect(
        reader.accept(Uint8List.fromList([BleFramer.last, 123, 125])), isNull);
  });

  test('each frame fits the minimum BLE MTU and has correct boundary flags',
      () {
    final frames = BleFramer.encode({'text': 'a' * 200});
    expect(frames.first[0], BleFramer.first);
    expect(frames.last[0], BleFramer.last);
    for (final frame in frames) {
      expect(frame.length, inInclusiveRange(2, 18));
    }
    for (final frame in frames.skip(1).take(frames.length - 2)) {
      expect(frame[0], 0);
    }
  });

  test('single-packet messages carry both boundary flags', () {
    final frames = BleFramer.encode({'x': 1});
    expect(frames, hasLength(1));
    expect(frames.single[0], BleFramer.first | BleFramer.last);
    expect(BleReassembler().accept(frames.single), {'x': 1});
  });

  test('enforces the byte limit including multibyte Unicode', () {
    final boundary = {'text': 'a' * (BleFramer.maxMessageBytes - 11)};
    expect(utf8.encode(jsonEncode(boundary)), hasLength(4096));
    final reader = BleReassembler();
    Map<String, dynamic>? decoded;
    for (final frame in BleFramer.encode(boundary)) {
      decoded = reader.accept(frame);
    }
    expect(decoded, boundary);
    expect(() => BleFramer.encode({'text': 'a' * 4086}), throwsStateError);
    expect(() => BleFramer.encode({'text': '🌍' * 1024}), throwsStateError);
  });

  test('a new start discards an incomplete previous message', () {
    final reader = BleReassembler();
    final abandoned = BleFramer.encode({'text': 'a' * 100});
    expect(reader.accept(abandoned.first), isNull);
    expect(reader.accept(BleFramer.encode({'x': 2}).single), {'x': 2});
  });

  test('explicit reset drops unfinished data', () {
    final reader = BleReassembler();
    final frames = BleFramer.encode({'text': 'a' * 100});
    reader.accept(frames.first);
    reader.reset();
    for (final frame in frames.skip(1)) {
      expect(reader.accept(frame), isNull);
    }
  });

  test('malformed packets reset assembly and allow the next valid message', () {
    for (final malformed in [Uint8List(0), Uint8List(1), Uint8List(21)]) {
      final reader = BleReassembler();
      final frames = BleFramer.encode({'text': 'a' * 100});
      reader.accept(frames.first);
      expect(reader.accept(malformed), isNull);
      for (final frame in frames.skip(1)) {
        expect(reader.accept(frame), isNull);
      }
      expect(
          reader.accept(BleFramer.encode({'ok': true}).single), {'ok': true});
    }
  });

  test('oversized incoming assembly is discarded', () {
    final reader = BleReassembler();
    reader
        .accept(Uint8List.fromList([BleFramer.first, ...List.filled(17, 97)]));
    for (var i = 0; i < 300; i++) {
      expect(reader.accept(Uint8List.fromList([0, ...List.filled(17, 97)])),
          isNull);
    }
    expect(reader.accept(Uint8List.fromList([BleFramer.last, 125])), isNull);
    expect(reader.accept(BleFramer.encode({'ok': true}).single), {'ok': true});
  });

  test('invalid UTF-8, invalid JSON and non-object JSON are discarded', () {
    for (final content in [
      [255, 254],
      utf8.encode('{broken'),
      utf8.encode('[1,2,3]'),
      utf8.encode('null'),
    ]) {
      final reader = BleReassembler();
      expect(
        reader.accept(
            Uint8List.fromList([BleFramer.first | BleFramer.last, ...content])),
        isNull,
      );
      expect(
          reader.accept(BleFramer.encode({'ok': true}).single), {'ok': true});
    }
  });

  test('consecutive messages do not share an assembly buffer', () {
    final reader = BleReassembler();
    for (final payload in [
      {'kind': 'hello', 'name': 'Alice'},
      {'kind': 'text', 'id': '1', 'text': 'Hello 🌍' * 20},
      {'kind': 'ack', 'id': '1'},
    ]) {
      Map<String, dynamic>? decoded;
      for (final frame in BleFramer.encode(payload)) {
        decoded = reader.accept(frame);
      }
      expect(decoded, payload);
    }
  });
}
