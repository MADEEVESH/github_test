class ChatMessage {
  ChatMessage({
    required this.id,
    required this.text,
    required this.outgoing,
    required this.time,
    this.status = 'received',
  });

  final String id;
  final String text;
  final bool outgoing;
  final DateTime time;
  String status; // sending, sent, delivered, failed, received

  Map<String, dynamic> toJson() => {
        'id': id,
        'text': text,
        'outgoing': outgoing,
        'time': time.toIso8601String(),
        'status': status,
      };

  factory ChatMessage.fromJson(Map<String, dynamic> json) => ChatMessage(
        id: (json['id'] ?? '').toString(),
        text: (json['text'] ?? '').toString(),
        outgoing: json['outgoing'] == true,
        time: DateTime.tryParse((json['time'] ?? '').toString()) ??
            DateTime.now(),
        status: (json['status'] ?? 'received').toString(),
      );
}

class NearbyPeer {
  const NearbyPeer(this.address, this.rssi);
  final String address;
  final int rssi;
}
