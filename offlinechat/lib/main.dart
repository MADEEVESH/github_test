import 'dart:async';

import 'package:flutter/material.dart';

import 'ble_chat_controller.dart';
import 'chat_message.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const OfflineChatApp());
}

class OfflineChatApp extends StatelessWidget {
  const OfflineChatApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'OfflineChat',
        debugShowCheckedModeBanner: false,
        theme: ThemeData(
          useMaterial3: true,
          colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xff1166bb)),
        ),
        home: const ChatHome(),
      );
}

class ChatHome extends StatefulWidget {
  const ChatHome({super.key});

  @override
  State<ChatHome> createState() => _ChatHomeState();
}

class _ChatHomeState extends State<ChatHome> {
  final BleChatController chat = BleChatController();
  final TextEditingController composer = TextEditingController();
  final ScrollController scroll = ScrollController();

  @override
  void initState() {
    super.initState();
    unawaited(chat.restore());
  }

  @override
  void dispose() {
    composer.dispose();
    scroll.dispose();
    chat.dispose();
    super.dispose();
  }

  Future<void> rename() async {
    final input = TextEditingController(text: chat.nickname);
    final chosen = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Your display name'),
        content: TextField(
          controller: input,
          maxLength: 30,
          autofocus: true,
          decoration: const InputDecoration(labelText: 'Name'),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, input.text),
              child: const Text('Save')),
        ],
      ),
    );
    input.dispose();
    if (!mounted) return;
    if (chosen != null) await chat.changeName(chosen);
  }

  Future<void> send() async {
    final text = composer.text.trim();
    if (text.isEmpty || !chat.canSend) return;
    composer.clear();
    await chat.sendText(text);
    if (!mounted) return;
    if (scroll.hasClients) {
      await scroll.animateTo(
        scroll.position.maxScrollExtent,
        duration: const Duration(milliseconds: 250),
        curve: Curves.easeOut,
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: chat,
      builder: (context, _) {
        return Scaffold(
          appBar: AppBar(
            title: const Text('OfflineChat'),
            actions: [
              IconButton(
                tooltip: 'Edit nickname',
                onPressed: rename,
                icon: const Icon(Icons.edit_outlined),
              ),
              PopupMenuButton<String>(
                onSelected: (choice) {
                  if (choice == 'clear') unawaited(chat.clearHistory());
                },
                itemBuilder: (context) => const [
                  PopupMenuItem(
                      value: 'clear', child: Text('Clear local history')),
                ],
              ),
            ],
          ),
          body: SafeArea(
            child: Column(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 10, 16, 8),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text('You: ${chat.nickname}',
                          style: Theme.of(context).textTheme.labelLarge),
                      const SizedBox(height: 8),
                      Wrap(
                        spacing: 8,
                        runSpacing: 8,
                        children: [
                          FilledButton.icon(
                            onPressed: chat.busy ? null : chat.startHost,
                            icon: const Icon(Icons.sensors),
                            label: const Text('Host'),
                          ),
                          FilledButton.tonalIcon(
                            onPressed: chat.busy ? null : chat.startScan,
                            icon: const Icon(Icons.bluetooth_searching),
                            label: const Text('Find devices'),
                          ),
                          OutlinedButton.icon(
                            onPressed: chat.busy ? null : chat.stop,
                            icon: const Icon(Icons.stop_circle_outlined),
                            label: const Text('Stop'),
                          ),
                        ],
                      ),
                      const SizedBox(height: 8),
                      Row(
                        children: [
                          Icon(
                            chat.connected
                                ? Icons.bluetooth_connected
                                : Icons.bluetooth_disabled,
                            size: 18,
                            color: chat.connected ? Colors.green : Colors.grey,
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                              child: Text(chat.status,
                                  style:
                                      Theme.of(context).textTheme.bodySmall)),
                        ],
                      ),
                      if (chat.connected) ...[
                        const SizedBox(height: 6),
                        Text('Chatting with: ${chat.peerName}',
                            style:
                                const TextStyle(fontWeight: FontWeight.bold)),
                      ],
                    ],
                  ),
                ),
                if (chat.scanning) ...[
                  const Divider(height: 1),
                  SizedBox(
                    height: chat.peers.isEmpty ? 85 : 155,
                    child: chat.peers.isEmpty
                        ? const Center(
                            child: Text(
                                'No host found yet. Keep both devices nearby.'))
                        : ListView(
                            children: chat.peers.values.map((NearbyPeer peer) {
                              return ListTile(
                                leading: const Icon(Icons.devices),
                                title: const Text('OfflineChat host'),
                                subtitle: Text(
                                    '${peer.address} • RSSI ${peer.rssi} dBm'),
                                trailing: const Icon(Icons.chevron_right),
                                onTap: chat.busy
                                    ? null
                                    : () => chat.join(peer.address),
                              );
                            }).toList(),
                          ),
                  ),
                ],
                const Divider(height: 1),
                Expanded(
                  child: chat.messages.isEmpty
                      ? Center(
                          child: Padding(
                            padding: const EdgeInsets.all(24),
                            child: Text(
                              'Start hosting on one device, then search on another.\n\nMessages work over Bluetooth without Wi-Fi or internet.',
                              textAlign: TextAlign.center,
                              style: TextStyle(color: Colors.grey.shade700),
                            ),
                          ),
                        )
                      : ListView.builder(
                          controller: scroll,
                          padding: const EdgeInsets.symmetric(
                              horizontal: 12, vertical: 8),
                          itemCount: chat.messages.length,
                          itemBuilder: (context, i) {
                            final msg = chat.messages[i];
                            return Align(
                              alignment: msg.outgoing
                                  ? Alignment.centerRight
                                  : Alignment.centerLeft,
                              child: ConstrainedBox(
                                constraints: BoxConstraints(
                                    maxWidth:
                                        MediaQuery.sizeOf(context).width * .78),
                                child: Card(
                                  color: msg.outgoing
                                      ? Theme.of(context)
                                          .colorScheme
                                          .primaryContainer
                                      : Theme.of(context)
                                          .colorScheme
                                          .surfaceContainerHighest,
                                  child: Padding(
                                    padding: const EdgeInsets.symmetric(
                                        horizontal: 12, vertical: 8),
                                    child: Column(
                                      crossAxisAlignment:
                                          CrossAxisAlignment.end,
                                      children: [
                                        Text(msg.text),
                                        const SizedBox(height: 3),
                                        Text(
                                          '${TimeOfDay.fromDateTime(msg.time).format(context)}  ${msg.outgoing ? msg.status : ''}',
                                          style: Theme.of(context)
                                              .textTheme
                                              .labelSmall,
                                        ),
                                      ],
                                    ),
                                  ),
                                ),
                              ),
                            );
                          },
                        ),
                ),
                const Divider(height: 1),
                Padding(
                  padding: const EdgeInsets.fromLTRB(12, 8, 12, 10),
                  child: Row(
                    children: [
                      Expanded(
                        child: TextField(
                          controller: composer,
                          maxLength: 200,
                          maxLines: 3,
                          minLines: 1,
                          enabled: chat.canSend,
                          textInputAction: TextInputAction.send,
                          onSubmitted: (_) => unawaited(send()),
                          decoration: const InputDecoration(
                            hintText: 'Type a message',
                            counterText: '',
                            border: OutlineInputBorder(),
                            isDense: true,
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      IconButton.filled(
                        tooltip: 'Send',
                        onPressed: chat.canSend ? send : null,
                        icon: const Icon(Icons.send),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}
