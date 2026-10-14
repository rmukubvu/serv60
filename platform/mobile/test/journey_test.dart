import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:geolocator/geolocator.dart';
import 'package:serv60_mobile/src/auth/access_token_provider.dart';
import 'package:serv60_mobile/src/design/serv60_theme.dart';
import 'package:serv60_mobile/src/journey/journey_repository.dart';
import 'package:serv60_mobile/src/journey/customer_location.dart';
import 'package:serv60_mobile/src/journey/discovery_page.dart';
import 'package:serv60_mobile/src/journey/assistant_page.dart';
import 'package:serv60_mobile/src/journey/appointments_page.dart';
import 'package:serv60_mobile/src/journey/plan_visit_page.dart';
import 'package:serv60_mobile/src/journey/journey_widgets.dart';
import 'package:serv60_mobile/src/payment/payment_repository.dart';
import 'package:serv60_mobile/src/payment/payment_checkout.dart';
import 'payment_checkout_test.dart'
    show FakeLauncher, FakePendingStore, FakePaymentRepository, order;

final start = DateTime.now().toUtc().add(const Duration(days: 5));
final hubJson = <String, dynamic>{
  'id': 'hub-1',
  'name': 'Sea Point',
  'address': 'Demo location',
  'timezone': 'Africa/Johannesburg',
  'distanceKm': 3.2,
  'complimentaryServices': ['BARBER', 'MASSAGE']
};
final contact = <String, dynamic>{
  'customerName': 'Demo Customer',
  'phoneNumber': '+27000000000',
  'vehicleMake': 'Toyota',
  'vehicleModel': 'Corolla',
  'registration': 'DEMO060'
};
Map<String, dynamic> appointmentJson(
        {String status = 'CONFIRMED',
        bool modifiable = true,
        bool cancellable = true}) =>
    {
      'id': 'visit-1',
      'hubId': 'hub-1',
      'hubName': 'Sea Point',
      'timezone': 'Africa/Johannesburg',
      'status': status,
      'startsAt': start.toIso8601String(),
      'changeDeadline':
          start.subtract(const Duration(hours: 48)).toIso8601String(),
      'expiresAt': start.toIso8601String(),
      'complimentaryServiceCode': 'BARBER',
      'revision': 2,
      'modifiable': modifiable,
      'cancellable': cancellable,
      'contact': contact
    };
Map<String, dynamic> proposalJson({String kind = 'BOOK'}) => {
      'id': 'proposal-1',
      'kind': kind,
      'hubName': 'Sea Point',
      'startsAt': start.toIso8601String(),
      'expiresAt': start.toIso8601String(),
      'complimentaryServiceCode': 'BARBER',
      'contact': contact
    };

class FakeJourney extends JourneyRepository {
  List<Hub> hubs = [Hub.fromJson(hubJson)];
  List<Appointment> bookings = [];
  List<DateTime> times = [start];
  String? failure, selectedOption, confirmed, paid;
  int changes = 0, reservations = 0;
  bool more = false;
  List<Map<String, String>>? history;
  AssistantReply response = AssistantReply.fromJson(
      {'message': 'Review your visit', 'proposal': proposalJson()});
  void check() {
    if (failure != null) throw JourneyException(failure!);
  }

  @override
  Future<List<Hub>> nearby(double lat, double lon, {String query = ''}) async {
    check();
    final needle = query.trim().toLowerCase();
    if (needle.isEmpty) return hubs;
    return hubs
        .where((h) =>
            '${h.name} ${h.address} ${h.options.map(optionLabel).join(' ')}'
                .toLowerCase()
                .contains(needle))
        .toList();
  }

  @override
  Future<Hub> hub(String id) async {
    check();
    return hubs.first;
  }

  @override
  Future<List<DateTime>> slots(String hub, DateTime date, String? option,
      {String? replacing}) async {
    check();
    selectedOption = option;
    return times;
  }

  @override
  Future<BookingPageResult> appointments(
      {String? after, String? afterId}) async {
    check();
    return BookingPageResult.fromJson({
      'items': bookings
          .map((x) =>
              appointmentJson(status: x.status, modifiable: x.modifiable))
          .toList(),
      'nextStartsAt': more ? start.toIso8601String() : null,
      'nextId': more ? 'next' : null
    });
  }

  @override
  Future<VisitHold> reserve(String hub, DateTime start, String? option,
      Map<String, String> contact, String key,
      {String? vehicleId}) async {
    check();
    reservations++;
    return VisitHold.fromJson({
      'id': 'visit-1',
      'hubId': hub,
      'serviceCode': 'EXPRESS_OIL',
      'startsAt': start.toIso8601String(),
      'endsAt': start.add(const Duration(hours: 1)).toIso8601String(),
      'status': 'HELD',
      'expiresAt': DateTime.now()
          .toUtc()
          .add(const Duration(minutes: 5))
          .toIso8601String(),
      'complimentaryServiceCode': option,
    });
  }

  @override
  Future<Appointment> cancel(String id) async {
    check();
    return Appointment.fromJson(appointmentJson(status: 'CANCELLED'));
  }

  @override
  Future<void> change(
      Appointment booking, DateTime start, String? option, String key) async {
    check();
    changes++;
  }

  @override
  Future<AssistantReply> chat(
      String hub, List<Map<String, String>> messages) async {
    check();
    history = List.of(messages);
    return response;
  }

  @override
  Future<void> confirmProposal(String id) async {
    check();
    confirmed = id;
  }

  @override
  Future<PaymentOrder> payment(String id) async {
    check();
    return order(url: 'https://checkout.yoco.test');
  }

  @override
  Future<void> confirmPayment(String id) async {
    check();
    paid = id;
  }
}

class FakeLocation implements CustomerLocation {
  bool fail = false;
  @override
  Future<({double latitude, double longitude})> current() async {
    if (fail) throw JourneyException('Location unavailable');
    return (latitude: -33.925, longitude: 18.424);
  }
}

class FakeGeo extends GeolocatorPlatform {
  bool enabled = true;
  LocationPermission permission = LocationPermission.denied,
      requested = LocationPermission.whileInUse;
  @override
  Future<bool> isLocationServiceEnabled() async => enabled;
  @override
  Future<LocationPermission> checkPermission() async => permission;
  @override
  Future<LocationPermission> requestPermission() async => requested;
  @override
  Future<Position> getCurrentPosition(
          {LocationSettings? locationSettings}) async =>
      Position(
          latitude: -33.925,
          longitude: 18.424,
          timestamp: DateTime.now(),
          accuracy: 30,
          altitude: 0,
          altitudeAccuracy: 0,
          heading: 0,
          headingAccuracy: 0,
          speed: 0,
          speedAccuracy: 0);
}

Widget app(Widget page) =>
    MaterialApp(theme: serv60Theme(), home: Scaffold(body: page));
Future<void> tapVisible(WidgetTester tester, Finder finder) async {
  if (finder.evaluate().isEmpty)
    await tester.scrollUntilVisible(finder, 200,
        scrollable: find.byType(Scrollable).first);
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

void main() {
  test(
      'customer API attaches authentication and only server-scoped booking fields',
      () async {
    final requests = <http.Request>[];
    final repo = JourneyRepository(
        baseUrl: 'https://api.test',
        tokenProvider: CallbackAccessTokenProvider(() async => 'token'),
        client: MockClient((r) async {
          requests.add(r);
          dynamic result = {};
          if (r.url.path.endsWith('/hubs'))
            result = [hubJson];
          else if (r.url.path.endsWith('/hubs/hub-1'))
            result = hubJson;
          else if (r.url.path.endsWith('/slots'))
            result = [
              {'startsAt': start.toIso8601String()}
            ];
          else if (r.method == 'GET' && r.url.path.endsWith('/appointments'))
            result = {
              'items': [appointmentJson()],
              'nextStartsAt': null,
              'nextId': null
            };
          else if (r.url.path.endsWith('/assistant'))
            result = {'message': 'Review', 'proposal': proposalJson()};
          else if (r.url.path.endsWith('/payment'))
            result = {
              'id': 'pay',
              'customerId': 'customer',
              'holdId': 'visit-1',
              'amountMinor': 24900,
              'currency': 'ZAR',
              'provider': 'YOCO',
              'status': 'PENDING',
              'createdAt': start.toIso8601String()
            };
          else if (r.method == 'POST' && r.url.path.endsWith('/appointments'))
            result = {
              'id': 'visit-1',
              'hubId': 'hub-1',
              'serviceCode': 'EXPRESS_OIL',
              'startsAt': start.toIso8601String(),
              'endsAt': start.add(const Duration(hours: 1)).toIso8601String(),
              'status': 'HELD',
              'expiresAt': start.toIso8601String(),
              'complimentaryServiceCode': 'BARBER',
            };
          return http.Response(jsonEncode(result), 200);
        }));
    expect((await repo.nearby(1, 2, query: 'coffee')).first.distanceKm, 3.2);
    expect((await repo.hub('hub-1')).options, ['BARBER', 'MASSAGE']);
    expect(await repo.slots('hub-1', start, 'BARBER', replacing: 'visit-1'),
        [start]);
    final page = await repo.appointments(
        after: start.toIso8601String(), afterId: 'previous');
    expect(page.items.first.revision, 2);
    final key = JourneyRepository.newKey();
    await repo.reserve(
        'hub-1', start, 'BARBER', contact.cast<String, String>(), key);
    await repo.change(page.items.first, start, null, key);
    expect(
        (await repo.chat('hub-1', [
          {'role': 'user', 'content': 'Hi'}
        ]))
            .proposal!
            .kind,
        'BOOK');
    await repo.confirmProposal('proposal-1');
    await repo.confirmPayment('visit-1');
    expect((await repo.payment('visit-1')).amountMinor, 24900);
    expect(requests.every((r) => r.headers['Authorization'] == 'Bearer token'),
        isTrue);
    final create = requests.firstWhere(
        (r) => r.method == 'POST' && r.url.path.endsWith('/appointments'));
    expect(jsonDecode(create.body).containsKey('customerId'), isFalse);
    expect(create.headers['Idempotency-Key'], key);
    expect(optionLabel(null), 'Just the car service');
    expect(optionLabel('PRIVATE_WORK'), 'Private workspace');
    repo.close();
  });
  test('API errors are readable including non-JSON gateway failures', () async {
    for (final body in ['{"error":"Slot taken"}', 'Bad gateway']) {
      final repo = JourneyRepository(
          client: MockClient((_) async => http.Response(body, 409)));
      await expectLater(repo.nearby(1, 2), throwsA(isA<JourneyException>()));
      repo.close();
    }
  });
  test(
      'foreground location handles disabled denied permanent and granted permission',
      () async {
    final original = GeolocatorPlatform.instance;
    final fake = FakeGeo();
    GeolocatorPlatform.instance = fake;
    addTearDown(() => GeolocatorPlatform.instance = original);
    final location = DeviceCustomerLocation();
    expect((await location.current()).latitude, -33.925);
    fake.enabled = false;
    await expectLater(location.current(), throwsA(isA<JourneyException>()));
    fake.enabled = true;
    fake.requested = LocationPermission.denied;
    await expectLater(location.current(), throwsA(isA<JourneyException>()));
    fake.permission = LocationPermission.deniedForever;
    await expectLater(location.current(), throwsA(isA<JourneyException>()));
    fake.permission = LocationPermission.always;
    expect((await location.current()).longitude, 18.424);
  });
  testWidgets(
      'discovery requests location, searches real options and selects a hub',
      (tester) async {
    final repo = FakeJourney();
    String? booked, asked;
    final location = FakeLocation();
    await tester.pumpWidget(app(DiscoveryPage(
        repository: repo,
        location: location,
        demoEnabled: true,
        onBook: (h) => booked = h.id,
        onAsk: (h) => asked = h.id)));
    await tapVisible(tester, find.text('Use my location'));
    expect(find.text('Sea Point'), findsOneWidget);
    await tester.enterText(find.byType(TextField), 'massage');
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pumpAndSettle();
    expect(find.text('1 hub nearby'), findsOneWidget);
    await tapVisible(tester, find.text('Book a visit'));
    expect(booked, 'hub-1');
    await tapVisible(tester, find.text('Details'));
    await tester.pumpAndSettle();
    await tapVisible(tester, find.text('Ask about times'));
    expect(asked, 'hub-1');
    await tester.enterText(find.byType(TextField), 'nothing');
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pumpAndSettle();
    expect(find.textContaining('No hubs match'), findsOneWidget);
    await tester.enterText(find.byType(TextField), '');
    await tester.pumpAndSettle();
    await tapVisible(tester, find.text('Explore Cape Town demo'));
    expect(find.text('Cape Town · demo location'), findsOneWidget);
    location.fail = true;
    await tapVisible(tester, find.text('Use my location'));
    expect(find.text('Location unavailable'), findsOneWidget);
  });
  testWidgets(
      'assistant uses selected hub and confirms only from the review button',
      (tester) async {
    final repo = FakeJourney();
    bool booked = false;
    await tester.pumpWidget(app(AssistantPage(
        repository: repo,
        hub: Hub.fromJson(hubJson),
        onExplore: () {},
        onBooked: () => booked = true)));
    await tester.enterText(find.byType(TextField), 'Book Friday');
    await tester.tap(find.byTooltip('Send message'));
    await tester.pumpAndSettle();
    expect(repo.confirmed, isNull);
    expect(repo.history!.last['content'], 'Book Friday');
    await tapVisible(tester, find.text('Confirm reservation'));
    expect(repo.confirmed, 'proposal-1');
    expect(booked, isTrue);
    repo.failure = 'Try later';
    await tester.enterText(find.byType(TextField), 'New visit');
    await tester.tap(find.byTooltip('Send message'));
    await tester.pumpAndSettle();
    expect(find.text('Try later'), findsOneWidget);
    expect(find.widgetWithText(TextField, 'New visit'), findsOneWidget);
  });
  testWidgets('assistant asks for a hub and supports change proposals',
      (tester) async {
    final repo = FakeJourney();
    bool explore = false;
    await tester.pumpWidget(app(AssistantPage(
        repository: repo,
        hub: null,
        onExplore: () => explore = true,
        onBooked: () {})));
    await tapVisible(tester, find.text('Find a hub'));
    expect(explore, isTrue);
    repo.response = AssistantReply.fromJson(
        {'message': 'Review change', 'proposal': proposalJson(kind: 'CHANGE')});
    await tester.pumpWidget(app(AssistantPage(
        repository: repo,
        hub: Hub.fromJson(hubJson),
        onExplore: () {},
        onBooked: () {})));
    await tapVisible(tester, find.text('Find me a time tomorrow'));
    expect(repo.history!.first['content'], contains('Barber'));
    repo.failure = 'Time is no longer available';
    await tapVisible(tester, find.text('Confirm change'));
    expect(find.text('Time is no longer available'), findsOneWidget);
  });
  testWidgets(
      'manual visit keeps car mandatory and allows only one complimentary checkbox',
      (tester) async {
    final repo = FakeJourney();
    final profile = CustomerProfile.fromJson({
      'customerId': '00000000-0000-0000-0000-000000000001',
      'customerName': 'Demo Customer',
      'phoneNumber': '',
      'vehicles': [
        {
          'id': '00000000-0000-0000-0000-0000000000aa',
          'vehicleMake': 'Toyota',
          'vehicleModel': 'Corolla',
          'registration': 'DEMO060',
          'isDefault': true,
        },
        {
          'id': '00000000-0000-0000-0000-0000000000bb',
          'vehicleMake': 'VW',
          'vehicleModel': 'Polo',
          'registration': 'DEMO061',
          'isDefault': false,
        },
      ],
    });
    await tester.pumpWidget(app(PlanVisitPage(
        hub: Hub.fromJson(hubJson), repository: repo, profile: profile)));
    await tester.pumpAndSettle();
    await tapVisible(tester, find.text('Barber'));
    expect(repo.selectedOption, 'BARBER');
    await tapVisible(tester, find.text('Massage'));
    expect(repo.selectedOption, 'MASSAGE');
    await tester.scrollUntilVisible(find.text('Barber'), -150,
        scrollable: find.byType(Scrollable).first);
    await tester.pumpAndSettle();
    expect(
        tester
            .widget<CheckboxListTile>(
                find.widgetWithText(CheckboxListTile, 'Barber'))
            .value,
        isFalse);
    await tapVisible(tester, find.byIcon(Icons.calendar_today_outlined));
    await tester.tap(find.text('OK'));
    await tester.pumpAndSettle();
    await tapVisible(tester, find.text(slotTime(start)));
    await tester.scrollUntilVisible(find.text('Demo Customer'), 300,
        scrollable: find.byType(Scrollable).first);
    expect(find.text('Demo Customer'), findsOneWidget);
    final phone = find.widgetWithText(TextFormField, 'Phone number');
    await tester.ensureVisible(phone);
    await tester.enterText(phone, '+27000000000');
    expect(find.textContaining('Toyota Corolla'), findsWidgets);
    await tapVisible(tester, find.byType(DropdownButton<String>));
    await tester.pumpAndSettle();
    await tapVisible(tester, find.text('VW Polo · DEMO061').last);
    await tester.pumpAndSettle();
    expect(find.text('VW'), findsWidgets);
    repo.failure = 'Slot taken';
    await tapVisible(tester, find.text('Reserve this visit'));
    expect(find.text('Slot taken'), findsOneWidget);
    expect(repo.reservations, 0);
    repo.failure = null;
    await tapVisible(tester, find.text('Reserve this visit'));
    expect(repo.reservations, 1);
  });
  testWidgets('edit sends revision and shows empty or failed slots clearly',
      (tester) async {
    final repo = FakeJourney();
    final booking = Appointment.fromJson(appointmentJson());
    await tester.pumpWidget(app(PlanVisitPage(
        hub: Hub.fromJson(hubJson), repository: repo, appointment: booking)));
    await tester.pumpAndSettle();
    await tapVisible(tester, find.text(slotTime(start)));
    await tapVisible(tester, find.text('Confirm change'));
    expect(repo.changes, 1);
    await tester.pumpWidget(const SizedBox());
    repo.times = [];
    await tester.pumpWidget(
        app(PlanVisitPage(hub: Hub.fromJson(hubJson), repository: repo)));
    await tester.pumpAndSettle();
    expect(find.textContaining('No times available'), findsOneWidget);
    repo.failure = 'Offline';
    await tapVisible(tester, find.text('Barber'));
    await tester.scrollUntilVisible(find.text('Offline'), 200,
        scrollable: find.byType(Scrollable).first);
    expect(find.text('Offline'), findsOneWidget);
  });
  testWidgets(
      'bookings distinguishes confirmed payable and locked visits and refreshes',
      (tester) async {
    final repo = FakeJourney();
    final coordinator = PaymentCheckoutCoordinator(
        payments:
            FakePaymentRepository(order(url: 'https://checkout.yoco.test')),
        launcher: FakeLauncher(),
        pendingStore: FakePendingStore());
    addTearDown(coordinator.dispose);
    repo.bookings = [Appointment.fromJson(appointmentJson())];
    await tester.pumpWidget(app(AppointmentsPage(
        repository: repo,
        checkout: coordinator,
        refreshVersion: 0,
        onExplore: () {})));
    await tester.pumpAndSettle();
    expect(find.text('CONFIRMED'), findsOneWidget);
    await tapVisible(tester, find.text('Change booking'));
    await tester.pageBack();
    await tester.pumpAndSettle();
    repo.bookings = [Appointment.fromJson(appointmentJson(modifiable: false))];
    await tapVisible(tester, find.byTooltip('Refresh bookings'));
    expect(find.textContaining('Online changes close'), findsOneWidget);
    repo.bookings = [
      Appointment.fromJson(appointmentJson(status: 'HELD', modifiable: false))
    ];
    await tapVisible(tester, find.byTooltip('Refresh bookings'));
    await tapVisible(tester, find.text('Pay securely with Yoco'));
    expect(coordinator.order!.id, 'payment-1');
    coordinator.order = order(status: 'PAID');
    coordinator.notifyListeners();
    await tester.pumpAndSettle();
    expect(repo.paid, 'hold-1');
    repo.failure = 'Offline';
    await tapVisible(tester, find.byTooltip('Refresh bookings'));
    await tester.scrollUntilVisible(find.text('Offline'), 200,
        scrollable: find.byType(Scrollable).first);
    expect(find.text('Offline'), findsOneWidget);
  });
  testWidgets('bookings empty state opens discovery and loads next page',
      (tester) async {
    final repo = FakeJourney();
    bool explore = false;
    final coordinator =
        PaymentCheckoutCoordinator(payments: PaymentRepository());
    addTearDown(coordinator.dispose);
    await tester.pumpWidget(app(AppointmentsPage(
        repository: repo,
        checkout: coordinator,
        refreshVersion: 0,
        onExplore: () => explore = true)));
    await tester.pumpAndSettle();
    await tapVisible(tester, find.text('Find a hub'));
    expect(explore, isTrue);
    repo.more = true;
    repo.bookings = [Appointment.fromJson(appointmentJson())];
    await tester.pumpWidget(app(AppointmentsPage(
        repository: repo,
        checkout: coordinator,
        refreshVersion: 1,
        onExplore: () {})));
    await tester.pumpAndSettle();
    await tapVisible(tester, find.text('More bookings'));
    expect(find.text('CONFIRMED'), findsNWidgets(2));
  });
}
