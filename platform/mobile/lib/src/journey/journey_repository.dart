import 'dart:async';
import 'dart:convert';
import 'dart:math';
import 'package:http/http.dart' as http;
import '../auth/access_token_provider.dart';
import '../payment/payment_repository.dart';
import 'event_stream.dart';

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
        distanceKm = (data['distanceKm'] as num?)?.toDouble() ?? 0,
        opensAt = data['opensAt']?.toString(),
        closesAt = data['closesAt']?.toString(),
        options = List<String>.from(data['complimentaryServices'] ?? const []);
  final String id, name, address, timezone;
  final double distanceKm;
  final String? opensAt, closesAt;
  final List<String> options;
}

class CustomerProfile {
  CustomerProfile.fromJson(Map<String, dynamic> data)
      : customerId = data['customerId'],
        customerName = data['customerName'] ?? '',
        phoneNumber = data['phoneNumber'] ?? '',
        vehicles = (data['vehicles'] as List? ?? const [])
            .map((x) => CustomerVehicle.fromJson(x))
            .toList();
  final String customerId, customerName, phoneNumber;
  final List<CustomerVehicle> vehicles;
  CustomerVehicle? get defaultVehicle {
    for (final vehicle in vehicles) {
      if (vehicle.isDefault) return vehicle;
    }
    return vehicles.isEmpty ? null : vehicles.first;
  }

  Map<String, String> get bookingContact {
    final vehicle = defaultVehicle;
    return {
      'customerName': customerName,
      'phoneNumber': phoneNumber,
      if (vehicle != null) 'vehicleMake': vehicle.vehicleMake,
      if (vehicle != null) 'vehicleModel': vehicle.vehicleModel,
      if (vehicle != null) 'registration': vehicle.registration,
    };
  }
}

class CustomerVehicle {
  CustomerVehicle.fromJson(Map<String, dynamic> data)
      : id = data['id'],
        vehicleMake = data['vehicleMake'] ?? '',
        vehicleModel = data['vehicleModel'] ?? '',
        registration = data['registration'] ?? '',
        isDefault = data['isDefault'] == true;
  final String? id;
  final String vehicleMake, vehicleModel, registration;
  final bool isDefault;
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
        modifiable = data['modifiable'] == true,
        cancellable = data['cancellable'] == true,
        contact = data['contact'];
  final String id, hubId, hubName, timezone, status;
  final DateTime startsAt, changeDeadline, expiresAt;
  final String? option;
  final int revision;
  final bool modifiable;
  final bool cancellable;
  final Map<String, dynamic>? contact;
}

class VisitHold {
  VisitHold.fromJson(Map<String, dynamic> data)
      : id = data['id'],
        hubId = data['hubId'],
        serviceCode = data['serviceCode'],
        startsAt = DateTime.parse(data['startsAt']),
        endsAt = DateTime.parse(data['endsAt']),
        status = data['status'],
        expiresAt = DateTime.parse(data['expiresAt']),
        option = data['complimentaryServiceCode'];
  final String id, hubId, serviceCode, status;
  final DateTime startsAt, endsAt, expiresAt;
  final String? option;
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

DateTime? _parseTime(dynamic value) =>
    value == null ? null : DateTime.parse(value.toString());
int? _parseInt(dynamic value) => (value as num?)?.toInt();

/// One recorded step of a visit, in the order the hub moved the car.
class StageEvent {
  StageEvent.fromJson(Map<String, dynamic> data)
      : stage = data['stage']?.toString() ?? '',
        recordedAt = _parseTime(data['recordedAt']),
        note = data['note'];
  final String stage;
  final DateTime? recordedAt;
  final String? note;
}

class ScopeItem {
  ScopeItem.fromJson(Map<String, dynamic> data)
      : description = data['description']?.toString() ?? '',
        amountMinor = _parseInt(data['amountMinor']) ?? 0;
  final String description;
  final int amountMinor;
}

/// Extra work the workshop proposes mid-visit for the customer to approve or
/// decline; approved work is settled at handover.
class ScopeChange {
  ScopeChange.fromJson(Map<String, dynamic> data)
      : id = data['id']?.toString() ?? '',
        visitId = data['visitId']?.toString() ?? '',
        status = data['status']?.toString() ?? 'PROPOSED',
        items = (data['items'] as List? ?? const [])
            .map((x) => ScopeItem.fromJson(x))
            .toList(),
        note = data['note'],
        totalMinor = _parseInt(data['totalMinor']) ?? 0,
        currency = data['currency']?.toString() ?? 'ZAR',
        proposedAt = _parseTime(data['proposedAt']),
        decidedAt = _parseTime(data['decidedAt']);
  final String id, visitId, status, currency;
  final List<ScopeItem> items;
  final String? note;
  final int totalMinor;
  final DateTime? proposedAt, decidedAt;
  bool get proposed => status == 'PROPOSED';
}

/// Live state of a visit. [stage] is null until the hub checks the car in;
/// [elapsedMinutes] counts from check-in and freezes once the car is ready.
class VisitProgress {
  VisitProgress.fromJson(Map<String, dynamic> data)
      : visitId = data['visitId']?.toString() ?? '',
        hubName = data['hubName']?.toString() ?? '',
        startsAt = _parseTime(data['startsAt']),
        option = data['complimentaryServiceCode'],
        stage = data['stage'],
        checkedInAt = _parseTime(data['checkedInAt']),
        readyAt = _parseTime(data['readyAt']),
        handedOverAt = _parseTime(data['handedOverAt']),
        targetMinutes = _parseInt(data['targetMinutes']) ?? 60,
        elapsedMinutes = _parseInt(data['elapsedMinutes']),
        events = (data['events'] as List? ?? const [])
            .map((x) => StageEvent.fromJson(x))
            .toList(),
        scopeChanges = (data['scopeChanges'] as List? ?? const [])
            .map((x) => ScopeChange.fromJson(x))
            .toList(),
        voucherCode = data['voucherCode'];
  final String visitId, hubName;
  final DateTime? startsAt, checkedInAt, readyAt, handedOverAt;
  final String? option, stage, voucherCode;
  final int targetMinutes;
  final int? elapsedMinutes;
  final List<StageEvent> events;
  final List<ScopeChange> scopeChanges;
  bool get checkedIn => stage != null;
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
  Future<VisitHold> reserve(String hub, DateTime start, String? option,
      Map<String, String> contact, String key,
      {String? vehicleId}) async {
    return VisitHold.fromJson(await _request('POST', 'appointments',
        body: {
          'hubId': hub,
          'startsAt': start.toUtc().toIso8601String(),
          'complimentaryServiceCode': option,
          if (vehicleId != null) 'vehicleId': vehicleId,
          'contact': contact
        },
        key: key));
  }

  Future<Appointment> cancel(String id) async =>
      Appointment.fromJson(await _request('POST', 'appointments/$id/cancel'));

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

  Future<CustomerProfile> profile() async =>
      CustomerProfile.fromJson(await _request('GET', 'profile'));

  Future<CustomerProfile> saveProfile(
          {required String customerName,
          required String phoneNumber,
          List<Map<String, dynamic>> vehicles = const []}) async =>
      CustomerProfile.fromJson(await _request('PUT', 'profile', body: {
        'customerName': customerName,
        'phoneNumber': phoneNumber,
        'vehicles': vehicles
      }));

  Future<VisitProgress> progress(String id) async => VisitProgress.fromJson(
      await _request('GET', 'appointments/$id/progress'));

  Future<VisitProgress> decideScopeChange(
          String id, String scopeId, String decision) async =>
      VisitProgress.fromJson(await _request(
          'PUT', 'appointments/$id/scope-changes/$scopeId/decision',
          body: {'decision': decision}));

  /// Waits between reconnects of [progressStream]; the last entry repeats.
  static const progressStreamBackoff = [
    Duration(seconds: 1),
    Duration(seconds: 2),
    Duration(seconds: 5),
    Duration(seconds: 10),
  ];

  /// The server comments `:keepalive` every 15 s, so three missed in a row
  /// mean the socket is dead even though it never closed.
  static const progressStreamIdleTimeout = Duration(seconds: 45);

  /// Live [VisitProgress] for one appointment over server-sent events.
  ///
  /// The server sends the current progress on connect and again on every
  /// change. The stream reconnects with [progressStreamBackoff] when the
  /// connection drops, goes silent or is answered with anything but 200,
  /// skips events it cannot parse, and completes when the listener cancels.
  Stream<VisitProgress> progressStream(String id) {
    final uri =
        Uri.parse('$baseUrl/v1/customer/appointments/$id/progress/stream');
    late final StreamController<VisitProgress> controller;
    StreamSubscription<ServerEvent>? connection;
    Timer? retry;
    var failures = 0, closed = false;

    Future<void> connect() async {
      // Drops the current connection, if any, and schedules the next attempt.
      // The connection is not awaited: its cancel future may settle on the
      // root zone, which would stall a fake clock, and nothing depends on it.
      void retryLater() {
        if (closed || (retry?.isActive ?? false)) return;
        connection?.cancel();
        connection = null;
        final wait = progressStreamBackoff[
            min(failures, progressStreamBackoff.length - 1)];
        failures++;
        retry = Timer(wait, connect);
      }

      try {
        final token = await tokenProvider.accessToken();
        if (closed) return;
        connection = openEventStream(client, uri,
                headers: {
                  if (token != null && token.isNotEmpty)
                    'Authorization': 'Bearer $token'
                },
                idleTimeout: progressStreamIdleTimeout)
            .listen((event) {
          if (event.name != 'progress') return;
          final VisitProgress latest;
          try {
            latest = VisitProgress.fromJson(jsonDecode(event.data));
          } catch (_) {
            return; // A malformed event is not worth dropping the stream.
          }
          failures = 0;
          controller.add(latest);
        }, onError: (Object _) => retryLater(), onDone: retryLater);
      } catch (_) {
        retryLater();
      }
    }

    controller = StreamController<VisitProgress>(
        onListen: connect,
        onCancel: () {
          closed = true;
          retry?.cancel();
          return connection?.cancel();
        });
    return controller.stream;
  }

  void close() => client.close();
}
