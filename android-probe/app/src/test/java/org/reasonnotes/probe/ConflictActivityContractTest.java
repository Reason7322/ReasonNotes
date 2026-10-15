package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.Test;

public final class ConflictActivityContractTest {
    private static final UUID NOTE = UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final String LINUX_ONE = "a".repeat(64);
    private static final String LINUX_TWO = "b".repeat(64);
    private static final String ANDROID_DELETE = "c".repeat(64);
    private static final String UNKNOWN = "d".repeat(64);

    @Test
    public void fullCandidatesKeepExactRevisionIdentityAndDeletionState() {
        String full = "# Linux\n\n" + "complete line\n".repeat(400);
        NoteService.ConflictSnapshot snapshot = new NoteService.ConflictSnapshot(
                NOTE,
                Set.of(LINUX_ONE, LINUX_TWO, ANDROID_DELETE, UNKNOWN),
                List.of(
                        candidate(LINUX_ONE, "linux", false, full),
                        candidate(LINUX_TWO, "linux", false, "# Second Linux\n\nbody"),
                        candidate(ANDROID_DELETE, "android", true, ""),
                        candidate(UNKNOWN, "tablet", false, "# Unknown\n\nbody")));

        List<ConflictActivity.CandidatePresentation> views = ConflictActivity.present(snapshot);

        assertEquals(4, views.size());
        assertEquals(full, views.get(0).fullContent());
        assertEquals(LINUX_ONE, views.get(0).revisionId());
        assertEquals("Linux", views.get(0).originLabel());
        assertEquals("Keep Linux · " + LINUX_ONE.substring(0, 12), views.get(0).keepLabel());
        assertEquals("Keep Linux · " + LINUX_TWO.substring(0, 12), views.get(1).keepLabel());
        assertTrue(views.get(2).deleted());
        assertEquals("[Deleted revision]", views.get(2).fullContent());
        assertEquals("Keep Android · " + ANDROID_DELETE.substring(0, 12), views.get(2).keepLabel());
        assertFalse(views.get(3).deleted());
        assertNull(views.get(3).keepLabel());
    }

    private static NoteService.ConflictCandidate candidate(
            String id, String origin, boolean deleted, String body) {
        return new NoteService.ConflictCandidate(
                id,
                origin,
                Instant.parse("2026-10-15T06:00:00Z"),
                deleted,
                body,
                deleted ? "Deleted note" : "Title");
    }
}
