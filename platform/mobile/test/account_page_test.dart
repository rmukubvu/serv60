import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:serv60_mobile/src/auth/clerk_auth.dart';
import 'package:serv60_mobile/src/journey/account_page.dart';
import 'package:serv60_mobile/src/journey/journey_repository.dart';

import 'clerk_auth_test.dart'
    show FakeAuthorizationClient, FakeSessionStore, configuration;
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
    final fields = find.byType(TextField);
    await tester.enterText(fields.at(4), 'VW');
    await tester.enterText(fields.at(5), 'Polo');
    await tester.enterText(fields.at(6), 'CA 2');
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
}
