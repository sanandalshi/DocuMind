import sys
from pathlib import Path

import pytest

from app.chunking import chunk_pages
from app.embeddings import HashEmbedder
from app.pdf import Page, TooManyPagesError, extract_pages

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "eval"))
from handbook import PAGES, build_pdf  # noqa: E402


def test_extract_pages_keeps_page_numbers(tmp_path):
    pdf = tmp_path / "handbook.pdf"
    build_pdf(str(pdf))
    pages = extract_pages(pdf.read_bytes())
    assert len(pages) == len(PAGES)
    assert pages[2].number == 3
    assert "24 days of paid annual leave" in " ".join(pages[2].text.split())


def test_chunks_never_span_pages_and_respect_size():
    long_text = "word " * 2000
    chunks = chunk_pages([Page(1, long_text), Page(2, "short page")], size=500, overlap=50)
    assert {c.page for c in chunks} == {1, 2}
    assert all(len(c.content) <= 500 for c in chunks)
    assert [c.index for c in chunks] == list(range(len(chunks)))
    assert chunks[-1].content == "short page"


def test_hash_embedder_is_normalised_and_ranks_shared_words_higher():
    emb = HashEmbedder(384)
    query, related, unrelated = emb.embed(
        ["How many days of annual leave?", "24 days of paid annual leave per year", "Gym membership is paid at 50%"]
    )
    dot = lambda a, b: sum(x * y for x, y in zip(a, b, strict=True))  # noqa: E731
    assert abs(dot(query, query) - 1.0) < 1e-5
    assert dot(query, related) > dot(query, unrelated)


def test_page_limit_is_enforced(tmp_path):
    pdf = tmp_path / "handbook.pdf"
    build_pdf(str(pdf))
    with pytest.raises(TooManyPagesError):
        extract_pages(pdf.read_bytes(), max_pages=5)
