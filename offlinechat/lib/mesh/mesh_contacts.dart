import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:qr_flutter/qr_flutter.dart';

import 'mesh_controller.dart';
import 'mesh_models.dart';

Future<void> showMeshIdentity(BuildContext context, MeshIdentity identity) =>
    showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(identity.name),
        content: SizedBox(
          width: 380,
          child: SingleChildScrollView(
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              const Text('Ask your contact to scan this public identity QR, '
                  'then compare the fingerprint together over a trusted channel.'),
              const SizedBox(height: 20),
              ColoredBox(
                color: Colors.white,
                child: QrImageView(
                    data: identity.qr,
                    size: 260,
                    backgroundColor: Colors.white),
              ),
              const SizedBox(height: 20),
              const Text('Identity fingerprint'),
              const SizedBox(height: 8),
              SelectableText(meshFingerprint(identity.id),
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontFamily: 'monospace')),
              const SizedBox(height: 16),
              Wrap(spacing: 8, alignment: WrapAlignment.center, children: [
                TextButton.icon(
                    onPressed: () => _copy(context, identity.qr),
                    icon: const Icon(Icons.copy),
                    label: const Text('Copy public QR text')),
                TextButton(
                    onPressed: () => _copy(context, identity.id),
                    child: const Text('Copy fingerprint')),
              ]),
            ]),
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('Done')),
        ],
      ),
    );

Future<void> _copy(BuildContext context, String text) async {
  try {
    await Clipboard.setData(ClipboardData(text: text));
    if (context.mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Copied to clipboard')));
    }
  } on PlatformException catch (error) {
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(error.message ?? 'Could not access the clipboard.')));
    }
  }
}

Future<void> showAddMeshContact(BuildContext context) => showDialog<void>(
      context: context,
      builder: (_) => const _AddContactDialog(),
    );

class _AddContactDialog extends ConsumerStatefulWidget {
  const _AddContactDialog();
  @override
  ConsumerState<_AddContactDialog> createState() => _AddContactDialogState();
}

class _AddContactDialogState extends ConsumerState<_AddContactDialog> {
  final _qr = TextEditingController();
  MeshContact? _preview;
  String? _validatedQr;
  bool _verified = false;
  String? _localError;

  @override
  void dispose() {
    _qr.dispose();
    super.dispose();
  }

  void _changed(String value) {
    setState(() {
      _preview = null;
      _validatedQr = null;
      _verified = false;
      _localError = null;
    });
  }

  Future<void> _previewContact() async {
    final qr = _qr.text.trim();
    final controller = ref.read(meshControllerProvider.notifier);
    final preview = await controller.validateContact(qr);
    if (!mounted || qr != _qr.text.trim()) return;
    setState(() {
      _preview = preview;
      _validatedQr = preview == null ? null : qr;
      _verified = false;
      _localError = preview == null
          ? ref.read(meshControllerProvider).error ?? 'Could not read this QR.'
          : null;
    });
  }

  Future<void> _scan() async {
    final qr = await Navigator.of(context).push<String>(
      MaterialPageRoute(builder: (_) => const _QrScannerPage()),
    );
    if (!mounted || qr == null) return;
    _qr.text = qr;
    _changed(qr);
    await _previewContact();
  }

  Future<void> _save() async {
    final qr = _validatedQr;
    if (!_verified || _preview == null || qr == null) return;
    final saved =
        await ref.read(meshControllerProvider.notifier).addVerifiedContact(qr);
    if (!mounted) return;
    if (saved) {
      Navigator.pop(context);
    } else {
      setState(() => _localError = ref.read(meshControllerProvider).error);
    }
  }

  @override
  Widget build(BuildContext context) {
    final busy = ref.watch(meshControllerProvider.select((s) => s.busy));
    return AlertDialog(
      title: const Text('Verify a contact'),
      content: SizedBox(
        width: 440,
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('Scan their public identity QR or paste its text. '
                  'A valid QR proves ownership of keys; compare the full '
                  'fingerprint to confirm who owns them.'),
              const SizedBox(height: 16),
              OutlinedButton.icon(
                  onPressed: busy ? null : _scan,
                  icon: const Icon(Icons.qr_code_scanner),
                  label: const Text('Scan QR with camera')),
              const SizedBox(height: 16),
              TextField(
                key: const Key('mesh-contact-qr'),
                controller: _qr,
                enabled: !busy,
                minLines: 2,
                maxLines: 4,
                autocorrect: false,
                decoration: const InputDecoration(labelText: 'Public QR text'),
                onChanged: _changed,
              ),
              const SizedBox(height: 12),
              OutlinedButton(
                  onPressed: busy ? null : _previewContact,
                  child: const Text('Preview contact')),
              if (_localError != null) ...[
                const SizedBox(height: 12),
                Text(_localError!,
                    style:
                        TextStyle(color: Theme.of(context).colorScheme.error)),
              ],
              if (_preview case final preview?) ...[
                const Divider(height: 32),
                Text(preview.name,
                    style: Theme.of(context).textTheme.titleLarge),
                const SizedBox(height: 8),
                const Text('Compare every group of this fingerprint:'),
                const SizedBox(height: 8),
                SelectableText(meshFingerprint(preview.id),
                    style: const TextStyle(fontFamily: 'monospace')),
                const SizedBox(height: 12),
                CheckboxListTile(
                  key: const Key('mesh-contact-verified'),
                  contentPadding: EdgeInsets.zero,
                  controlAffinity: ListTileControlAffinity.leading,
                  value: _verified,
                  onChanged: busy
                      ? null
                      : (value) => setState(() => _verified = value == true),
                  title: const Text('I compared this fingerprint with this '
                      'person over a trusted channel.'),
                ),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
            onPressed: busy ? null : () => Navigator.pop(context),
            child: const Text('Cancel')),
        FilledButton(
            key: const Key('mesh-contact-save'),
            onPressed: busy || !_verified || _preview == null ? null : _save,
            child: const Text('Add verified contact')),
      ],
    );
  }
}

class _QrScannerPage extends StatefulWidget {
  const _QrScannerPage();
  @override
  State<_QrScannerPage> createState() => _QrScannerPageState();
}

class _QrScannerPageState extends State<_QrScannerPage> {
  final _camera = MobileScannerController(
    formats: const [BarcodeFormat.qrCode],
    detectionSpeed: DetectionSpeed.noDuplicates,
  );
  bool _returned = false;

  @override
  void dispose() {
    unawaited(_camera.dispose());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('Scan contact QR')),
        body: Column(children: [
          Expanded(
            child: MobileScanner(
              controller: _camera,
              onDetect: (capture) {
                if (_returned) return;
                for (final barcode in capture.barcodes) {
                  final raw = barcode.rawValue;
                  if (raw == null || raw.trim().isEmpty) continue;
                  _returned = true;
                  Navigator.pop(context, raw);
                  return;
                }
              },
              errorBuilder: (context, error) => Center(
                child: Padding(
                  padding: const EdgeInsets.all(28),
                  child: Text(
                      'Camera unavailable: ${error.errorDetails?.message ?? error.errorCode.name}. '
                      'Return to paste the public QR text instead.',
                      textAlign: TextAlign.center),
                ),
              ),
            ),
          ),
          const SafeArea(
            top: false,
            child: Padding(
              padding: EdgeInsets.all(20),
              child: Text('Point the camera at your contact’s identity QR. '
                  'You will review their fingerprint before adding them.'),
            ),
          ),
        ]),
      );
}
