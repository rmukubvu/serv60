import 'package:flutter/material.dart';
import 'src/auth/access_token_provider.dart';
import 'src/auth/cognito_auth.dart';
import 'src/journey/journey_repository.dart';
import 'src/journey/discovery_page.dart';
import 'src/journey/assistant_page.dart';
import 'src/journey/appointments_page.dart';
import 'src/journey/plan_visit_page.dart';
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
    this.repository,
  });

  final JourneyRepository? repository;
  final AccessTokenProvider tokenProvider;
  final PaymentRepository payments;
  final PaymentCheckoutCoordinator checkout;

  @override
  State<Serv60Home> createState() => _Serv60HomeState();
}

class _Serv60HomeState extends State<Serv60Home> {
  int selectedIndex = 0, refreshVersion = 0;
  Hub? selectedHub;
  late final JourneyRepository repository;
  @override
  void initState() {
    super.initState();
    repository = widget.repository ??
        JourneyRepository(tokenProvider: widget.tokenProvider);
  }

  @override
  void dispose() {
    if (widget.repository == null) repository.close();
    super.dispose();
  }

  void explore() => setState(() => selectedIndex = 0);
  void booked() => setState(() {
        selectedIndex = 2;
        refreshVersion++;
      });
  Future<void> book(Hub hub) async {
    setState(() => selectedHub = hub);
    final saved = await Navigator.push<bool>(
        context,
        MaterialPageRoute(
            builder: (_) => PlanVisitPage(hub: hub, repository: repository)));
    if (mounted && saved == true) booked();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        body: SafeArea(
            bottom: false,
            child: Center(
                child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 640),
                    child: IndexedStack(index: selectedIndex, children: [
                      DiscoveryPage(
                          repository: repository,
                          onBook: book,
                          onAsk: (hub) => setState(() {
                                selectedHub = hub;
                                selectedIndex = 1;
                              })),
                      AssistantPage(
                          repository: repository,
                          hub: selectedHub,
                          onExplore: explore,
                          onBooked: booked),
                      AppointmentsPage(
                          repository: repository,
                          checkout: widget.checkout,
                          refreshVersion: refreshVersion,
                          onExplore: explore,
                          active: selectedIndex == 2),
                      RoadsidePage(tokenProvider: widget.tokenProvider),
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
                          onDestinationSelected: (index) => setState(() {
                            selectedIndex = index;
                            if (index == 2) refreshVersion++;
                          }),
                          destinations: const [
                            NavigationDestination(
                                icon: Icon(Icons.explore_outlined),
                                label: 'Explore'),
                            NavigationDestination(
                                icon: Icon(Icons.chat_bubble_outline),
                                label: 'Assistant'),
                            NavigationDestination(
                                icon: Icon(Icons.event_available_outlined),
                                label: 'Bookings'),
                            NavigationDestination(
                                icon: Icon(Icons.car_repair),
                                label: 'Roadside'),
                          ],
                        ))))),
      );
}
