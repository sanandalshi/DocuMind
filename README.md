# DocuMind

Chat with your PDFs. Upload documents, ask questions in plain language, and get answers that cite the exact page they came from.

```
React (Nginx) ──► Spring Boot Core API ──► PostgreSQL (users, documents, chats)
                     │      │
                     │      └──► Redis (rate limits, answer cache)
                     ├── upload ──► S3 storage (SeaweedFS)
                     └── document.uploaded ──► RabbitMQ ──► Python AI Service (FastAPI)
                                                             │  PyMuPDF → chunks → embeddings
                                                             └──► PostgreSQL + pgvector
     question ──► Core API ──► AI Service: vector search ──► OpenAI ──► streamed answer + citations
```

Only the Core API faces the browser. Slow work (reading and embedding PDFs) runs behind RabbitMQ, so uploads return immediately. Questions use a direct internal HTTP call because the user is waiting for the answer, which streams back token by token.

| Part | Stack |
|---|---|
| `core-api/` | Java 21, Spring Boot 3.4, Spring Security (JWT), Spring Data JPA, Flyway, Spring AMQP, Redis, AWS SDK (S3), Actuator + Prometheus, OpenAPI |
| `ai-service/` | Python 3.12, FastAPI, PyMuPDF, LangChain text splitters, sentence-transformers (`all-MiniLM-L6-v2`), pgvector + SQLAlchemy, Alembic, aio-pika, OpenAI SDK |
| `frontend/` | React 18, Vite, React Router, TanStack Query, react-pdf |
| Infrastructure | PostgreSQL 16 + pgvector, Redis 7, RabbitMQ 3.13, SeaweedFS (S3-compatible), Docker Compose, GitHub Actions |

## Quick start

You need Docker with Docker Compose v2, and about 4 CPU cores and 8 GB of RAM.

1. Create your settings file:

   ```bash
   cp .env.example .env
   ```

2. Edit `.env`:
   - Set `OPENAI_API_KEY` to your OpenAI key.
   - Replace every `change-me` value with your own random strings. `JWT_SECRET` must be at least 32 characters.
   - Optional: put your email in `ADMIN_EMAILS` to get the admin page.

3. Start everything:

   ```bash
   docker compose up --build
   ```

   The first build takes a while: it downloads the embedding model into the AI Service image.

4. Open <http://localhost:3000>, create an account and upload a PDF.

API docs (Swagger UI) are at <http://localhost:8080/swagger-ui.html>.

### Running without an OpenAI key or a model download

Two settings in `.env` make the whole system run offline. This is useful for development and CI, or if your OpenAI account has no credit:

```
LLM_PROVIDER=extractive        # answers with the most relevant passages instead of generated text
EMBEDDING_PROVIDER=hash        # word-matching embeddings, no model download
INSTALL_LOCAL_MODEL=false      # smaller AI image without PyTorch
```

Answers still come with page citations, but they are quoted passages rather than written answers. Search matches shared words rather than meaning.

## Running parts locally (development)

Start the infrastructure with its ports published:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up postgres redis rabbitmq storage
```

Then start each service in its own terminal:

```bash
cd ai-service && python -m venv .venv && .venv/bin/pip install -r requirements.txt -r requirements-ml.txt && .venv/bin/uvicorn app.main:app --port 8000
```

```bash
cd core-api && mvn spring-boot:run
```

```bash
cd frontend && npm install && npm run dev
```

The frontend runs at <http://localhost:5173> and proxies `/api` to port 8080. Locally, the services use the default passwords in `application.yml` and `app/config.py`, so set the same values in `.env` for the infrastructure (or export the matching environment variables).

## Tests

| What | Command | Covers |
|---|---|---|
| Core API | `cd core-api && mvn verify` | JWT, cache keys, and an end-to-end API test against real PostgreSQL, Redis and RabbitMQ (Testcontainers, needs Docker) |
| AI Service | `cd ai-service && pytest` | PDF extraction, chunking, embeddings, retrieval and streaming logic, prompt rules, service-token check |
| Frontend | `cd frontend && npm test` | SSE stream parser |
| RAG evaluation | `python ai-service/eval/run_eval.py` | 30 questions on a sample handbook against the running system; reports page-citation accuracy and latency |

The evaluation script registers a throwaway user, uploads `eval/handbook.py`'s sample handbook, and checks that each answer cites the page holding the fact.

### Measured results

These numbers were measured with the **offline settings** (`EMBEDDING_PROVIDER=hash`, `LLM_PROVIDER=extractive`) on a 4-core machine, so they test the pipeline rather than answer quality:

| Check | Result |
|---|---|
| Correct page cited in the top 2 | 25 / 30 (83%, target 80%) |
| Unanswerable questions answered "not found" | 2 / 2 |
| Upload accepted (11-page PDF) | 659 ms |
| Processed and ready | about 1 s |
| Answer latency | p50 43 ms, p95 62 ms |

Re-run the evaluation with `EMBEDDING_PROVIDER=local` and `LLM_PROVIDER=openai` and record those numbers too: the real embedding model matches meaning, not just words, and OpenAI adds about 1–3 s per answer.

## API overview

Base path `/api/v1`; everything except register, login and refresh needs `Authorization: Bearer <accessToken>`.

| Method | Endpoint | Purpose |
|---|---|---|
| POST | `/auth/register`, `/auth/login`, `/auth/refresh` | Accounts and tokens (access 15 min, refresh 7 days, rotated on use) |
| GET | `/auth/me` | Current user |
| POST | `/documents` | Upload a PDF (multipart `file`); 202 new, 200 duplicate |
| GET | `/documents`, `/documents/{id}` | List, details |
| GET | `/documents/{id}/file` | The PDF itself |
| GET | `/documents/{id}/events` | SSE progress stream |
| DELETE | `/documents/{id}` | Delete file, chunks and record |
| POST / GET | `/chats` | Start a chat (`{"documentId": ...}` or `{}` for all documents), list chats |
| POST | `/chats/{id}/messages` | Ask; SSE events `citations`, `token`, `done`, `error` |
| GET | `/chats/{id}/messages` | History |
| GET / PATCH | `/admin/users`, `/admin/users/{id}` | Usage per user, enable or disable (admin only) |

Errors always look like `{"status": 404, "error": "NOT_FOUND", "message": "...", "timestamp": "...", "path": "..."}`.

## Design notes

- **Security:** secrets only come from environment variables. Passwords use BCrypt; refresh tokens are stored as SHA-256 hashes. Every query and vector search filters by user ID, and another user's resources return 404, so their existence is not revealed. Uploads are checked by file signature, and storage keys never use the uploaded file name. Document text goes to the LLM inside delimiters, with instructions to ignore commands inside it (prompt-injection defence). Only the frontend (3000) and Core API (8080) ports are published.
- **Reliability:** RabbitMQ messages are acknowledged only after processing succeeds. Failures are retried 3 times with backoff (2, 4, 8 s), then moved to a dead-letter queue and the document is marked FAILED. Retries never duplicate chunks. If OpenAI fails, the question returns a clear error and everything else keeps working.
- **Observability:** JSON logs with a correlation ID that travels from the Core API through RabbitMQ to the AI Service. Prometheus metrics at `/actuator/prometheus` (admin only) and the AI Service's internal `/metrics` (queries, processing time, time to first token, LLM tokens).
- **Caching:** first questions are cached in Redis for 24 hours, keyed by user, the exact set of documents and the normalised question. Documents never change after processing, so a cache hit is always current. Follow-up questions skip the cache because they depend on the conversation.

## Differences from the SRS

| SRS | Here | Why |
|---|---|---|
| MinIO for file storage | SeaweedFS, through the standard S3 API | MinIO stopped publishing Docker images in 2025. The code uses the generic S3 API, so MinIO or AWS S3 work by changing the `S3_*` settings. |
| `/documents/{id}/file` returns a short-lived link | Streams the PDF through the Core API | Storage stays on the internal network and the browser never needs storage credentials. |
| Chunks of about 500 tokens | 2,000 characters with 200 overlap (about 500 / 50 tokens) | Avoids a tokenizer download. |
| Stateless Core API that scales horizontally | Stateless except live progress streams | Progress streams are held in memory. With several Core API instances, give each instance its own status queue (or use Redis pub/sub) so every instance sees every event. |

## Project layout

```
core-api/     Spring Boot service (auth, documents, chats, admin)
ai-service/   FastAPI service (ingestion, retrieval, answers), Alembic migrations, eval/
frontend/     React app and the Nginx config that serves it
docker-compose.yml        the full system
docker-compose.dev.yml    exposes PostgreSQL, RabbitMQ UI and storage for development
.github/workflows/ci.yml  tests, lint, image builds (pushed to GHCR from main)
```
