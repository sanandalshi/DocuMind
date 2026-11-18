"""Create the chunks table with a pgvector embedding column and HNSW index.

Revision ID: 0001
"""

from alembic import op

revision = "0001"
down_revision = None


def upgrade() -> None:
    op.execute("CREATE EXTENSION IF NOT EXISTS vector")
    op.execute(
        """
        CREATE TABLE chunks (
            id          UUID PRIMARY KEY,
            document_id UUID NOT NULL,
            user_id     UUID NOT NULL,
            page        INTEGER NOT NULL,
            chunk_index INTEGER NOT NULL,
            content     TEXT NOT NULL,
            embedding   vector(384) NOT NULL
        )
        """
    )
    op.execute("CREATE INDEX ix_chunks_document_id ON chunks (document_id)")
    op.execute("CREATE INDEX ix_chunks_user_id ON chunks (user_id)")
    op.execute("CREATE INDEX ix_chunks_embedding_hnsw ON chunks USING hnsw (embedding vector_cosine_ops)")


def downgrade() -> None:
    op.execute("DROP TABLE chunks")
