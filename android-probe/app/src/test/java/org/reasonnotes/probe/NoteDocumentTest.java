package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NoteDocumentTest {
    @Test
    public void splitUsesFirstNonemptyMarkdownLineWithoutRewritingSource() {
        String source = "\n  \n## Zażółć gęślą\r\n\r\nBody\nnext";

        NoteDocument.Parts parts = NoteDocument.split(source);

        assertEquals("Zażółć gęślą", parts.title());
        assertEquals("Body\nnext", parts.content());
        assertEquals("\n  \n", parts.prefix());
        assertEquals("## Zażółć gęślą", parts.heading());
        assertEquals("\r\n", parts.lineEnding());
        assertEquals("\r\n", parts.gap());
        assertTrue(parts.hasTitle());
        assertEquals(source, parts.original());
    }

    @Test
    public void splitEmptyDocumentLeavesAllTextAsContent() {
        NoteDocument.Parts parts = NoteDocument.split("\n \n");

        assertEquals("", parts.title());
        assertEquals("\n \n", parts.content());
        assertFalse(parts.hasTitle());
    }

    @Test
    public void deliberateTitleChangePreservesFollowingMarkdownVerbatim() {
        assertEquals(
                "\n# Nowy tytuł\r\n\r\n- item\n",
                NoteDocument.changeTitle("\nOld title\r\n\r\n- item\n", "  Nowy tytuł\n"));
    }

    @Test
    public void titleChangeCreatesHeadingWithoutAbsorbingLegacyText() {
        assertEquals("# Untitled\n\n\n", NoteDocument.changeTitle("\n", " \n "));
    }

    @Test
    public void contentChangePreservesExistingTitleLayout() {
        assertEquals(
                "preface blank?\n\nreplacement",
                NoteDocument.changeContent("preface blank?\n\nold", "replacement"));
        assertEquals(
                "# Heading\n\nbody",
                NoteDocument.changeContent("# Heading", "body"));
    }

    @Test
    public void contentChangeAddsExplicitUntitledHeadingWhenDocumentHasNoTitle() {
        assertEquals("# Untitled\n\nŻółw", NoteDocument.changeContent("\n", "Żółw"));
    }
}
