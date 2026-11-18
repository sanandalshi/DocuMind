/**
 * Parses a Server-Sent Events text stream. Returns the complete events found in `buffer`
 * and the unfinished remainder to prepend to the next chunk.
 */
export function parseSSE(buffer) {
  const events = [];
  const normalized = buffer.replace(/\r\n/g, '\n');
  const blocks = normalized.split('\n\n');
  const rest = blocks.pop();
  for (const block of blocks) {
    let event = 'message';
    const data = [];
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
    }
    if (data.length) {
      const text = data.join('\n');
      let parsed = text;
      try {
        parsed = JSON.parse(text);
      } catch {
        /* plain text event */
      }
      events.push({ event, data: parsed });
    }
  }
  return { events, rest };
}

/** Reads an SSE response body and calls onEvent({event, data}) for each event. */
export async function readSSE(response, onEvent) {
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const { events, rest } = parseSSE(buffer);
    buffer = rest;
    events.forEach(onEvent);
  }
  const { events } = parseSSE(buffer + '\n\n');
  events.forEach(onEvent);
}
