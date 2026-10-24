import 'package:flutter/material.dart';
import '../design/serv60_theme.dart';
import '../payment/payment_checkout.dart';
import 'journey_repository.dart';
import 'journey_widgets.dart';
import 'plan_visit_page.dart';
import 'visit_progress_page.dart';

class AppointmentsPage extends StatefulWidget {
  const AppointmentsPage(
      {super.key,
      required this.repository,
      required this.checkout,
      required this.refreshVersion,
      required this.onExplore,
      this.onAskHelp,
      this.active = true});
  final bool active;
  final JourneyRepository repository;
  final PaymentCheckoutCoordinator checkout;
  final int refreshVersion;
  final VoidCallback onExplore;
  final Future<void> Function(Hub hub)? onAskHelp;
  @override
  State<AppointmentsPage> createState() => _AppointmentsPageState();
}

class _AppointmentsPageState extends State<AppointmentsPage> {
  List<Appointment> items = [];
  String? after, afterId, error;
  bool loading = false, working = false;
  @override
  void initState() {
    super.initState();
    widget.checkout.addListener(onPayment);
    if (widget.active) load();
  }

  @override
  void didUpdateWidget(AppointmentsPage old) {
    super.didUpdateWidget(old);
    if (widget.active && old.refreshVersion != widget.refreshVersion) load();
  }

  @override
  void dispose() {
    widget.checkout.removeListener(onPayment);
    super.dispose();
  }

  Future<void> load({bool more = false}) async {
    if (loading) return;
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final page = await widget.repository.appointments(
          after: more ? after : null, afterId: more ? afterId : null);
      if (mounted)
        setState(() {
          items = more ? [...items, ...page.items] : page.items;
          after = page.nextStartsAt;
          afterId = page.nextId;
        });
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> onPayment() async {
    final order = widget.checkout.order;
    if (order == null || order.status != 'PAID' || working) return;
    setState(() => working = true);
    try {
      await widget.repository.confirmPayment(order.holdId);
      await load();
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => working = false);
    }
  }

  Future<void> pay(Appointment booking) async {
    setState(() {
      working = true;
      error = null;
    });
    try {
      final order = await widget.repository.payment(booking.id);
      await widget.checkout.begin(order.id);
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => working = false);
    }
  }

  Future<void> edit(Appointment booking) async {
    setState(() => working = true);
    try {
      final hub = await widget.repository.hub(booking.hubId);
      if (!mounted) return;
      final changed = await Navigator.push<bool>(
          context,
          MaterialPageRoute(
              builder: (_) => PlanVisitPage(
                  hub: hub,
                  repository: widget.repository,
                  appointment: booking,
                  checkout: widget.checkout)));
      if (changed == true) await load();
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => working = false);
    }
  }

  /// Live view of the hour once the hub checks the car in. Confirmed visits
  /// stay listed until four hours after they start, so this is reachable
  /// while the car is on the floor even though the change cutoff has passed.
  Future<void> track(Appointment booking) => Navigator.push<void>(
      context,
      MaterialPageRoute(
          builder: (_) => VisitProgressPage(
              repository: widget.repository, appointment: booking)));

  Future<void> askHelp(Appointment booking) async {
    final ask = widget.onAskHelp;
    if (ask == null || working) return;
    setState(() => working = true);
    try {
      final hub = await widget.repository.hub(booking.hubId);
      if (!mounted) return;
      await ask(hub);
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => working = false);
    }
  }

  Future<void> cancel(Appointment booking) async {
    final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
              title: const Text('Cancel this visit?'),
              content: const Text(
                  'Held reservations release immediately. Confirmed visits need 48 hours’ notice.'),
              actions: [
                TextButton(
                    onPressed: () => Navigator.pop(context, false),
                    child: const Text('Keep visit')),
                FilledButton(
                    onPressed: () => Navigator.pop(context, true),
                    child: const Text('Cancel visit')),
              ],
            ));
    if (confirmed != true || !mounted) return;
    setState(() {
      working = true;
      error = null;
    });
    try {
      await widget.repository.cancel(booking.id);
      await load();
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => working = false);
    }
  }

  @override
  Widget build(BuildContext context) => RefreshIndicator(
      onRefresh: load,
      child: ListView(
          padding: const EdgeInsets.fromLTRB(24, 28, 24, 40),
          children: [
            const JourneyHeader(
                'Your plans, in one place',
                'Good things\non the calendar.',
                'See when your car is due and keep your visit up to date.'),
            Row(children: [
              const Expanded(
                  child:
                      Text('Upcoming visits', style: TextStyle(fontSize: 22))),
              IconButton(
                  onPressed: loading ? null : load,
                  tooltip: 'Refresh bookings',
                  icon: const Icon(Icons.refresh))
            ]),
            if (loading) const LinearProgressIndicator(),
            if (error != null) JourneyNotice(error!),
            if (items.isEmpty && !loading && error == null) ...[
              const SizedBox(height: 24),
              const Text('Your next useful hour starts here.'),
              const SizedBox(height: 16),
              FilledButton(
                  onPressed: widget.onExplore, child: const Text('Find a hub'))
            ],
            for (final booking in items)
              Padding(
                  padding: const EdgeInsets.only(top: 16),
                  child: Card(
                      child: Padding(
                          padding: const EdgeInsets.all(20),
                          child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(children: [
                                  Expanded(
                                      child: Text(
                                          booking.status == 'CONFIRMED'
                                              ? 'CONFIRMED'
                                              : 'AWAITING PAYMENT',
                                          style: const TextStyle(
                                              color: serv60AccentInk,
                                              fontSize: 11,
                                              letterSpacing: 1.3))),
                                  const Icon(Icons.directions_car_outlined)
                                ]),
                                const SizedBox(height: 16),
                                Text(visitTime(booking.startsAt),
                                    style: const TextStyle(fontSize: 23)),
                                const Text('South Africa time · SAST',
                                    style: TextStyle(
                                        color: serv60Muted, fontSize: 11)),
                                const SizedBox(height: 12),
                                Text(booking.hubName,
                                    style: const TextStyle(
                                        fontWeight: FontWeight.w600)),
                                const Text(
                                    'Express vehicle service · 60 minutes'),
                                Text(optionLabel(booking.option)),
                                if (booking.contact != null) ...[
                                  const Divider(height: 28),
                                  Text(
                                      '${booking.contact!['vehicleMake'] ?? ''} ${booking.contact!['vehicleModel'] ?? ''} · ${booking.contact!['registration'] ?? ''}')
                                ],
                                const SizedBox(height: 20),
                                if (booking.status == 'CONFIRMED') ...[
                                  FilledButton.icon(
                                      onPressed:
                                          working ? null : () => track(booking),
                                      icon: const Icon(Icons.timer_outlined),
                                      label: const Text('Track your visit')),
                                  const SizedBox(height: 8),
                                ],
                                if (booking.status == 'HELD') ...[
                                  Text(
                                      'Reserved until ${slotTime(booking.expiresAt)} SAST. Complete payment before the hold expires.'),
                                  const SizedBox(height: 12),
                                  FilledButton(
                                      onPressed:
                                          working ? null : () => pay(booking),
                                      child:
                                          const Text('Pay securely with Yoco')),
                                  if (booking.cancellable) ...[
                                    const SizedBox(height: 8),
                                    TextButton(
                                        onPressed: working
                                            ? null
                                            : () => cancel(booking),
                                        child:
                                            const Text('Cancel reservation')),
                                  ],
                                ] else if (booking.modifiable &&
                                    !DateTime.now()
                                        .isAfter(booking.changeDeadline)) ...[
                                  OutlinedButton.icon(
                                      onPressed:
                                          working ? null : () => edit(booking),
                                      icon: const Icon(
                                          Icons.edit_calendar_outlined),
                                      label: const Text('Change booking')),
                                  if (widget.onAskHelp != null) ...[
                                    const SizedBox(height: 8),
                                    TextButton(
                                        onPressed: working
                                            ? null
                                            : () => askHelp(booking),
                                        child: const Text(
                                            'Describe a change in plain language')),
                                  ],
                                  if (booking.cancellable) ...[
                                    const SizedBox(height: 8),
                                    TextButton(
                                        onPressed: working
                                            ? null
                                            : () => cancel(booking),
                                        child: const Text('Cancel visit')),
                                  ],
                                  const SizedBox(height: 8),
                                  Text(
                                      'Changes until ${visitTime(booking.changeDeadline)} SAST.',
                                      style: const TextStyle(
                                          color: serv60Muted, fontSize: 12)),
                                ] else
                                  const Text(
                                      'Online changes close 48 hours before your visit. Please contact the hub if you need help.',
                                      style: TextStyle(color: serv60Muted)),
                              ])))),
            if (afterId != null)
              TextButton(
                  onPressed: loading ? null : () => load(more: true),
                  child: const Text('More bookings')),
          ]));
}
