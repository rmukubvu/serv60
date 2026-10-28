import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import '../auth/clerk_auth.dart';
import '../design/serv60_theme.dart';
import 'invoice_page.dart';
import 'journey_repository.dart';
import 'journey_widgets.dart';

class AccountPage extends StatefulWidget {
  const AccountPage(
      {super.key, required this.repository, this.auth, this.active = true});

  final JourneyRepository repository;
  final ClerkAuthController? auth;
  final bool active;

  @override
  State<AccountPage> createState() => _AccountPageState();
}

class _VehicleDraft {
  _VehicleDraft({
    this.id,
    String make = '',
    String model = '',
    String registration = '',
    this.isDefault = false,
  })  : make = TextEditingController(text: make),
        model = TextEditingController(text: model),
        registration = TextEditingController(text: registration);

  String? id;
  final TextEditingController make;
  final TextEditingController model;
  final TextEditingController registration;
  bool isDefault;

  void dispose() {
    make.dispose();
    model.dispose();
    registration.dispose();
  }

  Map<String, dynamic> toJson() => {
        if (id != null) 'id': id,
        'vehicleMake': make.text.trim(),
        'vehicleModel': model.text.trim(),
        'registration': registration.text.trim(),
        'isDefault': isDefault,
      };

  bool get isBlank =>
      make.text.trim().isEmpty &&
      model.text.trim().isEmpty &&
      registration.text.trim().isEmpty;
}

class _AccountPageState extends State<AccountPage> {
  final phone = TextEditingController();
  final vehicles = <_VehicleDraft>[];
  bool loading = false, saving = false, historyLoading = false;
  String? error, historyError;
  List<ServiceRecord>? history;

  String get clerkName => widget.auth?.displayName?.trim().isNotEmpty == true
      ? widget.auth!.displayName!.trim()
      : '';
  String get clerkEmail => widget.auth?.displayEmail?.trim().isNotEmpty == true
      ? widget.auth!.displayEmail!.trim()
      : '';

  @override
  void initState() {
    super.initState();
    if (widget.active) refresh();
  }

  @override
  void didUpdateWidget(AccountPage old) {
    super.didUpdateWidget(old);
    if (widget.active && !old.active) refresh();
  }

  @override
  void dispose() {
    phone.dispose();
    for (final vehicle in vehicles) {
      vehicle.dispose();
    }
    super.dispose();
  }

  void _replaceVehicles(List<_VehicleDraft> next) {
    for (final vehicle in vehicles) {
      vehicle.dispose();
    }
    vehicles
      ..clear()
      ..addAll(next);
  }

  void refresh() {
    load();
    loadHistory();
  }

  Future<void> loadHistory() async {
    setState(() {
      historyLoading = true;
      historyError = null;
    });
    try {
      final records = await widget.repository.serviceHistory();
      if (mounted) setState(() => history = records);
    } catch (e) {
      if (mounted) setState(() => historyError = e.toString());
    } finally {
      if (mounted) setState(() => historyLoading = false);
    }
  }

  void openInvoice(ServiceRecord record) =>
      Navigator.of(context).push(MaterialPageRoute<void>(
          builder: (_) => InvoicePage(
              repository: widget.repository, visitId: record.visitId)));

  Future<void> load() async {
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final profile = await widget.repository.profile();
      if (!mounted) return;
      phone.text = profile.phoneNumber;
      if (profile.vehicles.isEmpty) {
        _replaceVehicles([_VehicleDraft(isDefault: true)]);
      } else {
        _replaceVehicles([
          for (final vehicle in profile.vehicles)
            _VehicleDraft(
              id: vehicle.id,
              make: vehicle.vehicleMake,
              model: vehicle.vehicleModel,
              registration: vehicle.registration,
              isDefault: vehicle.isDefault,
            )
        ]);
      }
      setState(() {});
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  void addVehicle() {
    if (vehicles.length >= 5) return;
    setState(() {
      vehicles.add(_VehicleDraft(isDefault: vehicles.isEmpty));
    });
  }

  void removeVehicle(int index) {
    setState(() {
      final removed = vehicles.removeAt(index);
      removed.dispose();
      if (vehicles.isNotEmpty && !vehicles.any((v) => v.isDefault)) {
        vehicles.first.isDefault = true;
      }
    });
  }

  void setDefault(int index) {
    setState(() {
      for (var i = 0; i < vehicles.length; i++) {
        vehicles[i].isDefault = i == index;
      }
    });
  }

  Future<void> save() async {
    final name = clerkName;
    if (name.isEmpty) {
      setState(() => error =
          'Sign in with Clerk so your name is available before saving.');
      return;
    }
    setState(() {
      saving = true;
      error = null;
    });
    try {
      final payload = <Map<String, dynamic>>[];
      for (final vehicle in vehicles) {
        if (vehicle.isBlank) continue;
        if (vehicle.make.text.trim().isEmpty ||
            vehicle.model.text.trim().isEmpty ||
            vehicle.registration.text.trim().isEmpty) {
          throw StateError('Each vehicle needs make, model, and registration.');
        }
        payload.add(vehicle.toJson());
      }
      if (payload.isNotEmpty && !payload.any((v) => v['isDefault'] == true)) {
        payload.first['isDefault'] = true;
      }
      final saved = await widget.repository.saveProfile(
          customerName: name,
          phoneNumber: phone.text.trim(),
          vehicles: payload);
      _replaceVehicles([
        for (final vehicle in saved.vehicles)
          _VehicleDraft(
            id: vehicle.id,
            make: vehicle.vehicleMake,
            model: vehicle.vehicleModel,
            registration: vehicle.registration,
            isDefault: vehicle.isDefault,
          )
      ]);
      if (vehicles.isEmpty) {
        vehicles.add(_VehicleDraft(isDefault: true));
      }
      if (mounted) {
        setState(() {});
        ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Profile saved for faster booking.')));
      }
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => saving = false);
    }
  }

  @override
  Widget build(BuildContext context) => KeyboardDismiss(
        child: ListView(
            keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
            padding: const EdgeInsets.fromLTRB(24, 28, 24, 40),
            children: [
              const JourneyHeader('Your details', 'Ready when\nyou book.',
                  'Phone and vehicles stay here. Name and email come from your Clerk sign-in.'),
              if (loading) const LinearProgressIndicator(),
              if (error != null) JourneyNotice(error!),
              const SizedBox(height: 8),
              const Text('Identity',
                  style: TextStyle(fontWeight: FontWeight.w600)),
              const SizedBox(height: 8),
              _ReadOnlyField(
                  label: 'Full name',
                  value: clerkName.isEmpty ? '—' : clerkName),
              const SizedBox(height: 12),
              _ReadOnlyField(
                  label: 'Email', value: clerkEmail.isEmpty ? '—' : clerkEmail),
              const SizedBox(height: 16),
              TextField(
                  controller: phone,
                  keyboardType: TextInputType.phone,
                  textInputAction: TextInputAction.done,
                  onEditingComplete: dismissKeyboard,
                  onTapOutside: (_) => dismissKeyboard(),
                  decoration: InputDecoration(
                    labelText: 'Phone number',
                    suffixIcon: IconButton(
                      tooltip: 'Hide keyboard',
                      onPressed: dismissKeyboard,
                      icon: const Icon(Icons.keyboard_hide_outlined),
                    ),
                  )),
              const SizedBox(height: 28),
              Row(children: [
                const Expanded(
                    child: Text('Your vehicles',
                        style: TextStyle(fontWeight: FontWeight.w600))),
                TextButton.icon(
                    onPressed: saving || loading || vehicles.length >= 5
                        ? null
                        : addVehicle,
                    icon: const Icon(Icons.add),
                    label: const Text('Add')),
              ]),
              Text(
                  '${vehicles.length}/5 vehicles · pick a default for quicker booking',
                  style: const TextStyle(color: serv60Muted, fontSize: 12)),
              const SizedBox(height: 12),
              for (var i = 0; i < vehicles.length; i++) ...[
                _VehicleCard(
                  index: i,
                  vehicle: vehicles[i],
                  enabled: !saving && !loading,
                  onDefault: () => setDefault(i),
                  onRemove: vehicles.length > 1 ? () => removeVehicle(i) : null,
                ),
                const SizedBox(height: 12),
              ],
              const SizedBox(height: 12),
              FilledButton(
                  onPressed: saving || loading
                      ? null
                      : () {
                          dismissKeyboard();
                          save();
                        },
                  child: Text(saving ? 'Saving…' : 'Save profile')),
              const SizedBox(height: 36),
              ...serviceHistory(),
              if (widget.auth != null && widget.auth!.isConfigured) ...[
                const SizedBox(height: 16),
                OutlinedButton(
                    onPressed: saving
                        ? null
                        : () {
                            dismissKeyboard();
                            widget.auth!.signOut();
                          },
                    child: const Text('Sign out')),
              ],
            ]),
      );

  List<Widget> serviceHistory() {
    final records = history ?? const <ServiceRecord>[];
    final byVehicle = <String, List<ServiceRecord>>{};
    for (final record in records) {
      byVehicle.putIfAbsent(record.registration, () => []).add(record);
    }
    return [
      const Text('Service history',
          style: TextStyle(fontWeight: FontWeight.w600)),
      const SizedBox(height: 8),
      if (historyLoading) const LinearProgressIndicator(),
      if (historyError != null) JourneyNotice(historyError!),
      if (history != null && records.isEmpty)
        const Text('Your completed visits and invoices will appear here.',
            style: TextStyle(color: serv60Muted)),
      for (final visits in byVehicle.values) ...[
        const SizedBox(height: 8),
        Text(vehicleLine(visits.first.vehicle, visits.first.registration),
            style: const TextStyle(color: serv60Muted, fontSize: 12)),
        const SizedBox(height: 8),
        for (final record in visits) ...[
          _ServiceRecordCard(record: record, onTap: () => openInvoice(record)),
          const SizedBox(height: 12),
        ],
      ],
    ];
  }
}

class _ServiceRecordCard extends StatelessWidget {
  const _ServiceRecordCard({required this.record, required this.onTap});
  final ServiceRecord record;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final handedOver = record.handedOverAt;
    return Material(
        color: serv60Surface,
        shape: Border.all(color: serv60Border),
        child: InkWell(
            onTap: onTap,
            child: Padding(
                padding: const EdgeInsets.all(16),
                child: Row(children: [
                  Expanded(
                      child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                        Text(
                            handedOver == null
                                ? record.hubName
                                : '${DateFormat('d MMM yyyy').format(hubTime(handedOver))} · ${record.hubName}',
                            style:
                                const TextStyle(fontWeight: FontWeight.w600)),
                        const SizedBox(height: 4),
                        Text(record.invoiceNumber,
                            style: const TextStyle(
                                color: serv60Muted, fontSize: 12)),
                        if (record.dueMinor > 0) ...[
                          const SizedBox(height: 4),
                          Text('${rands(record.dueMinor)} due at counter',
                              style: const TextStyle(
                                  color: serv60AccentInk,
                                  fontWeight: FontWeight.w600)),
                        ],
                      ])),
                  const SizedBox(width: 12),
                  Text(rands(record.totalMinor)),
                  const Icon(Icons.chevron_right, color: serv60Muted),
                ]))));
  }
}

class _ReadOnlyField extends StatelessWidget {
  const _ReadOnlyField({required this.label, required this.value});
  final String label, value;

  @override
  Widget build(BuildContext context) => InputDecorator(
        decoration: InputDecoration(
          labelText: label,
          filled: true,
          fillColor: serv60Surface,
        ),
        child: Text(value, style: const TextStyle(color: serv60Ink)),
      );
}

class _VehicleCard extends StatelessWidget {
  const _VehicleCard({
    required this.index,
    required this.vehicle,
    required this.enabled,
    required this.onDefault,
    this.onRemove,
  });

  final int index;
  final _VehicleDraft vehicle;
  final bool enabled;
  final VoidCallback onDefault;
  final VoidCallback? onRemove;

  @override
  // Material, not DecoratedBox: the checkbox tile paints its ink on the nearest Material.
  Widget build(BuildContext context) => Material(
        color: serv60Surface,
        shape: Border.all(color: serv60Border),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(children: [
                Expanded(
                    child: Text('Vehicle ${index + 1}',
                        style: const TextStyle(fontWeight: FontWeight.w600))),
                if (onRemove != null)
                  IconButton(
                      onPressed: enabled ? onRemove : null,
                      icon: const Icon(Icons.delete_outline),
                      tooltip: 'Remove vehicle'),
              ]),
              TextField(
                  controller: vehicle.make,
                  enabled: enabled,
                  textInputAction: TextInputAction.next,
                  onTapOutside: (_) => dismissKeyboard(),
                  decoration: const InputDecoration(labelText: 'Car make')),
              const SizedBox(height: 8),
              TextField(
                  controller: vehicle.model,
                  enabled: enabled,
                  textInputAction: TextInputAction.next,
                  onTapOutside: (_) => dismissKeyboard(),
                  decoration: const InputDecoration(labelText: 'Car model')),
              const SizedBox(height: 8),
              TextField(
                  controller: vehicle.registration,
                  enabled: enabled,
                  textInputAction: TextInputAction.done,
                  onEditingComplete: dismissKeyboard,
                  onTapOutside: (_) => dismissKeyboard(),
                  decoration: const InputDecoration(labelText: 'Registration')),
              CheckboxListTile(
                contentPadding: EdgeInsets.zero,
                value: vehicle.isDefault,
                title: const Text('Default for booking'),
                onChanged: enabled
                    ? (value) {
                        if (value == true) onDefault();
                      }
                    : null,
              ),
            ],
          ),
        ),
      );
}
