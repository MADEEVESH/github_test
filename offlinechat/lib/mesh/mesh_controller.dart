import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'mesh_api.dart';
import 'mesh_models.dart';

final meshApiProvider = Provider<MeshApi>((ref) => const ChannelMeshApi());
final meshControllerProvider =
    StateNotifierProvider<MeshController, MeshViewState>((ref) =>
        MeshController(ref.watch(meshApiProvider)));

class MeshViewState {
  const MeshViewState({
    this.snapshot,
    this.loading = true,
    this.busy = false,
    this.error,
    this.messages = const {},
    this.messagesLoading = false,
    this.selectedContact,
  });
  final MeshSnapshot? snapshot;
  final bool loading;
  final bool busy;
  final String? error;
  final Map<String, List<MeshMessage>> messages;
  final bool messagesLoading;
  final String? selectedContact;

  MeshViewState copyWith({
    MeshSnapshot? snapshot,
    bool? loading,
    bool? busy,
    String? error,
    bool clearError = false,
    Map<String, List<MeshMessage>>? messages,
    bool? messagesLoading,
    String? selectedContact,
    bool clearSelection = false,
  }) =>
      MeshViewState(
        snapshot: snapshot ?? this.snapshot,
        loading: loading ?? this.loading,
        busy: busy ?? this.busy,
        error: clearError ? null : error ?? this.error,
        messages: messages ?? this.messages,
        messagesLoading: messagesLoading ?? this.messagesLoading,
        selectedContact:
            clearSelection ? null : selectedContact ?? this.selectedContact,
      );
}

class MeshController extends StateNotifier<MeshViewState> {
  MeshController(this._api) : super(const MeshViewState()) {
    _subscription = _api.snapshots.listen(_acceptEvent, onError: (Object error) {
      if (mounted) state = state.copyWith(error: _message(error));
    });
    unawaited(reload());
  }
  final MeshApi _api;
  StreamSubscription<MeshSnapshot>? _subscription;
  int _revision = 0;
  int _messageRequest = 0;

  String _message(Object error) {
    if (error is PlatformException) {
      return error.message ?? 'Native mesh operation failed: ' + error.code;
    }
    if (error is MissingPluginException) {
      return 'Android Mesh v2 is unavailable in this build. Install the Android '
          'mesh APK. The Windows app uses the earlier direct-chat protocol.';
    }
    return error.toString();
  }

  void _acceptEvent(MeshSnapshot snapshot) {
    if (!mounted) return;
    _revision++;
    state = state.copyWith(snapshot: snapshot, loading: false);
    final selected = state.selectedContact;
    if (selected != null) unawaited(loadMessages(selected));
  }

  Future<void> reload() async {
    if (!mounted) return;
    state = state.copyWith(loading: state.snapshot == null, clearError: true);
    final revision = _revision;
    try {
      final snapshot = await _api.bootstrap();
      if (!mounted) return;
      if (revision == _revision) {
        state = state.copyWith(snapshot: snapshot, loading: false);
      }
    } catch (error) {
      if (mounted) {
        state = state.copyWith(loading: false, error: _message(error));
      }
    }
  }

  Future<bool> _mutate(Future<MeshSnapshot> Function() action) async {
    if (!mounted || state.busy) return false;
    state = state.copyWith(busy: true, clearError: true);
    try {
      final snapshot = await action();
      if (!mounted) return false;
      state = state.copyWith(snapshot: snapshot, loading: false, busy: false);
      final selected = state.selectedContact;
      if (selected != null) await loadMessages(selected);
      return true;
    } catch (error) {
      if (mounted) state = state.copyWith(busy: false, error: _message(error));
      return false;
    }
  }

  Future<bool> onboard(String name) {
    final clean = name.trim();
    if (clean.isEmpty) {
      state = state.copyWith(error: 'Enter a display name.');
      return Future.value(false);
    }
    return _mutate(() => _api.onboard(clean));
  }

  Future<MeshContact?> validateContact(String qr) async {
    if (state.busy) return null;
    if (qr.trim().isEmpty) {
      state = state.copyWith(error: 'Scan or paste a contact QR first.');
      return null;
    }
    state = state.copyWith(busy: true, clearError: true);
    try {
      final contact = await _api.validateContact(qr.trim());
      if (!mounted) return null;
      state = state.copyWith(busy: false);
      return contact;
    } catch (error) {
      if (mounted) state = state.copyWith(busy: false, error: _message(error));
      return null;
    }
  }

  Future<bool> addVerifiedContact(String qr) =>
      _mutate(() => _api.addContact(qr.trim()));

  Future<void> openConversation(String contactId) async {
    state = state.copyWith(selectedContact: contactId, clearError: true);
    await loadMessages(contactId);
  }

  void closeConversation() {
    _messageRequest++;
    if (mounted) {
      state = state.copyWith(clearSelection: true, messagesLoading: false);
    }
  }

  Future<void> loadMessages(String contactId) async {
    final request = ++_messageRequest;
    if (!mounted) return;
    state = state.copyWith(messagesLoading: true);
    try {
      final messages = await _api.messages(contactId);
      if (!mounted || request != _messageRequest) return;
      state = state.copyWith(
        messages: {...state.messages, contactId: messages},
        messagesLoading: false,
      );
    } catch (error) {
      if (mounted && request == _messageRequest) {
        state = state.copyWith(
            messagesLoading: false, error: _message(error));
      }
    }
  }

  Future<bool> send(String contactId, String text) {
    final clean = text.trim();
    if (clean.isEmpty) return Future.value(false);
    if (meshTextBytes(clean) > 1024) {
      state = state.copyWith(error: 'Message is too large. Maximum: 1 KB (1024 UTF-8 bytes).');
      return Future.value(false);
    }
    return _mutate(() => _api.send(contactId, clean));
  }

  Future<bool> start() => _mutate(_api.start);
  Future<bool> stop() => _mutate(_api.stop);
  Future<bool> updateSettings(MeshSettings settings) =>
      _mutate(() => _api.updateSettings(settings));
  void clearError() {
    if (mounted) state = state.copyWith(clearError: true);
  }

  @override
  void dispose() {
    unawaited(_subscription?.cancel() ?? Future<void>.value());
    super.dispose();
  }
}
