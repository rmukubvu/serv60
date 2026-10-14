import 'dart:async';

import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../auth/access_token_provider.dart';
import '../design/serv60_theme.dart';
import '../journey/customer_location.dart';
import 'roadside_map.dart';
import 'roadside_repository.dart';

class RoadsidePage extends StatefulWidget {
  const RoadsidePage(
      {super.key,
      this.repository,
      this.tokenProvider,
      this.location,
      this.demoEnabled =
          const bool.fromEnvironment('SERV60_DEMO_MODE', defaultValue: false)});

  final RoadsideRepository? repository;
  final AccessTokenProvider? tokenProvider;
  final CustomerLocation? location;
  final bool demoEnabled;

  @override
  State<RoadsidePage> createState() => _RoadsidePageState();
}

class _RoadsidePageState extends State<RoadsidePage> {
  late final RoadsideRepository repository;
  final description = TextEditingController(text: 'Breakdown / need a tow');
  final dropoff = TextEditingController(text: 'Home');
  double lat = -33.925, lon = 18.424;
  List<NearbyTruck> trucks = [];
  RoadsideIncident? incident;
  RoadsideQuote? selectedQuote;
  bool loading = false, rideHome = false;
  String? error;
  Timer? poll;

  @override
  void initState() {
    super.initState();
    repository = widget.repository ??
        RoadsideRepository(tokenProvider: widget.tokenProvider);
    locate(demo: widget.demoEnabled);
  }

  @override
  void dispose() {
    poll?.cancel();
    description.dispose();
    dropoff.dispose();
    super.dispose();
  }

  Future<void> locate({bool demo = false}) async {
    setState(() {
      loading = true;
      error = null;
    });
    try {
      if (!demo) {
        final point =
            await (widget.location ?? DeviceCustomerLocation()).current();
        lat = point.latitude;
        lon = point.longitude;
      } else {
        lat = -33.925;
        lon = 18.424;
      }
      trucks = await repository.nearby(lat, lon);
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> requestQuotes() async {
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final created = await repository.createGeoIncident(
          latitude: lat,
          longitude: lon,
          locationLabel:
              'Pinned breakdown · ${lat.toStringAsFixed(4)}, ${lon.toStringAsFixed(4)}',
          description: description.text.trim(),
          rideHomeRequested: rideHome,
          rideDropoffLabel: dropoff.text.trim());
      if (!mounted) return;
      setState(() {
        incident = created;
        selectedQuote = created.quotes.isEmpty ? null : created.quotes.first;
      });
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> acceptSelected() async {
    final current = incident;
    final quote = selectedQuote;
    if (current == null || quote == null) return;
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final updated = await repository.acceptQuote(current.id, quote.id);
      if (!mounted) return;
      setState(() => incident = updated);
      poll?.cancel();
      poll = Timer.periodic(const Duration(seconds: 8), (_) => refresh());
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> refresh() async {
    final current = incident;
    if (current == null) return;
    try {
      final updated = await repository.detail(current.id);
      if (roadsideClosed(updated.status)) poll?.cancel();
      if (mounted) setState(() => incident = updated);
    } catch (_) {}
  }

  Future<void> cancelHelp() async {
    final current = incident;
    if (current == null) return;
    setState(() => loading = true);
    try {
      final cancelled = await repository.cancelIncident(current.id);
      poll?.cancel();
      if (mounted) setState(() => incident = cancelled);
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  String money(int minor) => 'R${(minor / 100).toStringAsFixed(0)}';

  @override
  Widget build(BuildContext context) {
    final points = <MapPoint>[
      MapPoint(
          id: 'me',
          latitude: lat,
          longitude: lon,
          label: 'You',
          isCustomer: true),
      for (final truck in trucks)
        MapPoint(
            id: truck.partnerId,
            latitude: truck.latitude,
            longitude: truck.longitude,
            label: truck.name,
            isTruck: true),
      if (incident?.truckLatitude != null && incident?.truckLongitude != null)
        MapPoint(
            id: 'assigned',
            latitude: incident!.truckLatitude!,
            longitude: incident!.truckLongitude!,
            label: incident!.truckLabel ?? 'Truck',
            isTruck: true),
    ];
    return ListView(
      padding: const EdgeInsets.fromLTRB(24, 28, 24, 40),
      children: [
        const Text('HERE WHEN YOU NEED US.',
            style: TextStyle(
                fontSize: 10, letterSpacing: 1.6, color: serv60Muted)),
        const SizedBox(height: 12),
        Text('Roadside help', style: Theme.of(context).textTheme.headlineLarge),
        const SizedBox(height: 8),
        const Text(
            'See nearby tow trucks, get a price, and optionally add a ride home.',
            style: TextStyle(color: serv60Muted)),
        const SizedBox(height: 20),
        RoadsideMapCanvas(centerLat: lat, centerLon: lon, points: points),
        const SizedBox(height: 8),
        Text(
            trucks.isEmpty
                ? 'Looking for trucks…'
                : '${trucks.length} tow trucks nearby',
            style: const TextStyle(color: serv60Muted, fontSize: 12)),
        const SizedBox(height: 16),
        Row(children: [
          Expanded(
              child: OutlinedButton.icon(
                  onPressed: loading ? null : () => locate(),
                  icon: const Icon(Icons.my_location),
                  label: const Text('Use my location'))),
          if (widget.demoEnabled) ...[
            const SizedBox(width: 8),
            TextButton(
                onPressed: loading ? null : () => locate(demo: true),
                child: const Text('Demo pin')),
          ],
        ]),
        if (error != null) ...[
          const SizedBox(height: 12),
          Text(error!, style: const TextStyle(color: serv60Error)),
        ],
        if (incident == null) ...[
          const SizedBox(height: 20),
          TextField(
              controller: description,
              maxLines: 2,
              decoration: const InputDecoration(
                  labelText: 'What happened?',
                  hintText: 'Flat tyre, battery, accident…')),
          SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Also get me a ride home'),
              subtitle: Text(rideHome
                  ? 'Our dispatcher arranges a ride home with a local partner'
                  : 'Tow only'),
              value: rideHome,
              onChanged:
                  loading ? null : (value) => setState(() => rideHome = value)),
          if (rideHome)
            TextField(
                controller: dropoff,
                decoration: const InputDecoration(
                    labelText: 'Ride drop-off',
                    hintText: 'Home, work or hotel')),
          const SizedBox(height: 16),
          FilledButton(
              onPressed: loading ? null : requestQuotes,
              child: Text(loading ? 'Getting quotes…' : 'Request tow quotes')),
        ] else ...[
          const SizedBox(height: 20),
          Text('Status · ${incident!.status}',
              style: const TextStyle(
                  fontWeight: FontWeight.w600, letterSpacing: 0.4)),
          Text(incident!.location, style: const TextStyle(color: serv60Muted)),
          if (roadsideStatusNote(incident!.status) case final note?) ...[
            const SizedBox(height: 8),
            Text(note),
          ],
          if (incident!.truckLabel != null) ...[
            const SizedBox(height: 8),
            Text(incident!.truckLabel!,
                style: const TextStyle(fontWeight: FontWeight.w600)),
            if (incident!.etaMinutes != null)
              Text('ETA about ${incident!.etaMinutes} minutes'),
          ],
          if (incident!.status == 'QUOTED' && incident!.quotes.isNotEmpty) ...[
            const SizedBox(height: 16),
            const Text('Choose a quote',
                style: TextStyle(fontWeight: FontWeight.w600)),
            for (final quote in incident!.quotes)
              RadioListTile<RoadsideQuote>(
                  contentPadding: EdgeInsets.zero,
                  value: quote,
                  groupValue: selectedQuote,
                  onChanged: loading
                      ? null
                      : (value) => setState(() => selectedQuote = value),
                  title: Text(
                      '${quote.partnerName} · ${money(quote.totalAmountMinor)}'),
                  subtitle: Text('Tow ${money(quote.towAmountMinor)}'
                      '${quote.rideAmountMinor > 0 ? ' + ride ${money(quote.rideAmountMinor)}' : ''}'
                      ' · ETA ${quote.etaMinutes} min')),
            FilledButton(
                onPressed:
                    loading || selectedQuote == null ? null : acceptSelected,
                child: Text(loading
                    ? 'Confirming…'
                    : 'Accept quote ${selectedQuote == null ? '' : money(selectedQuote!.totalAmountMinor)}')),
          ],
          if (incident!.ride != null) ...[
            const SizedBox(height: 16),
            Text(
                'Ride home · ${incident!.ride!.provider} · ${incident!.ride!.status}',
                style: const TextStyle(fontWeight: FontWeight.w600)),
            Text(
                '${incident!.ride!.dropoffLabel} · ${money(incident!.ride!.amountMinor)}'),
            if (incident!.ride!.pinCode != null)
              Text('PIN ${incident!.ride!.pinCode}'),
            if (incident!.ride!.trackingUrl != null)
              TextButton(
                  onPressed: () => launchUrl(
                      Uri.parse(incident!.ride!.trackingUrl!),
                      mode: LaunchMode.externalApplication),
                  child: const Text('Open ride tracking')),
          ],
          if (incident!.status != 'CANCELLED' &&
              ['OPEN', 'QUOTED', 'SEARCHING'].contains(incident!.status)) ...[
            const SizedBox(height: 12),
            TextButton(
                onPressed: loading ? null : cancelHelp,
                child: const Text('Cancel request')),
          ],
        ],
      ],
    );
  }
}

/// Closed requests never change again, so the page stops polling them.
bool roadsideClosed(String status) =>
    const {'COMPLETED', 'CANCELLED', 'EXPIRED'}.contains(status);

/// Plain-language meaning of the statuses a customer cannot act on directly.
String? roadsideStatusNote(String status) => switch (status) {
      'SEARCHING' =>
        'Quote accepted. Our dispatcher is confirming the truck with the tow partner.',
      'EN_ROUTE' => 'Your tow truck is on its way.',
      'ON_SCENE' => 'The tow truck has arrived.',
      'TOWING' => 'Your vehicle is being towed.',
      'COMPLETED' => 'This tow is complete.',
      'EXPIRED' =>
        'These quotes expired. Request new quotes if you still need help.',
      _ => null,
    };
