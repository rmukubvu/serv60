import 'dart:async';
import 'dart:math';

import 'package:flutter/material.dart';
import '../design/serv60_theme.dart';
import 'invoice_page.dart';
import 'journey_repository.dart';
import 'journey_widgets.dart';

/// The hub moves every car through these stages in order; the 60-minute
/// target runs from CHECKED_IN to READY.
const visitStages = [
  'CHECKED_IN',
  'INSPECTING',
  'SERVICING',
  'QUALITY_CHECK',
  'WASHING',
  'READY',
  'HANDED_OVER',
];

String stageLabel(String stage) => switch (stage) {
      'CHECKED_IN' => 'Checked in',
      'INSPECTING' => 'Inspection',
      'SERVICING' => 'Service',
      'QUALITY_CHECK' => 'Quality check',
      'WASHING' => 'Wash',
      'READY' => 'Ready',
      'HANDED_OVER' => 'Handed over',
      _ => stage,
    };

String optionLine(String? code) => code == null
    ? optionLabel(null)
    : '${optionLabel(code)} · booked inside your hour';

/// How long after the last streamed event the screen still counts as live.
const liveWindow = Duration(seconds: 45);
const _liveGreen = Color(0xff2f6b3a);

class VisitProgressPage extends StatefulWidget {
  /// Progress arrives over the repository's event stream; [pollEvery] is only
  /// a safety net for when that stream is down.
  const VisitProgressPage(
      {super.key,
      required this.repository,
      required this.appointment,
      this.pollEvery = const Duration(seconds: 30)});
  final JourneyRepository repository;
  final Appointment appointment;
  final Duration pollEvery;
  @override
  State<VisitProgressPage> createState() => _VisitProgressPageState();
}

class _VisitProgressPageState extends State<VisitProgressPage> {
  VisitProgress? progress;
  String? error, deciding;
  final decisionErrors = <String, String>{};
  bool loading = false, live = false;
  Timer? poll, liveUntil;
  StreamSubscription<VisitProgress>? updates;

  @override
  void initState() {
    super.initState();
    load();
    poll = Timer.periodic(widget.pollEvery, (_) => load());
    updates = widget.repository
        .progressStream(widget.appointment.id)
        .listen(streamed, onError: (Object _) {});
  }

  @override
  void dispose() {
    poll?.cancel();
    liveUntil?.cancel();
    updates?.cancel();
    super.dispose();
  }

  void streamed(VisitProgress latest) {
    liveUntil?.cancel();
    liveUntil = Timer(liveWindow, () {
      if (mounted) setState(() => live = false);
    });
    setState(() {
      progress = latest;
      error = null;
      live = true;
    });
  }

  Future<void> load() async {
    if (loading) return;
    setState(() => loading = true);
    try {
      final latest = await widget.repository.progress(widget.appointment.id);
      if (mounted) {
        setState(() {
          progress = latest;
          error = null;
        });
      }
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> decide(ScopeChange change, String decision) async {
    setState(() {
      deciding = change.id;
      decisionErrors.remove(change.id);
    });
    try {
      final latest = await widget.repository
          .decideScopeChange(widget.appointment.id, change.id, decision);
      if (mounted) setState(() => progress = latest);
    } catch (e) {
      if (mounted) setState(() => decisionErrors[change.id] = e.toString());
    } finally {
      if (mounted) setState(() => deciding = null);
    }
  }

  String hubName(VisitProgress data) =>
      data.hubName.isEmpty ? widget.appointment.hubName : data.hubName;

  @override
  Widget build(BuildContext context) {
    final data = progress;
    return Scaffold(
        appBar: AppBar(title: const Text('Your visit'), actions: [
          if (live)
            const Padding(
                padding: EdgeInsets.only(right: 16),
                child: Row(mainAxisSize: MainAxisSize.min, children: [
                  Icon(Icons.circle, size: 8, color: _liveGreen),
                  SizedBox(width: 6),
                  Text('Live',
                      style: TextStyle(
                          color: serv60Muted,
                          fontSize: 12,
                          letterSpacing: 0.4)),
                ])),
          if (loading)
            const Padding(
                padding: EdgeInsets.only(right: 24),
                child: Row(mainAxisSize: MainAxisSize.min, children: [
                  SizedBox(
                      width: 14,
                      height: 14,
                      child: CircularProgressIndicator(
                          strokeWidth: 2, color: serv60AccentInk)),
                  SizedBox(width: 8),
                  Text('Updating…',
                      style: TextStyle(color: serv60Muted, fontSize: 12)),
                ])),
        ]),
        body: SafeArea(
            child: Center(
                child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 640),
                    child: SingleChildScrollView(
                        padding: const EdgeInsets.fromLTRB(24, 28, 24, 40),
                        child: Column(
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [
                              if (error != null) JourneyNotice(error!),
                              if (data == null) ...[
                                if (error == null)
                                  const Text('Checking on your car…',
                                      style: TextStyle(color: serv60Muted)),
                              ] else if (!data.checkedIn)
                                ...beforeCheckIn(context, data)
                              else
                                ...onTheFloor(context, data),
                            ]))))));
  }

  Widget eyebrow(String text) => Text(text.toUpperCase(),
      style: const TextStyle(
          color: serv60AccentInk,
          fontSize: 11,
          letterSpacing: 1.8,
          fontWeight: FontWeight.w600));

  List<Widget> beforeCheckIn(BuildContext context, VisitProgress data) {
    final startsAt = data.startsAt ?? widget.appointment.startsAt;
    return [
      eyebrow('Confirmed visit'),
      const SizedBox(height: 12),
      Text(hubName(data), style: Theme.of(context).textTheme.headlineLarge),
      const SizedBox(height: 12),
      Text('${visitTime(startsAt)} SAST', style: const TextStyle(fontSize: 23)),
      const SizedBox(height: 4),
      Text(optionLine(data.option), style: const TextStyle(color: serv60Muted)),
      const SizedBox(height: 24),
      const Card(
          child: Padding(
              padding: EdgeInsets.all(20),
              child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(children: [
                      Icon(Icons.timer_outlined, color: serv60AccentInk),
                      SizedBox(width: 12),
                      Expanded(
                          child: Text('We’ll start the clock when you arrive.',
                              style: TextStyle(fontWeight: FontWeight.w600))),
                    ]),
                    SizedBox(height: 8),
                    Text('Show this screen at the intake lane.'),
                  ]))),
    ];
  }

  List<Widget> onTheFloor(BuildContext context, VisitProgress data) {
    final stage = data.stage!;
    final target = data.targetMinutes;
    final elapsed = data.elapsedMinutes ?? 0;
    final over = elapsed > target;
    final fraction = target <= 0 ? 1.0 : min(1.0, elapsed / target);
    return [
      eyebrow(hubName(data)),
      const SizedBox(height: 12),
      Text(stageLabel(stage), style: Theme.of(context).textTheme.headlineLarge),
      const SizedBox(height: 20),
      Text('$elapsed of $target min',
          style: const TextStyle(
              fontSize: 44,
              height: 1,
              fontWeight: FontWeight.w500,
              letterSpacing: -1.5)),
      const SizedBox(height: 12),
      ClipRRect(
          borderRadius: BorderRadius.circular(3),
          child: LinearProgressIndicator(
              value: fraction,
              minHeight: 8,
              color: over ? serv60Error : serv60Orange,
              backgroundColor: serv60Border)),
      const SizedBox(height: 8),
      if (over)
        const Text('Running over the hour. The team will keep you posted.',
            style: TextStyle(color: serv60Error, fontWeight: FontWeight.w600))
      else if (data.checkedInAt != null)
        Text(
            'Checked in ${slotTime(data.checkedInAt!)} SAST · $target-minute target',
            style: const TextStyle(color: serv60Muted, fontSize: 12)),
      if (stage == 'READY') banner('Your car is ready for collection'),
      if (stage == 'HANDED_OVER') ...[
        banner('Thanks for visiting. Your invoice is ready.'),
        const SizedBox(height: 12),
        FilledButton(
            onPressed: () => Navigator.of(context).push(MaterialPageRoute<void>(
                builder: (_) => InvoicePage(
                    repository: widget.repository,
                    visitId: data.visitId.isEmpty
                        ? widget.appointment.id
                        : data.visitId))),
            child: const Text('View invoice')),
      ],
      const SizedBox(height: 24),
      timeline(data, stage),
      const SizedBox(height: 16),
      Text(optionLine(data.option)),
      if (data.voucherCode != null) voucherCard(data.voucherCode!),
      for (final change in data.scopeChanges) scopeCard(change),
    ];
  }

  Widget banner(String message) => Padding(
      padding: const EdgeInsets.only(top: 16),
      child: Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
              color: serv60Selected,
              border: Border.all(color: serv60Orange),
              borderRadius: BorderRadius.circular(3)),
          child: Text(message,
              style: const TextStyle(
                  color: serv60AccentInk,
                  fontSize: 16,
                  fontWeight: FontWeight.w600))));

  Widget timeline(VisitProgress data, String stage) {
    final currentIndex = visitStages.indexOf(stage);
    final recorded = {for (final event in data.events) event.stage: event};
    return Card(
        child: Padding(
            padding: const EdgeInsets.fromLTRB(20, 16, 20, 16),
            child: Column(children: [
              for (final (index, name) in visitStages.indexed)
                stageRow(name, recorded[name],
                    done: index < currentIndex,
                    current: index == currentIndex,
                    last: index == visitStages.length - 1),
            ])));
  }

  Widget stageRow(String name, StageEvent? event,
      {required bool done, required bool current, required bool last}) {
    final accent = done
        ? serv60Ink
        : current
            ? serv60Orange
            : serv60Border;
    final recordedAt = event?.recordedAt;
    return Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Column(children: [
        Container(
            width: 24,
            height: 24,
            decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: done || current ? accent : Colors.transparent,
                border: Border.all(color: accent, width: 1.5)),
            child: done
                ? const Icon(Icons.check, size: 14, color: serv60Paper)
                : current
                    ? const Icon(Icons.directions_car,
                        size: 14, color: serv60Ink)
                    : null),
        if (!last)
          Container(
              width: 2, height: 22, color: done ? serv60Ink : serv60Border),
      ]),
      const SizedBox(width: 14),
      Expanded(
          child: Padding(
              padding: const EdgeInsets.only(top: 3),
              child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(stageLabel(name),
                        style: TextStyle(
                            fontWeight:
                                current ? FontWeight.w700 : FontWeight.w500,
                            color: current
                                ? serv60AccentInk
                                : done
                                    ? serv60Ink
                                    : serv60Muted)),
                    if (event?.note != null)
                      Text(event!.note!,
                          style: const TextStyle(
                              color: serv60Muted, fontSize: 12)),
                  ]))),
      if ((done || current) && recordedAt != null)
        Padding(
            padding: const EdgeInsets.only(top: 3),
            child: Text(slotTime(recordedAt),
                style: const TextStyle(color: serv60Muted, fontSize: 12))),
    ]);
  }

  Widget voucherCard(String code) => Padding(
      padding: const EdgeInsets.only(top: 16),
      child: Container(
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
              color: serv60Selected,
              border: Border.all(color: serv60Border),
              borderRadius: BorderRadius.circular(3)),
          child:
              Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Row(children: [
              Icon(Icons.coffee_outlined, color: serv60AccentInk),
              SizedBox(width: 10),
              Text('Your coffee’s on us',
                  style: TextStyle(fontWeight: FontWeight.w600)),
            ]),
            const SizedBox(height: 12),
            Text(code,
                style: const TextStyle(
                    fontSize: 32,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 2)),
            const SizedBox(height: 6),
            const Text('Show this at the barista bar.',
                style: TextStyle(color: serv60Muted)),
          ])));

  Widget statusChip(String status) {
    final approved = status == 'APPROVED';
    return Chip(
        label: Text(approved ? 'Approved' : 'Declined'),
        visualDensity: VisualDensity.compact,
        backgroundColor: approved ? serv60Selected : serv60Surface,
        side: const BorderSide(color: serv60Border),
        labelStyle: TextStyle(
            color: approved ? serv60AccentInk : serv60Muted,
            fontSize: 12,
            fontWeight: FontWeight.w600));
  }

  Widget scopeCard(ScopeChange change) {
    final busy = deciding != null;
    final failure = decisionErrors[change.id];
    return Padding(
        padding: const EdgeInsets.only(top: 16),
        child: Card(
            child: Padding(
                padding: const EdgeInsets.all(20),
                child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(children: [
                        Expanded(child: eyebrow('Extra work proposed')),
                        if (!change.proposed) statusChip(change.status),
                      ]),
                      const SizedBox(height: 12),
                      for (final item in change.items)
                        Padding(
                            padding: const EdgeInsets.only(bottom: 6),
                            child: Row(children: [
                              Expanded(child: Text(item.description)),
                              Text(rands(item.amountMinor)),
                            ])),
                      if (change.note != null)
                        Text(change.note!,
                            style: const TextStyle(color: serv60Muted)),
                      const Divider(height: 24),
                      Row(children: [
                        const Expanded(
                            child: Text('Total',
                                style: TextStyle(fontWeight: FontWeight.w600))),
                        Text(rands(change.totalMinor),
                            style:
                                const TextStyle(fontWeight: FontWeight.w600)),
                      ]),
                      if (change.proposed) ...[
                        const SizedBox(height: 16),
                        FilledButton(
                            onPressed:
                                busy ? null : () => decide(change, 'APPROVE'),
                            child: const Text('Approve · settle at handover')),
                        const SizedBox(height: 8),
                        OutlinedButton(
                            onPressed:
                                busy ? null : () => decide(change, 'DECLINE'),
                            child: const Text('Decline')),
                        if (failure != null) JourneyNotice(failure),
                      ],
                    ]))));
  }
}
