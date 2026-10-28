import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

/// One server-sent event. [name] is `message` when the server sent no
/// `event:` line; [data] joins the event's `data:` lines with newlines.
class ServerEvent {
  const ServerEvent(this.name, this.data);
  final String name;
  final String data;
  @override
  String toString() => 'ServerEvent($name, $data)';
}

/// The server answered an event-stream request with something other than 200.
class EventStreamException implements Exception {
  EventStreamException(this.statusCode);
  final int statusCode;
  @override
  String toString() => 'Event stream rejected with HTTP $statusCode';
}

/// Accumulates the lines of one `text/event-stream` event.
///
/// A blank line dispatches the event collected so far; lines starting with
/// `:` are comments (the server's keepalives); `id:` and `retry:` are accepted
/// and ignored. As the spec requires, an `event:` line with no data
/// dispatches nothing, and whatever is pending when the stream ends is
/// dropped because nothing ever calls [feed] with the closing blank line.
class ServerEventParser {
  var _name = 'message';
  final _data = <String>[];

  /// Returns the event that [line] completes, or null.
  ServerEvent? feed(String line) {
    if (line.isEmpty) {
      final event = _data.isEmpty ? null : ServerEvent(_name, _data.join('\n'));
      _name = 'message';
      _data.clear();
      return event;
    }
    if (line.startsWith(':')) return null;
    final colon = line.indexOf(':');
    final field = colon < 0 ? line : line.substring(0, colon);
    var value = colon < 0 ? '' : line.substring(colon + 1);
    if (value.startsWith(' ')) value = value.substring(1);
    switch (field) {
      case 'event':
        _name = value;
      case 'data':
        _data.add(value);
      default:
      // id, retry and unknown fields carry nothing this client uses.
    }
    return null;
  }
}

/// Decodes event-stream framing from already-split lines.
Stream<ServerEvent> parseServerEvents(Stream<String> lines) {
  final parser = ServerEventParser();
  return lines.expand((line) {
    final event = parser.feed(line);
    return event == null ? const <ServerEvent>[] : [event];
  });
}

/// Opens one event-stream connection and emits its events until the server
/// closes it or the listener cancels, which drops the connection at once.
///
/// The stream fails with an [EventStreamException] when the server does not
/// answer 200, and, given an [idleTimeout], with a [TimeoutException] when
/// the connection stays silent that long (no event, no keepalive comment).
/// Either way it then closes, so the caller decides whether to reconnect.
Stream<ServerEvent> openEventStream(http.Client client, Uri uri,
    {Map<String, String> headers = const {},
    Duration? idleTimeout,
    Duration connectTimeout = const Duration(seconds: 15)}) {
  late final StreamController<ServerEvent> controller;
  StreamSubscription<String>? lines;
  Timer? idle;
  var cancelled = false;

  void fail(Object error, [StackTrace? trace]) {
    if (cancelled) return;
    controller.addError(error, trace);
    controller.close();
  }

  void arm() {
    if (idleTimeout == null) return;
    idle?.cancel();
    idle = Timer(idleTimeout, () {
      lines?.cancel();
      fail(TimeoutException('No event or keepalive', idleTimeout));
    });
  }

  Future<void> start() async {
    try {
      final request = http.Request('GET', uri)
        ..headers.addAll({...headers, 'Accept': 'text/event-stream'});
      final response = await client.send(request).timeout(connectTimeout);
      if (cancelled) {
        // Not awaited: a cancel future can settle on the root zone, which a
        // fake clock never reaches, and nothing here depends on it.
        unawaited(response.stream.listen(null).cancel());
        return;
      }
      if (response.statusCode != 200) {
        // Not awaited: a cancel future can settle on the root zone, which a
        // fake clock never reaches, and nothing here depends on it.
        unawaited(response.stream.listen(null).cancel());
        throw EventStreamException(response.statusCode);
      }
      final parser = ServerEventParser();
      lines = response.stream
          .transform(utf8.decoder)
          .transform(const LineSplitter())
          .listen(
              (line) {
                arm();
                final event = parser.feed(line);
                if (event != null) controller.add(event);
              },
              onError: fail,
              onDone: () {
                idle?.cancel();
                controller.close();
              });
      arm();
    } catch (error, trace) {
      fail(error, trace);
    }
  }

  controller = StreamController<ServerEvent>(
      onListen: start,
      onCancel: () {
        cancelled = true;
        idle?.cancel();
        lines?.cancel();
      });
  return controller.stream;
}
