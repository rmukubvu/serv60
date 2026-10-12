import 'dart:convert';
import 'dart:math';
import 'package:http/http.dart' as http;
import '../auth/access_token_provider.dart';
import '../payment/payment_repository.dart';

String optionLabel(String? code) => switch (code) {
      'BARBER' => 'Barber',
      'MASSAGE' => 'Massage',
      'PRIVATE_WORK' => 'Private workspace',
      _ => 'Just the car service',
    };

class Hub {
  Hub.fromJson(Map<String, dynamic> data)
      : id = data['id'],
        name = data['name'],
        address = data['address'],
        timezone = data['timezone'],
        distanceKm = (data['distanceKm'] as num).toDouble(),
        options = List<String>.from(data['complimentaryServices']);
  final String id, name, address, timezone;
  final double distanceKm;
  final List<String> options;
}

class Appointment {
  Appointment.fromJson(Map<String, dynamic> data)
      : id = data['id'],
        hubId = data['hubId'],
        hubName = data['hubName'],
        timezone = data['timezone'],
        status = data['status'],
        startsAt = DateTime.parse(data['startsAt']),
        changeDeadline = DateTime.parse(data['changeDeadline']),
        expiresAt = DateTime.parse(data['expiresAt']),
        option = data['complimentaryServiceCode'],
        revision = data['revision'],
        modifiable = data['modifiable'],
        contact = data['contact'];
  final String id, hubId, hubName, timezone, status;
  final DateTime startsAt, changeDeadline, expiresAt;
  final String? option;
  final int revision;
  final bool modifiable;
  final Map<String, dynamic>? contact;
}

class BookingPageResult {
  BookingPageResult.fromJson(Map<String, dynamic> data)
      : items = (data['items'] as List)
            .map((x) => Appointment.fromJson(x))
            .toList(),
        nextStartsAt = data['nextStartsAt'],
        nextId = data['nextId'];
  final List<Appointment> items;
  final String? nextStartsAt, nextId;
}

class BookingProposal {
  BookingProposal.fromJson(Map<String, dynamic> data)
      : id = data['id'],
        kind = data['kind'],
        hubName = data['hubName'],
        startsAt = DateTime.parse(data['startsAt']),
        expiresAt = DateTime.parse(data['expiresAt']),
        option = data['complimentaryServiceCode'],
        contact = data['contact'];
  final String id, kind, hubName;
  final DateTime startsAt, expiresAt;
  final String? option;
  final Map<String, dynamic> contact;
}

class AssistantReply {
  AssistantReply.fromJson(Map<String, dynamic> data)
      : message = data['message'],
        proposal = data['proposal'] == null
            ? null
            : BookingProposal.fromJson(data['proposal']);
  final String message;
  final BookingProposal? proposal;
}

class JourneyException implements Exception {
  JourneyException(this.message);
  final String message;
  @override
  String toString() => message;
}

class JourneyRepository {
  JourneyRepository(
      {http.Client? client,
      AccessTokenProvider? tokenProvider,
      this.baseUrl = const String.fromEnvironment('SERV60_API_BASE_URL',
          defaultValue: 'http://10.0.2.2:8080')})
      : client = client ?? http.Client(),
        tokenProvider = tokenProvider ?? NoopAccessTokenProvider();
  final http.Client client;
  final AccessTokenProvider tokenProvider;
  final String baseUrl;

  static String newKey() => List.generate(24,
          (_) => Random.secure().nextInt(256).toRadixString(16).padLeft(2, '0'))
      .join();

  Future<dynamic> _request(String method, String path,
      {Map<String, String>? query,
      Object? body,
      String? key,
      bool assistant = false}) async {
    final token = await tokenProvider.accessToken();
    final request = http.Request(method,
        Uri.parse('$baseUrl/v1/customer/$path').replace(queryParameters: query))
      ..headers.addAll({
        'Content-Type': 'application/json',
        if (token != null && token.isNotEmpty) 'Authorization': 'Bearer $token',
        if (key != null) 'Idempotency-Key': key
      });
    if (body != null) request.body = jsonEncode(body);
    final response = await client
        .send(request)
        .then(http.Response.fromStream)
        .timeout(Duration(seconds: assistant ? 45 : 15));
    if (response.statusCode >= 200 && response.statusCode < 300)
      return jsonDecode(response.body);
    String message = 'We could not complete that request. Please try again.';
    try {
      final data = jsonDecode(response.body);
      if (data is Map && data['error'] is String) message = data['error'];
    } on FormatException {
      /* A proxy may return HTML rather than the API error contract. */
    }
    throw JourneyException(message);
  }

  Future<Hub> hub(String id) async =>
      Hub.fromJson(await _request('GET', 'hubs/$id'));
  Future<PaymentOrder> payment(String id) async =>
      PaymentOrder.fromJson(await _request('POST', 'appointments/$id/payment'));
  Future<List<Hub>> nearby(double lat, double lon, {String query = ''}) async =>
      ((await _request('GET', 'hubs',
                  query: {'latitude': '$lat', 'longitude': '$lon', 'q': query}))
              as List)
          .map((x) => Hub.fromJson(x))
          .toList();
  Future<List<DateTime>> slots(String hub, DateTime date, String? option,
          {String? replacing}) async =>
      ((await _request('GET', 'hubs/$hub/slots', query: {
        'date': date.toIso8601String().split('T').first,
        if (option != null) 'complimentaryServiceCode': option,
        if (replacing != null) 'replacing': replacing
      })) as List)
          .map((x) => DateTime.parse(x['startsAt']))
          .toList();
  Future<BookingPageResult> appointments(
          {String? after, String? afterId}) async =>
      BookingPageResult.fromJson(await _request('GET', 'appointments', query: {
        if (after != null) 'after': after,
        if (afterId != null) 'afterId': afterId
      }));
  Future<void> reserve(String hub, DateTime start, String? option,
      Map<String, String> contact, String key) async {
    await _request('POST', 'appointments',
        body: {
          'hubId': hub,
          'startsAt': start.toUtc().toIso8601String(),
          'complimentaryServiceCode': option,
          'contact': contact
        },
        key: key);
  }

  Future<void> change(
      Appointment booking, DateTime start, String? option, String key) async {
    await _request('PUT', 'appointments/${booking.id}',
        body: {
          'startsAt': start.toUtc().toIso8601String(),
          'complimentaryServiceCode': option,
          'revision': booking.revision
        },
        key: key);
  }

  Future<AssistantReply> chat(
          String hub, List<Map<String, String>> messages) async =>
      AssistantReply.fromJson(await _request('POST', 'hubs/$hub/assistant',
          body: {'messages': messages}, assistant: true));
  Future<void> confirmProposal(String id) async {
    await _request('PUT', 'assistant/proposals/$id/confirm');
  }

  Future<void> confirmPayment(String id) async {
    await _request('PUT', 'appointments/$id/confirm-payment');
  }

  void close() => client.close();
}
