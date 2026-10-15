package org.reasonnotes.probe;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/** Application behavior over the immutable Android repository. */
public final class NoteService {
    public record NoteSummary(
            UUID noteId,
            String title,
            String preview,
            boolean conflicted,
            Instant modifiedUtc) {}

    public record OpenNote(
            UUID noteId,
            String revisionId,
            Set<String> baseHeadIds,
            String document,
            String title,
            String content) {}

    public record ConflictCandidate(
            String revisionId,
            String originDevice,
            Instant modifiedUtc,
            boolean deleted,
            String body,
            String title) {}

    public record ConflictSnapshot(
            UUID noteId,
            Set<String> observedHeadIds,
            List<ConflictCandidate> candidates) {}

    public static final class NoteNotFoundException extends IllegalStateException {
        public NoteNotFoundException(UUID noteId) { super("note is not available: " + noteId); }
    }

    public static final class ConflictRequiredException extends IllegalStateException {
        public ConflictRequiredException(UUID noteId) {
            super("explicit conflict resolution is required: " + noteId);
        }
    }

    public static final class StaleConflictException extends IllegalStateException {
        private final ConflictSnapshot refreshed;

        private StaleConflictException(ConflictSnapshot refreshed, Throwable cause) {
            super("conflict changed before resolution", cause);
            this.refreshed = refreshed;
        }

        public ConflictSnapshot refreshed() { return refreshed; }
    }

    private final ProbeRepository repository;
    private final String originDevice;
    private final Supplier<Instant> clock;
    private final Supplier<UUID> noteIds;

    public NoteService(
            ProbeRepository repository,
            String originDevice,
            Supplier<Instant> clock,
            Supplier<UUID> noteIds) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.originDevice = Objects.requireNonNull(originDevice, "originDevice");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.noteIds = Objects.requireNonNull(noteIds, "noteIds");
    }

    public List<NoteSummary> listNotes(String query) {
        String queryKey = searchKey(query == null ? "" : query);
        ArrayList<NoteSummary> summaries = new ArrayList<>();
        for (UUID noteId : repository.noteIds()) {
            List<RevisionEnvelope> heads = repository.heads(noteId);
            if (heads.isEmpty()) continue;
            if (heads.size() == 1 && heads.get(0).deleted()) continue;

            RevisionEnvelope display = heads.stream()
                    .filter(head -> !head.deleted())
                    .max(Comparator.comparing(RevisionEnvelope::modifiedUtc)
                            .thenComparing(RevisionEnvelope::revisionId))
                    .orElse(null);
            String body = display == null ? "" : display.body();
            String searchable = heads.stream()
                    .filter(head -> !head.deleted())
                    .map(RevisionEnvelope::body)
                    .reduce("", (left, right) -> left + "\n" + right);
            if (!queryKey.isEmpty() && !searchKey(searchable).contains(queryKey)) continue;

            Instant modified = heads.stream()
                    .map(RevisionEnvelope::modifiedUtc)
                    .max(Comparator.naturalOrder())
                    .orElse(Instant.EPOCH);
            summaries.add(new NoteSummary(
                    noteId,
                    display == null ? "Deletion conflict" : title(body),
                    display == null ? "" : preview(NoteDocument.split(body).content()),
                    heads.size() > 1,
                    modified));
        }
        summaries.sort(Comparator.comparing(NoteSummary::modifiedUtc)
                .thenComparing(item -> item.noteId().toString())
                .reversed());
        return List.copyOf(summaries);
    }

    public OpenNote createNote(String title, String content) {
        UUID noteId = noteIds.get();
        String document = NoteDocument.changeContent(NoteDocument.changeTitle("", title), content);
        RevisionEnvelope revision = repository.createRevision(
                noteId, Set.of(), document, originDevice, now(), false);
        return open(revision);
    }

    public OpenNote openNote(UUID noteId) {
        List<RevisionEnvelope> heads = repository.heads(noteId);
        if (heads.size() > 1) throw new ConflictRequiredException(noteId);
        if (heads.size() != 1 || heads.get(0).deleted()) throw new NoteNotFoundException(noteId);
        return open(heads.get(0));
    }

    public OpenNote saveNote(
            UUID noteId,
            Set<String> expectedHeadIds,
            String title,
            String content) {
        List<RevisionEnvelope> heads = repository.requireHeads(noteId, expectedHeadIds);
        if (heads.size() != 1) throw new ConflictRequiredException(noteId);
        RevisionEnvelope current = heads.get(0);
        if (current.deleted()) throw new NoteNotFoundException(noteId);
        String document = NoteDocument.changeContent(
                NoteDocument.changeTitle(current.body(), title), content);
        if (document.equals(current.body())) return open(current);
        RevisionEnvelope revision = repository.createRevision(
                noteId, expectedHeadIds, document, originDevice, now(), false);
        return open(revision);
    }

    public RevisionEnvelope deleteNote(UUID noteId, Set<String> expectedHeadIds) {
        List<RevisionEnvelope> heads = repository.requireHeads(noteId, expectedHeadIds);
        if (heads.size() != 1) throw new ConflictRequiredException(noteId);
        if (heads.get(0).deleted()) throw new NoteNotFoundException(noteId);
        return repository.createRevision(
                noteId, expectedHeadIds, "", originDevice, now(), true);
    }

    public ConflictSnapshot inspectConflict(UUID noteId) {
        List<RevisionEnvelope> heads = repository.heads(noteId);
        if (heads.size() < 2) throw new ConflictRequiredException(noteId);
        ArrayList<ConflictCandidate> candidates = new ArrayList<>();
        TreeSet<String> observed = new TreeSet<>();
        for (RevisionEnvelope head : heads) {
            observed.add(head.revisionId());
            candidates.add(new ConflictCandidate(
                    head.revisionId(),
                    head.originDevice(),
                    head.modifiedUtc(),
                    head.deleted(),
                    head.body(),
                    head.deleted() ? "Deleted note" : title(head.body())));
        }
        return new ConflictSnapshot(noteId, Set.copyOf(observed), List.copyOf(candidates));
    }

    public RevisionEnvelope resolveConflict(ConflictSnapshot snapshot, String selectedRevisionId) {
        Objects.requireNonNull(snapshot, "snapshot");
        try {
            return repository.resolveConflict(
                    snapshot.noteId(),
                    snapshot.observedHeadIds(),
                    selectedRevisionId,
                    originDevice,
                    now());
        } catch (ProbeRepository.StaleHeadsException error) {
            ConflictSnapshot refreshed;
            try {
                refreshed = inspectConflict(snapshot.noteId());
            } catch (ConflictRequiredException noLongerConflicted) {
                refreshed = new ConflictSnapshot(
                        snapshot.noteId(), repository.headIds(snapshot.noteId()), List.of());
            }
            throw new StaleConflictException(refreshed, error);
        }
    }

    public void skipConflict(ConflictSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
    }

    private OpenNote open(RevisionEnvelope revision) {
        NoteDocument.Parts parts = NoteDocument.split(revision.body());
        return new OpenNote(
                revision.noteId(),
                revision.revisionId(),
                Set.of(revision.revisionId()),
                revision.body(),
                parts.title().isEmpty() ? "Untitled" : parts.title(),
                parts.content());
    }

    private Instant now() {
        Instant value = clock.get();
        return Instant.ofEpochSecond(value.getEpochSecond());
    }

    private static String title(String body) {
        String value = NoteDocument.split(body).title();
        return value.isEmpty() ? "Untitled" : value;
    }

    private static String preview(String body) {
        String value = body.strip().replaceAll("\\s+", " ");
        return value.length() <= 240 ? value : value.substring(0, 240);
    }

    private static String searchKey(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
