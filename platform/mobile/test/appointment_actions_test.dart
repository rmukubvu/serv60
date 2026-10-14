import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:serv60_mobile/src/journey/appointments_page.dart';
import 'package:serv60_mobile/src/journey/journey_repository.dart';
import 'package:serv60_mobile/src/payment/payment_checkout.dart';
import 'package:serv60_mobile/src/payment/payment_repository.dart';

import 'journey_test.dart' show FakeJourney, app, appointmentJson, tapVisible;

class CountingJourney extends FakeJourney {
  int cancels = 0;
  String? hubRequested;

  @override
  Future<Appointment> cancel(String id) async {
    cancels++;
    return super.cancel(id);
  }

  @override
  Future<Hub> hub(String id) async {
    hubRequested = id;
    return super.hub(id);
  }
}

void main() {
  late PaymentCheckoutCoordinator checkout;
  setUp(() =>
      checkout = PaymentCheckoutCoordinator(payments: PaymentRepository()));
  tearDown(() => checkout.dispose());

  testWidgets('cancels a visit only after the customer confirms',
      (tester) async {
    final repo = CountingJourney()
      ..bookings = [Appointment.fromJson(appointmentJson())];
    await tester.pumpWidget(app(AppointmentsPage(
        repository: repo,
        checkout: checkout,
        refreshVersion: 0,
        onExplore: () {})));
    await tester.pumpAndSettle();

    await tapVisible(tester, find.text('Cancel visit'));
    await tester.tap(find.text('Keep visit'));
    await tester.pumpAndSettle();
    expect(repo.cancels, 0);
    await tapVisible(tester, find.text('Cancel visit'));
    await tester.tap(find.descendant(
        of: find.byType(AlertDialog), matching: find.text('Cancel visit')));
    await tester.pumpAndSettle();

    expect(repo.cancels, 1);
  });

  testWidgets('hands a booking to the assistant and reports failures',
      (tester) async {
    Hub? asked;
    final repo = CountingJourney()
      ..bookings = [Appointment.fromJson(appointmentJson())];
    await tester.pumpWidget(app(AppointmentsPage(
        repository: repo,
        checkout: checkout,
        refreshVersion: 0,
        onExplore: () {},
        onAskHelp: (hub) async => asked = hub)));
    await tester.pumpAndSettle();

    await tapVisible(tester, find.text('Describe a change in plain language'));
    expect(asked?.id, 'hub-1');
    expect(repo.hubRequested, 'hub-1');

    repo.failure = 'Hub unavailable';
    await tapVisible(tester, find.text('Describe a change in plain language'));
    await tester.scrollUntilVisible(find.text('Hub unavailable'), 200,
        scrollable: find.byType(Scrollable).first);
    expect(find.text('Hub unavailable'), findsOneWidget);
  });

  testWidgets('reports a failed cancellation and a failed change lookup',
      (tester) async {
    final repo = CountingJourney()
      ..bookings = [Appointment.fromJson(appointmentJson())];
    await tester.pumpWidget(app(AppointmentsPage(
        repository: repo,
        checkout: checkout,
        refreshVersion: 0,
        onExplore: () {})));
    await tester.pumpAndSettle();

    repo.failure = 'Cancellation closed';
    await tapVisible(tester, find.text('Cancel visit'));
    await tester.tap(find.descendant(
        of: find.byType(AlertDialog), matching: find.text('Cancel visit')));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(find.text('Cancellation closed'), 200,
        scrollable: find.byType(Scrollable).first);
    expect(find.text('Cancellation closed'), findsOneWidget);
  });
}
