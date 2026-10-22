import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:offlinechat/main.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  testWidgets(
      'restores the nickname and keeps messaging disabled until connected',
      (tester) async {
    SharedPreferences.setMockInitialValues({'nickname': 'Alice'});
    await tester.pumpWidget(const OfflineChatApp());
    await tester.pumpAndSettle();

    expect(find.text('OfflineChat'), findsOneWidget);
    expect(find.text('You: Alice'), findsOneWidget);
    expect(find.text('Host'), findsOneWidget);
    expect(find.text('Find devices'), findsOneWidget);
    expect(tester.widget<TextField>(find.byType(TextField)).enabled, isFalse);
    final send =
        tester.widget<IconButton>(find.widgetWithIcon(IconButton, Icons.send));
    expect(send.onPressed, isNull);
    expect(tester.takeException(), isNull);
  });

  testWidgets('Windows shows device controls and a platform-appropriate name',
      (tester) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.windows;
    try {
      SharedPreferences.setMockInitialValues({});
      await tester.pumpWidget(const OfflineChatApp());
      await tester.pumpAndSettle();

      expect(find.text('You: Windows User'), findsOneWidget);
      expect(find.text('Host'), findsOneWidget);
      expect(find.text('Find devices'), findsOneWidget);
      expect(
          find.textContaining('Start hosting on one device'), findsOneWidget);
      expect(tester.widget<TextField>(find.byType(TextField)).enabled, isFalse);
      expect(tester.takeException(), isNull);
    } finally {
      debugDefaultTargetPlatformOverride = null;
    }
  });

  testWidgets(
      'restores a local conversation and clears it from persistent storage',
      (tester) async {
    SharedPreferences.setMockInitialValues({
      'messages': jsonEncode([
        {
          'id': 'saved-message',
          'text': 'Saved offline conversation',
          'outgoing': false,
          'time': '2026-10-21T09:00:00.000',
          'status': 'received',
        },
      ]),
    });
    await tester.pumpWidget(const OfflineChatApp());
    await tester.pumpAndSettle();
    expect(find.text('Saved offline conversation'), findsOneWidget);

    await tester.tap(find.byType(PopupMenuButton<String>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Clear local history'));
    await tester.pumpAndSettle();

    expect(find.text('Saved offline conversation'), findsNothing);
    final prefs = await SharedPreferences.getInstance();
    expect(jsonDecode(prefs.getString('messages')!), isEmpty);
    expect(tester.takeException(), isNull);
  });
}
