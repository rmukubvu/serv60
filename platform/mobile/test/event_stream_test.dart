import 'dart:async';
import 'dart:convert';

import 'package:fake_async/fake_async.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:serv60_mobile/src/auth/access_token_provider.dart';
import 'package:serv60_mobile/src/journey/event_stream.dart';
import 'package:serv60_mobile/src/journey/journey_repository.dart';

import 'visit_progress_page_test.dart' show progressJson;

List<int> frame(String name, Object data) =>
    utf8.encode('event: $name\ndata: ${jsonEncode(data)}\n\n');

/// Stands in for the progress stream endpoint: every connection gets its own
/// body the test feeds, and [statuses] queues non-200 answers to give first.
class SseServer {
  final connections = <StreamController<List<int>>>[];
  final requests = <http.BaseRequest>[];
  final statuses = <int>[];

  late final client = MockClient.streaming((request, _) async {
    requests.add(request);
    final status = statuses.isEmpty ? 200 : statuses.removeAt(0);
    final body = StreamController<List<int>>();
    connections.add(body);
    if (status != 200) {
      body.add(utf8.encode('{"error":"busy"}'));
      unawaited(body.close());
    }
    return http.StreamedResponse(body.stream, status, headers: {
      'content-type': status == 200 ? 'text/event-stream' : 'application/json'
    });
  });

  JourneyRepository repository() => JourneyRepository(
      baseUrl: 'https://api.test',
      client: client,
      tokenProvider: CallbackAccessTokenProvider(() async => 't0k3n'));
}

void main() {
  test('parses names, multi-line data, comments and ignored fields', () async {
    final events = await parseServerEvents(Stream.fromIterable([
      ':keepalive',
      'event: progress',
      'data: {"stage":"SERVICING"}',
      '',
      'data: first line',
      'data:second line',
      'id: 7',
      'retry: 3000',
      '',
      'data',
      '',
      'event: empty',
      '',
      'event: progress',
      'data: {"stage":"READY"}',
    ])).toList();
    // The last event never got its blank line, so it is dropped.
    expect(
        events.map((e) => e.name).toList(), ['progress', 'message', 'message']);
    expect(events[0].data, '{"stage":"SERVICING"}');
    expect(events[1].data, 'first line\nsecond line');
    expect(events[2].data, '');
    expect(
        events[0].toString(), 'ServerEvent(progress, {"stage":"SERVICING"})');
  });

  test('openEventStream asks for text/event-stream and rejects non-200',
      () async {
    final server = SseServer()..statuses.add(503);
    final uri = Uri.parse('https://api.test/stream');
    await expectLater(
        openEventStream(server.client, uri).toList(),
        throwsA(isA<EventStreamException>()
            .having((e) => e.statusCode, 'statusCode', 503)
            .having((e) => e.toString(), 'message', contains('503'))));

    final events = openEventStream(server.client, uri,
        headers: {'Authorization': 'Bearer x'}).toList();
    await pumpEventQueue();
    expect(server.requests.last.headers['Accept'], 'text/event-stream');
    expect(server.requests.last.headers['Authorization'], 'Bearer x');
    // A multi-byte character split across two chunks still decodes.
    final bytes = utf8.encode('event: progress\ndata: {"hub":"Côte"}\n\n');
    final cut = bytes.indexOf(0xC3) + 1;
    server.connections.last
      ..add(bytes.sublist(0, cut))
      ..add(bytes.sublist(cut));
    await server.connections.last.close();
    expect((await events).single.data, '{"hub":"Côte"}');
  });

  test('progressStream reconnects with back-off and completes when cancelled',
      () {
    final server = SseServer();
    final repo = server.repository();
    fakeAsync((async) {
      final received = <String?>[];
      final sub =
          repo.progressStream('visit-1').listen((p) => received.add(p.stage));
      async.flushMicrotasks();
      expect(server.requests.single.url.path,
          '/v1/customer/appointments/visit-1/progress/stream');
      expect(server.requests.single.headers['Accept'], 'text/event-stream');
      expect(server.requests.single.headers['Authorization'], 'Bearer t0k3n');

      server.connections[0]
        ..add(frame('progress', progressJson(stage: 'SERVICING', elapsed: 23)))
        ..add(utf8.encode(':keepalive\n'))
        ..add(utf8.encode('event: note\ndata: {"ignored":true}\n\n'))
        ..add(utf8.encode('event: progress\ndata: not json\n\n'));
      async.flushMicrotasks();
      expect(received, ['SERVICING']);

      server.connections[0].close();
      async.flushMicrotasks();
      async.elapse(const Duration(milliseconds: 999));
      expect(server.connections, hasLength(1));
      async.elapse(const Duration(milliseconds: 1));
      expect(server.connections, hasLength(2));
      server.connections[1]
          .add(frame('progress', progressJson(stage: 'READY', elapsed: 52)));
      async.flushMicrotasks();
      expect(received, ['SERVICING', 'READY']);

      sub.cancel();
      async.flushMicrotasks();
      async.elapse(const Duration(minutes: 1));
      expect(server.connections, hasLength(2));
      expect(async.pendingTimers, isEmpty);
    });
  });

  test('progressStream retries non-200 answers, backing off further each time',
      () {
    final server = SseServer()..statuses.addAll([503, 503]);
    final repo = server.repository();
    fakeAsync((async) {
      final received = <String?>[];
      final sub =
          repo.progressStream('visit-1').listen((p) => received.add(p.stage));
      async.flushMicrotasks();
      expect(server.connections, hasLength(1));
      async.elapse(const Duration(seconds: 1));
      expect(server.connections, hasLength(2));
      async.elapse(const Duration(milliseconds: 1999));
      expect(server.connections, hasLength(2));
      async.elapse(const Duration(milliseconds: 1));
      expect(server.connections, hasLength(3));
      server.connections[2]
          .add(frame('progress', progressJson(stage: 'WASHING', elapsed: 40)));
      async.flushMicrotasks();
      expect(received, ['WASHING']);
      // A delivered event resets the back-off to its first step.
      server.connections[2].close();
      async.elapse(const Duration(seconds: 1));
      expect(server.connections, hasLength(4));
      sub.cancel();
      async.flushMicrotasks();
      expect(async.pendingTimers, isEmpty);
    });
  });

  test('progressStream drops a connection that misses three keepalives', () {
    final server = SseServer();
    final repo = server.repository();
    fakeAsync((async) {
      final sub = repo.progressStream('visit-1').listen((_) {});
      async.flushMicrotasks();
      async.elapse(const Duration(seconds: 30));
      server.connections[0].add(utf8.encode(':keepalive\n'));
      async.elapse(const Duration(seconds: 44));
      expect(server.connections, hasLength(1));
      // 45 s of silence ends the connection; the first back-off step follows.
      async.elapse(const Duration(seconds: 2));
      expect(server.connections, hasLength(2));
      sub.cancel();
      async.flushMicrotasks();
      expect(async.pendingTimers, isEmpty);
    });
  });
}
