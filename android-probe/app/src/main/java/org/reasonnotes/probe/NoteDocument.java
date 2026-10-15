package org.reasonnotes.probe;

import java.util.Objects;

/** Lossless title/body projection over the synchronized Markdown document. */
public final class NoteDocument {
    public record Parts(
            String title,
            String content,
            String prefix,
            String heading,
            String lineEnding,
            String gap,
            boolean hasTitle,
            String original) {}

    private NoteDocument() {}

    public static Parts split(String document) {
        Objects.requireNonNull(document, "document");
        int offset = 0;
        while (offset < document.length()) {
            int lineEnd = offset;
            while (lineEnd < document.length()
                    && document.charAt(lineEnd) != '\n'
                    && document.charAt(lineEnd) != '\r') {
                lineEnd++;
            }
            String ending = "";
            int next = lineEnd;
            if (next < document.length()) {
                if (document.charAt(next) == '\r'
                        && next + 1 < document.length()
                        && document.charAt(next + 1) == '\n') {
                    ending = "\r\n";
                    next += 2;
                } else {
                    ending = String.valueOf(document.charAt(next));
                    next++;
                }
            }
            String heading = document.substring(offset, lineEnd);
            if (!heading.strip().isEmpty()) {
                String remainder = document.substring(next);
                String gap = !ending.isEmpty() && remainder.startsWith(ending) ? ending : "";
                String title = heading.strip().replaceFirst("^#+", "").strip();
                if (title.isEmpty()) title = "Untitled";
                return new Parts(
                        title,
                        remainder.substring(gap.length()),
                        document.substring(0, offset),
                        heading,
                        ending,
                        gap,
                        true,
                        document);
            }
            offset = next;
        }
        return new Parts("", document, "", "", "", "", false, document);
    }

    public static String changeTitle(String document, String title) {
        Objects.requireNonNull(title, "title");
        String value = title.replace('\r', ' ').replace('\n', ' ').strip();
        if (value.isEmpty()) value = "Untitled";
        Parts parts = split(document);
        if (!parts.hasTitle()) return "# " + value + "\n\n" + document;
        return parts.prefix() + "# " + value + parts.lineEnding() + parts.gap() + parts.content();
    }

    public static String changeContent(String document, String content) {
        Objects.requireNonNull(content, "content");
        Parts parts = split(document);
        if (!parts.hasTitle()) return "# Untitled\n\n" + content;
        String ending = parts.lineEnding();
        String gap = parts.gap();
        if (!content.isEmpty() && ending.isEmpty()) {
            ending = "\n";
            gap = "\n";
        } else if (!content.isEmpty() && parts.content().isEmpty() && gap.isEmpty()) {
            gap = ending.isEmpty() ? "\n" : ending;
        }
        return parts.prefix() + parts.heading() + ending + gap + content;
    }
}
