import { Fragment, Suspense, lazy, useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api, stream } from '../api/client.js';
// pdf.js is large: load it only when a citation is opened
const PdfViewer = lazy(() => import('../components/PdfViewer.jsx'));

/** Turns "[1]" markers in the answer into buttons that open the cited page. */
function AnswerText({ text, citations, onCite }) {
  const parts = text.split(/(\[\d+\])/g);
  return (
    <p className="answer-text">
      {parts.map((part, i) => {
        const match = part.match(/^\[(\d+)\]$/);
        const citation = match && citations.find((c) => c.index === Number(match[1]));
        if (!citation) return <Fragment key={i}>{part}</Fragment>;
        return (
          <button key={i} className="cite" onClick={() => onCite(citation)} title={`${citation.documentName}, page ${citation.page}`}>
            {citation.index}
          </button>
        );
      })}
    </p>
  );
}

function Citations({ citations, onCite }) {
  if (!citations?.length) return null;
  return (
    <div className="citations">
      {citations.map((c) => (
        <button key={c.index} className="citation" onClick={() => onCite(c)}>
          <span className="cite">{c.index}</span>
          <span>
            <strong>{c.documentName}</strong> · page {c.page}
            <span className="excerpt">{c.excerpt.length > 160 ? `${c.excerpt.slice(0, 160)}…` : c.excerpt}</span>
          </span>
        </button>
      ))}
    </div>
  );
}

export default function ChatPage() {
  const { chatId } = useParams();
  const [messages, setMessages] = useState([]);
  const [question, setQuestion] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [viewing, setViewing] = useState(null);
  const bottom = useRef(null);
  const abort = useRef(null);

  const chats = useQuery({ queryKey: ['chats'], queryFn: () => api('/chats') });
  const history = useQuery({ queryKey: ['messages', chatId], queryFn: () => api(`/chats/${chatId}/messages`) });
  const chat = chats.data?.find((c) => c.id === chatId);

  useEffect(() => {
    if (history.data) setMessages(history.data);
  }, [history.data]);
  useEffect(() => {
    // braces matter: newer browsers return a Promise from scrollIntoView, which React would call as a cleanup
    bottom.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);
  useEffect(() => () => abort.current?.abort(), []);

  async function ask(e) {
    e.preventDefault();
    const q = question.trim();
    if (!q || busy) return;
    setQuestion('');
    setError('');
    setBusy(true);
    const assistantId = `pending-${Date.now()}`;
    setMessages((m) => [
      ...m,
      { id: `q-${Date.now()}`, role: 'user', content: q },
      { id: assistantId, role: 'assistant', content: '', citations: [], streaming: true },
    ]);
    const patch = (fn) => setMessages((m) => m.map((msg) => (msg.id === assistantId ? fn(msg) : msg)));

    abort.current = new AbortController();
    try {
      await stream(`/chats/${chatId}/messages`, {
        method: 'POST',
        body: { question: q },
        signal: abort.current.signal,
        onEvent: ({ event, data }) => {
          if (event === 'citations') patch((msg) => ({ ...msg, citations: data.citations }));
          else if (event === 'token') patch((msg) => ({ ...msg, content: msg.content + data.text }));
          else if (event === 'done') patch((msg) => ({ ...msg, id: data.messageId, streaming: false, latencyMs: data.latencyMs, cached: data.cached }));
          else if (event === 'error') {
            setError(data.message);
            patch((msg) => ({ ...msg, streaming: false, failed: true }));
          }
        },
      });
    } catch (err) {
      if (err.name !== 'AbortError') {
        setError(err.message);
        setMessages((m) => m.filter((msg) => msg.id !== assistantId));
      }
    } finally {
      patch((msg) => ({ ...msg, streaming: false }));
      setBusy(false);
    }
  }

  return (
    <div className={`chat-layout ${viewing ? 'with-viewer' : ''}`}>
      <div className="chat">
        <div className="chat-head">
          <Link to="/" className="muted small">← Documents</Link>
          <h1>{chat?.title || 'Chat'}</h1>
        </div>

        <div className="messages">
          {history.isLoading && <p className="muted">Loading conversation…</p>}
          {!history.isLoading && messages.length === 0 && (
            <div className="empty">
              Ask anything about {chat?.documentId ? 'this document' : 'your documents'}, for example
              <em> “What does it say about annual leave?”</em>
            </div>
          )}
          {messages.map((m) =>
            m.role === 'user' ? (
              <div key={m.id} className="msg msg-user">{m.content}</div>
            ) : (
              <div key={m.id} className="msg msg-assistant">
                {m.content ? (
                  <AnswerText text={m.content} citations={m.citations || []} onCite={setViewing} />
                ) : (
                  m.streaming && <p className="muted typing">Searching your documents…</p>
                )}
                <Citations citations={m.citations} onCite={setViewing} />
                {!m.streaming && m.latencyMs != null && (
                  <div className="muted tiny">{m.cached ? 'Cached · ' : ''}{(m.latencyMs / 1000).toFixed(1)} s</div>
                )}
              </div>
            )
          )}
          <div ref={bottom} />
        </div>

        {error && <div className="error" role="alert">{error}</div>}

        <form className="ask" onSubmit={ask}>
          <textarea
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && !e.shiftKey && ask(e)}
            placeholder="Ask a question…"
            rows={2}
            maxLength={2000}
            disabled={busy}
            aria-label="Question"
          />
          <button className="primary" disabled={busy || !question.trim()}>{busy ? 'Answering…' : 'Ask'}</button>
        </form>
      </div>

      {viewing && (
        <Suspense fallback={<aside className="viewer"><p className="muted" style={{ padding: 12 }}>Loading viewer…</p></aside>}>
          <PdfViewer
            documentId={viewing.documentId}
            documentName={viewing.documentName}
            page={viewing.page}
            onClose={() => setViewing(null)}
          />
        </Suspense>
      )}
    </div>
  );
}
