import 'dart:convert';
import 'package:http/http.dart' as http;

class VisitHold {
  const VisitHold({required this.id, required this.status, required this.serviceCode, required this.startsAt, required this.endsAt});
  final String id;
  final String status;
  final String serviceCode;
  final DateTime startsAt;
  final DateTime endsAt;
  factory VisitHold.fromJson(Map<String, dynamic> json) => VisitHold(id: json['id'] as String, status: json['status'] as String, serviceCode: json['serviceCode'] as String, startsAt: DateTime.parse(json['startsAt'] as String), endsAt: DateTime.parse(json['endsAt'] as String));
}

class BookingRepository {
  BookingRepository({http.Client? client, this.baseUrl = 'http://10.0.2.2:8080'}) : client = client ?? http.Client();
  final http.Client client; final String baseUrl;
  Future<VisitHold> createHold({required String serviceCode}) async { final now = DateTime.now().toUtc(); final response = await client.post(Uri.parse('$baseUrl/v1/visit-holds'), headers: {'Content-Type': 'application/json', 'Idempotency-Key': 'mobile-demo-${now.millisecondsSinceEpoch}'}, body: jsonEncode({'hubId':'00000000-0000-0000-0000-000000000060','customerId':'00000000-0000-0000-0000-000000000001','serviceCode':serviceCode,'startsAt':now.toIso8601String(),'endsAt':now.add(const Duration(hours: 1)).toIso8601String(),'policy':'CONDITIONAL_PROCUREMENT'})); if (response.statusCode >= 200 && response.statusCode < 300) return VisitHold.fromJson(jsonDecode(response.body) as Map<String, dynamic>); throw Exception('We could not hold that time yet.'); }
  Future<VisitHold?> findHold(String id) async { final response = await client.get(Uri.parse('$baseUrl/v1/visit-holds/$id')); if (response.statusCode == 404) return null; if (response.statusCode >= 200 && response.statusCode < 300) return VisitHold.fromJson(jsonDecode(response.body) as Map<String, dynamic>); throw Exception('We could not refresh the visit status.'); }
}
