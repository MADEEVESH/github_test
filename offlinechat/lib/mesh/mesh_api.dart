import 'package:flutter/services.dart';

import 'mesh_models.dart';

abstract interface class MeshApi {
  Stream<MeshSnapshot> get snapshots;
  Future<MeshSnapshot> bootstrap();
  Future<MeshSnapshot> onboard(String name);
  Future<MeshContact> validateContact(String qr);
  Future<MeshSnapshot> addContact(String qr);
  Future<List<MeshMessage>> messages(String contactId);
  Future<MeshSnapshot> send(String contactId, String text);
  Future<MeshSnapshot> start();
  Future<MeshSnapshot> stop();
  Future<MeshSnapshot> updateSettings(MeshSettings settings);
  Future<List<MeshDiagnostic>> diagnostics();
}

class ChannelMeshApi implements MeshApi {
  const ChannelMeshApi({
    MethodChannel methodChannel = const MethodChannel('offlinechat/mesh'),
    EventChannel eventChannel = const EventChannel('offlinechat/mesh_events'),
  })  : _methods = methodChannel,
        _events = eventChannel;
  final MethodChannel _methods;
  final EventChannel _events;

  Future<MeshSnapshot> _state(String method,
          [Map<String, Object?>? arguments]) async =>
      MeshSnapshot.fromMap(
          meshMap(await _methods.invokeMethod(method, arguments)));

  @override
  Stream<MeshSnapshot> get snapshots => _events
      .receiveBroadcastStream()
      .map((value) => MeshSnapshot.fromMap(meshMap(value)));

  @override
  Future<MeshSnapshot> bootstrap() => _state('bootstrap');
  @override
  Future<MeshSnapshot> onboard(String name) =>
      _state('onboard', {'name': name});
  @override
  Future<MeshContact> validateContact(String qr) async => MeshContact.fromMap(
      meshMap(await _methods.invokeMethod('validateContact', {'qr': qr})));
  @override
  Future<MeshSnapshot> addContact(String qr) =>
      _state('addContact', {'qr': qr, 'verified': true});
  @override
  Future<List<MeshMessage>> messages(String contactId) async => meshRows(
          await _methods.invokeMethod('messages', {'contactId': contactId}))
      .map(MeshMessage.fromMap)
      .toList();
  @override
  Future<MeshSnapshot> send(String contactId, String text) =>
      _state('send', {'contactId': contactId, 'text': text});
  @override
  Future<MeshSnapshot> start() => _state('start');
  @override
  Future<MeshSnapshot> stop() => _state('stop');
  @override
  Future<MeshSnapshot> updateSettings(MeshSettings settings) =>
      _state('updateSettings', settings.toMap());
  @override
  Future<List<MeshDiagnostic>> diagnostics() async =>
      meshRows(await _methods.invokeMethod('diagnostics'))
          .map(MeshDiagnostic.fromMap)
          .toList();
}
