import 'dart:async';
import 'package:flutter/material.dart';
import '../design/serv60_theme.dart';
import 'customer_location.dart';
import 'hub_detail_page.dart';
import 'journey_repository.dart';
import 'journey_widgets.dart';

class DiscoveryPage extends StatefulWidget {
  const DiscoveryPage(
      {super.key,
      required this.repository,
      required this.onBook,
      required this.onAsk,
      this.location,
      this.demoEnabled =
          const bool.fromEnvironment('SERV60_DEMO_MODE', defaultValue: false)});
  final JourneyRepository repository;
  final void Function(Hub) onBook, onAsk;
  final CustomerLocation? location;
  final bool demoEnabled;
  @override
  State<DiscoveryPage> createState() => _DiscoveryPageState();
}

class _DiscoveryPageState extends State<DiscoveryPage> {
  final search = TextEditingController();
  List<Hub> hubs = [];
  bool busy = false, loaded = false;
  String? error;
  String area = 'Find your nearest hub';
  double? lastLat, lastLon;
  Timer? searchDebounce;

  Future<void> locate({bool demo = false}) async {
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final point = demo
          ? (latitude: -33.925, longitude: 18.424)
          : await (widget.location ?? DeviceCustomerLocation()).current();
      lastLat = point.latitude;
      lastLon = point.longitude;
      await refreshHubs();
      if (!mounted) return;
      setState(() {
        loaded = true;
        area = demo ? 'Cape Town · demo location' : 'Within 20 km of you';
      });
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> refreshHubs() async {
    if (lastLat == null || lastLon == null) return;
    final results = await widget.repository
        .nearby(lastLat!, lastLon!, query: search.text.trim());
    if (mounted) setState(() => hubs = results);
  }

  void onSearchChanged(String _) {
    searchDebounce?.cancel();
    searchDebounce = Timer(const Duration(milliseconds: 350), () async {
      if (!loaded || lastLat == null) return;
      setState(() => busy = true);
      try {
        await refreshHubs();
      } catch (e) {
        if (mounted) setState(() => error = e.toString());
      } finally {
        if (mounted) setState(() => busy = false);
      }
    });
  }

  Future<void> openHub(Hub hub) async {
    await Navigator.push(
        context,
        MaterialPageRoute(
            builder: (_) => HubDetailPage(
                hub: hub, onBook: widget.onBook, onAsk: widget.onAsk)));
  }

  @override
  void dispose() {
    searchDebounce?.cancel();
    search.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final visible = hubs;
    return ListView(
        padding: const EdgeInsets.fromLTRB(24, 28, 24, 40),
        children: [
          const JourneyHeader(
              'A better kind of pit stop',
              'Your next stop.\nYour hour back.',
              'A car service for you. A little time for yourself.'),
          Container(
              padding: const EdgeInsets.all(20),
              decoration: const BoxDecoration(color: serv60Ink),
              child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Row(children: [
                      Icon(Icons.near_me_outlined,
                          color: serv60Orange, size: 20),
                      SizedBox(width: 10),
                      Text('START WITH WHERE YOU ARE',
                          style: TextStyle(
                              color: Colors.white,
                              fontSize: 11,
                              letterSpacing: 1.2))
                    ]),
                    const SizedBox(height: 16),
                    Text(area,
                        style:
                            const TextStyle(color: Colors.white, fontSize: 20)),
                    const SizedBox(height: 16),
                    SizedBox(
                        width: double.infinity,
                        child: FilledButton.icon(
                            onPressed: busy ? null : locate,
                            icon: Icon(
                                busy ? Icons.hourglass_top : Icons.my_location),
                            label: Text(
                                busy ? 'Finding hubs…' : 'Use my location'))),
                    if (widget.demoEnabled)
                      TextButton(
                          onPressed: busy ? null : () => locate(demo: true),
                          child: const Text('Explore Cape Town demo',
                              style: TextStyle(color: serv60Orange))),
                    const SizedBox(height: 4),
                    const Text(
                        'Location is used only when you ask. Distances are straight-line estimates.',
                        style:
                            TextStyle(color: Color(0xffc1c5c1), fontSize: 11)),
                  ])),
          if (error != null) JourneyNotice(error!),
          const SizedBox(height: 28),
          if (loaded) ...[
            TextField(
                controller: search,
                onChanged: onSearchChanged,
                decoration: const InputDecoration(
                    prefixIcon: Icon(Icons.search),
                    hintText:
                        'Search hubs, suburbs or complimentary services')),
            const SizedBox(height: 24),
            Text(
                '${visible.length} ${visible.length == 1 ? 'hub' : 'hubs'} nearby',
                style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 16),
            if (visible.isEmpty)
              const Text(
                  'No hubs match this location or search. Try a different search or refresh your location.'),
            for (final hub in visible)
              Padding(
                  padding: const EdgeInsets.only(bottom: 16),
                  child: Card(
                      child: InkWell(
                          onTap: () => openHub(hub),
                          child: Padding(
                              padding: const EdgeInsets.all(20),
                              child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Row(
                                        crossAxisAlignment:
                                            CrossAxisAlignment.start,
                                        children: [
                                          Expanded(
                                              child: Text(hub.name,
                                                  style: const TextStyle(
                                                      fontSize: 21,
                                                      fontWeight:
                                                          FontWeight.w500))),
                                          const SizedBox(width: 12),
                                          Text(
                                              '${hub.distanceKm.toStringAsFixed(1)} km',
                                              style: const TextStyle(
                                                  color: serv60AccentInk))
                                        ]),
                                    const SizedBox(height: 8),
                                    Text(hub.address,
                                        style: const TextStyle(
                                            color: serv60Muted)),
                                    if (hub.opensAt != null &&
                                        hub.closesAt != null) ...[
                                      const SizedBox(height: 8),
                                      Text(
                                          'Open ${hub.opensAt} – ${hub.closesAt}',
                                          style: const TextStyle(
                                              color: serv60Muted,
                                              fontSize: 12)),
                                    ],
                                    const SizedBox(height: 20),
                                    const Text('60-minute vehicle service',
                                        style: TextStyle(
                                            fontWeight: FontWeight.w600)),
                                    const SizedBox(height: 6),
                                    const Text(
                                        'Choose one complimentary experience',
                                        style: TextStyle(
                                            color: serv60Muted, fontSize: 12)),
                                    const SizedBox(height: 12),
                                    Wrap(spacing: 8, runSpacing: 8, children: [
                                      for (final option in hub.options)
                                        Chip(
                                            label: Text(optionLabel(option)),
                                            visualDensity:
                                                VisualDensity.compact)
                                    ]),
                                    const SizedBox(height: 20),
                                    Row(children: [
                                      Expanded(
                                          child: FilledButton(
                                              onPressed: () =>
                                                  widget.onBook(hub),
                                              child:
                                                  const Text('Book a visit'))),
                                      const SizedBox(width: 12),
                                      TextButton(
                                          onPressed: () => openHub(hub),
                                          child: const Text('Details'))
                                    ]),
                                  ]))))),
          ] else ...[
            const Text('One service. One useful hour.',
                style: TextStyle(fontSize: 23)),
            const SizedBox(height: 16),
            for (final entry in [
              ('01', 'Find your hub', 'See the services available near you.'),
              (
                '02',
                'Make the hour yours',
                'Add a barber, massage or workspace at no extra cost.'
              ),
              (
                '03',
                'Drive away ready',
                'We take care of your car while you take care of you.'
              )
            ])
              Padding(
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(entry.$1,
                            style: const TextStyle(color: serv60AccentInk)),
                        const SizedBox(width: 20),
                        Expanded(
                            child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                              Text(entry.$2,
                                  style: const TextStyle(
                                      fontWeight: FontWeight.w600)),
                              Text(entry.$3,
                                  style: const TextStyle(color: serv60Muted))
                            ]))
                      ])),
          ],
        ]);
  }
}
