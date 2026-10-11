import 'dart:convert';
import 'package:http/http.dart' as http;
class BookingRepository {
  BookingRepository({http.Client? client, this.baseUrl = 'http://10.0.2.2:8080'}) : client = client ?? http.Client();
  final http.Client client; final String baseUrl;
  Future<String> createHold({required String serviceCode}) async { final now = DateTime.now().toUtc(); final response = await client.post(Uri.parse('$baseUrl/v1/visit-holds'), headers: {'Content-Type': 'application/json', 'Idempotency-Key': 'mobile-demo-${now.millisecondsSinceEpoch}'}, body: jsonEncode({'hubId':'00000000-0000-0000-0000-000000000060','customerId':'00000000-0000-0000-0000-000000000001','serviceCode':serviceCode,'startsAt':now.toIso8601String(),'endsAt':now.add(const Duration(hours: 1)).toIso8601String(),'policy':'CONDITIONAL_PROCUREMENT'})); if (response.statusCode >= 200 && response.statusCode < 300) return 'Time held. Review the quote to confirm.'; return 'We could not hold that time yet. Please try again.'; }
}
