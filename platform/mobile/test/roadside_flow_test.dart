import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart';
import 'package:http/testing.dart';
import 'package:serv60_mobile/src/design/serv60_theme.dart';
import 'package:serv60_mobile/src/roadside/roadside_page.dart';
import 'package:serv60_mobile/src/roadside/roadside_repository.dart';

import 'journey_test.dart' show FakeLocation;

Map<String, dynamic> quote(String id, String partner, int total) => {
      'id': id,
      'partnerId': 'p-$id',
      'partnerName': partner,
      'towAmountMinor': total - 8500,
      'rideAmountMinor': 8500,
      'totalAmountMinor': total,
      'currency': 'ZAR',
      'etaMinutes': 12,
      'distanceKm': 4.2,
      'expiresAt': '2035-01-01T12:00:00Z'
    };

Map<String, dynamic> incident(String status, {bool dispatched = false}) => {
      'id': 'incident-1',
      'customerId': 'c',
      'status': status,
      'location': 'Pin',
      'description': 'Flat',
      'latitude': -33.9,
      'longitude': 18.4,
      'rideHomeRequested': true,
      'quotes': [
        quote('q1', 'City Bowl Tow', 60000),
        quote('q2', 'Sea Point Recovery', 58000)
      ],
      if (dispatched) ...{
        'truckLabel': 'Sea Point Recovery',
        'truckLatitude': -33.91,
        'truckLongitude': 18.39,
        'etaMinutes': 9,
      },
      'ride': {
        'id': 'ride-1',
        'provider': 'LOCAL_PARTNER',
        'status': dispatched ? 'REQUESTED' : 'QUOTED',
        'amountMinor': 8500,
        'dropoffLabel': 'Office',
        'trackingUrl': dispatched ? 'https://rides.example.test/r1' : null,
        'pinCode': dispatched ? '1234' : null,
      },
      'createdAt': '2026-10-12T08:00:00Z'
    };

Response json(Object body, [int status = 200]) =>
    Response(jsonEncode(body), status,
        headers: {'content-type': 'application/json'});

Widget page(RoadsideRepository repository) => MaterialApp(
    theme: serv60Theme(),
    home: Scaffold(
        body: RoadsidePage(repository: repository, location: FakeLocation())));

Future<void> tapScrolled(WidgetTester tester, Finder finder) async {
  await tester.scrollUntilVisible(finder, 300,
      scrollable: find.byType(Scrollable).first);
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
      'requests a ride home, accepts the chosen quote and keeps polling',
      (tester) async {
    String? acceptedQuote;
    var refreshes = 0;
    final repository = RoadsideRepository(client: MockClient((request) async {
      final path = request.url.path;
      if (path.endsWith('/nearby')) {
        return json([
          {
            'partnerId': 'p1',
            'name': 'City Bowl Tow',
            'latitude': -33.92,
            'longitude': 18.42,
            'etaMinutes': 10,
            'distanceKm': 1.2
          }
        ]);
      }
      if (path.endsWith('/accept-quote')) {
        acceptedQuote = jsonDecode(request.body)['quoteId'];
        return json(incident('EN_ROUTE', dispatched: true));
      }
      if (request.method == 'GET') {
        refreshes++;
        return json(incident('EN_ROUTE', dispatched: true));
      }
      return json(incident('QUOTED'), 201);
    }));
    await tester.pumpWidget(page(repository));
    await tester.pumpAndSettle();

    await tapScrolled(tester, find.text('Also get me a ride home'));
    await tester.enterText(
        find.widgetWithText(TextField, 'Ride drop-off'), 'Office');
    await tapScrolled(tester, find.text('Request tow quotes'));
    await tapScrolled(tester, find.textContaining('Sea Point Recovery · R580'));
    await tapScrolled(tester, find.textContaining('Accept quote'));
    await tester.pump(const Duration(seconds: 9));

    expect(acceptedQuote, 'q2');
    expect(find.textContaining('Ride home · LOCAL_PARTNER · REQUESTED'),
        findsOneWidget);
    expect(find.text('ETA about 9 minutes'), findsOneWidget);
    expect(find.text('PIN 1234'), findsOneWidget);
    expect(refreshes, greaterThanOrEqualTo(1));
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('shows nearby, quote and dispatch failures', (tester) async {
    var acceptFails = false;
    final repository = RoadsideRepository(client: MockClient((request) async {
      final path = request.url.path;
      if (path.endsWith('/nearby')) return Response('', 503);
      if (path.endsWith('/accept-quote'))
        return json({'error': 'That partner went offline'}, 409);
      if (!acceptFails) {
        acceptFails = true;
        return json({'error': 'Outside our service area'}, 400);
      }
      return json(incident('QUOTED'), 201);
    }));
    await tester.pumpWidget(page(repository));
    await tester.pumpAndSettle();
    expect(find.text('Exception: We could not find nearby tow trucks.'),
        findsOneWidget);

    await tapScrolled(tester, find.text('Request tow quotes'));
    expect(find.textContaining('Outside our service area'), findsOneWidget);
    await tapScrolled(tester, find.text('Request tow quotes'));
    await tapScrolled(tester, find.textContaining('Accept quote'));

    expect(find.textContaining('That partner went offline'), findsOneWidget);
  });

  test('repository surfaces server errors for every roadside call', () async {
    var legacyStatus = 404;
    final repository = RoadsideRepository(
        baseUrl: 'https://api.example.test',
        client: MockClient((request) async {
          if (request.url.path
              .startsWith('/v1/roadside/incidents/incident-1')) {
            return legacyStatus == 200
                ? json(incident('OPEN'))
                : Response('', legacyStatus);
          }
          return json({'error': 'Service paused'}, 500);
        }));

    expect(await repository.findIncident('incident-1'), isNull);
    legacyStatus = 200;
    expect((await repository.findIncident('incident-1'))!.status, 'OPEN');
    legacyStatus = 500;
    await expectLater(repository.findIncident('incident-1'), throwsException);
    await expectLater(repository.detail('incident-1'),
        throwsA(predicate((e) => e.toString().contains('Service paused'))));
    await expectLater(
        repository.acceptQuote('incident-1', 'q1'), throwsException);
    await expectLater(repository.nearby(-33.9, 18.4), throwsException);
    await expectLater(
        repository.createGeoIncident(
            latitude: -33.9,
            longitude: 18.4,
            locationLabel: 'Pin',
            description: 'Flat',
            rideHomeRequested: false),
        throwsException);
    await expectLater(repository.listIncidents(), throwsException);
    await expectLater(repository.cancelIncident('incident-1'), throwsException);
  });
}
