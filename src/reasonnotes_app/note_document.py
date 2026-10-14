"""Edit a note's title and body without changing its Markdown storage format.

The first non-empty source line has historically been the note title.
Keep its original bytes/text layout until the user deliberately edits that
field, so opening an older note does not silently rewrite its history.
"""

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class NoteParts:
    title: str
    content: str
    prefix: str
    heading: str
    line_ending: str
    gap: str
    has_title: bool


def split_note(document: str) -> NoteParts:
    offset = 0
    for line in document.splitlines(keepends=True):
        heading = line.rstrip("\r\n")
        ending = line[len(heading):]
        if heading.strip():
            remainder = document[offset + len(line):]
            # Treat one optional blank separator as layout, not editable body.
            gap = ending if ending and remainder.startswith(ending) else ""
            return NoteParts(
                title=heading.strip().lstrip("#").strip() or "Untitled",
                content=remainder[len(gap):],
                prefix=document[:offset],
                heading=heading,
                line_ending=ending,
                gap=gap,
                has_title=True,
            )
        offset += len(line)
    return NoteParts("", document, "", "", "", "", False)


def change_title(document: str, title: str) -> str:
    """Rename the first logical line; retain all following Markdown verbatim."""
    value = title.replace("\r", " ").replace("\n", " ").strip() or "Untitled"
    parts = split_note(document)
    if not parts.has_title:
        return f"# {value}\n\n" + document
    return (
        parts.prefix + f"# {value}" + parts.line_ending + parts.gap + parts.content
    )


def change_content(document: str, content: str) -> str:
    """Replace only the writing area, preserving the current title line."""
    parts = split_note(document)
    if not parts.has_title:
        # A note started with body text before being named still has an
        # explicit editable title and never absorbs the first body line.
        return "# Untitled\n\n" + content

    ending, gap = parts.line_ending, parts.gap
    if content and not ending:
        ending, gap = "\n", "\n"
    elif content and not parts.content and not gap:
        gap = ending or "\n"
    return parts.prefix + parts.heading + ending + gap + content
