import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import '../lib/src/booking/booking_repository.dart';

const holdId = '00000000-0000-0000-0000-000000000099';
final start = DateTime.utc(2026, 10, 11, 8);
final end = DateTime.utc(2026, 10, 11, 8, 45);

Map<String, dynamic> holdJson() => {
      'id': holdId,
      'status': 'HELD',
      'serviceCode': 'BARBER',
      'startsAt': start.toIso8601String(),
      'endsAt': end.toIso8601String(),
    };

void main() {
  group('BookingRepository.createHold', () {
    test('sends the booking command and parses the typed hold', () async {
      late http.Request request;
      final repository = BookingRepository(
        baseUrl: 'https://api.example.test',
        client: MockClient((incoming) async {
          request = incoming;
          return http.Response(jsonEncode(holdJson()), 201);
        }),
      );

      final hold = await repository.createHold(
        serviceCode: 'BARBER',
        startsAt: start,
        endsAt: end,
      );

      expect(request.method, 'POST');
      expect(request.url.path, '/v1/visit-holds');
      expect(request.headers['idempotency-key'], startsWith('mobile-demo-'));
      final body = jsonDecode(request.body) as Map<String, dynamic>;
      expect(body['hubId'], '00000000-0000-0000-0000-000000000060');
      expect(body['customerId'], '00000000-0000-0000-0000-000000000001');
      expect(body['serviceCode'], 'BARBER');
      expect(body['startsAt'], start.toIso8601String());
      expect(body['endsAt'], end.toIso8601String());
      expect(body['policy'], 'CONDITIONAL_PROCUREMENT');
      expect(hold.id, holdId);
      expect(hold.status, 'HELD');
      expect(hold.startsAt, start);
      expect(hold.endsAt, end);
    });

    test('raises a stable error for a rejected command', () async {
      final repository = BookingRepository(
        client: MockClient((_) async => http.Response('conflict', 409)),
      );

      expect(
        () => repository.createHold(serviceCode: 'BARBER', startsAt: start),
        throwsA(isA<Exception>()),
      );
    });
  });

  group('BookingRepository.findHold', () {
    test('returns null when the hold no longer exists', () async {
      final repository = BookingRepository(
        baseUrl: 'https://api.example.test',
        client: MockClient((request) async {
          expect(request.url.path, '/v1/visit-holds/$holdId');
          return http.Response('', 404);
        }),
      );

      expect(await repository.findHold(holdId), isNull);
    });

    test('parses a current hold status', () async {
      final repository = BookingRepository(
        client:
            MockClient((_) async => http.Response(jsonEncode(holdJson()), 200)),
      );

      final hold = await repository.findHold(holdId);

      expect(hold, isNotNull);
      expect(hold!.status, 'HELD');
      expect(hold.serviceCode, 'BARBER');
    });

    test('raises a stable error for an unavailable status endpoint', () async {
      final repository = BookingRepository(
        client: MockClient((_) async => http.Response('unavailable', 503)),
      );

      expect(() => repository.findHold(holdId), throwsA(isA<Exception>()));
    });
  });

  group('BookingRepository.availability', () {
    test('sends the requested time window and parses slots', () async {
      late http.Request request;
      final repository = BookingRepository(
        baseUrl: 'https://api.example.test',
        client: MockClient((incoming) async {
          request = incoming;
          return http.Response(
            jsonEncode([
              {
                'hubId': '00000000-0000-0000-0000-000000000060',
                'serviceCode': 'BARBER',
                'startsAt': start.toIso8601String(),
                'endsAt': end.toIso8601String(),
                'readinessPolicy': 'CONDITIONAL_PROCUREMENT',
              },
            ]),
            200,
          );
        }),
      );

      final slots =
          await repository.availability(serviceCode: 'BARBER', from: start);

      expect(request.method, 'GET');
      expect(request.url.path,
          '/v1/hubs/00000000-0000-0000-0000-000000000060/availability');
      expect(request.url.queryParameters['serviceCode'], 'BARBER');
      expect(request.url.queryParameters['from'], start.toIso8601String());
      expect(request.url.queryParameters['to'],
          start.add(const Duration(days: 1)).toIso8601String());
      expect(slots, hasLength(1));
      expect(slots.single.startsAt, start);
      expect(slots.single.readinessPolicy, 'CONDITIONAL_PROCUREMENT');
    });

    test('raises a stable error when availability cannot be loaded', () async {
      final repository = BookingRepository(
        client:
            MockClient((_) async => http.Response('downstream failure', 502)),
      );

      expect(
        () => repository.availability(serviceCode: 'BARBER', from: start),
        throwsA(isA<Exception>()),
      );
    });
  });
}
