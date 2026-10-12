import 'package:flutter/material.dart';
import 'src/auth/access_token_provider.dart';
import 'src/auth/cognito_auth.dart';
import 'src/booking/booking_page.dart';
import 'src/design/serv60_theme.dart';
import 'src/payment/payment_checkout.dart';
import 'src/payment/payment_repository.dart';
import 'src/roadside/roadside_page.dart';

void main() {
  final auth = CognitoAuthController(CognitoAuthClient(
      configuration: CognitoAuthConfiguration.fromEnvironment()));
  final payments = PaymentRepository(tokenProvider: auth);
  final checkout = PaymentCheckoutCoordinator(payments: payments);
  runApp(Serv60App(auth: auth, payments: payments, checkout: checkout));
}

class Serv60App extends StatefulWidget {
  const Serv60App({
    super.key,
    required this.auth,
    required this.payments,
    required this.checkout,
  });

  final CognitoAuthController auth;
  final PaymentRepository payments;
  final PaymentCheckoutCoordinator checkout;

  @override
  State<Serv60App> createState() => _Serv60AppState();
}

class _Serv60AppState extends State<Serv60App> {
  @override
  void initState() {
    super.initState();
    widget.auth.restore();
    widget.checkout.listenForReturns();
  }

  @override
  void dispose() {
    widget.auth.dispose();
    widget.checkout.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
      title: 'Serv60',
      debugShowCheckedModeBanner: false,
      theme: serv60Theme(),
      home: AuthGate(
          auth: widget.auth,
          payments: widget.payments,
          checkout: widget.checkout));
}

class AuthGate extends StatelessWidget {
  const AuthGate({
    super.key,
    required this.auth,
    required this.payments,
    required this.checkout,
  });

  final CognitoAuthController auth;
  final PaymentRepository payments;
  final PaymentCheckoutCoordinator checkout;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
      listenable: auth,
      builder: (context, child) {
        if (!auth.isConfigured) {
          return Serv60Home(
              tokenProvider: auth, payments: payments, checkout: checkout);
        }
        if (auth.loading) {
          return const Scaffold(
              body: Center(child: CircularProgressIndicator()));
        }
        if (!auth.isSignedIn) return SignInPage(auth: auth);
        return Serv60Home(
            tokenProvider: auth, payments: payments, checkout: checkout);
      });
}

class SignInPage extends StatelessWidget {
  const SignInPage({super.key, required this.auth});

  final CognitoAuthController auth;

  @override
  Widget build(BuildContext context) => Scaffold(
        body: SafeArea(
            child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(28),
            child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 440),
                child: Column(mainAxisSize: MainAxisSize.min, children: [
                  const Serv60Wordmark(),
                  const SizedBox(height: 48),
                  Text('Your hour, made useful.',
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.headlineLarge),
                  const SizedBox(height: 12),
                  const Text(
                      'Sign in to plan your visit. We’ll take care of the rest.',
                      textAlign: TextAlign.center,
                      style: TextStyle(color: serv60Muted)),
                  const SizedBox(height: 24),
                  FilledButton.icon(
                      onPressed: auth.loading ? null : auth.signIn,
                      icon: const Icon(Icons.login),
                      label: Text(auth.loading
                          ? 'Signing in…'
                          : 'Continue with Google')),
                  if (auth.error != null) ...[
                    const SizedBox(height: 12),
                    Text(auth.error.toString(),
                        textAlign: TextAlign.center,
                        style: const TextStyle(color: serv60Error)),
                  ],
                ])),
          ),
        )),
      );
}

class Serv60Home extends StatefulWidget {
  const Serv60Home({
    super.key,
    required this.tokenProvider,
    required this.payments,
    required this.checkout,
  });

  final AccessTokenProvider tokenProvider;
  final PaymentRepository payments;
  final PaymentCheckoutCoordinator checkout;

  @override
  State<Serv60Home> createState() => _Serv60HomeState();
}

class _Serv60HomeState extends State<Serv60Home> {
  int selectedIndex = 0;

  @override
  Widget build(BuildContext context) => Scaffold(
        body: SafeArea(
            bottom: false,
            child: Center(
                child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 640),
                    child: IndexedStack(index: selectedIndex, children: [
                      BookingPage(
                          tokenProvider: widget.tokenProvider,
                          paymentRepository: widget.payments,
                          checkoutCoordinator: widget.checkout),
                      RoadsidePage(tokenProvider: widget.tokenProvider)
                    ])))),
        bottomNavigationBar: DecoratedBox(
            decoration: const BoxDecoration(
                border: Border(top: BorderSide(color: serv60Border))),
            child: SafeArea(
                top: false,
                child: Center(
                    heightFactor: 1,
                    child: ConstrainedBox(
                        constraints: const BoxConstraints(maxWidth: 640),
                        child: NavigationBar(
                            selectedIndex: selectedIndex,
                            onDestinationSelected: (index) =>
                                setState(() => selectedIndex = index),
                            destinations: const [
                              NavigationDestination(
                                  icon: Icon(Icons.event_available),
                                  label: 'Book'),
                              NavigationDestination(
                                  icon: Icon(Icons.car_repair),
                                  label: 'Roadside')
                            ]))))),
      );
}
