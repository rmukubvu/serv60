import { apiFetch } from './api';

/** One server-sent event: the `event:` name (`message` when the server sent none) and its `data:` lines joined with "\n". */
export type SseEvent = { event: string; data: string };

type LineEnd = { end: number; next: number };

/**
 * Where the line that starts at `from` ends (CRLF, LF or CR) and where the next one begins; null while the
 * line is still incomplete. A CR at the very end of the text is held back because an LF may follow in the
 * next chunk.
 */
function lineEnd(text: string, from: number): LineEnd | null {
  for (let index = from; index < text.length; index += 1) {
    const char = text[index];
    if (char === '\n') return { end: index, next: index + 1 };
    if (char === '\r') {
      if (index === text.length - 1) return null;
      return { end: index, next: text[index + 1] === '\n' ? index + 2 : index + 1 };
    }
  }
  return null;
}

/**
 * Incremental parser for the text/event-stream wire format. Feed it decoded text in whatever pieces the
 * network delivers: partial lines are kept between calls and only complete events come back.
 *
 * Framing: `field: value` lines; a blank line ends the event; lines starting with `:` are comments (the
 * server's `:keepalive`); several `data:` lines join with "\n"; `id:` and `retry:` are ignored.
 */
export class SseParser {
  #buffer = '';
  #event = '';
  #data: string[] = [];

  /** Parse the next chunk and return every event it completed, in order. */
  push(chunk: string): SseEvent[] {
    this.#buffer += chunk;
    const events: SseEvent[] = [];
    let start = 0;
    let line = lineEnd(this.#buffer, start);
    while (line) {
      const event = this.#field(this.#buffer.slice(start, line.end));
      if (event) events.push(event);
      start = line.next;
      line = lineEnd(this.#buffer, start);
    }
    this.#buffer = this.#buffer.slice(start);
    return events;
  }

  /** The stream closed: a held-back trailing CR still ends its line, and an event with no blank line after it is dropped. */
  flush(): SseEvent[] {
    const events = this.#buffer.endsWith('\r') ? this.push('\n') : [];
    this.#buffer = '';
    this.#event = '';
    this.#data = [];
    return events;
  }

  /** Apply one line; returns the event it completes, if any. */
  #field(line: string): SseEvent | null {
    if (line === '') return this.#complete();
    if (line.startsWith(':')) return null;
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    const raw = colon === -1 ? '' : line.slice(colon + 1);
    const value = raw.startsWith(' ') ? raw.slice(1) : raw;
    if (field === 'event') this.#event = value;
    else if (field === 'data') this.#data.push(value);
    return null;
  }

  /** A blank line: dispatch what was collected, unless there was no data at all. */
  #complete(): SseEvent | null {
    const data = this.#data;
    const event = this.#event;
    this.#data = [];
    this.#event = '';
    return data.length === 0 ? null : { event: event || 'message', data: data.join('\n') };
  }
}

/** Parse a complete event-stream body in one go. */
export function parseSse(text: string): SseEvent[] {
  const parser = new SseParser();
  return [...parser.push(text), ...parser.flush()];
}

export type EventStreamOptions = {
  /** Aborting it closes the connection, and the returned promise resolves. */
  signal?: AbortSignal;
  /** Called whenever the server sends anything, including `:keepalive` comments that produce no event. */
  onActivity?: () => void;
};

/**
 * Open a server-sent event stream on the platform API. EventSource cannot send the staff bearer token, so
 * this reads the response body by hand. Resolves when the server closes the stream or the signal aborts;
 * rejects on a non-OK status or when the connection fails.
 */
export async function openEventStream(
  path: string,
  onEvent: (event: SseEvent) => void,
  { signal, onActivity }: EventStreamOptions = {},
): Promise<void> {
  const response = await apiFetch(path, { headers: { Accept: 'text/event-stream' }, signal });
  if (!response.ok) throw new Error(`Event stream request failed (${response.status})`);
  if (!response.body) throw new Error('Event stream response has no body');

  const reader = response.body.getReader();
  // fetch rejects a pending read once the signal aborts; cancelling as well covers bodies that are not tied
  // to the signal, and releases the connection either way.
  const cancel = () => {
    reader.cancel().catch(() => undefined);
  };
  if (signal?.aborted) cancel();
  else signal?.addEventListener('abort', cancel, { once: true });

  const decoder = new TextDecoder();
  const parser = new SseParser();
  const deliver = (events: SseEvent[]) => {
    for (const event of events) onEvent(event);
  };
  try {
    let chunk = await reader.read();
    while (!chunk.done) {
      onActivity?.();
      deliver(parser.push(decoder.decode(chunk.value, { stream: true })));
      chunk = await reader.read();
    }
    deliver(parser.push(decoder.decode()));
    deliver(parser.flush());
  } catch (error) {
    if (!signal?.aborted) throw error;
  } finally {
    signal?.removeEventListener('abort', cancel);
    cancel();
  }
}
