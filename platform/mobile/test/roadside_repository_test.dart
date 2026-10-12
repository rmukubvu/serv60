import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'package:serv60_mobile/src/auth/access_token_provider.dart';
import 'package:serv60_mobile/src/roadside/roadside_repository.dart';

const incidentId = '00000000-0000-0000-0000-000000000009';
const customerId = '00000000-0000-0000-0000-000000000001';

Map<String, dynamic> incidentJson([String status = 'OPEN']) => {
      'id': incidentId,
      'customerId': customerId,
      'status': status,
      'location': 'Cape Town CBD',
      'description': 'Flat tyre',
      'vehicleId': null,
      'createdAt': '2026-10-12T08:00:00Z',
    };

void main() {
  test('creates an incident with configured customer and bearer token',
      () async {
    late http.Request request;
    final repository = RoadsideRepository(
      baseUrl: 'https://api.example.test',
      tokenProvider: CallbackAccessTokenProvider(() async => 'token-123'),
      client: MockClient((incoming) async {
        request = incoming;
        return http.Response(jsonEncode(incidentJson()), 201);
      }),
    );

    final incident = await repository.createIncident(
        location: 'Cape Town CBD', description: 'Flat tyre');

    expect(request.url.path, '/v1/roadside/incidents');
    expect(request.headers['authorization'], 'Bearer token-123');
    expect((jsonDecode(request.body) as Map<String, dynamic>)['customerId'],
        customerId);
    expect(incident.status, 'OPEN');
  });

  test('returns null when an incident no longer exists', () async {
    final repository = RoadsideRepository(
      client: MockClient((_) async => http.Response('', 404)),
    );

    expect(await repository.findIncident(incidentId), isNull);
  });

  test('cancels an incident and parses the new status', () async {
    late http.Request request;
    final repository = RoadsideRepository(
      client: MockClient((incoming) async {
        request = incoming;
        return http.Response(jsonEncode(incidentJson('CANCELLED')), 200);
      }),
    );

    final incident = await repository.cancelIncident(incidentId);

    expect(request.method, 'POST');
    expect(request.url.path, '/v1/roadside/incidents/$incidentId/cancel');
    expect(incident.status, 'CANCELLED');
  });

  test('lists active incidents for the configured customer', () async {
    late http.Request request;
    final repository = RoadsideRepository(
      baseUrl: 'https://api.example.test',
      client: MockClient((incoming) async {
        request = incoming;
        return http.Response(jsonEncode([incidentJson()]), 200);
      }),
    );

    final incidents = await repository.listIncidents();

    expect(request.url.path, '/v1/roadside/incidents');
    expect(request.url.queryParameters['customerId'], customerId);
    expect(incidents.single.id, incidentId);
  });

  test('raises a stable error when roadside creation fails', () async {
    final repository = RoadsideRepository(
      client: MockClient((_) async => http.Response('conflict', 409)),
    );

    expect(
      () => repository.createIncident(
          location: 'Cape Town CBD', description: 'Flat tyre'),
      throwsA(isA<Exception>()),
    );
  });
}
