import 'package:flutter/material.dart';
import '../design/serv60_theme.dart';
import 'journey_repository.dart';
import 'journey_widgets.dart';

class AssistantPage extends StatefulWidget {
  const AssistantPage(
      {super.key,
      required this.repository,
      required this.hub,
      required this.onExplore,
      required this.onBooked});
  final JourneyRepository repository;
  final Hub? hub;
  final VoidCallback onExplore, onBooked;
  @override
  State<AssistantPage> createState() => _AssistantPageState();
}

class _AssistantPageState extends State<AssistantPage> {
  final input = TextEditingController();
  final scroll = ScrollController();
  final List<Map<String, String>> messages = [];
  BookingProposal? proposal;
  String? error;
  bool sending = false, confirming = false;
  @override
  void didUpdateWidget(AssistantPage old) {
    super.didUpdateWidget(old);
    if (old.hub?.id != widget.hub?.id) {
      messages.clear();
      proposal = null;
      error = null;
    }
  }

  Future<void> send() async {
    final message = input.text.trim();
    final hub = widget.hub;
    if (message.isEmpty || sending || confirming || hub == null) return;
    setState(() {
      messages.add({'role': 'user', 'content': message});
      sending = true;
      error = null;
      proposal = null;
      input.clear();
    });
    // Keep complete turns and the newest customer message within the API's 16-message budget.
    final history =
        messages.sublist(messages.length > 15 ? messages.length - 15 : 0);
    try {
      final reply = await widget.repository.chat(hub.id, history);
      if (!mounted || widget.hub?.id != hub.id) return;
      setState(() {
        messages.add({'role': 'assistant', 'content': reply.message});
        proposal = reply.proposal;
      });
    } catch (e) {
      if (mounted && widget.hub?.id == hub.id)
        setState(() {
          error = e.toString();
          input.text = message;
          messages.removeLast();
        });
    } finally {
      if (mounted) {
        setState(() => sending = false);
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (scroll.hasClients)
            scroll.animateTo(scroll.position.maxScrollExtent,
                duration: const Duration(milliseconds: 200),
                curve: Curves.easeOut);
        });
      }
    }
  }

  Future<void> confirm() async {
    final selected = proposal;
    if (selected == null || confirming) return;
    setState(() {
      confirming = true;
      error = null;
    });
    try {
      await widget.repository.confirmProposal(selected.id);
      if (mounted) {
        setState(() => proposal = null);
        widget.onBooked();
      }
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => confirming = false);
    }
  }

  @override
  void dispose() {
    input.dispose();
    scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Column(children: [
        Expanded(
            child: ListView(
                controller: scroll,
                padding: const EdgeInsets.fromLTRB(24, 28, 24, 24),
                children: [
              const JourneyHeader(
                  'Visit help',
                  'Change it in plain language.',
                  'Best for moving an existing booking. For a new visit, use Plan your visit — it’s faster and clearer.'),
              if (widget.hub == null) ...[
                const Text('Choose a nearby hub to get started.'),
                const SizedBox(height: 16),
                FilledButton(
                    onPressed: widget.onExplore,
                    child: const Text('Find a hub'))
              ] else ...[
                Row(children: [
                  const Icon(Icons.location_on_outlined, size: 18),
                  const SizedBox(width: 8),
                  Expanded(child: Text(widget.hub!.name)),
                  TextButton(
                      onPressed:
                          sending || confirming ? null : widget.onExplore,
                      child: const Text('Change'))
                ]),
                const Divider(),
                if (messages.isEmpty) ...[
                  const SizedBox(height: 20),
                  const Text('Try something like'),
                  const SizedBox(height: 12),
                  OutlinedButton(
                      onPressed: () {
                        input.text =
                            'Move my booking to Saturday morning if a bay is free.';
                        send();
                      },
                      child: const Text('Move my booking')),
                  const SizedBox(height: 10),
                  OutlinedButton(
                      onPressed: () {
                        input.text =
                            'What times are available tomorrow with ${widget.hub!.options.isEmpty ? 'just a car service' : optionLabel(widget.hub!.options.first)}?';
                        send();
                      },
                      child: const Text('Find me a time tomorrow')),
                  const SizedBox(height: 10),
                  const Text(
                      'We’ll only propose real slots from this hub. Confirming checks availability again.'),
                ],
                for (final message in messages)
                  Align(
                      alignment: message['role'] == 'user'
                          ? Alignment.centerRight
                          : Alignment.centerLeft,
                      child: Container(
                          margin: const EdgeInsets.only(top: 16),
                          padding: const EdgeInsets.all(16),
                          constraints: const BoxConstraints(maxWidth: 460),
                          decoration: BoxDecoration(
                              color: message['role'] == 'user'
                                  ? serv60Ink
                                  : serv60Surface,
                              border: Border.all(color: serv60Border)),
                          child: Text(message['content']!,
                              style: TextStyle(
                                  color: message['role'] == 'user'
                                      ? Colors.white
                                      : serv60Ink)))),
                if (sending)
                  const Padding(
                      padding: EdgeInsets.symmetric(vertical: 20),
                      child: LinearProgressIndicator()),
                if (proposal != null)
                  Padding(
                      padding: const EdgeInsets.only(top: 20),
                      child: Card(
                          child: Padding(
                              padding: const EdgeInsets.all(20),
                              child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                        proposal!.kind == 'BOOK'
                                            ? 'Review your visit'
                                            : 'Review your change',
                                        style: const TextStyle(fontSize: 22)),
                                    const SizedBox(height: 12),
                                    Text(proposal!.hubName),
                                    Text(
                                        '${visitTime(proposal!.startsAt)} SAST'),
                                    const Text(
                                        'Express vehicle service · 60 minutes'),
                                    Text(optionLabel(proposal!.option)),
                                    const Divider(height: 28),
                                    Text(
                                        '${proposal!.contact['customerName']} · ${proposal!.contact['phoneNumber']}'),
                                    Text(
                                        '${proposal!.contact['vehicleMake']} ${proposal!.contact['vehicleModel']} · ${proposal!.contact['registration']}'),
                                    const SizedBox(height: 16),
                                    FilledButton(
                                        onPressed: confirming ? null : confirm,
                                        child: Text(confirming
                                            ? 'Saving…'
                                            : proposal!.kind == 'BOOK'
                                                ? 'Confirm reservation'
                                                : 'Confirm change')),
                                    const SizedBox(height: 10),
                                    const Text(
                                        'The time is checked again when you confirm. New reservations require payment.'),
                                  ])))),
                if (error != null) JourneyNotice(error!),
              ],
            ])),
        if (widget.hub != null)
          Container(
              padding: const EdgeInsets.fromLTRB(24, 12, 24, 16),
              decoration: const BoxDecoration(
                  border: Border(top: BorderSide(color: serv60Border))),
              child:
                  Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Expanded(
                    child: TextField(
                        controller: input,
                        enabled: !sending && !confirming,
                        maxLength: 2000,
                        minLines: 1,
                        maxLines: 4,
                        decoration: const InputDecoration(
                            hintText: 'Move my Friday oil to Saturday…',
                            counterText: ''),
                        onSubmitted: (_) => send())),
                const SizedBox(width: 8),
                IconButton.filled(
                    onPressed: sending || confirming ? null : send,
                    tooltip: 'Send message',
                    icon: const Icon(Icons.arrow_upward)),
              ])),
      ]);
}
