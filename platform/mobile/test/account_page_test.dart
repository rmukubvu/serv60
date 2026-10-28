import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:serv60_mobile/src/auth/clerk_auth.dart';
import 'package:serv60_mobile/src/journey/account_page.dart';
import 'package:serv60_mobile/src/journey/invoice_page.dart';
import 'package:serv60_mobile/src/journey/journey_repository.dart';

import 'clerk_auth_test.dart'
    show FakeAuthorizationClient, FakeSessionStore, configuration;
import 'invoice_page_test.dart' show InvoiceJourney, recordJson;
import 'journey_test.dart' show FakeJourney, app, tapVisible;

class ProfileJourney extends FakeJourney {
  Map<String, dynamic> stored = {
    'customerId': 'customer-1',
    'customerName': 'Alex Demo',
    'phoneNumber': '+27000000000',
    'vehicles': [
      {
        'id': 'v1',
        'vehicleMake': 'Toyota',
        'vehicleModel': 'Corolla',
        'registration': 'CA 1',
        'isDefault': true
      }
    ]
  };
  Map<String, dynamic>? saved;
  bool failLoad = false;

  @override
  Future<CustomerProfile> profile() async {
    if (failLoad) throw JourneyException('Profile unavailable');
    return CustomerProfile.fromJson(stored);
  }

  @override
  Future<CustomerProfile> saveProfile(
      {required String customerName,
      required String phoneNumber,
      List<Map<String, dynamic>> vehicles = const []}) async {
    saved = {
      'customerName': customerName,
      'phoneNumber': phoneNumber,
      'vehicles': vehicles
    };
    return CustomerProfile.fromJson({'customerId': 'customer-1', ...saved!});
  }
}

class HistoryJourney extends ProfileJourney {
  List<Map<String, dynamic>> records = [];
  String? historyFailure;
  int historyCalls = 0;
  final invoices = InvoiceJourney();

  @override
  Future<List<ServiceRecord>> serviceHistory({int limit = 20}) async {
    historyCalls++;
    if (historyFailure != null) throw JourneyException(historyFailure!);
    return records.map(ServiceRecord.fromJson).toList();
  }

  @override
  Future<Invoice> invoice(String visitId) => invoices.invoice(visitId);

  @override
  Future<VisitProgress> progress(String id) => invoices.progress(id);
}

class FakeAuth extends ClerkAuthController {
  FakeAuth({this.name})
      : super(ClerkAuthClient(
            configuration: configuration,
            sessionStore: FakeSessionStore(),
            authorizationClient: FakeAuthorizationClient()));
  final String? name;
  bool signedOut = false;

  @override
  String? get displayName => name;
  @override
  String? get displayEmail => 'alex@example.test';
  @override
  bool get isConfigured => true;
  @override
  Future<void> signOut() async => signedOut = true;
}

/// The notice sits at the top of a lazy list, so scroll back up before asserting.
Future<void> showNotice(WidgetTester tester, String text) async {
  await tester.scrollUntilVisible(find.textContaining(text), -200,
      scrollable: find.byType(Scrollable).first);
  expect(find.textContaining(text), findsOneWidget);
}

void main() {
  testWidgets('edits vehicles and saves with the Clerk name', (tester) async {
    final repo = ProfileJourney();
    final auth = FakeAuth(name: 'Alex Demo');
    await tester.pumpWidget(app(AccountPage(repository: repo, auth: auth)));
    await tester.pumpAndSettle();
    expect(find.text('alex@example.test'), findsOneWidget);

    await tapVisible(tester, find.text('Add'));
    // The list is lazy, so address the new card by label, not position.
    await tester.enterText(
        find.widgetWithText(TextField, 'Car make').last, 'VW');
    await tester.enterText(
        find.widgetWithText(TextField, 'Car model').last, 'Polo');
    await tester.enterText(
        find.widgetWithText(TextField, 'Registration').last, 'CA 2');
    await tapVisible(tester, find.text('Default for booking').last);
    await tapVisible(tester, find.byTooltip('Remove vehicle').first);
    await tapVisible(tester, find.text('Save profile'));

    final vehicles = repo.saved!['vehicles'] as List<Map<String, dynamic>>;
    expect(repo.saved!['customerName'], 'Alex Demo');
    expect(vehicles.single['registration'], 'CA 2');
    expect(vehicles.single['isDefault'], isTrue);
    expect(find.text('Profile saved for faster booking.'), findsOneWidget);
    await tapVisible(tester, find.text('Sign out'));
    expect(auth.signedOut, isTrue);
  });

  testWidgets('refuses to save without a name or with a partial vehicle',
      (tester) async {
    final repo = ProfileJourney()..stored = {'customerId': 'customer-1'};
    await tester.pumpWidget(app(AccountPage(repository: repo)));
    await tester.pumpAndSettle();

    await tapVisible(tester, find.text('Save profile'));
    await showNotice(tester, 'Sign in with Clerk');

    await tester.pumpWidget(app(AccountPage(
        key: const ValueKey('named'),
        repository: repo,
        auth: FakeAuth(name: 'Alex Demo'))));
    await tester.pumpAndSettle();
    final make = find.widgetWithText(TextField, 'Car make');
    await tester.scrollUntilVisible(make, 200,
        scrollable: find.byType(Scrollable).first);
    await tester.enterText(make, 'Toyota');
    await tapVisible(tester, find.text('Save profile'));
    await showNotice(tester, 'make, model, and registration');
    expect(repo.saved, isNull);
  });

  testWidgets('shows load failures and loads once it becomes active',
      (tester) async {
    final repo = ProfileJourney()..failLoad = true;
    await tester.pumpWidget(app(AccountPage(repository: repo, active: false)));
    await tester.pumpAndSettle();
    expect(find.textContaining('Profile unavailable'), findsNothing);

    await tester.pumpWidget(app(AccountPage(repository: repo)));
    await tester.pumpAndSettle();

    expect(find.textContaining('Profile unavailable'), findsOneWidget);
  });

  group('service history', () {
    Future<void> seen(WidgetTester tester, Finder finder) async {
      await tester.scrollUntilVisible(finder, 200,
          scrollable: find.byType(Scrollable).first);
      await tester.pumpAndSettle();
      expect(finder, findsOneWidget);
    }

    testWidgets('groups visits by vehicle and opens the invoice',
        (tester) async {
      final repo = HistoryJourney()
        ..records = [
          recordJson(),
          recordJson(
              visitId: 'visit-2',
              number: 'S60-20261024-B00000',
              vehicle: 'VW Polo',
              registration: 'CA 2',
              handedOverAt: '2026-10-24T15:30:00+02:00',
              total: 24900,
              due: 0),
          recordJson(
              visitId: 'visit-0',
              number: 'S60-20261001-A00000',
              handedOverAt: null,
              total: 35000,
              due: 0),
        ];
      await tester.pumpWidget(app(AccountPage(repository: repo)));
      await tester.pumpAndSettle();
      expect(repo.historyCalls, 1);
      await seen(tester, find.text('Service history'));
      await seen(tester, find.text('Toyota Corolla Cross · CA 60 060'));
      await seen(tester, find.text('25 Oct 2026 · Serv60 City Bowl · Demo'));
      await seen(tester, find.text('S60-20261025-C00000'));
      await seen(tester, find.text('R1,985.00 due at counter'));
      // The older Corolla visit sits under the same heading, before the Polo.
      await seen(tester, find.text('S60-20261001-A00000'));
      await seen(tester, find.text('R350.00'));
      await seen(tester, find.text('VW Polo · CA 2'));
      await seen(tester, find.text('S60-20261024-B00000'));
      // Settled visits show no amount due.
      expect(find.text('R350.00 due at counter'), findsNothing);
      expect(find.text('R249.00 due at counter'), findsNothing);
      expect(
          tester.getTopLeft(find.text('S60-20261001-A00000')).dy <
              tester.getTopLeft(find.text('VW Polo · CA 2')).dy,
          isTrue);
      expect(find.textContaining('Your completed visits'), findsNothing);

      await tapVisible(tester, find.text('S60-20261024-B00000'));
      expect(find.byType(InvoicePage), findsOneWidget);
      expect(repo.invoices.requested, contains('invoice/visit-2'));
      expect(find.text('Invoice'), findsOneWidget);
      await tester.pageBack();
      await tester.pumpAndSettle();
      expect(find.byType(InvoicePage), findsNothing);
    });

    testWidgets('shows the empty state and history failures', (tester) async {
      final repo = HistoryJourney();
      await tester.pumpWidget(app(AccountPage(repository: repo)));
      await tester.pumpAndSettle();
      await seen(tester,
          find.text('Your completed visits and invoices will appear here.'));

      final failing = HistoryJourney()..historyFailure = 'History unavailable';
      await tester.pumpWidget(app(
          AccountPage(key: const ValueKey('failing'), repository: failing)));
      await tester.pumpAndSettle();
      await seen(tester, find.text('History unavailable'));
      expect(find.textContaining('Your completed visits'), findsNothing);
    });

    testWidgets('loads history only once the tab becomes active',
        (tester) async {
      final repo = HistoryJourney()..records = [recordJson()];
      await tester
          .pumpWidget(app(AccountPage(repository: repo, active: false)));
      await tester.pumpAndSettle();
      expect(repo.historyCalls, 0);
      await tester.pumpWidget(app(AccountPage(repository: repo)));
      await tester.pumpAndSettle();
      expect(repo.historyCalls, 1);
      await seen(tester, find.text('S60-20261025-C00000'));
    });
  });
}
