from fastapi.testclient import TestClient

from app.main import app, settings


class FakeRag:
    async def answer(self, question, user_id, doc_ids, history):
        yield {"type": "citations", "citations": []}
        yield {"type": "token", "text": "hi"}
        yield {"type": "done", "notFound": False}


def client():
    app.state.rag = FakeRag()
    return TestClient(app)  # not used as a context manager: lifespan (DB, RabbitMQ) is skipped


BODY = {"question": "q", "userId": "00000000-0000-0000-0000-000000000001", "documentIds": []}


def test_query_requires_service_token():
    assert client().post("/internal/query", json=BODY).status_code == 401


def test_query_streams_ndjson():
    resp = client().post("/internal/query", json=BODY, headers={"X-Service-Token": settings.service_token})
    assert resp.status_code == 200
    lines = resp.text.strip().split("\n")
    assert len(lines) == 3 and '"token"' in lines[1]
