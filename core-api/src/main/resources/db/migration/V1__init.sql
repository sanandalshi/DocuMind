CREATE TABLE users (
    id            UUID PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL
);

CREATE TABLE refresh_tokens (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked    BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE TABLE documents (
    id             UUID PRIMARY KEY,
    user_id        UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name           VARCHAR(255) NOT NULL,
    storage_key    VARCHAR(255) NOT NULL,
    file_hash      VARCHAR(64)  NOT NULL,
    size_bytes     BIGINT       NOT NULL,
    page_count     INTEGER,
    chunk_count    INTEGER,
    status         VARCHAR(20)  NOT NULL,
    progress       INTEGER      NOT NULL DEFAULT 0,
    failure_reason VARCHAR(500),
    processing_ms  BIGINT,
    created_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_documents_user ON documents (user_id, created_at DESC);
CREATE INDEX ix_documents_user_hash ON documents (user_id, file_hash);

CREATE TABLE chats (
    id          UUID PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    document_id UUID REFERENCES documents (id) ON DELETE CASCADE,
    title       VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_chats_user ON chats (user_id, created_at DESC);

CREATE TABLE messages (
    id         UUID PRIMARY KEY,
    chat_id    UUID        NOT NULL REFERENCES chats (id) ON DELETE CASCADE,
    role       VARCHAR(20) NOT NULL,
    content    TEXT        NOT NULL,
    citations  JSONB,
    latency_ms BIGINT,
    cached     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_messages_chat ON messages (chat_id, created_at);
