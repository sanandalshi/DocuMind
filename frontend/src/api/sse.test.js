import { describe, expect, it } from 'vitest';
import { parseSSE } from './sse.js';

describe('parseSSE', () => {
  it('parses named JSON events and keeps the unfinished tail', () => {
    const input = 'event:token\ndata:{"text":"Hel"}\n\nevent:token\ndata:{"text":"lo"}\n\nevent:do';
    const { events, rest } = parseSSE(input);
    expect(events).toEqual([
      { event: 'token', data: { text: 'Hel' } },
      { event: 'token', data: { text: 'lo' } },
    ]);
    expect(rest).toBe('event:do');
  });

  it('joins multi-line data and handles CRLF', () => {
    const { events } = parseSSE('data: line one\r\ndata: line two\r\n\r\n');
    expect(events).toEqual([{ event: 'message', data: 'line one\nline two' }]);
  });
});
