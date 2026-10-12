import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart';
import 'package:http/testing.dart';

import '../lib/src/roadside/roadside_page.dart';
import '../lib/src/roadside/roadside_repository.dart';

Map<String, dynamic> incidentJson(String status) => {
      'id': 'incident-1',
      'customerId': 'customer-1',
      'status': status,
      'location': 'Cape Town CBD',
      'description': 'Flat tyre',
      'vehicleId': null,
      'createdAt': '2026-10-12T08:00:00Z',
    };

void main() {
  testWidgets('requests roadside help and can cancel an open incident',
      (tester) async {
    var cancel = false;
    final repository = RoadsideRepository(
      client: MockClient((request) async {
        if (request.url.path.endsWith('/cancel')) {
          cancel = true;
          return Response(jsonEncode(incidentJson('CANCELLED')), 200);
        }
        return Response(jsonEncode(incidentJson('OPEN')), 201);
      }),
    );

    await tester.pumpWidget(MaterialApp(
        home: Scaffold(body: RoadsidePage(repository: repository))));
    await tester.enterText(find.byType(TextField).first, 'Cape Town CBD');
    await tester.enterText(find.byType(TextField).last, 'Flat tyre');
    await tester.tap(find.text('Request roadside help'));
    await tester.pumpAndSettle();

    expect(find.text('OPEN · Cape Town CBD'), findsOneWidget);
    await tester.tap(find.text('Cancel request'));
    await tester.pumpAndSettle();

    expect(cancel, isTrue);
    expect(find.text('CANCELLED · Cape Town CBD'), findsOneWidget);
  });
}
