"""End-to-end RAG evaluation against a running DocuMind (NFR-21, acceptance criteria).

Usage:  python eval/run_eval.py [--base http://localhost:3000/api/v1]

Registers a throwaway user, uploads the sample handbook, waits until it is processed,
asks 30 questions and reports how many answers cite the correct page.
"""

import argparse
import json
import os
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid

sys.path.insert(0, os.path.dirname(__file__))
from handbook import QUESTIONS, UNANSWERABLE, build_pdf  # noqa: E402


def call(method, url, token=None, body=None, raw=None, content_type="application/json"):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", content_type)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=60) as resp:
        return resp.read().decode()


def multipart(path):
    boundary = uuid.uuid4().hex
    with open(path, "rb") as f:
        content = f.read()
    body = (
        (
            f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="northwind-handbook.pdf"\r\n'
            f"Content-Type: application/pdf\r\n\r\n"
        ).encode()
        + content
        + f"\r\n--{boundary}--\r\n".encode()
    )
    return body, f"multipart/form-data; boundary={boundary}"


def ask(base, token, chat_id, question):
    """Read the SSE stream and return (answer, citations, latency_ms). Waits out rate limits."""
    while True:
        start = time.perf_counter()
        try:
            stream = call("POST", f"{base}/chats/{chat_id}/messages", token, {"question": question})
            break
        except urllib.error.HTTPError as err:
            if err.code != 429:
                raise
            wait = int(err.headers.get("Retry-After", "10"))
            print(f"      (rate limited, waiting {wait} s)")
            time.sleep(wait + 1)
    answer, citations, event = [], [], None
    for line in stream.splitlines():
        if line.startswith("event:"):
            event = line[6:].strip()
        elif line.startswith("data:"):
            data = json.loads(line[5:])
            if event == "citations":
                citations = data["citations"]
            elif event == "token":
                answer.append(data["text"])
            elif event == "error":
                raise RuntimeError(f"Answer failed: {data}")
    return "".join(answer), citations, int((time.perf_counter() - start) * 1000)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:3000/api/v1")
    args = parser.parse_args()
    base = args.base

    email = f"eval-{uuid.uuid4().hex[:8]}@example.com"
    call("POST", f"{base}/auth/register", body={"name": "Eval", "email": email, "password": "eval-password-123"})
    token = json.loads(call("POST", f"{base}/auth/login", body={"email": email, "password": "eval-password-123"}))[
        "accessToken"
    ]

    pdf = os.path.join(tempfile.mkdtemp(), "northwind-handbook.pdf")
    build_pdf(pdf)
    body, ctype = multipart(pdf)
    upload_start = time.perf_counter()
    doc = json.loads(call("POST", f"{base}/documents", token, raw=body, content_type=ctype))
    print(f"Upload accepted in {int((time.perf_counter() - upload_start) * 1000)} ms")

    for _ in range(120):
        status = json.loads(call("GET", f"{base}/documents/{doc['id']}", token))["status"]
        if status in ("READY", "FAILED"):
            break
        time.sleep(1)
    print(f"Document status: {status} after {int(time.perf_counter() - upload_start)} s")
    if status != "READY":
        sys.exit(1)

    chat = json.loads(call("POST", f"{base}/chats", token, {"documentId": doc["id"]}))
    right_page = keyword_hits = 0
    latencies = []
    for question, page, keyword in QUESTIONS:
        # a fresh chat per question so history does not influence retrieval
        chat = json.loads(call("POST", f"{base}/chats", token, {"documentId": doc["id"]}))
        answer, citations, ms = ask(base, token, chat["id"], question)
        latencies.append(ms)
        cited_pages = [c["page"] for c in citations[:2]]
        ok = page in cited_pages
        right_page += ok
        keyword_hits += keyword.lower() in answer.lower()
        print(f"{'PASS' if ok else 'FAIL'}  p{page} cited={cited_pages}  {ms:>5} ms  {question}")

    print("\nUnanswerable questions (meaningful with LLM_PROVIDER=openai):")
    for question in UNANSWERABLE:
        answer, _, _ = ask(base, token, chat["id"], question)
        print(f"  {question}\n    -> {answer[:120]!r}")

    latencies.sort()
    n = len(QUESTIONS)
    print(f"\nCorrect page cited (top 2): {right_page}/{n} = {100 * right_page / n:.0f}%  (target: 80%)")
    print(f"Answer contains expected fact: {keyword_hits}/{n}")
    print(f"Answer latency p50={latencies[n // 2]} ms  p95={latencies[int(n * 0.95) - 1]} ms")
    sys.exit(0 if right_page / n >= 0.8 else 1)


if __name__ == "__main__":
    main()
