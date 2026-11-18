from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str = "postgresql+psycopg://documind:documind@localhost:5432/documind"
    rabbitmq_url: str = "amqp://guest:guest@localhost:5672/"

    s3_endpoint: str = "http://localhost:8333"
    s3_access_key: str = "documind"
    s3_secret_key: str = "documind-secret"
    s3_bucket: str = "documents"
    s3_region: str = "us-east-1"

    # Shared secret the Core API sends in X-Service-Token
    service_token: str = "change-me-service-token"

    # "local" = sentence-transformers all-MiniLM-L6-v2 (default)
    # "hash"  = dependency-free hashing embedder for offline development and CI
    embedding_provider: str = "local"
    embedding_model: str = "sentence-transformers/all-MiniLM-L6-v2"
    embedding_dim: int = 384

    # "openai" (default) or "extractive" (no API key: answers with the best passages)
    llm_provider: str = "openai"
    openai_api_key: str = ""
    openai_model: str = "gpt-4o-mini"
    llm_timeout_seconds: float = 30.0

    chunk_size_chars: int = 2000  # about 500 tokens
    chunk_overlap_chars: int = 200  # about 50 tokens
    top_k: int = 5
    min_similarity: float = 0.2
    max_retries: int = 3
    max_pages: int = 300


@lru_cache
def get_settings() -> Settings:
    return Settings()
