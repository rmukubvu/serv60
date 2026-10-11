import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import '../lib/src/booking/booking_page.dart';
import '../lib/src/booking/booking_repository.dart';

final _start = DateTime.utc(2026, 10, 11, 8);
final _end = DateTime.utc(2026, 10, 11, 8, 45);

Map<String, dynamic> _holdResponse() => {
      'id': '00000000-0000-0000-0000-000000000099',
      'status': 'HELD',
      'serviceCode': 'BARBER',
      'startsAt': _start.toIso8601String(),
      'endsAt': _end.toIso8601String(),
    };

void main() {
  testWidgets('loads availability and creates a hold for the selected service',
      (tester) async {
    final requests = <http.Request>[];
    final repository = BookingRepository(
      baseUrl: 'https://api.example.test',
      client: MockClient((request) async {
        requests.add(request);
        if (request.method == 'GET') {
          return http.Response(
            jsonEncode([
              {
                'startsAt': _start.toIso8601String(),
                'endsAt': _end.toIso8601String(),
                'readinessPolicy': 'READY',
              },
            ]),
            200,
          );
        }
        return http.Response(jsonEncode(_holdResponse()), 201);
      }),
    );

    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData.dark(),
        home: BookingPage(repository: repository),
      ),
    );

    expect(find.text('Serv60'), findsOneWidget);
    await tester.tap(find.text('Barber'));
    await tester.tap(find.text('Check live times'));
    await tester.pumpAndSettle();

    expect(find.text('08:00'), findsOneWidget);
    expect(find.text('READY'), findsOneWidget);
    expect(find.text('Hold selected service'), findsOneWidget);

    final holdButton =
        find.widgetWithText(FilledButton, 'Hold selected service');
    await tester.drag(find.byType(ListView), const Offset(0, -400));
    await tester.pump();
    await tester.tap(holdButton);
    await tester.pumpAndSettle();

    expect(find.text('BARBER · HELD'), findsOneWidget);
    expect(requests, hasLength(2));
    expect(requests.first.method, 'GET');
    expect(requests.first.url.queryParameters['serviceCode'], 'BARBER');
    expect(requests.last.method, 'POST');
    expect(jsonDecode(requests.last.body)['serviceCode'], 'BARBER');
  });

  testWidgets('shows a recoverable error when availability fails',
      (tester) async {
    final repository = BookingRepository(
      client: MockClient((_) async => http.Response('unavailable', 503)),
    );

    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData.dark(),
        home: BookingPage(repository: repository),
      ),
    );

    await tester.tap(find.text('Check live times'));
    await tester.pumpAndSettle();

    expect(find.text('Exception: We could not load available times.'),
        findsOneWidget);
    expect(find.text('Check live times'), findsOneWidget);
  });
}
