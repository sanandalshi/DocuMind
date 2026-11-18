import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, stream } from '../api/client.js';

const MAX_BYTES = 25 * 1024 * 1024;

function formatSize(bytes) {
  return bytes > 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.ceil(bytes / 1024)} KB`;
}

/** Keeps a document's row live while it is processed (FR-27). */
function useProgress(doc) {
  const queryClient = useQueryClient();
  const finished = doc.status === 'READY' || doc.status === 'FAILED';
  useEffect(() => {
    if (finished) return undefined;
    const controller = new AbortController();
    stream(`/documents/${doc.id}/events`, {
      signal: controller.signal,
      onEvent: ({ event, data }) => {
        if (event !== 'progress') return;
        queryClient.setQueryData(['documents'], (docs = []) => docs.map((d) => (d.id === data.id ? data : d)));
      },
    }).catch(() => {});
    return () => controller.abort();
  }, [doc.id, finished, queryClient]);
}

function DocumentRow({ doc, onAsk, onDelete }) {
  useProgress(doc);
  const working = doc.status === 'UPLOADED' || doc.status === 'PROCESSING';
  return (
    <li className="doc-row">
      <div className="doc-main">
        <div className="doc-name">{doc.name}</div>
        <div className="muted small">
          {formatSize(doc.sizeBytes)}
          {doc.pageCount ? ` · ${doc.pageCount} pages` : ''}
          {' · '}
          {new Date(doc.createdAt).toLocaleDateString()}
        </div>
        {working && (
          <div className="progress" aria-label={`Processing ${doc.progress}%`}>
            <div className="progress-bar" style={{ width: `${Math.max(doc.progress, 3)}%` }} />
          </div>
        )}
        {doc.status === 'FAILED' && <div className="error small">{doc.failureReason || 'Processing failed'}</div>}
      </div>
      <span className={`badge badge-${doc.status.toLowerCase()}`}>
        {working ? `${doc.status === 'UPLOADED' ? 'Queued' : 'Processing'} ${doc.progress}%` : doc.status === 'READY' ? 'Ready' : 'Failed'}
      </span>
      <div className="doc-actions">
        <button className="primary" disabled={doc.status !== 'READY'} onClick={() => onAsk(doc.id)}>Ask</button>
        <button className="ghost danger" onClick={() => onDelete(doc)}>Delete</button>
      </div>
    </li>
  );
}

export default function DocumentsPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const fileInput = useRef(null);
  const [dragging, setDragging] = useState(false);
  const [error, setError] = useState('');

  const documents = useQuery({ queryKey: ['documents'], queryFn: () => api('/documents') });
  const chats = useQuery({ queryKey: ['chats'], queryFn: () => api('/chats') });

  const upload = useMutation({
    mutationFn: (file) => {
      const form = new FormData();
      form.append('file', file);
      return api('/documents', { method: 'POST', body: form });
    },
    onSuccess: (doc) => {
      queryClient.setQueryData(['documents'], (docs = []) => [doc, ...docs.filter((d) => d.id !== doc.id)]);
    },
    onError: (err) => setError(err.message),
  });

  const remove = useMutation({
    mutationFn: (doc) => api(`/documents/${doc.id}`, { method: 'DELETE' }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['documents'] });
      queryClient.invalidateQueries({ queryKey: ['chats'] });
    },
    onError: (err) => setError(err.message),
  });

  const startChat = useMutation({
    mutationFn: (documentId) => api('/chats', { method: 'POST', body: documentId ? { documentId } : {} }),
    onSuccess: (chat) => {
      queryClient.invalidateQueries({ queryKey: ['chats'] });
      navigate(`/chats/${chat.id}`);
    },
    onError: (err) => setError(err.message),
  });

  function handleFiles(files) {
    setError('');
    for (const file of files) {
      if (file.type !== 'application/pdf' && !file.name.toLowerCase().endsWith('.pdf')) {
        setError(`${file.name} is not a PDF`);
      } else if (file.size > MAX_BYTES) {
        setError(`${file.name} is larger than 25 MB`);
      } else {
        upload.mutate(file);
      }
    }
  }

  const docs = documents.data || [];
  const readyCount = docs.filter((d) => d.status === 'READY').length;

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Your documents</h1>
          <p className="muted">Upload PDFs, then ask questions. Every answer cites its page.</p>
        </div>
        <button className="secondary" disabled={readyCount === 0} onClick={() => startChat.mutate(null)}>
          Ask all documents
        </button>
      </div>

      <div
        className={`dropzone ${dragging ? 'dragging' : ''}`}
        role="button"
        tabIndex={0}
        onClick={() => fileInput.current?.click()}
        onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && fileInput.current?.click()}
        onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
        onDragLeave={() => setDragging(false)}
        onDrop={(e) => { e.preventDefault(); setDragging(false); handleFiles(e.dataTransfer.files); }}
      >
        <strong>{upload.isPending ? 'Uploading…' : 'Drop PDFs here or click to choose'}</strong>
        <span className="muted small">Text PDFs up to 25 MB and 300 pages</span>
        <input
          ref={fileInput}
          type="file"
          accept="application/pdf,.pdf"
          multiple
          hidden
          onChange={(e) => { handleFiles(e.target.files); e.target.value = ''; }}
        />
      </div>

      {error && <div className="error" role="alert">{error}</div>}

      {documents.isLoading ? (
        <p className="muted">Loading documents…</p>
      ) : documents.isError ? (
        <div className="error">{documents.error.message}</div>
      ) : docs.length === 0 ? (
        <div className="empty">No documents yet. Upload your first PDF to get started.</div>
      ) : (
        <ul className="doc-list">
          {docs.map((doc) => (
            <DocumentRow
              key={doc.id}
              doc={doc}
              onAsk={(id) => startChat.mutate(id)}
              onDelete={(d) => window.confirm(`Delete ${d.name}? This cannot be undone.`) && remove.mutate(d)}
            />
          ))}
        </ul>
      )}

      {chats.data?.length > 0 && (
        <section className="recent">
          <h2>Recent chats</h2>
          <ul>
            {chats.data.slice(0, 8).map((chat) => (
              <li key={chat.id}>
                <Link to={`/chats/${chat.id}`}>{chat.title}</Link>
                <span className="muted small"> · {new Date(chat.createdAt).toLocaleString()}</span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
