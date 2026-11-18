from dataclasses import dataclass

import pymupdf


@dataclass
class Page:
    number: int  # 1-based
    text: str


class TooManyPagesError(ValueError):
    pass


def extract_pages(pdf_bytes: bytes, max_pages: int = 300) -> list[Page]:
    """Extract text page by page, keeping page numbers (FR-12)."""
    pages: list[Page] = []
    with pymupdf.open(stream=pdf_bytes, filetype="pdf") as doc:
        if doc.page_count > max_pages:
            raise TooManyPagesError(f"The PDF has {doc.page_count} pages; the limit is {max_pages}")
        for index, page in enumerate(doc):
            text = page.get_text("text").strip()
            if text:
                pages.append(Page(number=index + 1, text=text))
    return pages
