import 'dart:convert';

import 'package:http/http.dart' as http;

import '../auth/access_token_provider.dart';

class RoadsideIncident {
  const RoadsideIncident({
    required this.id,
    required this.customerId,
    required this.status,
    required this.location,
    required this.description,
    this.vehicleId,
    required this.createdAt,
  });

  final String id;
  final String customerId;
  final String status;
  final String location;
  final String description;
  final String? vehicleId;
  final DateTime createdAt;

  factory RoadsideIncident.fromJson(Map<String, dynamic> json) =>
      RoadsideIncident(
        id: json['id'] as String,
        customerId: json['customerId'] as String,
        status: json['status'] as String,
        location: json['location'] as String,
        description: json['description'] as String,
        vehicleId: json['vehicleId'] as String?,
        createdAt: DateTime.parse(json['createdAt'] as String),
      );
}

class RoadsideRepository {
  RoadsideRepository({
    http.Client? client,
    AccessTokenProvider? tokenProvider,
    this.baseUrl = const String.fromEnvironment('SERV60_API_BASE_URL',
        defaultValue: 'http://10.0.2.2:8080'),
    this.customerId = const String.fromEnvironment('SERV60_CUSTOMER_ID',
        defaultValue: '00000000-0000-0000-0000-000000000001'),
  })  : client = client ?? http.Client(),
        tokenProvider = tokenProvider ?? NoopAccessTokenProvider();

  final http.Client client;
  final AccessTokenProvider tokenProvider;
  final String baseUrl;
  final String customerId;

  Future<RoadsideIncident> createIncident({
    required String location,
    required String description,
    String? vehicleId,
  }) async {
    final response = await client.post(
      Uri.parse('$baseUrl/v1/roadside/incidents'),
      headers: await _headers({'Content-Type': 'application/json'}),
      body: jsonEncode({
        'customerId': customerId,
        'vehicleId': vehicleId,
        'location': location,
        'description': description,
      }),
    );
    if (response.statusCode >= 200 && response.statusCode < 300) {
      return RoadsideIncident.fromJson(
          jsonDecode(response.body) as Map<String, dynamic>);
    }
    throw Exception('We could not request roadside assistance.');
  }

  Future<RoadsideIncident?> findIncident(String id) async {
    final response = await client.get(
      Uri.parse('$baseUrl/v1/roadside/incidents/$id'),
      headers: await _headers(),
    );
    if (response.statusCode == 404) return null;
    if (response.statusCode >= 200 && response.statusCode < 300) {
      return RoadsideIncident.fromJson(
          jsonDecode(response.body) as Map<String, dynamic>);
    }
    throw Exception('We could not refresh roadside assistance.');
  }

  Future<List<RoadsideIncident>> listIncidents() async {
    final uri = Uri.parse('$baseUrl/v1/roadside/incidents')
        .replace(queryParameters: {'customerId': customerId, 'limit': '20'});
    final response = await client.get(uri, headers: await _headers());
    if (response.statusCode >= 200 && response.statusCode < 300) {
      return (jsonDecode(response.body) as List<dynamic>)
          .map(
              (item) => RoadsideIncident.fromJson(item as Map<String, dynamic>))
          .toList();
    }
    throw Exception('We could not load roadside assistance.');
  }

  Future<RoadsideIncident> cancelIncident(String id) async {
    final response = await client.post(
      Uri.parse('$baseUrl/v1/roadside/incidents/$id/cancel'),
      headers: await _headers(),
    );
    if (response.statusCode >= 200 && response.statusCode < 300) {
      return RoadsideIncident.fromJson(
          jsonDecode(response.body) as Map<String, dynamic>);
    }
    throw Exception('We could not cancel roadside assistance.');
  }

  Future<Map<String, String>> _headers(
      [Map<String, String> headers = const {}]) async {
    final token = await tokenProvider.accessToken();
    if (token == null || token.trim().isEmpty) return headers;
    return {...headers, 'Authorization': 'Bearer ${token.trim()}'};
  }
}
