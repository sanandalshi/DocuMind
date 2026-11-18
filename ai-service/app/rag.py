import asyncio
import logging
import time
import uuid
from collections.abc import AsyncIterator
from dataclasses import dataclass

from . import db
from .config import Settings
from .embeddings import Embedder
from .llm import LLM, NOT_FOUND
from .metrics import FIRST_TOKEN_SECONDS, QUERIES, RETRIEVAL_SECONDS

log = logging.getLogger(__name__)


@dataclass
class Excerpt:
    index: int
    document_id: str
    page: int
    content: str
    score: float


class RagService:
    def __init__(self, settings: Settings, embedder: Embedder, llm: LLM):
        self.settings = settings
        self.embedder = embedder
        self.llm = llm

    async def retrieve(self, question: str, user_id: uuid.UUID, document_ids: list[uuid.UUID]) -> list[Excerpt]:
        start = time.perf_counter()
        [query_vec] = await asyncio.to_thread(self.embedder.embed, [question])
        rows = await db.search_chunks(query_vec, user_id, document_ids, self.settings.top_k)
        RETRIEVAL_SECONDS.observe(time.perf_counter() - start)
        relevant = [(c, s) for c, s in rows if s >= self.settings.min_similarity]
        return [
            Excerpt(index=i + 1, document_id=str(c.document_id), page=c.page, content=c.content, score=round(s, 4))
            for i, (c, s) in enumerate(relevant)
        ]

    async def answer(
        self, question: str, user_id: uuid.UUID, document_ids: list[uuid.UUID], history: list[dict]
    ) -> AsyncIterator[dict]:
        """Yield events: citations, token*, done (or error)."""
        start = time.perf_counter()
        excerpts = await self.retrieve(question, user_id, document_ids) if document_ids else []

        yield {
            "type": "citations",
            "citations": [
                {
                    "index": e.index,
                    "documentId": e.document_id,
                    "page": e.page,
                    "excerpt": e.content[:500],
                    "score": e.score,
                }
                for e in excerpts
            ],
        }

        if not excerpts:  # FR-19: say so instead of guessing
            QUERIES.labels("not_found").inc()
            yield {"type": "token", "text": NOT_FOUND}
            yield {"type": "done", "notFound": True}
            return

        first = True
        answer_parts: list[str] = []
        try:
            async for token in self.llm.stream(question, excerpts, history):
                if first:
                    FIRST_TOKEN_SECONDS.observe(time.perf_counter() - start)
                    first = False
                answer_parts.append(token)
                yield {"type": "token", "text": token}
        except Exception as exc:  # NFR-16: fail clearly, keep the service up
            log.exception("LLM call failed")
            QUERIES.labels("error").inc()
            yield {"type": "error", "message": f"The language model failed: {type(exc).__name__}"}
            return

        not_found = NOT_FOUND.lower() in "".join(answer_parts).lower()
        QUERIES.labels("not_found" if not_found else "answered").inc()
        yield {"type": "done", "notFound": not_found}
