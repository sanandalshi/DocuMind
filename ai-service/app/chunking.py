from dataclasses import dataclass

from langchain_text_splitters import RecursiveCharacterTextSplitter

from .pdf import Page


@dataclass
class TextChunk:
    page: int
    index: int
    content: str


def chunk_pages(pages: list[Page], size: int, overlap: int) -> list[TextChunk]:
    """Split each page into overlapping chunks (FR-13). Chunks never span pages,
    so every chunk has exactly one page number to cite."""
    splitter = RecursiveCharacterTextSplitter(
        chunk_size=size, chunk_overlap=overlap, separators=["\n\n", "\n", ". ", " ", ""]
    )
    chunks: list[TextChunk] = []
    for page in pages:
        for piece in splitter.split_text(page.text):
            piece = piece.strip()
            if piece:
                chunks.append(TextChunk(page=page.number, index=len(chunks), content=piece))
    return chunks
