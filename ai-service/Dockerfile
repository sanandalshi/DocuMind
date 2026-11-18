FROM python:3.12-slim

# Set INSTALL_LOCAL_MODEL=false to build a small image that uses EMBEDDING_PROVIDER=hash
ARG INSTALL_LOCAL_MODEL=true
ENV PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1 HF_HOME=/opt/hf

WORKDIR /app
COPY requirements.txt requirements-ml.txt ./
RUN pip install -r requirements.txt
RUN if [ "$INSTALL_LOCAL_MODEL" = "true" ]; then \
      pip install torch --index-url https://download.pytorch.org/whl/cpu && \
      pip install -r requirements-ml.txt && \
      python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('sentence-transformers/all-MiniLM-L6-v2')"; \
    fi

COPY alembic.ini ./
COPY migrations ./migrations
COPY app ./app

RUN useradd --create-home appuser && chown -R appuser /app
USER appuser

EXPOSE 8000
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=5 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')"
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
