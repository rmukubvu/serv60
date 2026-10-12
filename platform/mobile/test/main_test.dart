import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../lib/main.dart';
import '../lib/src/auth/cognito_auth.dart';
import '../lib/src/payment/payment_checkout.dart';
import '../lib/src/payment/payment_repository.dart';

void main() {
  testWidgets('configured auth gate asks the customer to sign in',
      (tester) async {
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
      home: AuthGate(auth: auth, payments: payments, checkout: checkout),
    ));

    expect(find.text('Continue with Google'), findsOneWidget);
    expect(find.text('Your hour, made useful.'), findsOneWidget);

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
      home: AuthGate(auth: auth, payments: payments, checkout: checkout),
    ));

    expect(find.text('Choose a service'), findsOneWidget);
    expect(find.text('Roadside help', skipOffstage: false), findsOneWidget);

    checkout.dispose();
    auth.dispose();
  });
}
