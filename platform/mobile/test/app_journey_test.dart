import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:app_links/app_links.dart';
import 'package:serv60_mobile/main.dart';
import 'package:serv60_mobile/src/auth/access_token_provider.dart';
import 'package:serv60_mobile/src/auth/cognito_auth.dart';
import 'package:serv60_mobile/src/design/serv60_theme.dart';
import 'package:serv60_mobile/src/journey/journey_repository.dart';
import 'package:serv60_mobile/src/journey/discovery_page.dart';
import 'package:serv60_mobile/src/payment/payment_repository.dart';
import 'package:serv60_mobile/src/payment/payment_checkout.dart';
import 'cognito_auth_test.dart'
    show FakeAuthorizationClient, FakeSessionStore, configuration;
import 'journey_test.dart' show FakeJourney, hubJson, tapVisible;

class AppCheckout extends PaymentCheckoutCoordinator {
  AppCheckout() : super(payments: PaymentRepository());
  bool listening = false;
  @override
  Future<void> listenForReturns({AppLinks? appLinks}) async {
    listening = true;
  }
}

void main() {
  testWidgets(
      'application restores sign-in state and starts payment return listener',
      (tester) async {
    final provider = FakeAuthorizationClient();
    final auth = CognitoAuthController(CognitoAuthClient(
        configuration: configuration,
        sessionStore: FakeSessionStore(),
        authorizationClient: provider));
    final checkout = AppCheckout();
    await tester.pumpWidget(
        Serv60App(auth: auth, payments: checkout.payments, checkout: checkout));
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    await tester.pumpAndSettle();
    expect(checkout.listening, isTrue);
    await tester.tap(find.text('Continue with Google'));
    await tester.pumpAndSettle();
    expect(find.text('Your next stop.\nYour hour back.'), findsOneWidget);
    await tester.tap(find.text('Assistant'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Find a hub'));
    await tester.pumpAndSettle();
    expect(find.text('Your next stop.\nYour hour back.'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });
  testWidgets('sign-in error stays visible and retry remains possible',
      (tester) async {
    final provider = FakeAuthorizationClient()
      ..authorizationResponse = const AuthTokenResponse(
          accessToken: '', refreshToken: null, expiresAt: null);
    final auth = CognitoAuthController(CognitoAuthClient(
        configuration: configuration,
        sessionStore: FakeSessionStore(),
        authorizationClient: provider));
    await tester.pumpWidget(
        MaterialApp(theme: serv60Theme(), home: SignInPage(auth: auth)));
    await auth.signIn();
    await tester.pumpWidget(
        MaterialApp(theme: serv60Theme(), home: SignInPage(auth: auth)));
    await tester.pumpAndSettle();
    expect(
        find.textContaining('did not return an access token'), findsOneWidget);
    auth.dispose();
  });
  testWidgets('home uses selected hub for manual and assistant routes',
      (tester) async {
    final repo = FakeJourney();
    final checkout = AppCheckout();
    addTearDown(checkout.dispose);
    await tester.pumpWidget(MaterialApp(
        theme: serv60Theme(),
        home: Serv60Home(
            tokenProvider: NoopAccessTokenProvider(),
            payments: checkout.payments,
            checkout: checkout,
            repository: repo)));
    // Invoke the discovery selection callback with the same typed hub returned by discovery tests.
    final page = tester.widget<DiscoveryPage>(find.byType(DiscoveryPage));
    page.onBook(Hub.fromJson(hubJson));
    await tester.pumpAndSettle();
    expect(find.text('Plan your visit'), findsOneWidget);
    Navigator.of(tester.element(find.text('Plan your visit'))).pop(true);
    await tester.pumpAndSettle();
    expect(find.text('Upcoming visits'), findsOneWidget);
    await tester.tap(find.text('Explore'));
    await tester.pumpAndSettle();
    page.onAsk(Hub.fromJson(hubJson));
    await tester.pumpAndSettle();
    expect(find.text('Just say the word.'), findsOneWidget);
    await tapVisible(tester, find.text('Find me a time tomorrow'));
    await tapVisible(tester, find.text('Confirm reservation'));
    expect(find.text('Upcoming visits'), findsOneWidget);
  });
}
