import 'dart:convert';

Map<String, dynamic> meshMap(Object? value) {
  if (value is! Map) {
    throw const FormatException('Invalid native mesh response.');
  }
  return value.map((key, value) => MapEntry(key.toString(), value));
}

List<Map<String, dynamic>> meshRows(Object? value) {
  if (value == null) return const [];
  if (value is! List) throw const FormatException('Invalid native mesh list.');
  return value.map(meshMap).toList(growable: false);
}

int _number(Object? value, [int fallback = 0]) =>
    value is num ? value.toInt() : fallback;

class MeshIdentity {
  const MeshIdentity({required this.id, required this.name, required this.qr});
  factory MeshIdentity.fromMap(Map<String, dynamic> map) => MeshIdentity(
        id: map['id']?.toString() ?? '',
        name: map['name']?.toString() ?? '',
        qr: map['qr']?.toString() ?? '',
      );
  final String id;
  final String name;
  final String qr;
}

class MeshContact {
  const MeshContact(
      {required this.id, required this.name, required this.verified});
  factory MeshContact.fromMap(Map<String, dynamic> map) => MeshContact(
        id: map['id']?.toString() ?? '',
        name: map['name']?.toString() ?? 'Contact',
        verified: map['verified'] == true,
      );
  final String id;
  final String name;
  final bool verified;
}

class MeshChat {
  const MeshChat(
      {required this.contactId,
      required this.name,
      required this.lastText,
      required this.lastAt,
      required this.unread});
  factory MeshChat.fromMap(Map<String, dynamic> map) => MeshChat(
        contactId: map['contactId']?.toString() ?? '',
        name: map['name']?.toString() ?? 'Contact',
        lastText: map['lastText']?.toString() ?? '',
        lastAt: _number(map['lastAt']),
        unread: _number(map['unread']),
      );
  final String contactId;
  final String name;
  final String lastText;
  final int lastAt;
  final int unread;
}

class MeshPeer {
  const MeshPeer(
      {required this.address,
      required this.id,
      required this.name,
      required this.state,
      this.rssi});
  factory MeshPeer.fromMap(Map<String, dynamic> map) => MeshPeer(
        address: map['address']?.toString() ?? '',
        id: map['id']?.toString(),
        name: map['name']?.toString() ?? 'Nearby device',
        state: map['state']?.toString() ?? 'unknown',
        rssi: map['rssi'] is num ? (map['rssi'] as num).toInt() : null,
      );
  final String address;
  final String? id;
  final String name;
  final String state;
  final int? rssi;
}

class MeshSettings {
  const MeshSettings({
    this.relayEnabled = true,
    this.maxHops = 8,
    this.ttlSeconds = 86400,
    this.queueLimitBytes = 25 * 1024 * 1024,
  });
  factory MeshSettings.fromMap(Map<String, dynamic> map) => MeshSettings(
        relayEnabled: map['relayEnabled'] != false,
        maxHops: _number(map['maxHops'], 8),
        ttlSeconds: _number(map['ttlSeconds'], 86400),
        queueLimitBytes: _number(map['queueLimitBytes'], 25 * 1024 * 1024),
      );
  final bool relayEnabled;
  final int maxHops;
  final int ttlSeconds;
  final int queueLimitBytes;
  Map<String, Object?> toMap() => {
        'relayEnabled': relayEnabled,
        'maxHops': maxHops,
        'ttlSeconds': ttlSeconds,
        'queueLimitBytes': queueLimitBytes,
      };
}

class MeshCapabilities {
  const MeshCapabilities(
      {required this.supported,
      required this.advertising,
      required this.bluetoothOn,
      required this.permissionsGranted,
      this.reason});
  factory MeshCapabilities.fromMap(Map<String, dynamic> map) =>
      MeshCapabilities(
        supported: map['supported'] == true,
        advertising: map['advertising'] == true,
        bluetoothOn: map['bluetoothOn'] == true,
        permissionsGranted: map['permissionsGranted'] == true,
        reason: map['reason']?.toString(),
      );
  final bool supported;
  final bool advertising;
  final bool bluetoothOn;
  final bool permissionsGranted;
  final String? reason;
}

class MeshActivity {
  const MeshActivity(
      {required this.running,
      required this.foreground,
      required this.connections,
      required this.forwarded,
      required this.queueBytes,
      required this.status});
  factory MeshActivity.fromMap(Map<String, dynamic> map) => MeshActivity(
        running: map['running'] == true,
        foreground: map['foreground'] == true,
        connections: _number(map['connections']),
        forwarded: _number(map['forwarded']),
        queueBytes: _number(map['queueBytes']),
        status: map['status']?.toString() ?? 'Stopped',
      );
  final bool running;
  final bool foreground;
  final int connections;
  final int forwarded;
  final int queueBytes;
  final String status;
}

class MeshDiagnostic {
  const MeshDiagnostic(
      {required this.time, required this.level, required this.message});
  factory MeshDiagnostic.fromMap(Map<String, dynamic> map) => MeshDiagnostic(
        time: _number(map['time']),
        level: map['level']?.toString() ?? 'info',
        message: map['message']?.toString() ?? '',
      );
  final int time;
  final String level;
  final String message;
}

class MeshSnapshot {
  const MeshSnapshot({
    required this.onboarded,
    required this.identity,
    required this.contacts,
    required this.chats,
    required this.peers,
    required this.settings,
    required this.capabilities,
    required this.activity,
    required this.diagnostics,
  });
  factory MeshSnapshot.fromMap(Map<String, dynamic> map) {
    if (map['onboarded'] is! bool) {
      throw const FormatException(
          'Native mesh state is missing onboarding status.');
    }
    return MeshSnapshot(
      onboarded: map['onboarded'] as bool,
      identity: map['identity'] == null
          ? null
          : MeshIdentity.fromMap(meshMap(map['identity'])),
      contacts: meshRows(map['contacts']).map(MeshContact.fromMap).toList(),
      chats: meshRows(map['chats']).map(MeshChat.fromMap).toList(),
      peers: meshRows(map['peers']).map(MeshPeer.fromMap).toList(),
      settings: MeshSettings.fromMap(meshMap(map['settings'] ?? {})),
      capabilities:
          MeshCapabilities.fromMap(meshMap(map['capabilities'] ?? {})),
      activity: MeshActivity.fromMap(meshMap(map['mesh'] ?? {})),
      diagnostics:
          meshRows(map['diagnostics']).map(MeshDiagnostic.fromMap).toList(),
    );
  }
  final bool onboarded;
  final MeshIdentity? identity;
  final List<MeshContact> contacts;
  final List<MeshChat> chats;
  final List<MeshPeer> peers;
  final MeshSettings settings;
  final MeshCapabilities capabilities;
  final MeshActivity activity;
  final List<MeshDiagnostic> diagnostics;
}

class MeshMessage {
  const MeshMessage(
      {required this.id,
      required this.contactId,
      required this.text,
      required this.outgoing,
      required this.time,
      required this.status});
  factory MeshMessage.fromMap(Map<String, dynamic> map) {
    final status = map['status'];
    if (status is! String ||
        !const {'pending', 'relayed', 'delivered', 'expired'}
            .contains(status)) {
      throw const FormatException(
          'Native message has an invalid delivery status.');
    }
    return MeshMessage(
      id: map['id']?.toString() ?? '',
      contactId: map['contactId']?.toString() ?? '',
      text: map['text']?.toString() ?? '',
      outgoing: map['outgoing'] == true,
      time: _number(map['time']),
      status: status,
    );
  }
  final String id;
  final String contactId;
  final String text;
  final bool outgoing;
  final int time;
  final String status;
}

int meshTextBytes(String text) => utf8.encode(text).length;

String meshFingerprint(String id) {
  final chunks = <String>[];
  for (var i = 0; i < id.length; i += 4) {
    chunks.add(id.substring(i, i + 4 > id.length ? id.length : i + 4));
  }
  return chunks.join(' ');
}
