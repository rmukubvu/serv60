import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import '../auth/clerk_auth.dart';
import '../design/serv60_theme.dart';
import '../payment/payment_checkout.dart';
import 'journey_repository.dart';
import 'journey_widgets.dart';
import 'reservation_confirmation_page.dart';
import 'slot_rationale.dart';

class PlanVisitPage extends StatefulWidget {
  const PlanVisitPage(
      {super.key,
      required this.hub,
      required this.repository,
      this.appointment,
      this.checkout,
      this.profile,
      this.auth});
  final Hub hub;
  final JourneyRepository repository;
  final Appointment? appointment;
  final PaymentCheckoutCoordinator? checkout;
  final CustomerProfile? profile;
  final ClerkAuthController? auth;
  @override
  State<PlanVisitPage> createState() => _PlanVisitPageState();
}

class _PlanVisitPageState extends State<PlanVisitPage> {
  final form = GlobalKey<FormState>();
  final phone = TextEditingController();
  final make = TextEditingController();
  final model = TextEditingController();
  final registration = TextEditingController();
  late DateTime date;
  String? option, error;
  List<DateTime> slots = [];
  DateTime? selected;
  bool loading = false, saving = false;
  String key = JourneyRepository.newKey();
  int searchVersion = 0;
  String? selectedVehicleId;
  late List<CustomerVehicle> vehicles;

  String get clerkName {
    final fromAuth = widget.auth?.displayName?.trim();
    if (fromAuth != null && fromAuth.isNotEmpty) return fromAuth;
    final fromProfile = widget.profile?.customerName.trim() ?? '';
    final fromBooking = widget.appointment?.contact?['customerName']?.toString();
    if (fromBooking != null && fromBooking.trim().isNotEmpty) {
      return fromBooking.trim();
    }
    return fromProfile;
  }

  String get clerkEmail {
    final fromAuth = widget.auth?.displayEmail?.trim();
    if (fromAuth != null && fromAuth.isNotEmpty) return fromAuth;
    return widget.appointment?.contact?['email']?.toString().trim() ?? '';
  }

  @override
  void initState() {
    super.initState();
    final booking = widget.appointment;
    final now = hubTime(DateTime.now());
    date = booking == null
        ? DateTime(now.year, now.month, now.day).add(const Duration(days: 1))
        : DateTime(hubTime(booking.startsAt).year,
            hubTime(booking.startsAt).month, hubTime(booking.startsAt).day);
    option = booking?.option;
    vehicles = List<CustomerVehicle>.from(widget.profile?.vehicles ?? const []);
    final contact = booking?.contact;
    phone.text = contact?['phoneNumber']?.toString() ??
        widget.profile?.phoneNumber ??
        '';
    final defaultVehicle = widget.profile?.defaultVehicle;
    if (contact != null) {
      make.text = contact['vehicleMake']?.toString() ?? '';
      model.text = contact['vehicleModel']?.toString() ?? '';
      registration.text = contact['registration']?.toString() ?? '';
    } else if (defaultVehicle != null) {
      selectedVehicleId = defaultVehicle.id;
      _applyVehicle(defaultVehicle);
    }
    for (final field in [phone, make, model, registration]) {
      field.addListener(resetKey);
    }
    load();
  }

  void _applyVehicle(CustomerVehicle vehicle) {
    make.text = vehicle.vehicleMake;
    model.text = vehicle.vehicleModel;
    registration.text = vehicle.registration;
  }

  void resetKey() {
    key = JourneyRepository.newKey();
  }

  Future<void> load() async {
    final version = ++searchVersion;
    setState(() {
      loading = true;
      error = null;
      selected = null;
    });
    resetKey();
    try {
      final times = await widget.repository.slots(widget.hub.id, date, option,
          replacing: widget.appointment?.id);
      if (mounted && version == searchVersion) setState(() => slots = times);
    } catch (e) {
      if (mounted && version == searchVersion)
        setState(() {
          error = e.toString();
          slots = [];
        });
    } finally {
      if (mounted && version == searchVersion) setState(() => loading = false);
    }
  }

  Future<void> save() async {
    if (selected == null || !form.currentState!.validate()) return;
    if (widget.appointment == null && clerkName.isEmpty) {
      setState(() => error =
          'Sign in so your Clerk name can be used on this booking.');
      return;
    }
    setState(() {
      saving = true;
      error = null;
    });
    try {
      if (widget.appointment != null) {
        await widget.repository
            .change(widget.appointment!, selected!, option, key);
        if (mounted) Navigator.pop(context, true);
      } else {
        final hold = await widget.repository.reserve(
            widget.hub.id,
            selected!,
            option,
            {
              'customerName': clerkName,
              'phoneNumber': phone.text.trim(),
              'vehicleMake': make.text.trim(),
              'vehicleModel': model.text.trim(),
              'registration': registration.text.trim(),
              if (clerkEmail.isNotEmpty) 'email': clerkEmail,
            },
            key,
            vehicleId: selectedVehicleId);
        if (!mounted) return;
        final checkout = widget.checkout;
        if (checkout == null) {
          Navigator.pop(context, true);
          return;
        }
        await Navigator.of(context).pushReplacement(MaterialPageRoute(
            builder: (_) => ReservationConfirmationPage(
                hub: widget.hub,
                hold: hold,
                repository: widget.repository,
                checkout: checkout)));
      }
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => saving = false);
    }
  }

  @override
  void dispose() {
    phone.dispose();
    make.dispose();
    model.dispose();
    registration.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
      appBar: AppBar(
          title: Text(widget.appointment == null
              ? 'Plan your visit'
              : 'Change your visit')),
      body: Center(
          child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 640),
              child: Form(
                  key: form,
                  child: ListView(
                      keyboardDismissBehavior:
                          ScrollViewKeyboardDismissBehavior.onDrag,
                      padding: const EdgeInsets.all(24),
                      children: [
                    Text(widget.hub.name,
                        style: Theme.of(context).textTheme.headlineSmall),
                    const SizedBox(height: 16),
                    const ListTile(
                        contentPadding: EdgeInsets.zero,
                        leading: Icon(Icons.check_circle_outline),
                        title: Text('Express vehicle service'),
                        subtitle: Text('60 minutes · always included')),
                    const Divider(),
                    const SizedBox(height: 12),
                    const Text('Your complimentary experience',
                        style: TextStyle(fontWeight: FontWeight.w600)),
                    const Text(
                        'Choose up to one. Enjoy it while your car is serviced.'),
                    for (final code in widget.hub.options)
                      CheckboxListTile(
                          contentPadding: EdgeInsets.zero,
                          value: option == code,
                          title: Text(optionLabel(code)),
                          onChanged: saving
                              ? null
                              : (value) {
                                  setState(() =>
                                      option = value == true ? code : null);
                                  load();
                                }),
                    const SizedBox(height: 16),
                    OutlinedButton.icon(
                        icon: const Icon(Icons.calendar_today_outlined),
                        label: Text(DateFormat('EEEE, d MMMM').format(date)),
                        onPressed: saving
                            ? null
                            : () async {
                                final now = hubTime(DateTime.now());
                                final today =
                                    DateTime(now.year, now.month, now.day);
                                final first = today.add(Duration(
                                    days: widget.appointment == null ? 0 : 2));
                                final last =
                                    today.add(const Duration(days: 90));
                                final initial = date.isBefore(first)
                                    ? first
                                    : date.isAfter(last)
                                        ? last
                                        : date;
                                final picked = await showDatePicker(
                                    context: context,
                                    initialDate: initial,
                                    firstDate: first,
                                    lastDate: last);
                                if (picked != null && mounted) {
                                  setState(() => date = picked);
                                  load();
                                }
                              }),
                    const SizedBox(height: 12),
                    const Text('Hub time · South Africa (SAST)'),
                    const SizedBox(height: 12),
                    if (loading)
                      const LinearProgressIndicator()
                    else if (slots.isEmpty)
                      const Text(
                          'No times available for this selection. Try another day or complimentary option.')
                    else
                      Wrap(spacing: 8, runSpacing: 8, children: [
                        for (final time in slots)
                          ChoiceChip(
                              label: Text(slotTime(time)),
                              selected: selected == time,
                              onSelected: saving
                                  ? null
                                  : (_) {
                                      setState(() => selected = time);
                                      resetKey();
                                    })
                      ]),
                    if (selected != null) ...[
                      const SizedBox(height: 12),
                      Text(
                          slotRationale(
                              slot: selected!,
                              option: option,
                              vehicleMake: make.text,
                              vehicleModel: model.text),
                          style: const TextStyle(
                              color: serv60Muted, fontSize: 13, height: 1.45)),
                    ],
                    if (widget.appointment == null) ...[
                      const SizedBox(height: 28),
                      const Text('You and your car',
                          style: TextStyle(fontWeight: FontWeight.w600)),
                      const SizedBox(height: 8),
                      Text('Name and email come from Clerk and cannot be edited here.',
                          style: TextStyle(color: serv60Muted, fontSize: 12)),
                      const SizedBox(height: 12),
                      InputDecorator(
                          decoration:
                              const InputDecoration(labelText: 'Full name'),
                          child: Text(clerkName.isEmpty ? '—' : clerkName)),
                      const SizedBox(height: 12),
                      InputDecorator(
                          decoration: const InputDecoration(labelText: 'Email'),
                          child: Text(clerkEmail.isEmpty ? '—' : clerkEmail)),
                      const SizedBox(height: 12),
                      TextFormField(
                          controller: phone,
                          enabled: !saving,
                          keyboardType: TextInputType.phone,
                          textInputAction: TextInputAction.done,
                          onEditingComplete: dismissKeyboard,
                          onTapOutside: (_) => dismissKeyboard(),
                          maxLength: 32,
                          decoration: InputDecoration(
                              labelText: 'Phone number',
                              counterText: '',
                              suffixIcon: IconButton(
                                tooltip: 'Hide keyboard',
                                onPressed: dismissKeyboard,
                                icon: const Icon(
                                    Icons.keyboard_hide_outlined),
                              )),
                          validator: (value) =>
                              value == null || value.trim().isEmpty
                                  ? 'Please complete this field'
                                  : null),
                      const SizedBox(height: 16),
                      const Text('Vehicle for this visit',
                          style: TextStyle(fontWeight: FontWeight.w600)),
                      const SizedBox(height: 8),
                      if (vehicles.isNotEmpty)
                        InputDecorator(
                          decoration: const InputDecoration(
                              labelText: 'Choose a saved vehicle'),
                          child: DropdownButtonHideUnderline(
                            child: DropdownButton<String>(
                              isExpanded: true,
                              value: selectedVehicleId ??
                                  () {
                                    for (final vehicle in vehicles) {
                                      if (vehicle.id != null) return vehicle.id;
                                    }
                                    return null;
                                  }(),
                              items: [
                                for (final vehicle in vehicles)
                                  if (vehicle.id != null)
                                    DropdownMenuItem(
                                      value: vehicle.id,
                                      child: Text(
                                          '${vehicle.vehicleMake} ${vehicle.vehicleModel} · ${vehicle.registration}'),
                                    ),
                              ],
                              onChanged: saving
                                  ? null
                                  : (id) {
                                      setState(() {
                                        selectedVehicleId = id;
                                        for (final vehicle in vehicles) {
                                          if (vehicle.id == id) {
                                            _applyVehicle(vehicle);
                                            break;
                                          }
                                        }
                                      });
                                      resetKey();
                                    },
                            ),
                          ),
                        ),
                      if (vehicles.isEmpty)
                        const Text(
                            'No saved vehicles yet — enter the car you are bringing in.',
                            style: TextStyle(color: serv60Muted, fontSize: 12)),
                      const SizedBox(height: 12),
                      for (final entry in [
                        (make, 'Car make', 80),
                        (model, 'Car model', 80),
                        (registration, 'Registration', 24),
                      ])
                        Padding(
                            padding: const EdgeInsets.only(bottom: 12),
                            child: TextFormField(
                                controller: entry.$1,
                                enabled: !saving,
                                maxLength: entry.$3,
                                textInputAction: entry.$2 == 'Registration'
                                    ? TextInputAction.done
                                    : TextInputAction.next,
                                onEditingComplete: entry.$2 == 'Registration'
                                    ? dismissKeyboard
                                    : null,
                                onTapOutside: (_) => dismissKeyboard(),
                                decoration: InputDecoration(
                                    labelText: entry.$2, counterText: ''),
                                validator: (value) =>
                                    value == null || value.trim().isEmpty
                                        ? 'Please complete this field'
                                        : null)),
                    ] else ...[
                      const SizedBox(height: 24),
                      Text(
                          'Changes close ${visitTime(widget.appointment!.changeDeadline)} SAST. Your current booking stays reserved until this change succeeds.')
                    ],
                    if (error != null) JourneyNotice(error!),
                    const SizedBox(height: 24),
                    FilledButton(
                        onPressed:
                            selected == null || loading || saving ? null : save,
                        child: Text(saving
                            ? 'Saving…'
                            : widget.appointment == null
                                ? 'Reserve this visit'
                                : 'Confirm change')),
                    const SizedBox(height: 12),
                    if (widget.appointment == null)
                      const Text(
                          'A reservation holds your space briefly. Complete payment to confirm your visit.'),
                  ])))));
}
