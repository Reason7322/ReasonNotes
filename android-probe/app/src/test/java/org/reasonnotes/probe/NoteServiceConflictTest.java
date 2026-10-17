package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class NoteServiceConflictTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final UUID NOTE = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private static final class AdvancingClock implements Supplier<Instant> {
        private Instant value = Instant.parse("2026-10-15T03:00:00Z");
        @Override public Instant get() {
            Instant result = value;
            value = value.plusSeconds(1);
            return result;
        }
    }

    private static NoteService service(ProbeRepository repository) {
        ArrayDeque<UUID> ids = new ArrayDeque<>(List.of(NOTE));
        return new NoteService(repository, "android", new AdvancingClock(), ids::removeFirst);
    }

    private record ConflictFixture(
            ProbeRepository repository,
            NoteService service,
            RevisionEnvelope edit,
            RevisionEnvelope deletion) {}

    private ConflictFixture editDeleteConflict(boolean androidDeletes) throws Exception {
        ProbeRepository repository = ProbeRepository.open(temporary.newFolder("repo"));
        NoteService service = service(repository);
        NoteService.OpenNote base = service.createNote("Base", "original");
        String editOrigin = androidDeletes ? "linux" : "android";
        String deleteOrigin = androidDeletes ? "android" : "linux";
        RevisionEnvelope edit = RevisionEnvelope.create(
                NOTE,
                List.of(base.revisionId()),
                editOrigin,
                Instant.parse("2026-10-15T03:10:00Z"),
                false,
                "# Full edit\n\nComplete body that must not be truncated");
        RevisionEnvelope deletion = RevisionEnvelope.create(
                NOTE,
                List.of(base.revisionId()),
                deleteOrigin,
                Instant.parse("2026-10-15T03:10:01Z"),
                true,
                "");
        repository.importEnvelope(edit.serialize(), "edit.md");
        repository.importEnvelope(deletion.serialize(), "delete.md");
        return new ConflictFixture(repository, service, edit, deletion);
    }

    @Test
    public void linuxEditVersusAndroidDeleteRemainsDiscoverableWithoutCurrentView() throws Exception {
        ConflictFixture fixture = editDeleteConflict(true);
        Files.deleteIfExists(new File(fixture.repository().root(), "current/" + NOTE + ".md").toPath());

        NoteService.NoteSummary summary = fixture.service().listNotes("").get(0);
        NoteService.ConflictSnapshot snapshot = fixture.service().inspectConflict(NOTE);

        assertTrue(summary.conflicted());
        assertEquals("Full edit", summary.title());
        assertEquals(Set.of(fixture.edit().revisionId(), fixture.deletion().revisionId()), snapshot.observedHeadIds());
        assertEquals(2, snapshot.candidates().size());
        assertEquals(
                "# Full edit\n\nComplete body that must not be truncated",
                snapshot.candidates().stream().filter(candidate -> !candidate.deleted()).findFirst().orElseThrow().body());
        assertTrue(snapshot.candidates().stream().anyMatch(NoteService.ConflictCandidate::deleted));
        assertThrows(NoteService.ConflictRequiredException.class, () -> fixture.service().openNote(NOTE));
    }

    @Test
    public void androidEditVersusLinuxDeleteRemainsDiscoverableAndKeepingEditRestoresNote() throws Exception {
        ConflictFixture fixture = editDeleteConflict(false);
        NoteService.ConflictSnapshot snapshot = fixture.service().inspectConflict(NOTE);

        RevisionEnvelope resolution = fixture.service().resolveConflict(snapshot, fixture.edit().revisionId());

        assertEquals(snapshot.observedHeadIds(), Set.copyOf(resolution.parents()));
        assertFalse(resolution.deleted());
        assertEquals("Full edit", fixture.service().openNote(NOTE).title());
        assertFalse(fixture.service().listNotes("").get(0).conflicted());
    }

    @Test
    public void keepingDeletionHidesNoteOnlyAfterResolutionIsDurable() throws Exception {
        ConflictFixture fixture = editDeleteConflict(true);
        assertEquals(1, fixture.service().listNotes("").size());
        NoteService.ConflictSnapshot snapshot = fixture.service().inspectConflict(NOTE);

        RevisionEnvelope resolution = fixture.service().resolveConflict(snapshot, fixture.deletion().revisionId());

        assertTrue(resolution.deleted());
        assertTrue(fixture.service().listNotes("").isEmpty());
        assertEquals(resolution, fixture.repository().current(NOTE));
    }

    @Test
    public void exactSameOriginRevisionMustBeSelectedAndSkipWritesNothing() throws Exception {
        ConflictFixture fixture = editDeleteConflict(true);
        RevisionEnvelope secondLinux = RevisionEnvelope.create(
                NOTE,
                List.of(fixture.edit().parents().get(0)),
                "linux",
                Instant.parse("2026-10-15T03:10:02Z"),
                false,
                "# Other Linux\n\nother body");
        fixture.repository().importEnvelope(secondLinux.serialize(), "second-linux.md");
        NoteService.ConflictSnapshot snapshot = fixture.service().inspectConflict(NOTE);
        int before = fixture.repository().revisions().size();

        fixture.service().skipConflict(snapshot);

        assertEquals(before, fixture.repository().revisions().size());
        assertEquals(3, snapshot.candidates().size());
        RevisionEnvelope resolution = fixture.service().resolveConflict(snapshot, secondLinux.revisionId());
        assertEquals("# Other Linux\n\nother body", resolution.body());
    }

    @Test
    public void explicitConflictDeletionPurgesAllVersionsButPreservesUnrelatedNotes() throws Exception {
        ConflictFixture fixture = editDeleteConflict(true);
        UUID unrelated = UUID.fromString("55555555-5555-4555-8555-555555555555");
        RevisionEnvelope kept = fixture.repository().createRevision(
                unrelated, Set.of(), "# Other note\n\nuntouched", "android",
                Instant.parse("2026-10-15T04:00:00Z"), false);
        byte[] original = Files.readAllBytes(new File(
                fixture.repository().root(), "history/" + unrelated + "/"
                        + kept.revisionId() + ".md").toPath());
        NoteService.ConflictSnapshot selected = fixture.service().inspectConflict(NOTE);
        assertEquals(3, fixture.repository().revisions().size() - 1);

        fixture.service().permanentlyDeleteConflict(selected);

        assertTrue(fixture.repository().isPurged(NOTE));
        assertFalse(new File(fixture.repository().root(), "history/" + NOTE).exists());
        assertTrue(fixture.repository().heads(NOTE).isEmpty());
        assertTrue(fixture.service().listNotes("").stream()
                .noneMatch(summary -> summary.noteId().equals(NOTE)));
        assertEquals(kept, fixture.repository().current(unrelated));
        assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(
                new File(fixture.repository().root(), "history/" + unrelated + "/"
                        + kept.revisionId() + ".md").toPath())));
        fixture.repository().importEnvelope(fixture.edit().serialize(), "old.md");
        assertTrue(fixture.repository().isPurged(NOTE));
        assertTrue(fixture.repository().heads(NOTE).isEmpty());
    }

    @Test
    public void conflictDeletionRejectsStaleHeadsWithoutWritingAFence() throws Exception {
        ConflictFixture fixture = editDeleteConflict(true);
        NoteService.ConflictSnapshot captured = fixture.service().inspectConflict(NOTE);
        RevisionEnvelope later = RevisionEnvelope.create(
                NOTE, List.of(fixture.edit().parents().get(0)), "linux",
                Instant.parse("2026-10-15T03:10:03Z"), false,
                "# Concurrent third head\n\nother branch");
        fixture.repository().importEnvelope(later.serialize(), "third.md");

        assertThrows(ProbeRepository.StaleHeadsException.class,
                () -> fixture.service().permanentlyDeleteConflict(captured));
        assertFalse(fixture.repository().isPurged(NOTE));
        assertEquals(4, fixture.repository().revisions().size());
        assertEquals(3, fixture.repository().heads(NOTE).size());
    }

    @Test
    public void changedHeadsRejectStaleConflictChoiceAndRefreshInspection() throws Exception {
        ConflictFixture fixture = editDeleteConflict(true);
        NoteService.ConflictSnapshot stale = fixture.service().inspectConflict(NOTE);
        RevisionEnvelope later = RevisionEnvelope.create(
                NOTE,
                List.of(fixture.edit().parents().get(0)),
                "android-secondary",
                Instant.parse("2026-10-15T03:10:03Z"),
                false,
                "# Later\n\nnew head");
        fixture.repository().importEnvelope(later.serialize(), "later.md");
        int before = fixture.repository().revisions().size();

        NoteService.StaleConflictException error = assertThrows(
                NoteService.StaleConflictException.class,
                () -> fixture.service().resolveConflict(stale, fixture.edit().revisionId()));

        assertEquals(before, fixture.repository().revisions().size());
        assertTrue(error.refreshed().observedHeadIds().contains(later.revisionId()));
    }
}
