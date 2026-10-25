import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:serv60_mobile/src/design/serv60_theme.dart';
import 'package:serv60_mobile/src/journey/appointments_page.dart';
import 'package:serv60_mobile/src/journey/journey_repository.dart';
import 'package:serv60_mobile/src/journey/journey_widgets.dart';
import 'package:serv60_mobile/src/journey/visit_progress_page.dart';
import 'package:serv60_mobile/src/payment/payment_checkout.dart';
import 'package:serv60_mobile/src/payment/payment_repository.dart';

import 'journey_test.dart' show FakeJourney, app, appointmentJson, tapVisible;

// 08:03 SAST on the visit day.
final checkedIn = DateTime.parse('2026-10-24T06:03:00Z');
final visitStart = DateTime.parse('2026-10-24T08:00:00+02:00');

Map<String, dynamic> scopeJson(
        {String id = 'scope-1',
        String status = 'PROPOSED',
        String? note = 'Pads at 2 mm'}) =>
    {
      'id': id,
      'visitId': 'visit-1',
      'status': status,
      'items': [
        {'description': 'Front brake pads', 'amountMinor': 145000},
        {'description': 'Brake fluid', 'amountMinor': 35000},
      ],
      'note': note,
      'totalMinor': 180000,
      'currency': 'ZAR',
      'proposedAt':
          checkedIn.add(const Duration(minutes: 12)).toIso8601String(),
      'decidedAt': status == 'PROPOSED'
          ? null
          : checkedIn.add(const Duration(minutes: 15)).toIso8601String(),
    };

/// Progress as the backend reports it, with one event per stage reached.
Map<String, dynamic> progressJson(
    {String? stage,
    int? elapsed,
    int target = 60,
    String? voucher = 'COFFEE-3F2A',
    String? option = 'BARBER',
    String? inspectionNote,
    List<Map<String, dynamic>> scopeChanges = const []}) {
  final reached = stage == null ? -1 : visitStages.indexOf(stage);
  String? at(int stageIndex) => reached >= stageIndex
      ? checkedIn.add(Duration(minutes: stageIndex * 7)).toIso8601String()
      : null;
  return {
    'visitId': 'visit-1',
    'hubName': 'Sea Point',
    'startsAt': '2026-10-24T08:00:00+02:00',
    'complimentaryServiceCode': option,
    'stage': stage,
    'checkedInAt': at(0),
    'readyAt': at(5),
    'handedOverAt': at(6),
    'targetMinutes': target,
    'elapsedMinutes': stage == null ? null : elapsed,
    'events': [
      for (var i = 0; i <= reached; i++)
        {
          'stage': visitStages[i],
          'recordedAt': at(i),
          'note': i == 1 ? inspectionNote : null,
        }
    ],
    'scopeChanges': scopeChanges,
    'voucherCode': stage == null ? null : voucher,
  };
}

class VisitJourney extends FakeJourney {
  VisitJourney([Map<String, dynamic>? state]) : state = state ?? progressJson();
  Map<String, dynamic> state;
  int progressCalls = 0;
  final decisions = <String>[];
  Completer<void>? gate, decisionGate;
  String? decisionFailure;

  // Round-trip through JSON text so the models parse exactly what the wire
  // would carry.
  VisitProgress snapshot() =>
      VisitProgress.fromJson(jsonDecode(jsonEncode(state)));

  /// Delivers [json] over the event stream, as the server does on a change,
  /// and makes it the state the next poll would return.
  void push(Map<String, dynamic> json) {
    state = json;
    liveUpdates.add(snapshot());
  }

  @override
  Future<VisitProgress> progress(String id) async {
    progressCalls++;
    await gate?.future;
    check();
    return snapshot();
  }

  @override
  Future<VisitProgress> decideScopeChange(
      String id, String scopeId, String decision) async {
    decisions.add('$id/$scopeId/$decision');
    await decisionGate?.future;
    if (decisionFailure != null) throw JourneyException(decisionFailure!);
    state = {
      ...state,
      'scopeChanges': [
        for (final change in state['scopeChanges'] as List)
          change['id'] == scopeId
              ? {
                  ...change as Map,
                  'status': decision == 'APPROVE' ? 'APPROVED' : 'DECLINED',
                  'decidedAt': DateTime.now().toUtc().toIso8601String(),
                }
              : change
      ],
    };
    return snapshot();
  }
}

Widget page(VisitJourney repo,
        {Duration pollEvery = const Duration(minutes: 1)}) =>
    MaterialApp(
        theme: serv60Theme(),
        home: VisitProgressPage(
            repository: repo,
            appointment: Appointment.fromJson(appointmentJson()),
            pollEvery: pollEvery));

LinearProgressIndicator bar(WidgetTester tester) => tester
    .widget<LinearProgressIndicator>(find.byType(LinearProgressIndicator));

void main() {
  test('formats rands, stage names and the complimentary line', () {
    expect(rands(0), 'R0.00');
    expect(rands(145000), 'R1,450.00');
    expect(rands(123456789), 'R1,234,567.89');
    expect(stageLabel('QUALITY_CHECK'), 'Quality check');
    expect(stageLabel('SOMETHING_NEW'), 'SOMETHING_NEW');
    expect(optionLine(null), 'Just the car service');
    expect(optionLine('MASSAGE'), 'Massage · booked inside your hour');
  });

  test('polling is only a 30-second safety net behind the event stream', () {
    final page = VisitProgressPage(
        repository: VisitJourney(),
        appointment: Appointment.fromJson(appointmentJson()));
    expect(page.pollEvery, const Duration(seconds: 30));
    expect(liveWindow, const Duration(seconds: 45));
  });

  test('parses progress tolerantly when optional fields are missing', () {
    final sparse = VisitProgress.fromJson({'visitId': 'visit-1'});
    expect(sparse.checkedIn, isFalse);
    expect(sparse.targetMinutes, 60);
    expect(sparse.elapsedMinutes, isNull);
    expect(sparse.events, isEmpty);
    expect(sparse.scopeChanges, isEmpty);
    expect(sparse.startsAt, isNull);
    final full = VisitProgress.fromJson(
        progressJson(stage: 'READY', elapsed: 52, scopeChanges: [scopeJson()]));
    expect(full.readyAt, isNotNull);
    expect(full.handedOverAt, isNull);
    expect(full.events.map((e) => e.stage).toList(), visitStages.sublist(0, 6));
    final change = full.scopeChanges.single;
    expect(change.proposed, isTrue);
    expect(change.decidedAt, isNull);
    expect(change.items.map((i) => i.amountMinor).toList(), [145000, 35000]);
    expect(change.currency, 'ZAR');
  });

  test('repository calls the progress and decision endpoints as specified',
      () async {
    final requests = <http.Request>[];
    final repo = JourneyRepository(
        baseUrl: 'https://api.test',
        client: MockClient((request) async {
          requests.add(request);
          if (request.url.path.endsWith('/scope-changes/scope-2/decision')) {
            return http.Response(jsonEncode({'error': 'Already decided'}), 409);
          }
          return http.Response(
              jsonEncode(progressJson(stage: 'SERVICING', elapsed: 23)), 200);
        }));
    addTearDown(repo.close);
    final progress = await repo.progress('visit-1');
    expect(progress.stage, 'SERVICING');
    expect(progress.voucherCode, 'COFFEE-3F2A');
    expect(requests.single.method, 'GET');
    expect(
        requests.single.url.path, '/v1/customer/appointments/visit-1/progress');
    await repo.decideScopeChange('visit-1', 'scope-1', 'APPROVE');
    expect(requests.last.method, 'PUT');
    expect(requests.last.url.path,
        '/v1/customer/appointments/visit-1/scope-changes/scope-1/decision');
    expect(jsonDecode(requests.last.body), {'decision': 'APPROVE'});
    await expectLater(
        repo.decideScopeChange('visit-1', 'scope-2', 'DECLINE'),
        throwsA(isA<JourneyException>()
            .having((e) => e.message, 'message', 'Already decided')));
  });

  testWidgets('before check-in shows the visit details and intake copy',
      (tester) async {
    final repo = VisitJourney(progressJson());
    await tester.pumpWidget(page(repo));
    await tester.pumpAndSettle();
    expect(find.text('Your visit'), findsOneWidget);
    expect(find.text('Sea Point'), findsOneWidget);
    expect(find.text('${visitTime(visitStart)} SAST'), findsOneWidget);
    expect(find.text('Barber · booked inside your hour'), findsOneWidget);
    expect(find.text('We’ll start the clock when you arrive.'), findsOneWidget);
    expect(find.text('Show this screen at the intake lane.'), findsOneWidget);
    expect(find.textContaining('of 60 min'), findsNothing);
    expect(find.byType(LinearProgressIndicator), findsNothing);
    expect(find.text('Updating…'), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets(
      'on the floor shows elapsed time, the current stage, the timeline and the voucher',
      (tester) async {
    final repo = VisitJourney(progressJson(
        stage: 'SERVICING', elapsed: 23, inspectionNote: 'Tyres fine'));
    await tester.pumpWidget(page(repo));
    await tester.pumpAndSettle();
    expect(find.text('23 of 60 min'), findsOneWidget);
    // Headline plus the timeline row.
    expect(find.text('Service'), findsNWidgets(2));
    expect(bar(tester).value, closeTo(23 / 60, 0.001));
    expect(bar(tester).color, serv60Orange);
    expect(find.textContaining('Running over'), findsNothing);
    expect(
        find.text('Checked in 08:03 SAST · 60-minute target'), findsOneWidget);
    // Checked in and inspection are done; the other five are current or ahead.
    expect(find.byIcon(Icons.check), findsNWidgets(2));
    expect(find.byIcon(Icons.directions_car), findsOneWidget);
    expect(find.text('08:03'), findsOneWidget);
    expect(find.text('08:10'), findsOneWidget);
    expect(find.text('08:17'), findsOneWidget);
    expect(find.text('Tyres fine'), findsOneWidget);
    for (final label in ['Quality check', 'Wash', 'Ready', 'Handed over']) {
      expect(find.text(label), findsOneWidget);
    }
    expect(find.text('Your coffee’s on us'), findsOneWidget);
    expect(find.text('COFFEE-3F2A'), findsOneWidget);
    expect(find.text('Show this at the barista bar.'), findsOneWidget);
    expect(find.text('Barber · booked inside your hour'), findsOneWidget);
    expect(find.text('Your car is ready for collection'), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('approves proposed extra work with one tap', (tester) async {
    final repo = VisitJourney(progressJson(
        stage: 'SERVICING', elapsed: 23, scopeChanges: [scopeJson()]));
    await tester.pumpWidget(page(repo));
    await tester.pumpAndSettle();
    expect(find.text('EXTRA WORK PROPOSED'), findsOneWidget);
    expect(find.text('Front brake pads'), findsOneWidget);
    expect(find.text('R1,450.00'), findsOneWidget);
    expect(find.text('Brake fluid'), findsOneWidget);
    expect(find.text('R350.00'), findsOneWidget);
    expect(find.text('Pads at 2 mm'), findsOneWidget);
    expect(find.text('R1,800.00'), findsOneWidget);
    expect(find.byType(Chip), findsNothing);
    await tapVisible(tester, find.text('Approve · settle at handover'));
    expect(repo.decisions, ['visit-1/scope-1/APPROVE']);
    expect(find.widgetWithText(Chip, 'Approved'), findsOneWidget);
    expect(find.text('Approve · settle at handover'), findsNothing);
    expect(find.text('Decline'), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('declines proposed extra work', (tester) async {
    final repo = VisitJourney(progressJson(
        stage: 'SERVICING', elapsed: 23, scopeChanges: [scopeJson()]));
    await tester.pumpWidget(page(repo));
    await tester.pumpAndSettle();
    await tapVisible(tester, find.text('Decline'));
    expect(repo.decisions, ['visit-1/scope-1/DECLINE']);
    expect(find.widgetWithText(Chip, 'Declined'), findsOneWidget);
    expect(find.text('Decline'), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('shows the backend message when a decision is rejected',
      (tester) async {
    final repo = VisitJourney(progressJson(
        stage: 'SERVICING', elapsed: 23, scopeChanges: [scopeJson()]))
      ..decisionFailure = 'This proposal was already decided';
    await tester.pumpWidget(page(repo));
    await tester.pumpAndSettle();
    await tapVisible(tester, find.text('Approve · settle at handover'));
    expect(find.text('This proposal was already decided'), findsOneWidget);
    expect(find.text('Approve · settle at handover'), findsOneWidget);
    expect(find.byType(Chip), findsNothing);
    expect(
        tester
            .widget<FilledButton>(find.widgetWithText(
                FilledButton, 'Approve · settle at handover'))
            .enabled,
        isTrue);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('disables both decision buttons while a decision is in flight',
      (tester) async {
    final repo = VisitJourney(progressJson(
        stage: 'SERVICING',
        elapsed: 23,
        scopeChanges: [
          scopeJson(),
          scopeJson(id: 'scope-2', status: 'DECLINED')
        ]))
      ..decisionGate = Completer<void>();
    await tester.pumpWidget(page(repo));
    await tester.pumpAndSettle();
    expect(find.widgetWithText(Chip, 'Declined'), findsOneWidget);
    await tester.ensureVisible(find.text('Approve · settle at handover'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Approve · settle at handover'));
    // One frame in: the call has started but the reply has not been applied.
    await tester.pump();
    expect(
        tester
            .widget<OutlinedButton>(
                find.widgetWithText(OutlinedButton, 'Decline'))
            .enabled,
        isFalse);
    expect(
        tester
            .widget<FilledButton>(find.widgetWithText(
                FilledButton, 'Approve · settle at handover'))
            .enabled,
        isFalse);
    repo.decisionGate!.complete();
    await tester.pumpAndSettle();
    expect(repo.decisions, ['visit-1/scope-1/APPROVE']);
    expect(find.widgetWithText(Chip, 'Approved'), findsOneWidget);
    expect(find.widgetWithText(Chip, 'Declined'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('READY shows the collection banner with the clock frozen',
      (tester) async {
    final repo = VisitJourney(progressJson(stage: 'READY', elapsed: 52));
    await tester.pumpWidget(page(repo));
    await tester.pumpAndSettle();
    expect(find.text('Your car is ready for collection'), findsOneWidget);
    expect(find.text('52 of 60 min'), findsOneWidget);
    expect(find.text('Ready'), findsNWidgets(2));
    expect(find.byIcon(Icons.check), findsNWidgets(5));
    expect(find.textContaining('Thanks for visiting'), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('HANDED_OVER thanks the customer and points to Account',
      (tester) async {
    final repo = VisitJourney(progressJson(stage: 'HANDED_OVER', elapsed: 52));
    await tester.pumpWidget(page(repo));
    await tester.pumpAndSettle();
    expect(
        find.text(
            'Thanks for visiting. Your invoice and service record will appear under Account.'),
        findsOneWidget);
    expect(find.text('Your car is ready for collection'), findsNothing);
    expect(find.byIcon(Icons.check), findsNWidgets(6));
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('running over the hour caps the bar and warns in red',
      (tester) async {
    final repo = VisitJourney(progressJson(stage: 'WASHING', elapsed: 71));
    await tester.pumpWidget(page(repo));
    await tester.pumpAndSettle();
    expect(find.text('71 of 60 min'), findsOneWidget);
    expect(find.text('Running over the hour. The team will keep you posted.'),
        findsOneWidget);
    expect(bar(tester).value, 1.0);
    expect(bar(tester).color, serv60Error);
    expect(find.textContaining('minute target'), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('falls back to the booking when the hub name is missing',
      (tester) async {
    final repo = VisitJourney({
      ...progressJson(stage: 'CHECKED_IN'),
      'hubName': null,
      'startsAt': null,
      'complimentaryServiceCode': null,
      'elapsedMinutes': null,
      'targetMinutes': 0,
    });
    await tester.pumpWidget(page(repo));
    await tester.pumpAndSettle();
    expect(find.text('SEA POINT'), findsOneWidget);
    expect(find.text('0 of 0 min'), findsOneWidget);
    expect(bar(tester).value, 1.0);
    expect(find.text('Just the car service'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('polls on the interval and stops when the screen closes',
      (tester) async {
    final repo = VisitJourney(progressJson(stage: 'INSPECTING', elapsed: 9));
    await tester.pumpWidget(page(repo, pollEvery: const Duration(seconds: 5)));
    await tester.pump();
    expect(repo.progressCalls, 1);
    expect(find.text('Inspection'), findsNWidgets(2));
    repo.state = progressJson(stage: 'SERVICING', elapsed: 14);
    await tester.pump(const Duration(seconds: 5));
    await tester.pump();
    expect(repo.progressCalls, 2);
    expect(find.text('14 of 60 min'), findsOneWidget);
    expect(find.text('Service'), findsNWidgets(2));
    await tester.pump(const Duration(seconds: 5));
    expect(repo.progressCalls, 3);
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(seconds: 10));
    expect(repo.progressCalls, 3);
  });

  testWidgets('applies streamed progress without a poll and shows Live',
      (tester) async {
    final repo = VisitJourney(progressJson(stage: 'INSPECTING', elapsed: 9));
    await tester.pumpWidget(page(repo));
    await tester.pumpAndSettle();
    expect(repo.progressCalls, 1);
    expect(find.text('Live'), findsNothing);

    repo.push(progressJson(stage: 'SERVICING', elapsed: 14));
    await tester.pump();
    expect(find.text('14 of 60 min'), findsOneWidget);
    expect(find.text('Service'), findsNWidgets(2));
    expect(find.text('Live'), findsOneWidget);
    expect(repo.progressCalls, 1);

    // Still live just inside the window; a fresh event starts it over.
    await tester.pump(const Duration(seconds: 44));
    expect(find.text('Live'), findsOneWidget);
    repo.push(progressJson(stage: 'READY', elapsed: 52));
    await tester.pump();
    await tester.pump(const Duration(seconds: 44));
    expect(find.text('52 of 60 min'), findsOneWidget);
    expect(find.text('Live'), findsOneWidget);
    await tester.pump(const Duration(seconds: 2));
    expect(find.text('Live'), findsNothing);
    // The one-minute safety-net poll still ran in the meantime.
    expect(repo.progressCalls, 2);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('a streamed event clears the error left by a failed poll',
      (tester) async {
    final repo = VisitJourney(progressJson(stage: 'SERVICING', elapsed: 23));
    await tester.pumpWidget(page(repo, pollEvery: const Duration(seconds: 5)));
    await tester.pumpAndSettle();
    repo.failure = 'Hub offline';
    await tester.pump(const Duration(seconds: 5));
    await tester.pump();
    expect(find.text('Hub offline'), findsOneWidget);
    repo.failure = null;
    repo.push(progressJson(stage: 'QUALITY_CHECK', elapsed: 31));
    await tester.pump();
    expect(find.text('Hub offline'), findsNothing);
    expect(find.text('31 of 60 min'), findsOneWidget);
    expect(find.text('Live'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('keeps the last good data on screen when a refresh fails',
      (tester) async {
    final repo = VisitJourney(progressJson(stage: 'SERVICING', elapsed: 23));
    await tester.pumpWidget(page(repo, pollEvery: const Duration(seconds: 5)));
    await tester.pumpAndSettle();
    repo.failure = 'Hub offline';
    await tester.pump(const Duration(seconds: 5));
    await tester.pump();
    expect(find.text('Hub offline'), findsOneWidget);
    expect(find.text('23 of 60 min'), findsOneWidget);
    expect(find.text('COFFEE-3F2A'), findsOneWidget);
    repo.failure = null;
    await tester.pump(const Duration(seconds: 5));
    await tester.pump();
    expect(find.text('Hub offline'), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('shows only the error when the first load fails', (tester) async {
    final repo = VisitJourney()..failure = 'Hub offline';
    await tester.pumpWidget(page(repo));
    await tester.pumpAndSettle();
    expect(find.text('Hub offline'), findsOneWidget);
    expect(find.text('Checking on your car…'), findsNothing);
    expect(find.text('Sea Point'), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('shows an updating indicator while a refresh is in flight',
      (tester) async {
    final repo = VisitJourney(progressJson(stage: 'SERVICING', elapsed: 23))
      ..gate = Completer<void>();
    await tester.pumpWidget(page(repo));
    await tester.pump();
    expect(find.text('Updating…'), findsOneWidget);
    expect(find.text('Checking on your car…'), findsOneWidget);
    repo.gate!.complete();
    await tester.pumpAndSettle();
    expect(find.text('Updating…'), findsNothing);
    expect(find.text('23 of 60 min'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  group('bookings list', () {
    late PaymentCheckoutCoordinator checkout;
    setUp(() =>
        checkout = PaymentCheckoutCoordinator(payments: PaymentRepository()));
    tearDown(() => checkout.dispose());

    testWidgets('Track your visit opens the live screen for confirmed visits',
        (tester) async {
      final repo = VisitJourney(progressJson(stage: 'SERVICING', elapsed: 23))
        ..bookings = [Appointment.fromJson(appointmentJson())];
      await tester.pumpWidget(app(AppointmentsPage(
          repository: repo,
          checkout: checkout,
          refreshVersion: 0,
          onExplore: () {})));
      await tester.pumpAndSettle();
      expect(find.text('Change booking'), findsOneWidget);
      await tapVisible(tester, find.text('Track your visit'));
      expect(find.byType(VisitProgressPage), findsOneWidget);
      expect(find.text('Your visit'), findsOneWidget);
      expect(find.text('23 of 60 min'), findsOneWidget);
      expect(repo.progressCalls, 1);
      await tester.pageBack();
      await tester.pumpAndSettle();
      expect(find.byType(VisitProgressPage), findsNothing);
      await tester.pumpWidget(const SizedBox());
    });

    testWidgets(
        'a confirmed visit past its change cutoff still offers tracking',
        (tester) async {
      final repo = VisitJourney()
        ..bookings = [
          Appointment.fromJson(
              appointmentJson(status: 'CONFIRMED', modifiable: false))
        ];
      await tester.pumpWidget(app(AppointmentsPage(
          repository: repo,
          checkout: checkout,
          refreshVersion: 0,
          onExplore: () {})));
      await tester.pumpAndSettle();
      expect(find.textContaining('Online changes close'), findsOneWidget);
      expect(find.text('Change booking'), findsNothing);
      await tapVisible(tester, find.text('Track your visit'));
      expect(find.byType(VisitProgressPage), findsOneWidget);
      expect(find.text('Show this screen at the intake lane.'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
    });

    testWidgets('held reservations do not offer tracking', (tester) async {
      final repo = VisitJourney()
        ..bookings = [
          Appointment.fromJson(
              appointmentJson(status: 'HELD', modifiable: false))
        ];
      await tester.pumpWidget(app(AppointmentsPage(
          repository: repo,
          checkout: checkout,
          refreshVersion: 0,
          onExplore: () {})));
      await tester.pumpAndSettle();
      expect(find.text('Pay securely with Yoco'), findsOneWidget);
      expect(find.text('Track your visit'), findsNothing);
    });
  });
}
