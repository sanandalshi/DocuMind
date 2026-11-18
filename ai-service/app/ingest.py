import asyncio
import logging
import time
import uuid
from collections.abc import Awaitable, Callable

from . import db
from .chunking import chunk_pages
from .config import Settings
from .embeddings import Embedder
from .metrics import DOCUMENTS_PROCESSED, IN_FLIGHT, PROCESSING_SECONDS
from .pdf import TooManyPagesError, extract_pages
from .storage import Storage

log = logging.getLogger(__name__)

ProgressFn = Callable[[int, str], Awaitable[None]]


class IngestError(Exception):
    """A document that can never be processed (no retry)."""


class Ingestor:
    def __init__(self, settings: Settings, embedder: Embedder, storage: Storage):
        self.settings = settings
        self.embedder = embedder
        self.storage = storage

    async def process(self, document_id: uuid.UUID, user_id: uuid.UUID, storage_key: str, progress: ProgressFn) -> dict:
        start = time.perf_counter()
        IN_FLIGHT.inc()
        try:
            await progress(5, "downloading")
            pdf_bytes = await asyncio.to_thread(self.storage.download, storage_key)

            await progress(15, "extracting")
            try:
                pages = await asyncio.to_thread(extract_pages, pdf_bytes, self.settings.max_pages)
            except TooManyPagesError as exc:
                raise IngestError(str(exc)) from exc
            except Exception as exc:
                raise IngestError(f"Could not read the PDF: {exc}") from exc
            if not pages:
                raise IngestError("The PDF has no selectable text (scanned PDFs are not supported)")

            chunks = chunk_pages(pages, self.settings.chunk_size_chars, self.settings.chunk_overlap_chars)
            await progress(25, "embedding")

            # Re-processing (a retry) must not duplicate chunks
            async with db.SessionLocal() as session:
                await db.delete_document_chunks(session, document_id)

            batch_size = 32
            for i in range(0, len(chunks), batch_size):
                batch = chunks[i : i + batch_size]
                vectors = await asyncio.to_thread(self.embedder.embed, [c.content for c in batch])
                await db.save_chunks(
                    [
                        db.Chunk(
                            document_id=document_id,
                            user_id=user_id,
                            page=c.page,
                            chunk_index=c.index,
                            content=c.content,
                            embedding=v,
                        )
                        for c, v in zip(batch, vectors, strict=True)
                    ]
                )
                done = min(i + batch_size, len(chunks))
                await progress(25 + int(70 * done / len(chunks)), "embedding")

            duration_ms = int((time.perf_counter() - start) * 1000)
            PROCESSING_SECONDS.observe(duration_ms / 1000)
            DOCUMENTS_PROCESSED.labels("ok").inc()
            log.info(
                "Processed document %s: %d pages, %d chunks in %d ms", document_id, len(pages), len(chunks), duration_ms
            )
            return {"pageCount": len(pages), "chunkCount": len(chunks), "durationMs": duration_ms}
        except Exception:
            DOCUMENTS_PROCESSED.labels("failed").inc()
            raise
        finally:
            IN_FLIGHT.dec()
