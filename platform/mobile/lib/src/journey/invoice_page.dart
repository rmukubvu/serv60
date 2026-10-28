import 'package:flutter/material.dart';

import '../design/serv60_theme.dart';
import 'journey_repository.dart';
import 'journey_widgets.dart';
import 'visit_progress_page.dart' show stageLabel;

/// Customer-facing label for an invoice line status.
String lineStatusLabel(String status) => switch (status) {
      'PAID' => 'Paid',
      'INCLUDED' => 'Included',
      _ => 'Due at counter',
    };

/// The invoice issued at handover, with the visit's stage record beneath it.
class InvoicePage extends StatefulWidget {
  const InvoicePage(
      {super.key, required this.repository, required this.visitId});
  final JourneyRepository repository;
  final String visitId;
  @override
  State<InvoicePage> createState() => _InvoicePageState();
}

class _InvoicePageState extends State<InvoicePage> {
  Invoice? invoice;
  VisitProgress? progress;
  String? error, recordError;
  bool loading = true;

  @override
  void initState() {
    super.initState();
    load();
  }

  /// The stage record is secondary: its failure must not hide the invoice.
  Future<(VisitProgress?, String?)> record() async {
    try {
      return (await widget.repository.progress(widget.visitId), null);
    } catch (e) {
      return (null, e.toString());
    }
  }

  Future<void> load() async {
    final stages = record();
    try {
      final bill = await widget.repository.invoice(widget.visitId);
      final (latest, failure) = await stages;
      if (!mounted) return;
      setState(() {
        invoice = bill;
        progress = latest;
        recordError = failure;
      });
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final bill = invoice;
    return Scaffold(
        appBar: AppBar(title: const Text('Invoice')),
        body: SafeArea(
            child: Center(
                child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 640),
                    child: SingleChildScrollView(
                        padding: const EdgeInsets.fromLTRB(24, 28, 24, 40),
                        child: Column(
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [
                              if (loading) const LinearProgressIndicator(),
                              if (error != null) JourneyNotice(error!),
                              if (bill != null) ...details(context, bill),
                            ]))))));
  }

  Widget eyebrow(String text) => Text(text.toUpperCase(),
      style: const TextStyle(
          color: serv60AccentInk,
          fontSize: 11,
          letterSpacing: 1.8,
          fontWeight: FontWeight.w600));

  List<Widget> details(BuildContext context, Invoice bill) => [
        eyebrow(bill.hubName),
        const SizedBox(height: 12),
        Text(bill.number, style: Theme.of(context).textTheme.headlineLarge),
        if (bill.issuedAt != null) ...[
          const SizedBox(height: 8),
          Text('Issued ${visitTime(bill.issuedAt!)} SAST',
              style: const TextStyle(color: serv60Muted)),
        ],
        const SizedBox(height: 16),
        if (bill.customerName.isNotEmpty) Text(bill.customerName),
        Text(vehicleLine(bill.vehicle, bill.registration),
            style: const TextStyle(color: serv60Muted)),
        const SizedBox(height: 24),
        linesCard(bill),
        const SizedBox(height: 28),
        const Text('Service record',
            style: TextStyle(fontWeight: FontWeight.w600)),
        const SizedBox(height: 8),
        serviceRecord(),
      ];

  Widget linesCard(Invoice bill) => Card(
      child: Padding(
          padding: const EdgeInsets.all(20),
          child:
              Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            for (final line in bill.lines)
              Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(
                            child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                              Text(line.description),
                              const SizedBox(height: 4),
                              statusChip(line.status),
                            ])),
                        const SizedBox(width: 12),
                        Text(rands(line.amountMinor)),
                      ])),
            const Divider(height: 12),
            totalRow('Total', rands(bill.totalMinor), strong: true),
            totalRow('Paid in app', rands(bill.paidMinor)),
            if (bill.dueMinor > 0)
              totalRow('Due at counter', rands(bill.dueMinor),
                  strong: true, color: serv60AccentInk, size: 18)
            else
              totalRow('Due at counter', 'Nothing to pay', color: serv60Muted),
          ])));

  Widget totalRow(String label, String value,
      {bool strong = false, Color? color, double? size}) {
    final style = TextStyle(
        fontWeight: strong ? FontWeight.w600 : FontWeight.w400,
        color: color,
        fontSize: size);
    return Padding(
        padding: const EdgeInsets.only(top: 8),
        child: Row(children: [
          Expanded(child: Text(label, style: style)),
          Text(value, style: style),
        ]));
  }

  Widget statusChip(String status) {
    final (background, ink) = switch (status) {
      'PAID' => (serv60Selected, serv60AccentInk),
      'INCLUDED' => (serv60Surface, serv60Muted),
      _ => (serv60Paper, serv60Ink),
    };
    return Chip(
        label: Text(lineStatusLabel(status)),
        visualDensity: VisualDensity.compact,
        backgroundColor: background,
        side: const BorderSide(color: serv60Border),
        labelStyle:
            TextStyle(color: ink, fontSize: 12, fontWeight: FontWeight.w600));
  }

  Widget serviceRecord() {
    if (recordError != null) return JourneyNotice(recordError!);
    final events = progress?.events ?? const <StageEvent>[];
    if (events.isEmpty) {
      return const Text('No stages were recorded for this visit.',
          style: TextStyle(color: serv60Muted));
    }
    return Card(
        child: Padding(
            padding: const EdgeInsets.fromLTRB(20, 16, 20, 4),
            child: Column(children: [
              for (final event in events)
                Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Expanded(
                              child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                Text(stageLabel(event.stage),
                                    style: const TextStyle(
                                        fontWeight: FontWeight.w500)),
                                if (event.note != null)
                                  Text(event.note!,
                                      style: const TextStyle(
                                          color: serv60Muted, fontSize: 12)),
                              ])),
                          if (event.recordedAt != null)
                            Text(slotTime(event.recordedAt!),
                                style: const TextStyle(
                                    color: serv60Muted, fontSize: 12)),
                        ])),
            ])));
  }
}

/// "Toyota Corolla Cross · CA 60 060", dropping whichever half is missing.
String vehicleLine(String vehicle, String registration) =>
    [vehicle, registration].where((part) => part.isNotEmpty).join(' · ');
