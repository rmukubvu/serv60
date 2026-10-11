import 'package:flutter/material.dart';
import 'booking_repository.dart';

class BookingPage extends StatefulWidget {
  const BookingPage({super.key});
  @override
  State<BookingPage> createState() => _BookingPageState();
}

class _BookingPageState extends State<BookingPage> {
  final repository = BookingRepository();
  String selected = 'EXPRESS_OIL';
  bool loading = false;
  bool checking = false;
  VisitHold? hold;
  List<AvailabilitySlot> slots = const [];
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
        const Text('Choose a service',
            style: TextStyle(fontWeight: FontWeight.w700)),
        const SizedBox(height: 12),
        for (final item in const [
          ('EXPRESS_OIL', 'Express vehicle service', '60 min'),
          ('BARBER', 'Barber', '45 min'),
          ('PRIVATE_WORK', 'Private workspace', '60 min')
        ])
          Card(
              color: selected == item.$1
                  ? const Color(0xff1d2a1c)
                  : const Color(0xff121820),
              child: ListTile(
                  onTap: () => setState(() {
                        selected = item.$1;
                        slots = const [];
                      }),
                  leading: Icon(
                      selected == item.$1
                          ? Icons.check_circle
                          : Icons.circle_outlined,
                      color: const Color(0xffc7f464)),
                  title: Text(item.$2),
                  subtitle: Text(item.$3))),
        const SizedBox(height: 18),
        OutlinedButton(
            onPressed: checking
                ? null
                : () async {
                    setState(() => checking = true);
                    try {
                      final available =
                          await repository.availability(serviceCode: selected);
                      if (mounted) setState(() => slots = available);
                    } catch (error) {
                      if (mounted)
                        ScaffoldMessenger.of(context).showSnackBar(
                            SnackBar(content: Text(error.toString())));
                    } finally {
                      if (mounted) setState(() => checking = false);
                    }
                  },
            child: Text(checking ? 'Loading times…' : 'Check live times')),
        for (final slot in slots)
          ListTile(
              leading: const Icon(Icons.schedule),
              title: Text(
                  '${slot.startsAt.hour.toString().padLeft(2, '0')}:${slot.startsAt.minute.toString().padLeft(2, '0')}'),
              subtitle: Text(slot.readinessPolicy)),
        const SizedBox(height: 8),
        FilledButton(
            onPressed: loading || slots.isEmpty
                ? null
                : () async {
                    setState(() => loading = true);
                    try {
                      final selectedSlot = slots.first;
                      final created = await repository.createHold(
                          serviceCode: selected,
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
            child: Text(loading ? 'Holding time…' : 'Hold selected service')),
        const SizedBox(height: 28),
        _VisitCard(hold: hold)
      ]));
}

class _VisitCard extends StatelessWidget {
  const _VisitCard({this.hold});
  final VisitHold? hold;
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
                    ? 'Select a service to see real hub availability, parts readiness and lifestyle options.'
                    : '${hold!.serviceCode} · ${hold!.status}',
                style: const TextStyle(color: Colors.white60))
          ])));
}
