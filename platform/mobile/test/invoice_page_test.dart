import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:serv60_mobile/src/design/serv60_theme.dart';
import 'package:serv60_mobile/src/journey/invoice_page.dart';
import 'package:serv60_mobile/src/journey/journey_repository.dart';
import 'package:serv60_mobile/src/journey/journey_widgets.dart';

import 'journey_test.dart' show FakeJourney;
import 'visit_progress_page_test.dart' show progressJson;

const issued = '2026-10-25T12:09:19+02:00';
const notYet = 'No invoice yet: it is issued when the car is handed over';

Map<String, dynamic> recordJson(
        {String visitId = 'visit-1',
        String number = 'S60-20261025-C00000',
        String vehicle = 'Toyota Corolla Cross',
        String registration = 'CA 60 060',
        String? handedOverAt = issued,
        int total = 198500,
        int due = 198500}) =>
    {
      'visitId': visitId,
      'invoiceNumber': number,
      'hubName': 'Serv60 City Bowl · Demo',
      'vehicle': vehicle,
      'registration': registration,
      'handedOverAt': handedOverAt,
      'totalMinor': total,
      'dueMinor': due,
    };

Map<String, dynamic> invoiceJson({bool settled = false}) => {
      'visitId': 'visit-1',
      'number': 'S60-20261025-C00000',
      'hubName': 'Serv60 City Bowl · Demo',
      'customerName': 'Alex Demo',
      'vehicle': 'Toyota Corolla Cross',
      'registration': 'CA 60 060',
      'visitStartsAt': '2026-10-25T11:00:00+02:00',
      'issuedAt': issued,
      'lines': [
        {
          'description': 'Express vehicle service',
          'amountMinor': 24900,
          'status': settled ? 'PAID' : 'DUE'
        },
        {
          'description': 'Barber · complimentary',
          'amountMinor': 0,
          'status': 'INCLUDED'
        },
        {
          'description': 'Front brake pads',
          'amountMinor': 145000,
          'status': settled ? 'PAID' : 'DUE'
        },
      ],
      'totalMinor': 169900,
      'paidMinor': settled ? 169900 : 0,
      'dueMinor': settled ? 0 : 169900,
      'currency': 'ZAR',
    };

/// Answers the invoice and progress calls the invoice screen makes.
class InvoiceJourney extends FakeJourney {
  InvoiceJourney({Map<String, dynamic>? bill, Map<String, dynamic>? stages})
      : bill = bill ?? invoiceJson(),
        stages = stages ??
            progressJson(stage: 'HANDED_OVER', inspectionNote: 'Tyres fine');
  Map<String, dynamic>? bill;
  Map<String, dynamic> stages;
  String? progressFailure;
  Completer<void>? gate;
  final requested = <String>[];

  @override
  Future<Invoice> invoice(String visitId) async {
    requested.add('invoice/$visitId');
    await gate?.future;
    if (bill == null) return super.invoice(visitId);
    return Invoice.fromJson(jsonDecode(jsonEncode(bill)));
  }

  @override
  Future<VisitProgress> progress(String id) async {
    requested.add('progress/$id');
    if (progressFailure != null) throw JourneyException(progressFailure!);
    return VisitProgress.fromJson(jsonDecode(jsonEncode(stages)));
  }
}

Widget invoicePage(JourneyRepository repo) => MaterialApp(
    theme: serv60Theme(),
    home: InvoicePage(repository: repo, visitId: 'visit-1'));

Future<void> scrollTo(WidgetTester tester, Finder finder) async {
  await tester.scrollUntilVisible(finder, 200,
      scrollable: find.byType(Scrollable).first);
  await tester.pumpAndSettle();
}

void main() {
  test('repository reads service history and invoices from the customer API',
      () async {
    final requests = <http.Request>[];
    final repo = JourneyRepository(
        baseUrl: 'https://api.test',
        client: MockClient((request) async {
          requests.add(request);
          if (request.url.path.endsWith('/service-history')) {
            return http.Response(
                jsonEncode([recordJson(), recordJson(visitId: 'visit-0')]),
                200);
          }
          if (request.url.path.endsWith('/visit-2/invoice')) {
            return http.Response(jsonEncode({'error': notYet}), 404);
          }
          return http.Response(jsonEncode(invoiceJson()), 200);
        }));
    addTearDown(repo.close);

    final history = await repo.serviceHistory();
    expect(requests.last.method, 'GET');
    expect(requests.last.url.path, '/v1/customer/service-history');
    expect(requests.last.url.queryParameters, {'limit': '20'});
    expect(history.map((r) => r.visitId), ['visit-1', 'visit-0']);
    final record = history.first;
    expect(record.invoiceNumber, 'S60-20261025-C00000');
    expect(record.hubName, 'Serv60 City Bowl · Demo');
    expect(record.vehicle, 'Toyota Corolla Cross');
    expect(record.registration, 'CA 60 060');
    expect(record.handedOverAt, DateTime.parse(issued));
    expect(record.totalMinor, 198500);
    expect(record.dueMinor, 198500);
    await repo.serviceHistory(limit: 5);
    expect(requests.last.url.queryParameters, {'limit': '5'});

    final bill = await repo.invoice('visit-1');
    expect(requests.last.method, 'GET');
    expect(requests.last.url.path, '/v1/customer/appointments/visit-1/invoice');
    expect(bill.number, 'S60-20261025-C00000');
    expect(bill.customerName, 'Alex Demo');
    expect(bill.issuedAt, DateTime.parse(issued));
    expect(bill.visitStartsAt, DateTime.parse('2026-10-25T09:00:00Z'));
    expect(bill.lines.map((l) => l.status), ['DUE', 'INCLUDED', 'DUE']);
    expect(bill.lines.last.amountMinor, 145000);
    expect(
        (bill.totalMinor, bill.paidMinor, bill.dueMinor), (169900, 0, 169900));
    expect(bill.currency, 'ZAR');

    await expectLater(
        repo.invoice('visit-2'),
        throwsA(isA<JourneyException>()
            .having((e) => e.message, 'message', notYet)));
  });

  test('parses history and invoices tolerantly when fields are missing', () {
    final record = ServiceRecord.fromJson({});
    expect(record.visitId, '');
    expect(record.handedOverAt, isNull);
    expect((record.totalMinor, record.dueMinor), (0, 0));
    final bill = Invoice.fromJson({});
    expect(bill.number, '');
    expect(bill.issuedAt, isNull);
    expect(bill.lines, isEmpty);
    expect(bill.currency, 'ZAR');
    expect(InvoiceLine.fromJson({}).status, 'DUE');
    expect(lineStatusLabel('PAID'), 'Paid');
    expect(lineStatusLabel('INCLUDED'), 'Included');
    expect(lineStatusLabel('DUE'), 'Due at counter');
    expect(vehicleLine('Toyota Corolla Cross', 'CA 60 060'),
        'Toyota Corolla Cross · CA 60 060');
    expect(vehicleLine('', 'CA 60 060'), 'CA 60 060');
  });

  testWidgets('shows the invoice lines, status chips, totals and amount due',
      (tester) async {
    final repo = InvoiceJourney();
    await tester.pumpWidget(invoicePage(repo));
    await tester.pumpAndSettle();
    expect(repo.requested, ['progress/visit-1', 'invoice/visit-1']);
    expect(find.text('Invoice'), findsOneWidget);
    expect(find.text('SERV60 CITY BOWL · DEMO'), findsOneWidget);
    expect(find.text('S60-20261025-C00000'), findsOneWidget);
    expect(find.text('Issued ${visitTime(DateTime.parse(issued))} SAST'),
        findsOneWidget);
    expect(find.text('Issued Sun, 25 Oct · 12:09 SAST'), findsOneWidget);
    expect(find.text('Alex Demo'), findsOneWidget);
    expect(find.text('Toyota Corolla Cross · CA 60 060'), findsOneWidget);
    expect(find.text('Express vehicle service'), findsOneWidget);
    expect(find.text('R249.00'), findsOneWidget);
    expect(find.text('Barber · complimentary'), findsOneWidget);
    expect(find.text('R0.00'), findsNWidgets(2)); // the line and Paid in app
    expect(find.text('R1,450.00'), findsOneWidget);
    expect(find.widgetWithText(Chip, 'Due at counter'), findsNWidgets(2));
    expect(find.widgetWithText(Chip, 'Included'), findsOneWidget);
    expect(find.widgetWithText(Chip, 'Paid'), findsNothing);
    expect(find.text('Total'), findsOneWidget);
    expect(find.text('Paid in app'), findsOneWidget);
    // Total and amount due are both R1,699.00; the due one is emphasised.
    final due = tester.widgetList<Text>(find.text('R1,699.00')).last;
    expect(due.style!.color, serv60AccentInk);
    expect(due.style!.fontSize, 18);
    expect(find.text('Nothing to pay'), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('a settled invoice has nothing to pay and lists the stages',
      (tester) async {
    final repo = InvoiceJourney(bill: invoiceJson(settled: true));
    await tester.pumpWidget(invoicePage(repo));
    await tester.pumpAndSettle();
    expect(find.widgetWithText(Chip, 'Paid'), findsNWidgets(2));
    expect(find.widgetWithText(Chip, 'Due at counter'), findsNothing);
    expect(find.text('R1,699.00'), findsNWidgets(2)); // Total and Paid in app
    expect(find.text('Nothing to pay'), findsOneWidget);
    await scrollTo(tester, find.text('Handed over'));
    expect(find.text('Service record'), findsOneWidget);
    for (final label in [
      'Checked in',
      'Inspection',
      'Service',
      'Quality check',
      'Wash',
      'Ready',
      'Handed over'
    ]) {
      expect(find.text(label), findsOneWidget);
    }
    expect(find.text('Tyres fine'), findsOneWidget);
    expect(find.text('08:03'), findsOneWidget);
    expect(find.text('08:45'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('shows the backend message before the invoice is issued',
      (tester) async {
    final repo = InvoiceJourney()..bill = null;
    await tester.pumpWidget(invoicePage(repo));
    await tester.pumpAndSettle();
    expect(find.text(notYet), findsOneWidget);
    expect(find.byType(LinearProgressIndicator), findsNothing);
    expect(find.text('Service record'), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('shows a loading bar until the invoice arrives', (tester) async {
    final repo = InvoiceJourney()..gate = Completer<void>();
    await tester.pumpWidget(invoicePage(repo));
    await tester.pump();
    expect(find.byType(LinearProgressIndicator), findsOneWidget);
    expect(find.text('S60-20261025-C00000'), findsNothing);
    repo.gate!.complete();
    await tester.pumpAndSettle();
    expect(find.byType(LinearProgressIndicator), findsNothing);
    expect(find.text('S60-20261025-C00000'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('keeps the invoice when the stage record cannot load',
      (tester) async {
    final repo = InvoiceJourney()..progressFailure = 'Record unavailable';
    await tester.pumpWidget(invoicePage(repo));
    await tester.pumpAndSettle();
    expect(find.text('S60-20261025-C00000'), findsOneWidget);
    await scrollTo(tester, find.text('Record unavailable'));
    expect(find.text('Record unavailable'), findsOneWidget);

    await tester.pumpWidget(const SizedBox());
    final sparse = InvoiceJourney(
        bill: {...invoiceJson(), 'issuedAt': null, 'customerName': null},
        stages: progressJson());
    await tester.pumpWidget(invoicePage(sparse));
    await tester.pumpAndSettle();
    expect(find.textContaining('Issued'), findsNothing);
    expect(find.text('Alex Demo'), findsNothing);
    await scrollTo(
        tester, find.text('No stages were recorded for this visit.'));
    expect(
        find.text('No stages were recorded for this visit.'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });
}
