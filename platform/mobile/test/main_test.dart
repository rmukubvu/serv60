import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:serv60_mobile/src/design/serv60_theme.dart';

import 'package:serv60_mobile/main.dart';
import 'package:serv60_mobile/src/auth/clerk_auth.dart';
import 'package:serv60_mobile/src/payment/payment_checkout.dart';
import 'package:serv60_mobile/src/payment/payment_repository.dart';

void main() {
  testWidgets('configured auth gate asks the customer to sign in',
      (tester) async {
    tester.view.physicalSize = const Size(320, 480);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final auth = ClerkAuthController(ClerkAuthClient(
      configuration: const ClerkAuthConfiguration(
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
    final auth = ClerkAuthController(ClerkAuthClient(
      configuration: const ClerkAuthConfiguration(
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

    expect(find.text('Your next stop.\nYour hour back.'), findsOneWidget);
    expect(find.text('Roadside help', skipOffstage: false), findsOneWidget);
    await tester.tap(find.text('Roadside'));
    await tester.pumpAndSettle();
    expect(find.text('Roadside help'), findsOneWidget);
    await tester.tap(find.text('Explore'));
    await tester.pumpAndSettle();
    expect(find.text('Your next stop.\nYour hour back.'), findsOneWidget);

    checkout.dispose();
    auth.dispose();
  });
}
