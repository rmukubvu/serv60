import 'dart:convert';
import 'dart:math';

import 'package:http/http.dart' as http;

import '../auth/access_token_provider.dart';

class NearbyTruck {
  NearbyTruck.fromJson(Map<String, dynamic> json)
      : partnerId = json['partnerId'],
        name = json['name'],
        latitude = (json['latitude'] as num).toDouble(),
        longitude = (json['longitude'] as num).toDouble(),
        etaMinutes = json['etaMinutes'] as int,
        distanceKm = (json['distanceKm'] as num).toDouble();
  final String partnerId, name;
  final double latitude, longitude, distanceKm;
  final int etaMinutes;
}

class RoadsideQuote {
  RoadsideQuote.fromJson(Map<String, dynamic> json)
      : id = json['id'],
        partnerName = json['partnerName'],
        towAmountMinor = json['towAmountMinor'] as int,
        rideAmountMinor = json['rideAmountMinor'] as int,
        totalAmountMinor = json['totalAmountMinor'] as int,
        currency = json['currency'],
        etaMinutes = json['etaMinutes'] as int,
        distanceKm = (json['distanceKm'] as num).toDouble(),
        expiresAt = DateTime.parse(json['expiresAt']);
  final String id, partnerName, currency;
  final int towAmountMinor, rideAmountMinor, totalAmountMinor, etaMinutes;
  final double distanceKm;
  final DateTime expiresAt;
}

class RoadsideRide {
  RoadsideRide.fromJson(Map<String, dynamic> json)
      : id = json['id'],
        provider = json['provider'],
        status = json['status'],
        amountMinor = json['amountMinor'] as int,
        dropoffLabel = json['dropoffLabel'],
        trackingUrl = json['trackingUrl'],
        pinCode = json['pinCode'];
  final String id, provider, status, dropoffLabel;
  final int amountMinor;
  final String? trackingUrl, pinCode;
}

class RoadsideIncident {
  RoadsideIncident.fromJson(Map<String, dynamic> json)
      : id = json['id'],
        customerId = json['customerId'] ?? '',
        status = json['status'],
        location = json['location'] ?? '',
        description = json['description'] ?? '',
        vehicleId = json['vehicleId'],
        latitude = (json['latitude'] as num?)?.toDouble(),
        longitude = (json['longitude'] as num?)?.toDouble(),
        truckLabel = json['truckLabel'],
        truckLatitude = (json['truckLatitude'] as num?)?.toDouble(),
        truckLongitude = (json['truckLongitude'] as num?)?.toDouble(),
        etaMinutes = json['etaMinutes'] as int?,
        rideHomeRequested = json['rideHomeRequested'] == true,
        quotes = (json['quotes'] as List? ?? const [])
            .map((x) => RoadsideQuote.fromJson(x))
            .toList(),
        ride = json['ride'] == null
            ? null
            : RoadsideRide.fromJson(json['ride']),
        createdAt = DateTime.parse(json['createdAt']);

  final String id, customerId, status, location, description;
  final String? vehicleId, truckLabel;
  final double? latitude, longitude, truckLatitude, truckLongitude;
  final int? etaMinutes;
  final bool rideHomeRequested;
  final List<RoadsideQuote> quotes;
  final RoadsideRide? ride;
  final DateTime createdAt;
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

  static String newKey() => List.generate(24,
          (_) => Random.secure().nextInt(256).toRadixString(16).padLeft(2, '0'))
      .join();

  Future<List<NearbyTruck>> nearby(double lat, double lon) async {
    final response = await client.get(
      Uri.parse('$baseUrl/v1/customer/roadside/nearby')
          .replace(queryParameters: {
        'latitude': '$lat',
        'longitude': '$lon',
      }),
      headers: await _headers(),
    );
    if (response.statusCode >= 200 && response.statusCode < 300) {
      return (jsonDecode(response.body) as List)
          .map((x) => NearbyTruck.fromJson(x))
          .toList();
    }
    throw Exception('We could not find nearby tow trucks.');
  }

  Future<RoadsideIncident> createGeoIncident({
    required double latitude,
    required double longitude,
    required String locationLabel,
    required String description,
    required bool rideHomeRequested,
    String? rideDropoffLabel,
    String? vehicleId,
    String? key,
  }) async {
    final response = await client.post(
      Uri.parse('$baseUrl/v1/customer/roadside/incidents'),
      headers: await _headers({
        'Content-Type': 'application/json',
        'Idempotency-Key': key ?? newKey(),
      }),
      body: jsonEncode({
        'customerId': customerId,
        'vehicleId': vehicleId,
        'latitude': latitude,
        'longitude': longitude,
        'locationLabel': locationLabel,
        'description': description,
        'rideHomeRequested': rideHomeRequested,
        'rideDropoffLabel': rideDropoffLabel,
      }),
    );
    if (response.statusCode >= 200 && response.statusCode < 300) {
      return RoadsideIncident.fromJson(jsonDecode(response.body));
    }
    throw Exception(_error(response, 'We could not request roadside assistance.'));
  }

  Future<RoadsideIncident> acceptQuote(String incidentId, String quoteId) async {
    final response = await client.post(
      Uri.parse('$baseUrl/v1/customer/roadside/incidents/$incidentId/accept-quote'),
      headers: await _headers({'Content-Type': 'application/json'}),
      body: jsonEncode({'quoteId': quoteId}),
    );
    if (response.statusCode >= 200 && response.statusCode < 300) {
      return RoadsideIncident.fromJson(jsonDecode(response.body));
    }
    throw Exception(_error(response, 'We could not accept that tow quote.'));
  }

  Future<RoadsideIncident> detail(String id) async {
    final response = await client.get(
      Uri.parse('$baseUrl/v1/customer/roadside/incidents/$id'),
      headers: await _headers(),
    );
    if (response.statusCode >= 200 && response.statusCode < 300) {
      return RoadsideIncident.fromJson(jsonDecode(response.body));
    }
    throw Exception(_error(response, 'We could not refresh roadside assistance.'));
  }

  /// Legacy create used by older tests — still hits operator customer path.
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
    try {
      return await detail(id);
    } catch (_) {
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

  String _error(http.Response response, String fallback) {
    try {
      final data = jsonDecode(response.body);
      if (data is Map && data['error'] is String) return data['error'];
    } catch (_) {}
    return fallback;
  }

  Future<Map<String, String>> _headers(
      [Map<String, String> headers = const {}]) async {
    final token = await tokenProvider.accessToken();
    if (token == null || token.trim().isEmpty) return headers;
    return {...headers, 'Authorization': 'Bearer ${token.trim()}'};
  }
}
