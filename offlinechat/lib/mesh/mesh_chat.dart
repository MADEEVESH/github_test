import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'mesh_contacts.dart';
import 'mesh_controller.dart';
import 'mesh_models.dart';

class MeshChatsPage extends ConsumerWidget {
  const MeshChatsPage({required this.wide, super.key});
  final bool wide;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final view = ref.watch(meshControllerProvider);
    final selected = view.selectedContact;
    if (!wide) {
      return selected == null
          ? const _ChatList()
          : _Conversation(key: ValueKey(selected), contactId: selected);
    }
    return Row(children: [
      const SizedBox(width: 320, child: _ChatList()),
      const VerticalDivider(width: 1),
      Expanded(
        child: selected == null
            ? const MeshEmptyState(
                icon: Icons.lock_outline,
                title: 'A private place to talk',
                message: 'Choose a verified contact to send encrypted text. '
                    'Messages wait locally until a route becomes available.',
              )
            : _Conversation(key: ValueKey(selected), contactId: selected),
      ),
    ]);
  }
}

class _ChatList extends ConsumerWidget {
  const _ChatList();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final view = ref.watch(meshControllerProvider);
    final snapshot = view.snapshot!;
    final chatIds = snapshot.chats.map((chat) => chat.contactId).toSet();
    final otherContacts = snapshot.contacts
        .where((contact) => !chatIds.contains(contact.id))
        .toList();
    final chats = [...snapshot.chats]
      ..sort((a, b) => b.lastAt.compareTo(a.lastAt));
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(20, 20, 12, 12),
        child: Row(children: [
          Expanded(
              child: Text('Chats',
                  style: Theme.of(context).textTheme.headlineSmall)),
          IconButton(
              tooltip: 'New verified contact',
              onPressed: () => showAddMeshContact(context),
              icon: const Icon(Icons.edit_square)),
        ]),
      ),
      Expanded(
        child: chats.isEmpty && otherContacts.isEmpty
            ? MeshEmptyState(
                icon: Icons.forum_outlined,
                title: 'Start with a contact',
                message: 'Exchange identity QR codes and compare fingerprints '
                    'with someone you trust.',
                action: FilledButton.icon(
                    onPressed: () => showAddMeshContact(context),
                    icon: const Icon(Icons.person_add_outlined),
                    label: const Text('Add contact')),
              )
            : ListView(children: [
                for (final chat in chats)
                  _ChatTile(
                    id: chat.contactId,
                    name: chat.name,
                    subtitle: chat.lastText,
                    selected: view.selectedContact == chat.contactId,
                    trailing: Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.end,
                      children: [
                        Text(meshDateLabel(chat.lastAt),
                            style: Theme.of(context).textTheme.labelSmall),
                        if (chat.unread > 0) ...[
                          const SizedBox(height: 4),
                          Badge(label: Text('${chat.unread}')),
                        ],
                      ],
                    ),
                  ),
                if (chats.isNotEmpty && otherContacts.isNotEmpty)
                  const Padding(
                    padding: EdgeInsets.fromLTRB(20, 20, 20, 8),
                    child: Text('CONTACTS'),
                  ),
                for (final contact in otherContacts)
                  _ChatTile(
                      id: contact.id,
                      name: contact.name,
                      subtitle: contact.verified
                          ? 'Verified contact · start a conversation'
                          : 'Fingerprint has not been verified',
                      selected: view.selectedContact == contact.id),
              ]),
      ),
      Padding(
        padding: const EdgeInsets.all(16),
        child: Row(children: [
          Icon(
              snapshot.activity.running
                  ? Icons.hub
                  : Icons.pause_circle_outline,
              size: 16,
              color: Theme.of(context).colorScheme.onSurfaceVariant),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              snapshot.activity.running
                  ? 'Mesh sharing is running'
                  : 'Mesh stopped · messages can still be queued',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ),
        ]),
      ),
    ]);
  }
}

class _ChatTile extends ConsumerWidget {
  const _ChatTile(
      {required this.id,
      required this.name,
      required this.subtitle,
      required this.selected,
      this.trailing});
  final String id;
  final String name;
  final String subtitle;
  final bool selected;
  final Widget? trailing;

  @override
  Widget build(BuildContext context, WidgetRef ref) => ListTile(
        key: ValueKey('mesh-chat-$id'),
        selected: selected,
        leading: CircleAvatar(
            child:
                Text(name.isEmpty ? '?' : name.characters.first.toUpperCase())),
        title: Text(name, maxLines: 1, overflow: TextOverflow.ellipsis),
        subtitle: Text(subtitle, maxLines: 2, overflow: TextOverflow.ellipsis),
        trailing: trailing,
        onTap: () =>
            ref.read(meshControllerProvider.notifier).openConversation(id),
      );
}

class _Conversation extends ConsumerStatefulWidget {
  const _Conversation({required this.contactId, super.key});
  final String contactId;
  @override
  ConsumerState<_Conversation> createState() => _ConversationState();
}

class _ConversationState extends ConsumerState<_Conversation> {
  final _text = TextEditingController();
  bool _sending = false;
  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    if (_sending) return;
    final draft = _text.text;
    setState(() => _sending = true);
    final sent = await ref
        .read(meshControllerProvider.notifier)
        .send(widget.contactId, draft);
    if (!mounted) return;
    setState(() {
      _sending = false;
      if (sent && _text.text == draft) _text.clear();
    });
  }

  @override
  Widget build(BuildContext context) {
    final view = ref.watch(meshControllerProvider);
    final snapshot = view.snapshot!;
    final contacts = snapshot.contacts.where((c) => c.id == widget.contactId);
    final contact = contacts.isEmpty ? null : contacts.first;
    final chats = snapshot.chats.where((c) => c.contactId == widget.contactId);
    final name =
        contact?.name ?? (chats.isEmpty ? 'Contact' : chats.first.name);
    final messages = view.messages[widget.contactId] ?? const <MeshMessage>[];
    final verified = contact?.verified == true;
    final bytes = meshTextBytes(_text.text.trim());
    final canSend = verified &&
        !view.busy &&
        !_sending &&
        _text.text.trim().isNotEmpty &&
        bytes <= 1024;
    return Column(children: [
      Material(
        color: Theme.of(context).colorScheme.surface,
        child: ListTile(
          leading: IconButton(
              tooltip: 'Back to chats',
              onPressed:
                  ref.read(meshControllerProvider.notifier).closeConversation,
              icon: const Icon(Icons.arrow_back)),
          title: Text(name),
          subtitle: Text(verified
              ? 'Verified identity · end-to-end encrypted'
              : 'Verify this contact before sending'),
          trailing: IconButton(
            tooltip: 'Contact fingerprint',
            onPressed: contact == null
                ? null
                : () => showDialog<void>(
                      context: context,
                      builder: (context) => AlertDialog(
                        title: Text(contact.name),
                        content: SingleChildScrollView(
                          child: Column(
                            mainAxisSize: MainAxisSize.min,
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(verified
                                  ? 'You confirmed this identity fingerprint.'
                                  : 'This identity has not been verified.'),
                              const SizedBox(height: 16),
                              SelectableText(meshFingerprint(contact.id),
                                  style:
                                      const TextStyle(fontFamily: 'monospace')),
                            ],
                          ),
                        ),
                        actions: [
                          TextButton(
                              onPressed: () => Navigator.pop(context),
                              child: const Text('Done')),
                        ],
                      ),
                    ),
            icon: Icon(
                verified ? Icons.verified_user_outlined : Icons.info_outline),
          ),
        ),
      ),
      if (view.messagesLoading) const LinearProgressIndicator(minHeight: 2),
      Expanded(
        child: messages.isEmpty
            ? const MeshEmptyState(
                icon: Icons.lock_outline,
                title: 'Encrypted from this device to theirs',
                message:
                    'Nearby relays carry encrypted messages. Only a signed '
                    'receipt from your contact can mark a message Delivered.',
              )
            : ListView.builder(
                key: ValueKey('mesh-messages-${widget.contactId}'),
                reverse: true,
                padding: const EdgeInsets.all(16),
                itemCount: messages.length,
                itemBuilder: (context, index) => _MessageBubble(
                    message: messages[messages.length - 1 - index]),
              ),
      ),
      SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Expanded(
              child: TextField(
                key: const Key('mesh-message-input'),
                controller: _text,
                enabled: verified && !_sending,
                minLines: 1,
                maxLines: 5,
                textCapitalization: TextCapitalization.sentences,
                decoration: InputDecoration(
                  hintText: 'Encrypted message',
                  counterText: '$bytes / 1024 UTF-8 bytes',
                  errorText:
                      bytes > 1024 ? 'Message exceeds 1024 bytes.' : null,
                ),
                onChanged: (_) => setState(() {}),
              ),
            ),
            const SizedBox(width: 12),
            IconButton.filled(
              key: const Key('mesh-send'),
              tooltip: 'Send encrypted message',
              onPressed: canSend ? _send : null,
              icon: _sending
                  ? const SizedBox.square(
                      dimension: 20,
                      child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.send),
            ),
          ]),
        ),
      ),
    ]);
  }
}

class _MessageBubble extends StatelessWidget {
  const _MessageBubble({required this.message});
  final MeshMessage message;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final status = switch (message.status) {
      'pending' => 'Pending',
      'relayed' => 'Relayed',
      'delivered' => 'Delivered',
      'expired' => 'Expired',
      _ => message.status,
    };
    return Align(
      alignment:
          message.outgoing ? Alignment.centerRight : Alignment.centerLeft,
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 560),
        child: Container(
          margin: const EdgeInsets.only(bottom: 12),
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          decoration: BoxDecoration(
            color: message.outgoing
                ? scheme.primaryContainer
                : scheme.surfaceContainerHigh,
            borderRadius: BorderRadius.circular(18),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              SelectableText(message.text),
              const SizedBox(height: 6),
              Text(
                '${meshTimeLabel(message.time)} · ${message.outgoing ? status : 'Received'}',
                style: Theme.of(context).textTheme.labelSmall?.copyWith(
                    color: message.status == 'expired'
                        ? scheme.error
                        : scheme.onSurfaceVariant),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class MeshEmptyState extends StatelessWidget {
  const MeshEmptyState(
      {required this.icon,
      required this.title,
      required this.message,
      this.action,
      super.key});
  final IconData icon;
  final String title;
  final String message;
  final Widget? action;
  @override
  Widget build(BuildContext context) => Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 390),
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              Icon(icon,
                  size: 44, color: Theme.of(context).colorScheme.primary),
              const SizedBox(height: 20),
              Text(title,
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 12),
              Text(message,
                  textAlign: TextAlign.center,
                  style: TextStyle(
                      color: Theme.of(context).colorScheme.onSurfaceVariant)),
              if (action != null) ...[
                const SizedBox(height: 24),
                action!,
              ],
            ]),
          ),
        ),
      );
}

String meshTimeLabel(int milliseconds) {
  if (milliseconds <= 0) return '';
  final time = DateTime.fromMillisecondsSinceEpoch(milliseconds).toLocal();
  return '${time.hour.toString().padLeft(2, '0')}:'
      '${time.minute.toString().padLeft(2, '0')}';
}

String meshDateLabel(int milliseconds) {
  if (milliseconds <= 0) return '';
  final time = DateTime.fromMillisecondsSinceEpoch(milliseconds).toLocal();
  final now = DateTime.now();
  if (time.year == now.year && time.month == now.month && time.day == now.day) {
    return meshTimeLabel(milliseconds);
  }
  return '${time.day}/${time.month}/${time.year}';
}
