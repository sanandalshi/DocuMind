import uuid

from pgvector.sqlalchemy import Vector
from sqlalchemy import Integer, Text, delete, select
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

from .config import get_settings


class Base(DeclarativeBase):
    pass


class Chunk(Base):
    __tablename__ = "chunks"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    document_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), index=True)
    user_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), index=True)
    page: Mapped[int] = mapped_column(Integer)
    chunk_index: Mapped[int] = mapped_column(Integer)
    content: Mapped[str] = mapped_column(Text)
    embedding = mapped_column(Vector(get_settings().embedding_dim))


engine = create_async_engine(get_settings().database_url, pool_size=5, pool_pre_ping=True)
SessionLocal = async_sessionmaker(engine, expire_on_commit=False)


async def save_chunks(chunks: list[Chunk]) -> None:
    async with SessionLocal() as session:
        session.add_all(chunks)
        await session.commit()


async def delete_document_chunks(session: AsyncSession, document_id: uuid.UUID) -> int:
    result = await session.execute(delete(Chunk).where(Chunk.document_id == document_id))
    await session.commit()
    return result.rowcount or 0


async def search_chunks(
    query_embedding: list[float], user_id: uuid.UUID, document_ids: list[uuid.UUID], k: int
) -> list[tuple[Chunk, float]]:
    """Return the k chunks closest to the query, restricted to this user's documents."""
    distance = Chunk.embedding.cosine_distance(query_embedding)
    stmt = (
        select(Chunk, distance.label("distance"))
        .where(Chunk.user_id == user_id)
        .where(Chunk.document_id.in_(document_ids))
        .order_by(distance)
        .limit(k)
    )
    async with SessionLocal() as session:
        rows = (await session.execute(stmt)).all()
    return [(row.Chunk, 1.0 - float(row.distance)) for row in rows]
