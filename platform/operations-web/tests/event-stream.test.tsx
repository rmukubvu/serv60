import { describe, expect, it, vi } from 'vitest';
import { apiFetch } from '../app/api';
import { openEventStream, parseSse, type SseEvent, SseParser } from '../app/event-stream';

vi.mock('../app/api', () => ({ apiFetch: vi.fn() }));
const fetcher = vi.mocked(apiFetch);

const encoder = new TextEncoder();

/** A text/event-stream Response whose bytes the test pushes by hand. */
function serverStream(status = 200) {
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  const state = { cancelled: false };
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      controller = c;
    },
    cancel() {
      state.cancelled = true;
    },
  });
  const response = new Response(body, { status, headers: { 'Content-Type': 'text/event-stream' } });
  return {
    response,
    state,
    send: (text: string) => controller.enqueue(encoder.encode(text)),
    close: () => controller.close(),
    fail: (error: Error) => controller.error(error),
  };
}

const PATH = '/v1/hubs/hub-1/floor/stream';

describe('SseParser', () => {
  it('yields two events from one chunk', () => {
    const parser = new SseParser();
    expect(parser.push('event: floor\ndata: [1]\n\nevent: floor\ndata: [2]\n\n')).toEqual([
      { event: 'floor', data: '[1]' },
      { event: 'floor', data: '[2]' },
    ]);
    expect(parser.flush()).toEqual([]);
  });

  it('holds an event split across chunks until its blank line arrives', () => {
    const parser = new SseParser();
    expect(parser.push('event: fl')).toEqual([]);
    expect(parser.push('oor\ndata: {"visitId"')).toEqual([]);
    expect(parser.push(':"v1"}\n')).toEqual([]);
    expect(parser.push('\nevent: floor\n')).toEqual([{ event: 'floor', data: '{"visitId":"v1"}' }]);
    expect(parser.push('data: []\n\n')).toEqual([{ event: 'floor', data: '[]' }]);
  });

  it('joins multi-line data with newlines and strips one leading space from each value', () => {
    expect(parseSse('data: first\ndata:second\ndata:  spaced\ndata\n\n')).toEqual([
      { event: 'message', data: 'first\nsecond\n spaced\n' },
    ]);
  });

  it('ignores keepalive comments, ids and retry hints', () => {
    const parser = new SseParser();
    expect(parser.push(':keepalive\n\n:keepalive\n')).toEqual([]);
    expect(parser.push('id: 7\nretry: 3000\nevent: floor\ndata: []\n\n:keepalive\n')).toEqual([
      { event: 'floor', data: '[]' },
    ]);
    // An event name with no data is dropped, and does not leak into the next event.
    expect(parser.push('event: floor\n\n')).toEqual([]);
    expect(parser.push('data: after\n\n')).toEqual([{ event: 'message', data: 'after' }]);
  });

  it('accepts CRLF and lone CR line endings, even when split across chunks', () => {
    const parser = new SseParser();
    expect(parser.push('event: floor\r\ndata: a\r')).toEqual([]);
    expect(parser.push('\ndata: b\r\r\n')).toEqual([{ event: 'floor', data: 'a\nb' }]);
    expect(parser.push('data: tail\r\r')).toEqual([]);
    expect(parser.flush()).toEqual([{ event: 'message', data: 'tail' }]);
  });

  it('drops an event the stream closed in the middle of', () => {
    const parser = new SseParser();
    expect(parser.push('event: floor\ndata: [')).toEqual([]);
    expect(parser.flush()).toEqual([]);
    expect(parser.push('data: fresh\n\n')).toEqual([{ event: 'message', data: 'fresh' }]);
    expect(parseSse('data: cut')).toEqual([]);
  });
});

describe('openEventStream', () => {
  it('asks for an event stream, delivers events as they arrive and resolves when the server closes', async () => {
    const server = serverStream();
    fetcher.mockResolvedValue(server.response);
    const events: SseEvent[] = [];
    const activity = vi.fn();
    const controller = new AbortController();
    const finished = openEventStream(PATH, (event) => events.push(event), {
      signal: controller.signal,
      onActivity: activity,
    });
    expect(fetcher).toHaveBeenCalledWith(PATH, {
      headers: { Accept: 'text/event-stream' },
      signal: controller.signal,
    });

    server.send('event: floor\ndata: [1]\n\n');
    await vi.waitFor(() => expect(events).toEqual([{ event: 'floor', data: '[1]' }]));
    expect(activity).toHaveBeenCalledTimes(1);

    // A keepalive is activity but not an event.
    server.send(':keepalive\n');
    await vi.waitFor(() => expect(activity).toHaveBeenCalledTimes(2));
    expect(events).toHaveLength(1);

    // An event may arrive in pieces.
    server.send('event: fl');
    server.send('oor\ndata: [2]\n\n');
    await vi.waitFor(() => expect(events).toHaveLength(2));
    expect(events[1]).toEqual({ event: 'floor', data: '[2]' });

    server.close();
    await expect(finished).resolves.toBeUndefined();
  });

  it('rejects when the server refuses the stream or sends no body', async () => {
    fetcher.mockResolvedValue(new Response('{"error":"down"}', { status: 503 }));
    await expect(openEventStream(PATH, () => undefined)).rejects.toThrow('Event stream request failed (503)');
    fetcher.mockResolvedValue(new Response(null, { status: 200 }));
    await expect(openEventStream(PATH, () => undefined)).rejects.toThrow('no body');
  });

  it('rejects when the connection breaks mid-stream', async () => {
    const server = serverStream();
    fetcher.mockResolvedValue(server.response);
    const finished = openEventStream(PATH, () => undefined);
    server.send('event: floor\n');
    server.fail(new Error('connection reset'));
    await expect(finished).rejects.toThrow('connection reset');
  });

  it('resolves and releases the connection when aborted, even before the first byte', async () => {
    const server = serverStream();
    fetcher.mockResolvedValue(server.response);
    const controller = new AbortController();
    const events: SseEvent[] = [];
    const finished = openEventStream(PATH, (event) => events.push(event), { signal: controller.signal });
    server.send('event: floor\ndata: []\n\n');
    await vi.waitFor(() => expect(events).toHaveLength(1));
    controller.abort();
    await expect(finished).resolves.toBeUndefined();
    expect(server.state.cancelled).toBe(true);

    const early = serverStream();
    fetcher.mockResolvedValue(early.response);
    const aborted = new AbortController();
    aborted.abort();
    await expect(openEventStream(PATH, () => undefined, { signal: aborted.signal })).resolves.toBeUndefined();
    expect(early.state.cancelled).toBe(true);
  });
});
