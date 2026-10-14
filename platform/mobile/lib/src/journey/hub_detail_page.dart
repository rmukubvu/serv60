import 'package:flutter/material.dart';
import '../design/serv60_theme.dart';
import 'journey_repository.dart';
import 'journey_widgets.dart';

class HubDetailPage extends StatelessWidget {
  const HubDetailPage(
      {super.key,
      required this.hub,
      required this.onBook,
      required this.onAsk});

  final Hub hub;
  final void Function(Hub) onBook, onAsk;

  @override
  Widget build(BuildContext context) => Scaffold(
      appBar: AppBar(title: Text(hub.name)),
      body: Center(
          child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 640),
              child: ListView(padding: const EdgeInsets.all(24), children: [
                Text(hub.name,
                    style: Theme.of(context).textTheme.headlineSmall),
                const SizedBox(height: 8),
                Text(hub.address, style: const TextStyle(color: serv60Muted)),
                const SizedBox(height: 8),
                Text(
                    '${hub.distanceKm.toStringAsFixed(1)} km · ${hub.timezone}',
                    style: const TextStyle(color: serv60AccentInk)),
                const SizedBox(height: 24),
                const Text('Trading hours today',
                    style: TextStyle(fontWeight: FontWeight.w600)),
                const SizedBox(height: 6),
                Text(hub.opensAt != null && hub.closesAt != null
                    ? '${hub.opensAt} – ${hub.closesAt} SAST'
                    : 'Hours available after you select a booking date'),
                const SizedBox(height: 24),
                const Text('Express vehicle service',
                    style: TextStyle(fontWeight: FontWeight.w600)),
                const Text(
                    '60 minutes including inspection, maintenance, wash and handover.'),
                const SizedBox(height: 20),
                const Text('Complimentary experiences',
                    style: TextStyle(fontWeight: FontWeight.w600)),
                const SizedBox(height: 8),
                if (hub.options.isEmpty)
                  const Text('Complimentary options are not listed for this hub yet.',
                      style: TextStyle(color: serv60Muted))
                else
                  Wrap(spacing: 8, runSpacing: 8, children: [
                    for (final option in hub.options)
                      Chip(label: Text(optionLabel(option)))
                  ]),
                const SizedBox(height: 32),
                FilledButton(
                    onPressed: () {
                      Navigator.pop(context);
                      onBook(hub);
                    },
                    child: const Text('Book a visit')),
                const SizedBox(height: 12),
                OutlinedButton(
                    onPressed: () {
                      Navigator.pop(context);
                      onAsk(hub);
                    },
                    child: const Text('Ask about times')),
              ]))));
}
