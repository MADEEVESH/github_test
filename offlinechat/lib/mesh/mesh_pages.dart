import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'mesh_chat.dart';
import 'mesh_contacts.dart';
import 'mesh_controller.dart';
import 'mesh_models.dart';

class MeshNearbyPage extends StatelessWidget {
  const MeshNearbyPage({required this.snapshot, required this.busy, super.key});
  final MeshSnapshot snapshot;
  final bool busy;

  @override
  Widget build(BuildContext context) => _Page(
        children: [
          const _PageHeading(
              title: 'Nearby',
              subtitle:
                  'Compatible OfflineChat devices discovered over Bluetooth.'),
          _MeshControl(snapshot: snapshot, busy: busy),
          const SizedBox(height: 24),
          if (snapshot.peers.isEmpty)
            Card(
              child: MeshEmptyState(
                icon: Icons.radar,
                title: snapshot.activity.running
                    ? 'Looking for nearby devices'
                    : 'Discover your local mesh',
                message: snapshot.activity.running
                    ? 'Keep Bluetooth on and another OfflineChat Mesh v2 device '
                        'nearby. Compatible peers appear here as they are found.'
                    : 'Start mesh sharing on both devices to discover peers. '
                        'Your verified contacts do not need to be in direct range.',
              ),
            )
          else ...[
            Text(
                '${snapshot.peers.length} discovered '
                '${snapshot.peers.length == 1 ? 'device' : 'devices'}',
                style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),
            Card(
              child: Column(children: [
                for (final peer in snapshot.peers)
                  ListTile(
                    leading: Icon(switch (peer.state) {
                      'authenticated' || 'connected' => Icons.link,
                      'authenticating' => Icons.lock_clock_outlined,
                      _ => Icons.bluetooth_searching,
                    }),
                    title: Text(peer.name),
                    subtitle: Text([
                      _peerState(peer.state),
                      if (peer.rssi != null) '${peer.rssi} dBm',
                      if (peer.id != null) _shortFingerprint(peer.id!),
                    ].join(' · ')),
                    isThreeLine: false,
                  ),
              ]),
            ),
            const SizedBox(height: 16),
            const Text('A nearby authenticated relay is not automatically a '
                'trusted contact. Add people by QR and compare their fingerprints.'),
          ],
        ],
      );
}

class MeshStatusPage extends ConsumerWidget {
  const MeshStatusPage({required this.snapshot, required this.busy, super.key});
  final MeshSnapshot snapshot;
  final bool busy;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final activity = snapshot.activity;
    final capabilities = snapshot.capabilities;
    final logs = snapshot.diagnostics.reversed.toList();
    return _Page(children: [
      const _PageHeading(
          title: 'Mesh',
          subtitle:
              'The current Bluetooth service, encrypted queue and relay activity.'),
      _MeshControl(snapshot: snapshot, busy: busy),
      const SizedBox(height: 24),
      LayoutBuilder(builder: (context, constraints) {
        final width = constraints.maxWidth >= 600
            ? (constraints.maxWidth - 12) / 2
            : constraints.maxWidth;
        return Wrap(spacing: 12, runSpacing: 12, children: [
          _Stat(
              width: width,
              icon: Icons.link,
              value: '${activity.connections}',
              label: 'Authenticated connections'),
          _Stat(
              width: width,
              icon: Icons.alt_route,
              value: '${activity.forwarded}',
              label: 'Envelopes forwarded'),
          _Stat(
              width: width,
              icon: Icons.storage_outlined,
              value: meshBytesLabel(activity.queueBytes),
              label:
                  'Encrypted queue · ${meshBytesLabel(snapshot.settings.queueLimitBytes)} limit'),
          _Stat(
              width: width,
              icon: Icons.notifications_active_outlined,
              value: activity.foreground ? 'Active' : 'Inactive',
              label: 'Foreground service'),
        ]);
      }),
      const SizedBox(height: 24),
      Text('Device readiness', style: Theme.of(context).textTheme.titleLarge),
      const SizedBox(height: 12),
      Card(
        child: Column(children: [
          _Readiness(
              label: 'Bluetooth LE support', ready: capabilities.supported),
          _Readiness(
              label: 'BLE advertising support',
              ready: capabilities.advertising),
          _Readiness(
              label: 'Bluetooth enabled', ready: capabilities.bluetoothOn),
          _Readiness(
              label: 'Bluetooth permissions',
              ready: capabilities.permissionsGranted),
        ]),
      ),
      const SizedBox(height: 24),
      Row(children: [
        Expanded(
            child: Text('Diagnostics',
                style: Theme.of(context).textTheme.titleLarge)),
        IconButton(
            tooltip: 'Refresh diagnostics',
            onPressed:
                ref.read(meshControllerProvider.notifier).refreshDiagnostics,
            icon: const Icon(Icons.refresh)),
      ]),
      const Text(
          'Local transport and routing events. Message content and private '
          'keys are not shown.'),
      const SizedBox(height: 12),
      Card(
        child: logs.isEmpty
            ? const Padding(
                padding: EdgeInsets.all(20),
                child: Text('No diagnostics recorded.'))
            : Column(children: [
                for (final log in logs.take(100))
                  ListTile(
                    dense: true,
                    leading: Icon(
                        log.level == 'error'
                            ? Icons.error_outline
                            : Icons.info_outline,
                        size: 20),
                    title: SelectableText(log.message),
                    subtitle:
                        Text('${_diagnosticTime(log.time)} · ${log.level}'),
                  ),
              ]),
      ),
    ]);
  }
}

class MeshSettingsPage extends ConsumerStatefulWidget {
  const MeshSettingsPage({required this.settings, super.key});
  final MeshSettings settings;
  @override
  ConsumerState<MeshSettingsPage> createState() => _MeshSettingsPageState();
}

class _MeshSettingsPageState extends ConsumerState<MeshSettingsPage> {
  final _form = GlobalKey<FormState>();
  final _hops = TextEditingController();
  final _ttl = TextEditingController();
  final _storage = TextEditingController();
  late bool _relay;

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() {
    _relay = widget.settings.relayEnabled;
    _hops.text = '${widget.settings.maxHops}';
    _ttl.text = _minutes(widget.settings.ttlSeconds);
    _storage.text = '${widget.settings.queueLimitBytes ~/ (1024 * 1024)}';
  }

  @override
  void didUpdateWidget(covariant MeshSettingsPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    final a = oldWidget.settings;
    final b = widget.settings;
    if (a.relayEnabled != b.relayEnabled ||
        a.maxHops != b.maxHops ||
        a.ttlSeconds != b.ttlSeconds ||
        a.queueLimitBytes != b.queueLimitBytes) {
      _load();
    }
  }

  @override
  void dispose() {
    _hops.dispose();
    _ttl.dispose();
    _storage.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!_form.currentState!.validate()) return;
    final saved =
        await ref.read(meshControllerProvider.notifier).updateSettings(
              MeshSettings(
                relayEnabled: _relay,
                maxHops: int.parse(_hops.text.trim()),
                ttlSeconds: (double.parse(_ttl.text.trim()) * 60).round(),
                queueLimitBytes: int.parse(_storage.text.trim()) * 1024 * 1024,
              ),
            );
    if (mounted && saved) {
      ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Mesh settings saved on this device.')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final view = ref.watch(meshControllerProvider);
    final identity = view.snapshot?.identity;
    return _Page(children: [
      const _PageHeading(
          title: 'Settings',
          subtitle: 'Choose how this device participates in the local mesh.'),
      if (identity != null) ...[
        Card(
          child: ListTile(
            leading: const Icon(Icons.account_circle_outlined),
            title: Text(identity.name),
            subtitle: const Text('Your local identity and public QR code'),
            trailing: const Icon(Icons.qr_code_2),
            onTap: () => showMeshIdentity(context, identity),
          ),
        ),
        const SizedBox(height: 24),
      ],
      Form(
        key: _form,
        child:
            Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Card(
            child: SwitchListTile(
              key: const Key('mesh-relay-setting'),
              value: _relay,
              onChanged:
                  view.busy ? null : (value) => setState(() => _relay = value),
              secondary: const Icon(Icons.alt_route),
              title: const Text('Relay encrypted messages'),
              subtitle: const Text('Help nearby people reach their contacts. '
                  'Relays cannot read message content.'),
            ),
          ),
          const SizedBox(height: 24),
          TextFormField(
            key: const Key('mesh-hops-setting'),
            controller: _hops,
            enabled: !view.busy,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(
                labelText: 'Maximum hops',
                helperText:
                    '1–16 · default 8. Applies to new outgoing messages.'),
            validator: (text) => _integerRange(text, 1, 16),
          ),
          const SizedBox(height: 24),
          TextFormField(
            key: const Key('mesh-ttl-setting'),
            controller: _ttl,
            enabled: !view.busy,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: const InputDecoration(
                labelText: 'Message lifetime (minutes)',
                helperText:
                    '1–10080 · default 1440 (24 hours). New messages only.'),
            validator: (text) {
              final value = double.tryParse(text?.trim() ?? '');
              return value == null ||
                      !value.isFinite ||
                      value < 1 ||
                      value > 10080
                  ? 'Enter a lifetime from 1 to 10080 minutes.'
                  : null;
            },
          ),
          const SizedBox(height: 24),
          TextFormField(
            key: const Key('mesh-storage-setting'),
            controller: _storage,
            enabled: !view.busy,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(
                labelText: 'Encrypted queue limit (MiB)',
                helperText:
                    '1–100 · default 25. Also limited to 4096 queued envelopes.'),
            validator: (text) => _integerRange(text, 1, 100),
          ),
          const SizedBox(height: 24),
          Align(
            alignment: Alignment.centerLeft,
            child: FilledButton.icon(
              onPressed: view.busy ? null : _save,
              icon: const Icon(Icons.save_outlined),
              label: const Text('Save settings'),
            ),
          ),
        ]),
      ),
      const SizedBox(height: 32),
      const Text('OfflineChat Mesh v2 · Android'),
      const SizedBox(height: 8),
      Text(
          'Bluetooth must be enabled for sharing. Android may limit background '
          'radio activity; use Mesh diagnostics to check the actual service state.',
          style: Theme.of(context).textTheme.bodySmall),
    ]);
  }
}

class _MeshControl extends ConsumerWidget {
  const _MeshControl({required this.snapshot, required this.busy});
  final MeshSnapshot snapshot;
  final bool busy;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final activity = snapshot.activity;
    final reason = snapshot.capabilities.reason;
    final stoppable = activity.running || activity.foreground;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child:
            Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Icon(activity.running ? Icons.hub : Icons.pause_circle_outline,
                color: Theme.of(context).colorScheme.primary, size: 30),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                      activity.running
                          ? 'Mesh sharing is running'
                          : 'Mesh sharing is stopped',
                      style: Theme.of(context).textTheme.titleLarge),
                  const SizedBox(height: 8),
                  Text(activity.status),
                ],
              ),
            ),
          ]),
          if (reason != null && reason.isNotEmpty) ...[
            const SizedBox(height: 12),
            Text(reason),
          ],
          if (!snapshot.capabilities.permissionsGranted) ...[
            const SizedBox(height: 12),
            const Text(
                'Start sharing to request the required Bluetooth permissions.'),
          ],
          const SizedBox(height: 20),
          Align(
            alignment: Alignment.centerLeft,
            child: FilledButton.icon(
              key: const Key('mesh-toggle'),
              onPressed:
                  busy || (!stoppable && !snapshot.capabilities.supported)
                      ? null
                      : () => stoppable
                          ? ref.read(meshControllerProvider.notifier).stop()
                          : ref.read(meshControllerProvider.notifier).start(),
              icon: Icon(stoppable ? Icons.stop : Icons.play_arrow),
              label:
                  Text(stoppable ? 'Stop mesh sharing' : 'Start mesh sharing'),
            ),
          ),
        ]),
      ),
    );
  }
}

class _Stat extends StatelessWidget {
  const _Stat(
      {required this.width,
      required this.icon,
      required this.value,
      required this.label});
  final double width;
  final IconData icon;
  final String value;
  final String label;
  @override
  Widget build(BuildContext context) => SizedBox(
        width: width,
        child: Card(
          child: Padding(
            padding: const EdgeInsets.all(20),
            child:
                Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Icon(icon, color: Theme.of(context).colorScheme.primary),
              const SizedBox(height: 16),
              Text(value, style: Theme.of(context).textTheme.headlineSmall),
              const SizedBox(height: 6),
              Text(label),
            ]),
          ),
        ),
      );
}

class _Readiness extends StatelessWidget {
  const _Readiness({required this.label, required this.ready});
  final String label;
  final bool ready;
  @override
  Widget build(BuildContext context) => ListTile(
        dense: true,
        leading: Icon(ready ? Icons.check_circle_outline : Icons.warning_amber,
            color: ready ? Theme.of(context).colorScheme.primary : null),
        title: Text(label),
        trailing: Text(ready ? 'Ready' : 'Unavailable'),
      );
}

class _PageHeading extends StatelessWidget {
  const _PageHeading({required this.title, required this.subtitle});
  final String title;
  final String subtitle;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: 24),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(title, style: Theme.of(context).textTheme.headlineMedium),
          const SizedBox(height: 8),
          Text(subtitle,
              style: TextStyle(
                  color: Theme.of(context).colorScheme.onSurfaceVariant)),
        ]),
      );
}

class _Page extends StatelessWidget {
  const _Page({required this.children});
  final List<Widget> children;
  @override
  Widget build(BuildContext context) => Align(
        alignment: Alignment.topCenter,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 920),
          child: ListView(
            padding: const EdgeInsets.all(24),
            children: children,
          ),
        ),
      );
}

String _peerState(String state) => switch (state) {
      'compatible' => 'Compatible',
      'nearby' => 'Compatible · nearby',
      'discovered' => 'Discovered',
      'connecting' => 'Connecting',
      'authenticating' => 'Authenticating',
      'authenticated' || 'connected' => 'Connected · authenticated',
      _ => state,
    };

String _shortFingerprint(String id) => id.length <= 12
    ? meshFingerprint(id)
    : '${meshFingerprint(id.substring(0, 12))}…';

String meshBytesLabel(int bytes) {
  if (bytes >= 1024 * 1024) {
    final value = bytes / (1024 * 1024);
    return '${value.toStringAsFixed(value == value.roundToDouble() ? 0 : 1)} MiB';
  }
  if (bytes >= 1024) return '${(bytes / 1024).toStringAsFixed(1)} KiB';
  return '$bytes B';
}

String? _integerRange(String? text, int min, int max) {
  final value = int.tryParse(text?.trim() ?? '');
  return value == null || value < min || value > max
      ? 'Enter a whole number from $min to $max.'
      : null;
}

String _minutes(int seconds) {
  final minutes = seconds / 60;
  return minutes == minutes.roundToDouble()
      ? '${minutes.round()}'
      : minutes.toStringAsFixed(4);
}

String _diagnosticTime(int milliseconds) {
  if (milliseconds <= 0) return 'Time unavailable';
  final time = DateTime.fromMillisecondsSinceEpoch(milliseconds).toLocal();
  return '${time.day}/${time.month}/${time.year} ${meshTimeLabel(milliseconds)}';
}
