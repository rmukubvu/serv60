import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:serv60_mobile/src/journey/journey_repository.dart';
import 'package:serv60_mobile/src/journey/reservation_confirmation_page.dart';
import 'package:serv60_mobile/src/payment/payment_checkout.dart';

import 'journey_test.dart' show FakeJourney, app, hubJson, tapVisible;
import 'payment_checkout_test.dart'
    show FakeLauncher, FakePaymentRepository, FakePendingStore, order;

VisitHold hold() => VisitHold.fromJson({
      'id': 'hold-1',
      'hubId': 'hub-1',
      'serviceCode': 'EXPRESS_OIL',
      'startsAt': '2035-01-03T08:00:00Z',
      'endsAt': '2035-01-03T09:00:00Z',
      'status': 'HELD',
      'expiresAt': '2035-01-01T06:15:00Z',
      'complimentaryServiceCode': 'BARBER',
    });

void main() {
  late PaymentCheckoutCoordinator checkout;
  setUp(() => checkout = PaymentCheckoutCoordinator(
      payments: FakePaymentRepository(order(url: 'https://checkout.yoco.test')),
      launcher: FakeLauncher(),
      pendingStore: FakePendingStore()));
  tearDown(() => checkout.dispose());

  Widget page(FakeJourney repo) => app(ReservationConfirmationPage(
      hub: Hub.fromJson(hubJson),
      hold: hold(),
      repository: repo,
      checkout: checkout));

  testWidgets('opens checkout, then confirms the visit once it is paid',
      (tester) async {
    final repo = FakeJourney();
    await tester.pumpWidget(page(repo));

    await tapVisible(tester, find.text('Pay securely with Yoco'));
    expect(checkout.order!.id, 'payment-1');
    checkout.order = order(status: 'PAID');
    checkout.notifyListeners();
    await tester.pumpAndSettle();

    expect(repo.paid, 'hold-1');
    expect(find.text('Visit confirmed'), findsOneWidget);
    expect(find.text('View my bookings'), findsOneWidget);
  });

  testWidgets('shows payment and confirmation failures', (tester) async {
    final repo = FakeJourney()..failure = 'Checkout unavailable';
    await tester.pumpWidget(page(repo));

    await tapVisible(tester, find.text('Pay securely with Yoco'));
    expect(find.textContaining('Checkout unavailable'), findsOneWidget);
    checkout.order = order(status: 'PAID');
    checkout.notifyListeners();
    await tester.pumpAndSettle();
    expect(find.text('Visit confirmed'), findsNothing);
  });

  testWidgets('lets the customer pay later', (tester) async {
    bool? result;
    await tester.pumpWidget(app(Builder(
        builder: (context) => TextButton(
            onPressed: () async => result = await Navigator.push<bool>(
                context,
                MaterialPageRoute(
                    builder: (_) => ReservationConfirmationPage(
                        hub: Hub.fromJson(hubJson),
                        hold: hold(),
                        repository: FakeJourney(),
                        checkout: checkout))),
            child: const Text('open')))));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    await tapVisible(tester, find.text('Pay later from Bookings'));

    expect(result, isTrue);
  });
}
