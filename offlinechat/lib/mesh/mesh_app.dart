import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'mesh_chat.dart';
import 'mesh_contacts.dart';
import 'mesh_controller.dart';
import 'mesh_pages.dart';

class MeshApp extends StatelessWidget {
  const MeshApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'OfflineChat',
        debugShowCheckedModeBanner: false,
        theme: ThemeData(
          useMaterial3: true,
          colorScheme: ColorScheme.fromSeed(
            seedColor: const Color(0xFF77AAFF),
            brightness: Brightness.dark,
            surface: const Color(0xFF111C2E),
          ),
          scaffoldBackgroundColor: const Color(0xFF0A1220),
          inputDecorationTheme: const InputDecorationTheme(
            border: OutlineInputBorder(),
          ),
          cardTheme: const CardThemeData(
            margin: EdgeInsets.zero,
            clipBehavior: Clip.antiAlias,
          ),
        ),
        home: const _MeshHome(),
      );
}

class _MeshHome extends ConsumerStatefulWidget {
  const _MeshHome();
  @override
  ConsumerState<_MeshHome> createState() => _MeshHomeState();
}

class _MeshHomeState extends ConsumerState<_MeshHome> {
  int _destination = 0;

  void _select(int index) {
    ref.read(meshControllerProvider.notifier).closeConversation();
    setState(() => _destination = index);
  }

  @override
  Widget build(BuildContext context) {
    final view = ref.watch(meshControllerProvider);
    final controller = ref.read(meshControllerProvider.notifier);
    final snapshot = view.snapshot;
    if (snapshot == null) {
      return Scaffold(
        body: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 520),
            child: Padding(
              padding: const EdgeInsets.all(32),
              child: Column(mainAxisSize: MainAxisSize.min, children: [
                if (view.loading) ...[
                  const CircularProgressIndicator(),
                  const SizedBox(height: 24),
                  const Text('Opening your local mesh…'),
                ] else ...[
                  const Icon(Icons.error_outline, size: 48),
                  const SizedBox(height: 24),
                  Text(view.error ?? 'Could not open the local mesh.',
                      textAlign: TextAlign.center),
                  const SizedBox(height: 24),
                  FilledButton.icon(
                    onPressed: controller.reload,
                    icon: const Icon(Icons.refresh),
                    label: const Text('Try again'),
                  ),
                ],
              ]),
            ),
          ),
        ),
      );
    }
    if (!snapshot.onboarded) return const _Onboarding();
    return LayoutBuilder(builder: (context, constraints) {
      final wide = constraints.maxWidth >= 900;
      return Scaffold(
        appBar: AppBar(
          title: const Row(mainAxisSize: MainAxisSize.min, children: [
            Icon(Icons.hub_outlined),
            SizedBox(width: 12),
            Text('OfflineChat'),
          ]),
          actions: [
            IconButton(
              tooltip: 'My QR code',
              onPressed: snapshot.identity == null
                  ? null
                  : () => showMeshIdentity(context, snapshot.identity!),
              icon: const Icon(Icons.qr_code_2),
            ),
            IconButton(
              tooltip: 'Add contact',
              onPressed: () => showAddMeshContact(context),
              icon: const Icon(Icons.person_add_outlined),
            ),
            IconButton(
              tooltip: 'Refresh mesh state',
              onPressed: view.busy ? null : controller.reload,
              icon: const Icon(Icons.refresh),
            ),
            const SizedBox(width: 8),
          ],
        ),
        body: Column(children: [
          MeshErrorBanner(error: view.error, dismiss: controller.clearError),
          if (view.busy) const LinearProgressIndicator(minHeight: 2),
          Expanded(
            child: Row(children: [
              if (wide) ...[
                NavigationRail(
                  selectedIndex: _destination,
                  onDestinationSelected: _select,
                  labelType: NavigationRailLabelType.all,
                  destinations: const [
                    NavigationRailDestination(
                        icon: Icon(Icons.chat_bubble_outline),
                        selectedIcon: Icon(Icons.chat_bubble),
                        label: Text('Chats')),
                    NavigationRailDestination(
                        icon: Icon(Icons.radar), label: Text('Nearby')),
                    NavigationRailDestination(
                        icon: Icon(Icons.hub_outlined),
                        selectedIcon: Icon(Icons.hub),
                        label: Text('Mesh')),
                    NavigationRailDestination(
                        icon: Icon(Icons.settings_outlined),
                        selectedIcon: Icon(Icons.settings),
                        label: Text('Settings')),
                  ],
                ),
                const VerticalDivider(width: 1),
              ],
              Expanded(
                child: switch (_destination) {
                  0 => MeshChatsPage(wide: wide),
                  1 => MeshNearbyPage(snapshot: snapshot, busy: view.busy),
                  2 => MeshStatusPage(snapshot: snapshot, busy: view.busy),
                  _ => MeshSettingsPage(settings: snapshot.settings),
                },
              ),
            ]),
          ),
        ]),
        bottomNavigationBar: wide
            ? null
            : NavigationBar(
                selectedIndex: _destination,
                onDestinationSelected: _select,
                destinations: const [
                  NavigationDestination(
                      icon: Icon(Icons.chat_bubble_outline),
                      selectedIcon: Icon(Icons.chat_bubble),
                      label: 'Chats'),
                  NavigationDestination(
                      icon: Icon(Icons.radar), label: 'Nearby'),
                  NavigationDestination(
                      icon: Icon(Icons.hub_outlined),
                      selectedIcon: Icon(Icons.hub),
                      label: 'Mesh'),
                  NavigationDestination(
                      icon: Icon(Icons.settings_outlined),
                      selectedIcon: Icon(Icons.settings),
                      label: 'Settings'),
                ],
              ),
      );
    });
  }
}

class MeshErrorBanner extends StatelessWidget {
  const MeshErrorBanner(
      {required this.error, required this.dismiss, super.key});
  final String? error;
  final VoidCallback dismiss;
  @override
  Widget build(BuildContext context) {
    if (error == null) return const SizedBox.shrink();
    return Material(
      color: Theme.of(context).colorScheme.errorContainer,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 4, 4, 4),
        child: Row(children: [
          const Icon(Icons.error_outline),
          const SizedBox(width: 12),
          Expanded(child: Text(error!)),
          IconButton(
              tooltip: 'Dismiss error',
              onPressed: dismiss,
              icon: const Icon(Icons.close)),
        ]),
      ),
    );
  }
}

class _Onboarding extends ConsumerStatefulWidget {
  const _Onboarding();
  @override
  ConsumerState<_Onboarding> createState() => _OnboardingState();
}

class _OnboardingState extends ConsumerState<_Onboarding> {
  final _name = TextEditingController();
  final _form = GlobalKey<FormState>();
  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  Future<void> _create() async {
    if (_form.currentState!.validate()) {
      await ref.read(meshControllerProvider.notifier).onboard(_name.text);
    }
  }

  @override
  Widget build(BuildContext context) {
    final view = ref.watch(meshControllerProvider);
    return Scaffold(
      body: SafeArea(
        child: Column(children: [
          MeshErrorBanner(
            error: view.error,
            dismiss: ref.read(meshControllerProvider.notifier).clearError,
          ),
          Expanded(
            child: Center(
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(28),
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 460),
                  child: Form(
                    key: _form,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(Icons.hub,
                            size: 64,
                            color: Theme.of(context).colorScheme.primary),
                        const SizedBox(height: 28),
                        Text('Your conversations.\nYour local mesh.',
                            style: Theme.of(context).textTheme.headlineMedium),
                        const SizedBox(height: 16),
                        const Text('Send encrypted text through nearby Android '
                            'devices, without an internet connection or account. '
                            'Messages can wait for a route to your contact.'),
                        const SizedBox(height: 28),
                        TextFormField(
                          key: const Key('mesh-onboarding-name'),
                          controller: _name,
                          enabled: !view.busy,
                          textCapitalization: TextCapitalization.words,
                          textInputAction: TextInputAction.done,
                          decoration: const InputDecoration(
                            labelText: 'Your display name',
                            helperText:
                                'Visible to people who receive your QR code',
                          ),
                          validator: (value) {
                            final name = value?.trim() ?? '';
                            return name.isEmpty || name.runes.length > 32
                                ? 'Use 1 to 32 characters.'
                                : null;
                          },
                          onFieldSubmitted: (_) => view.busy ? null : _create(),
                        ),
                        const SizedBox(height: 24),
                        FilledButton.icon(
                          onPressed: view.busy ? null : _create,
                          icon: view.busy
                              ? const SizedBox.square(
                                  dimension: 18,
                                  child:
                                      CircularProgressIndicator(strokeWidth: 2))
                              : const Icon(Icons.lock_outline),
                          label: const Text('Create secure identity'),
                        ),
                        const SizedBox(height: 20),
                        Text(
                            'Your protected keys stay on this device. Verify '
                            'contacts by comparing fingerprints before chatting. '
                            'Start and stop Bluetooth sharing whenever you choose.',
                            style: Theme.of(context).textTheme.bodySmall),
                        const SizedBox(height: 12),
                        Text(
                            'Mesh v2 starts a new encrypted history. Earlier '
                            'direct-chat contacts and messages are not imported.',
                            style: Theme.of(context).textTheme.bodySmall),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ]),
      ),
    );
  }
}
