import 'dart:convert';
import 'package:http/http.dart' as http;

import '../auth/access_token_provider.dart';

class VisitHold {
  const VisitHold(
      {required this.id,
      required this.status,
      required this.serviceCode,
      required this.startsAt,
      required this.endsAt});
  final String id;
  final String status;
  final String serviceCode;
  final DateTime startsAt;
  final DateTime endsAt;
  factory VisitHold.fromJson(Map<String, dynamic> json) => VisitHold(
      id: json['id'] as String,
      status: json['status'] as String,
      serviceCode: json['serviceCode'] as String,
      startsAt: DateTime.parse(json['startsAt'] as String),
      endsAt: DateTime.parse(json['endsAt'] as String));
}

class AvailabilitySlot {
  const AvailabilitySlot(
      {required this.startsAt,
      required this.endsAt,
      required this.readinessPolicy});
  final DateTime startsAt;
  final DateTime endsAt;
  final String readinessPolicy;
  factory AvailabilitySlot.fromJson(Map<String, dynamic> json) =>
      AvailabilitySlot(
          startsAt: DateTime.parse(json['startsAt'] as String),
          endsAt: DateTime.parse(json['endsAt'] as String),
          readinessPolicy: json['readinessPolicy'] as String);
}

class BookingRepository {
  BookingRepository(
      {http.Client? client,
      AccessTokenProvider? tokenProvider,
      this.baseUrl = const String.fromEnvironment('SERV60_API_BASE_URL',
          defaultValue: 'http://10.0.2.2:8080'),
      this.hubId = const String.fromEnvironment('SERV60_HUB_ID',
          defaultValue: '00000000-0000-0000-0000-000000000060'),
      this.customerId = const String.fromEnvironment('SERV60_CUSTOMER_ID',
          defaultValue: '00000000-0000-0000-0000-000000000001')})
      : client = client ?? http.Client(),
        tokenProvider = tokenProvider ?? NoopAccessTokenProvider();
  final http.Client client;
  final AccessTokenProvider tokenProvider;
  final String baseUrl;
  final String hubId;
  final String customerId;
  Future<VisitHold> createHold(
      {required String serviceCode,
      DateTime? startsAt,
      DateTime? endsAt}) async {
    final now = startsAt ?? DateTime.now().toUtc();
    final finish = endsAt ?? now.add(const Duration(hours: 1));
    final response = await client.post(Uri.parse('$baseUrl/v1/visit-holds'),
        headers: await _headers({
          'Content-Type': 'application/json',
          'Idempotency-Key': 'mobile-demo-${now.millisecondsSinceEpoch}'
        }),
        body: jsonEncode({
          'hubId': hubId,
          'customerId': customerId,
          'serviceCode': serviceCode,
          'startsAt': now.toIso8601String(),
          'endsAt': finish.toIso8601String(),
          'policy': 'CONDITIONAL_PROCUREMENT'
        }));
    if (response.statusCode >= 200 && response.statusCode < 300)
      return VisitHold.fromJson(
          jsonDecode(response.body) as Map<String, dynamic>);
    throw Exception('We could not hold that time yet.');
  }

  Future<VisitHold?> findHold(String id) async {
    final response = await client.get(
      Uri.parse('$baseUrl/v1/visit-holds/$id'),
      headers: await _headers(),
    );
    if (response.statusCode == 404) return null;
    if (response.statusCode >= 200 && response.statusCode < 300)
      return VisitHold.fromJson(
          jsonDecode(response.body) as Map<String, dynamic>);
    throw Exception('We could not refresh the visit status.');
  }

  Future<VisitHold> confirmHold(String id) async {
    final response = await client.post(
      Uri.parse('$baseUrl/v1/visit-holds/$id/confirm'),
      headers: await _headers(),
    );
    if (response.statusCode >= 200 && response.statusCode < 300) {
      return VisitHold.fromJson(
          jsonDecode(response.body) as Map<String, dynamic>);
    }
    throw Exception('We could not confirm the paid visit.');
  }

  Future<List<AvailabilitySlot>> availability(
      {required String serviceCode, DateTime? from}) async {
    final start = (from ?? DateTime.now()).toUtc();
    final uri = Uri.parse('$baseUrl/v1/hubs/$hubId/availability')
        .replace(queryParameters: {
      'serviceCode': serviceCode,
      'from': start.toIso8601String(),
      'to': start.add(const Duration(days: 1)).toIso8601String()
    });
    final response = await client.get(uri, headers: await _headers());
    if (response.statusCode >= 200 && response.statusCode < 300)
      return (jsonDecode(response.body) as List<dynamic>)
          .map(
              (item) => AvailabilitySlot.fromJson(item as Map<String, dynamic>))
          .toList();
    throw Exception('We could not load available times.');
  }

  Future<Map<String, String>> _headers(
      [Map<String, String> headers = const {}]) async {
    final token = await tokenProvider.accessToken();
    if (token == null || token.trim().isEmpty) return headers;
    return {...headers, 'Authorization': 'Bearer ${token.trim()}'};
  }
}
