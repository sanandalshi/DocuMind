import hashlib
import logging
import re
from typing import Protocol

import numpy as np

from .config import Settings

log = logging.getLogger(__name__)

_WORD = re.compile(r"[a-z0-9]+")
_STOPWORDS = frozenset(
    "a an and are as at be by do does for from how i in is it many much of on or the to what when "
    "where which who why will with you your".split()
)


class Embedder(Protocol):
    dim: int

    def embed(self, texts: list[str]) -> list[list[float]]: ...


class LocalEmbedder:
    """sentence-transformers model running on CPU (FR-14)."""

    def __init__(self, model_name: str, dim: int):
        from sentence_transformers import SentenceTransformer

        self.model = SentenceTransformer(model_name, device="cpu")
        self.dim = dim

    def embed(self, texts: list[str]) -> list[list[float]]:
        vectors = self.model.encode(texts, batch_size=32, normalize_embeddings=True)
        return [v.tolist() for v in vectors]


class HashEmbedder:
    """Deterministic hashing-trick embedder (word unigrams + bigrams).

    Needs no model download, so the system runs fully offline and in CI.
    It matches on shared words rather than meaning; use LocalEmbedder in production.
    """

    def __init__(self, dim: int):
        self.dim = dim

    def _features(self, text: str) -> list[str]:
        words = [w for w in _WORD.findall(text.lower()) if w not in _STOPWORDS]
        return words + [f"{a}_{b}" for a, b in zip(words, words[1:], strict=False)]

    def embed(self, texts: list[str]) -> list[list[float]]:
        out = []
        for text in texts:
            vec = np.zeros(self.dim, dtype=np.float32)
            for feature in self._features(text):
                digest = hashlib.md5(feature.encode()).digest()
                bucket = int.from_bytes(digest[:4], "little") % self.dim
                vec[bucket] += 1.0 if digest[4] & 1 else -1.0
            norm = np.linalg.norm(vec)
            out.append((vec / norm if norm else vec).tolist())
        return out


def create_embedder(settings: Settings) -> Embedder:
    if settings.embedding_provider == "hash":
        log.warning("Using the hashing embedder: retrieval matches words, not meaning")
        return HashEmbedder(settings.embedding_dim)
    log.info("Loading embedding model %s", settings.embedding_model)
    return LocalEmbedder(settings.embedding_model, settings.embedding_dim)
