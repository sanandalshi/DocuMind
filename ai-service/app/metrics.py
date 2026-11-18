from prometheus_client import Counter, Gauge, Histogram

DOCUMENTS_PROCESSED = Counter("documind_documents_processed_total", "Documents processed", ["outcome"])
PROCESSING_SECONDS = Histogram(
    "documind_processing_seconds", "Document processing time", buckets=(1, 2, 5, 10, 20, 30, 60, 120, 300)
)
QUERIES = Counter("documind_queries_total", "Questions answered", ["outcome"])
RETRIEVAL_SECONDS = Histogram("documind_retrieval_seconds", "Embedding + vector search time")
FIRST_TOKEN_SECONDS = Histogram("documind_first_token_seconds", "Time to first answer token")
LLM_TOKENS = Counter("documind_llm_tokens_total", "LLM tokens used", ["kind"])
IN_FLIGHT = Gauge("documind_documents_in_flight", "Documents being processed now")
