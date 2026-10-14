import 'package:flutter/material.dart';
import '../design/serv60_theme.dart';
import '../payment/payment_checkout.dart';
import 'journey_repository.dart';
import 'journey_widgets.dart';

/// Shown immediately after a successful reserve so payment is not easy to miss.
class ReservationConfirmationPage extends StatefulWidget {
  const ReservationConfirmationPage(
      {super.key,
      required this.hub,
      required this.hold,
      required this.repository,
      required this.checkout});

  final Hub hub;
  final VisitHold hold;
  final JourneyRepository repository;
  final PaymentCheckoutCoordinator checkout;

  @override
  State<ReservationConfirmationPage> createState() =>
      _ReservationConfirmationPageState();
}

class _ReservationConfirmationPageState
    extends State<ReservationConfirmationPage> {
  bool working = false;
  String? error;
  bool paid = false;

  @override
  void initState() {
    super.initState();
    widget.checkout.addListener(_onPayment);
  }

  @override
  void dispose() {
    widget.checkout.removeListener(_onPayment);
    super.dispose();
  }

  Future<void> _onPayment() async {
    final order = widget.checkout.order;
    if (order == null ||
        order.status != 'PAID' ||
        order.holdId != widget.hold.id ||
        working) {
      return;
    }
    setState(() => working = true);
    try {
      await widget.repository.confirmPayment(widget.hold.id);
      if (mounted) setState(() => paid = true);
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => working = false);
    }
  }

  Future<void> pay() async {
    setState(() {
      working = true;
      error = null;
    });
    try {
      final order = await widget.repository.payment(widget.hold.id);
      await widget.checkout.begin(order.id);
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => working = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
      appBar: AppBar(title: const Text('Reservation held')),
      body: Center(
          child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 640),
              child: ListView(padding: const EdgeInsets.all(24), children: [
                Text(paid ? 'Visit confirmed' : 'Pay to confirm your visit',
                    style: Theme.of(context).textTheme.headlineSmall),
                const SizedBox(height: 8),
                Text(
                    paid
                        ? 'Your express service and complimentary option are booked.'
                        : 'Your space is reserved until ${slotTime(widget.hold.expiresAt)} SAST. Complete payment before the hold expires.',
                    style: const TextStyle(color: serv60Muted)),
                const SizedBox(height: 24),
                Text(widget.hub.name,
                    style: const TextStyle(
                        fontWeight: FontWeight.w600, fontSize: 18)),
                Text(visitTime(widget.hold.startsAt),
                    style: const TextStyle(fontSize: 22)),
                const Text('South Africa time · SAST',
                    style: TextStyle(color: serv60Muted, fontSize: 11)),
                const SizedBox(height: 12),
                const Text('Express vehicle service · 60 minutes'),
                Text(optionLabel(widget.hold.option)),
                if (error != null) JourneyNotice(error!),
                const SizedBox(height: 28),
                if (!paid)
                  FilledButton(
                      onPressed: working ? null : pay,
                      child: Text(working
                          ? 'Opening checkout…'
                          : 'Pay securely with Yoco')),
                const SizedBox(height: 12),
                OutlinedButton(
                    onPressed: working
                        ? null
                        : () => Navigator.pop(context, true),
                    child: Text(paid
                        ? 'View my bookings'
                        : 'Pay later from Bookings')),
              ]))));
}
