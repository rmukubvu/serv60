import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import '../lib/src/design/serv60_theme.dart';

import '../lib/main.dart';
import '../lib/src/auth/cognito_auth.dart';
import '../lib/src/payment/payment_checkout.dart';
import '../lib/src/payment/payment_repository.dart';

void main() {
  testWidgets('configured auth gate asks the customer to sign in',
      (tester) async {
    tester.view.physicalSize = const Size(320, 480);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final auth = CognitoAuthController(CognitoAuthClient(
      configuration: const CognitoAuthConfiguration(
        discoveryUrl:
            'https://serv60.auth.test/.well-known/openid-configuration',
        clientId: 'mobile-client',
        redirectUrl: 'serv60://oauth/callback',
      ),
    ));
    final payments = PaymentRepository();
    final checkout = PaymentCheckoutCoordinator(payments: payments);

    await tester.pumpWidget(MaterialApp(
      theme: serv60Theme(),
      home: AuthGate(auth: auth, payments: payments, checkout: checkout),
    ));

    expect(find.text('Continue with Google'), findsOneWidget);
    expect(find.text('Your hour, made useful.'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('Continue with Google'), 120);
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);

    checkout.dispose();
    auth.dispose();
  });

  testWidgets('unconfigured auth gate keeps the local demo available',
      (tester) async {
    final auth = CognitoAuthController(CognitoAuthClient(
      configuration: const CognitoAuthConfiguration(
        discoveryUrl: '',
        clientId: '',
        redirectUrl: 'serv60://oauth/callback',
      ),
    ));
    final payments = PaymentRepository();
    final checkout = PaymentCheckoutCoordinator(payments: payments);

    await tester.pumpWidget(MaterialApp(
      theme: serv60Theme(),
      home: AuthGate(auth: auth, payments: payments, checkout: checkout),
    ));

    expect(find.text('Your vehicle service'), findsOneWidget);
    expect(find.text('Roadside help', skipOffstage: false), findsOneWidget);
    await tester.tap(find.text('Roadside'));
    await tester.pumpAndSettle();
    expect(find.text('Roadside help'), findsOneWidget);
    await tester.tap(find.text('Book'));
    await tester.pumpAndSettle();
    expect(find.text('Your vehicle service'), findsOneWidget);

    checkout.dispose();
    auth.dispose();
  });
}
