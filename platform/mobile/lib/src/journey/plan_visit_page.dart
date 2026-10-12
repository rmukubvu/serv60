import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'journey_repository.dart';
import 'journey_widgets.dart';

class PlanVisitPage extends StatefulWidget {
  const PlanVisitPage(
      {super.key,
      required this.hub,
      required this.repository,
      this.appointment});
  final Hub hub;
  final JourneyRepository repository;
  final Appointment? appointment;
  @override
  State<PlanVisitPage> createState() => _PlanVisitPageState();
}

class _PlanVisitPageState extends State<PlanVisitPage> {
  final form = GlobalKey<FormState>();
  final fields = {
    for (final name in [
      'customerName',
      'phoneNumber',
      'vehicleMake',
      'vehicleModel',
      'registration'
    ])
      name: TextEditingController()
  };
  late DateTime date;
  String? option, error;
  List<DateTime> slots = [];
  DateTime? selected;
  bool loading = false, saving = false;
  String key = JourneyRepository.newKey();
  int searchVersion = 0;
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
    for (final entry in fields.entries) {
      entry.value.text = booking?.contact?[entry.key] ?? '';
      entry.value.addListener(resetKey);
    }
    load();
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
    setState(() {
      saving = true;
      error = null;
    });
    try {
      if (widget.appointment != null) {
        await widget.repository
            .change(widget.appointment!, selected!, option, key);
      } else {
        await widget.repository.reserve(
            widget.hub.id,
            selected!,
            option,
            {
              for (final entry in fields.entries)
                entry.key: entry.value.text.trim()
            },
            key);
      }
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => saving = false);
    }
  }

  @override
  void dispose() {
    for (final field in fields.values) {
      field.dispose();
    }
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
                  child: ListView(padding: const EdgeInsets.all(24), children: [
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
                    if (widget.appointment == null) ...[
                      const SizedBox(height: 28),
                      const Text('You and your car',
                          style: TextStyle(fontWeight: FontWeight.w600)),
                      const SizedBox(height: 12),
                      for (final entry in fields.entries)
                        Padding(
                            padding: const EdgeInsets.only(bottom: 12),
                            child: TextFormField(
                                controller: entry.value,
                                enabled: !saving,
                                keyboardType: entry.key == 'phoneNumber'
                                    ? TextInputType.phone
                                    : TextInputType.text,
                                maxLength: switch (entry.key) {
                                  'customerName' => 160,
                                  'phoneNumber' => 32,
                                  'registration' => 24,
                                  _ => 80
                                },
                                decoration: InputDecoration(
                                    labelText: switch (entry.key) {
                                      'customerName' => 'Full name',
                                      'phoneNumber' => 'Phone number',
                                      'vehicleMake' => 'Car make',
                                      'vehicleModel' => 'Car model',
                                      _ => 'Registration'
                                    },
                                    counterText: ''),
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
