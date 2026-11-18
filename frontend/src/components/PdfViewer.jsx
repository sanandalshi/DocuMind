import { useEffect, useState } from 'react';
import { Document, Page, pdfjs } from 'react-pdf';
import 'react-pdf/dist/Page/TextLayer.css';
import 'react-pdf/dist/Page/AnnotationLayer.css';
import { apiFetch } from '../api/client.js';

pdfjs.GlobalWorkerOptions.workerSrc = new URL('pdfjs-dist/build/pdf.worker.min.mjs', import.meta.url).toString();

/** Shows a document at a cited page (FR-29). The PDF is fetched with the user's token. */
export default function PdfViewer({ documentId, documentName, page, onClose }) {
  const [url, setUrl] = useState(null);
  const [error, setError] = useState('');
  const [pageNumber, setPageNumber] = useState(page);
  const [numPages, setNumPages] = useState(null);

  useEffect(() => {
    setPageNumber(page);
  }, [page, documentId]);

  useEffect(() => {
    let objectUrl;
    let cancelled = false;
    setUrl(null);
    setError('');
    apiFetch(`/documents/${documentId}/file`)
      .then((res) => res.blob())
      .then((blob) => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        setUrl(objectUrl);
      })
      .catch((err) => !cancelled && setError(err.message));
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [documentId]);

  return (
    <aside className="viewer" aria-label="Document viewer">
      <div className="viewer-head">
        <div className="viewer-title" title={documentName}>{documentName}</div>
        <div className="viewer-nav">
          <button className="ghost" disabled={pageNumber <= 1} onClick={() => setPageNumber((p) => p - 1)} aria-label="Previous page">‹</button>
          <span className="small">{pageNumber}{numPages ? ` / ${numPages}` : ''}</span>
          <button className="ghost" disabled={numPages !== null && pageNumber >= numPages} onClick={() => setPageNumber((p) => p + 1)} aria-label="Next page">›</button>
          <button className="ghost" onClick={onClose} aria-label="Close viewer">✕</button>
        </div>
      </div>
      <div className="viewer-body">
        {error && <div className="error">{error}</div>}
        {!url && !error && <p className="muted">Loading PDF…</p>}
        {url && (
          <Document file={url} onLoadSuccess={({ numPages: n }) => setNumPages(n)} loading={<p className="muted">Loading PDF…</p>}>
            <Page pageNumber={pageNumber} width={460} />
          </Document>
        )}
      </div>
    </aside>
  );
}
