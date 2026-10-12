import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import '../design/serv60_theme.dart';

// Launch hubs operate in South Africa, which observes UTC+2 year round.
DateTime hubTime(DateTime instant) =>
    instant.toUtc().add(const Duration(hours: 2));
String visitTime(DateTime instant) =>
    DateFormat('EEE, d MMM · HH:mm').format(hubTime(instant));
String slotTime(DateTime instant) =>
    DateFormat('HH:mm').format(hubTime(instant));

class JourneyHeader extends StatelessWidget {
  const JourneyHeader(this.eyebrow, this.title, this.subtitle, {super.key});
  final String eyebrow, title, subtitle;
  @override
  Widget build(BuildContext context) =>
      Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Serv60Wordmark(),
        const SizedBox(height: 36),
        Text(eyebrow.toUpperCase(),
            style: const TextStyle(
                color: serv60AccentInk,
                fontSize: 11,
                letterSpacing: 1.8,
                fontWeight: FontWeight.w600)),
        const SizedBox(height: 12),
        Text(title, style: Theme.of(context).textTheme.headlineLarge),
        const SizedBox(height: 12),
        Text(subtitle, style: const TextStyle(color: serv60Muted)),
        const SizedBox(height: 28),
      ]);
}

class JourneyNotice extends StatelessWidget {
  const JourneyNotice(this.message, {super.key});
  final String message;
  @override
  Widget build(BuildContext context) => Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Text(message, style: const TextStyle(color: serv60Error)));
}
