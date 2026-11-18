import asyncio
import json
import logging
import uuid
from contextlib import asynccontextmanager

from alembic import command
from alembic.config import Config
from fastapi import Depends, FastAPI, Header, HTTPException, Request, Response
from fastapi.responses import StreamingResponse
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from pydantic import BaseModel, Field
from sqlalchemy import text

from . import db
from .config import get_settings
from .embeddings import create_embedder
from .ingest import Ingestor
from .llm import create_llm
from .logging_setup import correlation_id, setup_logging
from .messaging import Messaging
from .rag import RagService
from .storage import Storage

setup_logging()
log = logging.getLogger(__name__)
settings = get_settings()


def run_migrations() -> None:
    cfg = Config("alembic.ini")
    cfg.set_main_option("sqlalchemy.url", settings.database_url)
    command.upgrade(cfg, "head")


@asynccontextmanager
async def lifespan(app: FastAPI):
    await asyncio.to_thread(run_migrations)
    embedder = await asyncio.to_thread(create_embedder, settings)
    app.state.rag = RagService(settings, embedder, create_llm(settings))
    app.state.messaging = Messaging(settings, Ingestor(settings, embedder, Storage(settings)))
    await app.state.messaging.start()
    log.info("AI service ready (embeddings=%s, llm=%s)", settings.embedding_provider, settings.llm_provider)
    yield
    await app.state.messaging.stop()
    await db.engine.dispose()


app = FastAPI(title="DocuMind AI Service", version="1.0.0", lifespan=lifespan)


def require_service_token(x_service_token: str = Header(default="")) -> None:
    if x_service_token != settings.service_token:
        raise HTTPException(status_code=401, detail="Invalid service token")


@app.middleware("http")
async def correlation(request: Request, call_next):
    correlation_id.set(request.headers.get("x-correlation-id", "-"))
    return await call_next(request)


class Turn(BaseModel):
    role: str
    content: str


class QueryRequest(BaseModel):
    question: str = Field(min_length=1, max_length=2000)
    userId: uuid.UUID
    documentIds: list[uuid.UUID]
    history: list[Turn] = []


@app.post("/internal/query", dependencies=[Depends(require_service_token)])
async def query(req: QueryRequest, request: Request) -> StreamingResponse:
    """Streams newline-delimited JSON events: citations, token*, done | error."""
    rag: RagService = request.app.state.rag

    async def events():
        async for event in rag.answer(req.question, req.userId, req.documentIds, [t.model_dump() for t in req.history]):
            yield json.dumps(event) + "\n"

    return StreamingResponse(events(), media_type="application/x-ndjson")


@app.delete("/internal/documents/{document_id}", dependencies=[Depends(require_service_token)])
async def delete_document(document_id: uuid.UUID) -> dict:
    async with db.SessionLocal() as session:
        deleted = await db.delete_document_chunks(session, document_id)
    return {"documentId": str(document_id), "deletedChunks": deleted}


@app.get("/health")
async def health() -> dict:
    async with db.SessionLocal() as session:
        await session.execute(text("SELECT 1"))
    return {"status": "UP"}


@app.get("/metrics")
async def metrics() -> Response:
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)
