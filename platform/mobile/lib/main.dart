import 'package:flutter/material.dart';
import 'src/auth/access_token_provider.dart';
import 'src/auth/cognito_auth.dart';
import 'src/booking/booking_page.dart';
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
      theme: ThemeData(
          brightness: Brightness.dark,
          colorSchemeSeed: const Color(0xffc7f464),
          scaffoldBackgroundColor: const Color(0xff0a0d12),
          useMaterial3: true),
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
        body: Center(
          child: Padding(
            padding: const EdgeInsets.all(28),
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              const Text('Your hour, made useful.',
                  style: TextStyle(fontSize: 30, fontWeight: FontWeight.w700)),
              const SizedBox(height: 12),
              const Text('Sign in securely with Google through Cognito.',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: Colors.white60)),
              const SizedBox(height: 24),
              FilledButton.icon(
                  onPressed: auth.loading ? null : auth.signIn,
                  icon: const Icon(Icons.login),
                  label: Text(
                      auth.loading ? 'Signing in…' : 'Continue with Google')),
              if (auth.error != null) ...[
                const SizedBox(height: 12),
                Text(auth.error.toString(),
                    textAlign: TextAlign.center,
                    style: const TextStyle(color: Colors.redAccent)),
              ],
            ]),
          ),
        ),
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
        body: IndexedStack(index: selectedIndex, children: [
          BookingPage(
              tokenProvider: widget.tokenProvider,
              paymentRepository: widget.payments,
              checkoutCoordinator: widget.checkout),
          RoadsidePage(tokenProvider: widget.tokenProvider)
        ]),
        bottomNavigationBar: NavigationBar(
            selectedIndex: selectedIndex,
            onDestinationSelected: (index) =>
                setState(() => selectedIndex = index),
            destinations: const [
              NavigationDestination(
                  icon: Icon(Icons.event_available), label: 'Book'),
              NavigationDestination(
                  icon: Icon(Icons.car_repair), label: 'Roadside')
            ]),
      );
}
