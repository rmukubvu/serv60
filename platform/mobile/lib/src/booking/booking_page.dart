import 'dart:async';

import 'package:flutter/material.dart';
import '../auth/access_token_provider.dart';
import '../payment/payment_checkout.dart';
import '../payment/payment_repository.dart';
import 'booking_repository.dart';

class BookingPage extends StatefulWidget {
  const BookingPage({
    super.key,
    this.repository,
    this.paymentRepository,
    this.checkoutCoordinator,
    this.tokenProvider,
  });
  final BookingRepository? repository;
  final PaymentRepository? paymentRepository;
  final PaymentCheckoutCoordinator? checkoutCoordinator;
  final AccessTokenProvider? tokenProvider;

  @override
  State<BookingPage> createState() => _BookingPageState();
}

class _BookingPageState extends State<BookingPage> {
  late final BookingRepository repository;
  late final PaymentRepository paymentRepository;
  late final PaymentCheckoutCoordinator checkoutCoordinator;
  bool ownsCheckoutCoordinator = false;
  static const serviceCode = 'EXPRESS_OIL';
  String? complimentaryServiceCode;
  bool checkedAvailability = false;
  bool loading = false;
  bool checking = false;
  VisitHold? hold;
  List<AvailabilitySlot> slots = const [];
  PaymentOrder? payment;
  bool paying = false;
  bool confirming = false;

  @override
  void initState() {
    super.initState();
    repository = widget.repository ??
        BookingRepository(tokenProvider: widget.tokenProvider);
    paymentRepository = widget.paymentRepository ??
        PaymentRepository(tokenProvider: widget.tokenProvider);
    checkoutCoordinator = widget.checkoutCoordinator ??
        PaymentCheckoutCoordinator(payments: paymentRepository);
    ownsCheckoutCoordinator = widget.checkoutCoordinator == null;
    payment = checkoutCoordinator.order;
    checkoutCoordinator.addListener(_checkoutChanged);
    if (payment?.status == 'PAID') {
      unawaited(_confirmPaidVisit(payment!));
    }
  }

  void _checkoutChanged() {
    final latest = checkoutCoordinator.order;
    if (mounted) setState(() => payment = latest);
    if (latest?.status == 'PAID') unawaited(_confirmPaidVisit(latest!));
  }

  Future<void> _confirmPaidVisit(PaymentOrder paidOrder) async {
    if (confirming) return;
    setState(() => confirming = true);
    try {
      final current = hold ?? await repository.findHold(paidOrder.holdId);
      if (current == null || current.status == 'CONFIRMED') return;
      final confirmed = await repository.confirmHold(current.id);
      if (mounted) setState(() => hold = confirmed);
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(error.toString())));
      }
    } finally {
      if (mounted) setState(() => confirming = false);
    }
  }

  @override
  void dispose() {
    checkoutCoordinator.removeListener(_checkoutChanged);
    if (ownsCheckoutCoordinator) checkoutCoordinator.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
      appBar: AppBar(title: const Text('Serv60'), actions: [
        IconButton(
            onPressed: hold == null
                ? null
                : () async {
                    final refreshed = await repository.findHold(hold!.id);
                    if (mounted && refreshed != null)
                      setState(() => hold = refreshed);
                  },
            icon: const Icon(Icons.refresh))
      ]),
      body: ListView(padding: const EdgeInsets.all(20), children: [
        const Text('Your hour, made useful.',
            style: TextStyle(
                fontSize: 32, fontWeight: FontWeight.w700, letterSpacing: -1)),
        const SizedBox(height: 8),
        const Text('Book the service, then spend the time your way.',
            style: TextStyle(color: Colors.white60)),
        const SizedBox(height: 28),
        const Text('Your vehicle service',
            style: TextStyle(fontWeight: FontWeight.w700)),
        const SizedBox(height: 12),
        const Card(
          color: Color(0xff1d2a1c),
          child: ListTile(
            leading: Icon(Icons.check_circle, color: Color(0xffc7f464)),
            title: Text('Express vehicle service'),
            subtitle: Text('60 min · Required with every visit'),
          ),
        ),
        const SizedBox(height: 20),
        const Text('Make the hour yours',
            style: TextStyle(fontWeight: FontWeight.w700)),
        const SizedBox(height: 8),
        const Text(
            'Choose one complimentary option while we service your car. No extra charge.',
            style: TextStyle(color: Colors.white60)),
        const SizedBox(height: 12),
        for (final item in const [
          ('BARBER', 'Barber', '45 min · Complimentary'),
          ('PRIVATE_WORK', 'Private workspace', '60 min · Complimentary')
        ])
          Card(
            color: complimentaryServiceCode == item.$1
                ? const Color(0xff1d2a1c)
                : const Color(0xff121820),
            child: CheckboxListTile(
              controlAffinity: ListTileControlAffinity.leading,
              value: complimentaryServiceCode == item.$1,
              onChanged: checking || loading || hold != null
                  ? null
                  : (checked) => setState(() {
                        complimentaryServiceCode =
                            checked == true ? item.$1 : null;
                        slots = const [];
                        checkedAvailability = false;
                      }),
              title: Text(item.$2),
              subtitle: Text(item.$3),
            ),
          ),
        const SizedBox(height: 18),
        OutlinedButton(
            onPressed: checking || loading || hold != null
                ? null
                : () async {
                    setState(() => checking = true);
                    try {
                      final available = await repository.availability(
                          serviceCode: serviceCode,
                          complimentaryServiceCode: complimentaryServiceCode);
                      if (mounted)
                        setState(() {
                          slots = available;
                          checkedAvailability = true;
                        });
                    } catch (error) {
                      if (mounted)
                        ScaffoldMessenger.of(context).showSnackBar(
                            SnackBar(content: Text(error.toString())));
                    } finally {
                      if (mounted) setState(() => checking = false);
                    }
                  },
            child: Text(checking ? 'Loading times…' : 'Check live times')),
        if (checkedAvailability && slots.isEmpty)
          const Padding(
              padding: EdgeInsets.symmetric(vertical: 12),
              child: Text(
                  'No times available for this combination. Try another complimentary option.')),
        for (final slot in slots)
          ListTile(
              leading: const Icon(Icons.schedule),
              title: Text(
                  '${slot.startsAt.toLocal().hour.toString().padLeft(2, '0')}:${slot.startsAt.toLocal().minute.toString().padLeft(2, '0')}'),
              subtitle: Text(slot.readinessPolicy)),
        const SizedBox(height: 8),
        FilledButton(
            onPressed: loading || slots.isEmpty || hold != null
                ? null
                : () async {
                    setState(() => loading = true);
                    try {
                      final selectedSlot = slots.first;
                      final created = await repository.createHold(
                          serviceCode: serviceCode,
                          complimentaryServiceCode: complimentaryServiceCode,
                          startsAt: selectedSlot.startsAt,
                          endsAt: selectedSlot.endsAt);
                      if (mounted) setState(() => hold = created);
                    } catch (error) {
                      if (mounted)
                        ScaffoldMessenger.of(context).showSnackBar(
                            SnackBar(content: Text(error.toString())));
                    } finally {
                      if (mounted) setState(() => loading = false);
                    }
                  },
            child: Text(loading ? 'Holding time…' : 'Hold my visit')),
        if (hold != null) ...[
          const SizedBox(height: 12),
          FilledButton.icon(
              onPressed: paying
                  ? null
                  : () async {
                      setState(() => paying = true);
                      try {
                        final created = await paymentRepository.createOrder(
                            holdId: hold!.id,
                            amountMinor: _priceFor(serviceCode),
                            provider: 'YOCO');
                        await checkoutCoordinator.begin(created.id);
                      } catch (error) {
                        if (mounted)
                          ScaffoldMessenger.of(context).showSnackBar(
                              SnackBar(content: Text(error.toString())));
                      } finally {
                        if (mounted) setState(() => paying = false);
                      }
                    },
              icon: const Icon(Icons.lock_open),
              label: Text(paying
                  ? 'Opening secure checkout…'
                  : 'Pay securely with Yoco')),
        ],
        const SizedBox(height: 28),
        _VisitCard(hold: hold, payment: payment)
      ]));
}

int _priceFor(String serviceCode) {
  final configured = switch (serviceCode) {
    'EXPRESS_OIL' => const String.fromEnvironment(
        'SERV60_EXPRESS_OIL_PRICE_MINOR',
        defaultValue: '24900'),
    'BARBER' => const String.fromEnvironment('SERV60_BARBER_PRICE_MINOR',
        defaultValue: '18000'),
    _ => const String.fromEnvironment('SERV60_PRIVATE_WORK_PRICE_MINOR',
        defaultValue: '12000'),
  };
  final amount = int.tryParse(configured);
  return amount != null && amount > 0 ? amount : 12000;
}

class _VisitCard extends StatelessWidget {
  const _VisitCard({this.hold, this.payment});
  final VisitHold? hold;
  final PaymentOrder? payment;
  @override
  Widget build(BuildContext context) => Card(
      color: const Color(0xff111820),
      child: Padding(
          padding: const EdgeInsets.all(18),
          child:
              Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('Your visit',
                style: TextStyle(fontWeight: FontWeight.w700)),
            const SizedBox(height: 12),
            Text(
                hold == null
                    ? 'Your visit includes the vehicle service and, if chosen, one complimentary option during the same hour.'
                    : '${hold!.serviceCode} · ${hold!.status}',
                style: const TextStyle(color: Colors.white60)),
            if (hold?.complimentaryServiceCode != null) ...[
              const SizedBox(height: 8),
              Text(
                  '${hold!.complimentaryServiceCode == 'BARBER' ? 'Barber' : 'Private workspace'} · Complimentary'),
            ],
            if (payment != null) ...[
              const SizedBox(height: 8),
              Text('Payment · ${payment!.status}',
                  style: const TextStyle(color: Colors.white60)),
            ]
          ])));
}
