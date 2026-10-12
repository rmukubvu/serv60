import 'package:flutter/material.dart';

import '../auth/access_token_provider.dart';
import 'roadside_repository.dart';

class RoadsidePage extends StatefulWidget {
  const RoadsidePage({super.key, this.repository, this.tokenProvider});

  final RoadsideRepository? repository;
  final AccessTokenProvider? tokenProvider;

  @override
  State<RoadsidePage> createState() => _RoadsidePageState();
}

class _RoadsidePageState extends State<RoadsidePage> {
  late final RoadsideRepository repository;
  final location = TextEditingController();
  final description = TextEditingController();
  RoadsideIncident? incident;
  bool loading = false;

  @override
  void initState() {
    super.initState();
    repository = widget.repository ??
        RoadsideRepository(tokenProvider: widget.tokenProvider);
  }

  @override
  void dispose() {
    location.dispose();
    description.dispose();
    super.dispose();
  }

  Future<void> requestHelp() async {
    if (location.text.trim().isEmpty || description.text.trim().isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content: Text('Add your location and what happened.')));
      return;
    }
    setState(() => loading = true);
    try {
      final created = await repository.createIncident(
          location: location.text.trim(), description: description.text.trim());
      if (mounted) setState(() => incident = created);
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(error.toString())));
      }
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> cancelHelp() async {
    final current = incident;
    if (current == null) return;
    setState(() => loading = true);
    try {
      final cancelled = await repository.cancelIncident(current.id);
      if (mounted) setState(() => incident = cancelled);
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(error.toString())));
      }
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  @override
  Widget build(BuildContext context) => ListView(
        padding: const EdgeInsets.all(20),
        children: [
          const Text('Roadside help',
              style: TextStyle(
                  fontSize: 32,
                  fontWeight: FontWeight.w700,
                  letterSpacing: -1)),
          const SizedBox(height: 8),
          const Text('Request a tow and a safe ride home when you need it.',
              style: TextStyle(color: Colors.white60)),
          const SizedBox(height: 28),
          TextField(
              controller: location,
              decoration: const InputDecoration(
                  labelText: 'Where are you?',
                  hintText: 'Street, suburb or landmark')),
          const SizedBox(height: 12),
          TextField(
              controller: description,
              maxLines: 3,
              decoration: const InputDecoration(
                  labelText: 'What happened?',
                  hintText: 'Flat tyre, breakdown or accident')),
          const SizedBox(height: 18),
          FilledButton(
              onPressed: loading ? null : requestHelp,
              child: Text(loading ? 'Sending…' : 'Request roadside help')),
          const SizedBox(height: 24),
          Card(
              color: const Color(0xff111820),
              child: Padding(
                  padding: const EdgeInsets.all(18),
                  child: incident == null
                      ? const Text(
                          'Your request will be sent to the dispatch team.',
                          style: TextStyle(color: Colors.white60))
                      : Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                              const Text('Assistance request',
                                  style:
                                      TextStyle(fontWeight: FontWeight.w700)),
                              const SizedBox(height: 8),
                              Text(
                                  '${incident!.status} · ${incident!.location}'),
                              const SizedBox(height: 4),
                              Text(incident!.description,
                                  style:
                                      const TextStyle(color: Colors.white60)),
                              if (incident!.status == 'OPEN') ...[
                                const SizedBox(height: 14),
                                OutlinedButton(
                                    onPressed: loading ? null : cancelHelp,
                                    child: const Text('Cancel request'))
                              ]
                            ])))
        ],
      );
}
