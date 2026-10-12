import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:serv60_mobile/src/design/serv60_theme.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'package:serv60_mobile/src/booking/booking_page.dart';
import 'package:serv60_mobile/src/booking/booking_repository.dart';
import 'package:serv60_mobile/src/payment/payment_repository.dart';

final _start = DateTime.utc(2026, 10, 11, 8);
final _end = DateTime.utc(2026, 10, 11, 9);

Map<String, dynamic> _holdResponse() => {
      'id': '00000000-0000-0000-0000-000000000099',
      'status': 'HELD',
      'serviceCode': 'EXPRESS_OIL',
      'complimentaryServiceCode': 'BARBER',
      'startsAt': _start.toIso8601String(),
      'endsAt': _end.toIso8601String(),
    };

void main() {
  testWidgets('reserves the required car service with a complimentary barber',
      (tester) async {
    final requests = <http.Request>[];
    final paymentRequests = <http.Request>[];
    final payments = PaymentRepository(client: MockClient((request) async {
      paymentRequests.add(request);
      return http.Response('Provider not configured for test', 503);
    }));
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
        theme: serv60Theme(),
        home: BookingPage(repository: repository, paymentRepository: payments),
      ),
    );

    expect(find.byType(Serv60Wordmark), findsOneWidget);
    await tester.tap(find.text('Barber'));
    await tester.scrollUntilVisible(find.text('Check live times'), 150);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Check live times'));
    await tester.pumpAndSettle();

    expect(find.text('${_start.toLocal().hour.toString().padLeft(2, '0')}:00'),
        findsOneWidget);
    expect(find.text('READY'), findsOneWidget);
    expect(find.text('Hold my visit'), findsOneWidget);

    final holdButton = find.widgetWithText(FilledButton, 'Hold my visit');
    await tester.scrollUntilVisible(holdButton, 150);
    await tester.pumpAndSettle();
    await tester.tap(holdButton);
    await tester.pumpAndSettle();

    expect(find.text('EXPRESS_OIL · HELD'), findsOneWidget);
    expect(requests, hasLength(2));
    expect(requests.first.method, 'GET');
    expect(requests.first.url.queryParameters['serviceCode'], 'EXPRESS_OIL');
    expect(requests.first.url.queryParameters['complimentaryServiceCode'],
        'BARBER');
    expect(
        jsonDecode(requests.last.body)['complimentaryServiceCode'], 'BARBER');
    expect(requests.last.method, 'POST');
    expect(jsonDecode(requests.last.body)['serviceCode'], 'EXPRESS_OIL');
    await tester.scrollUntilVisible(find.text('Pay securely with Yoco'), 150);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Pay securely with Yoco'));
    await tester.pumpAndSettle();
    expect(jsonDecode(paymentRequests.single.body)['amountMinor'], 24900);
    expect(find.text('Exception: We could not start the payment yet.'),
        findsOneWidget);
  });

  testWidgets(
      'keeps the car mandatory and permits at most one complimentary checkbox',
      (tester) async {
    await tester
        .pumpWidget(MaterialApp(theme: serv60Theme(), home: BookingPage()));
    expect(find.text('60 min · Required with every visit'), findsOneWidget);
    expect(find.byType(CheckboxListTile), findsNWidgets(2));
    List<bool> values() => tester
        .widgetList<CheckboxListTile>(find.byType(CheckboxListTile))
        .map((item) => item.value!)
        .toList();
    expect(values(), [false, false]);
    await tester.tap(find.text('Barber'));
    await tester.pump();
    expect(values(), [true, false]);
    await tester.scrollUntilVisible(find.text('Private workspace'), 150);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Private workspace'));
    await tester.pump();
    expect(values(), [false, true]);
    await tester.tap(find.text('Private workspace'));
    await tester.pump();
    expect(values(), [false, false]);
  });

  testWidgets(
      'changing the complimentary choice clears previously checked times',
      (tester) async {
    final repository = BookingRepository(
        client: MockClient((_) async => http.Response(
            jsonEncode([
              {
                'startsAt': _start.toIso8601String(),
                'endsAt': _end.toIso8601String(),
                'readinessPolicy': 'READY'
              }
            ]),
            200)));
    await tester.pumpWidget(MaterialApp(
        theme: serv60Theme(), home: BookingPage(repository: repository)));
    await tester.scrollUntilVisible(find.text('Check live times'), 150);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Check live times'));
    await tester.pumpAndSettle();
    expect(
        tester
            .widget<FilledButton>(
                find.widgetWithText(FilledButton, 'Hold my visit'))
            .onPressed,
        isNotNull);
    await tester.scrollUntilVisible(find.text('Barber'), -150);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Barber'));
    await tester.pump();
    expect(
        tester
            .widget<FilledButton>(
                find.widgetWithText(FilledButton, 'Hold my visit'))
            .onPressed,
        isNull);
    expect(find.text('READY'), findsNothing);
  });

  testWidgets('shows when no combined slot is available', (tester) async {
    final repository = BookingRepository(
        client: MockClient((_) async => http.Response('[]', 200)));
    await tester.pumpWidget(MaterialApp(
        theme: serv60Theme(), home: BookingPage(repository: repository)));
    await tester.scrollUntilVisible(find.text('Check live times'), 150);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Check live times'));
    await tester.pumpAndSettle();
    expect(find.textContaining('No times available'), findsOneWidget);
    expect(
        tester
            .widget<FilledButton>(
                find.widgetWithText(FilledButton, 'Hold my visit'))
            .onPressed,
        isNull);
  });

  testWidgets('shows a recoverable error when availability fails',
      (tester) async {
    final repository = BookingRepository(
      client: MockClient((_) async => http.Response('unavailable', 503)),
    );

    await tester.pumpWidget(
      MaterialApp(
        theme: serv60Theme(),
        home: BookingPage(repository: repository),
      ),
    );

    await tester.scrollUntilVisible(find.text('Check live times'), 150);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Check live times'));
    await tester.pumpAndSettle();

    expect(find.text('Exception: We could not load available times.'),
        findsOneWidget);
    expect(find.text('Check live times'), findsOneWidget);
  });
}
