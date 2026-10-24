package org.reasonnotes.probe;

import java.io.File;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/** Application behavior over the immutable Android repository. */
public final class NoteService {
    private static final Object APPLICATION_STORE_LOCK = new Object();
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

    public record BatchDeleteResult(
            List<UUID> deleted, List<UUID> skipped, Map<UUID, String> failed) {}

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
    private final CurrentNoteStore currentStore;
    private final String originDevice;
    private final Supplier<Instant> clock;
    private final Supplier<UUID> noteIds;

    public NoteService(
            ProbeRepository repository,
            String originDevice,
            Supplier<Instant> clock,
            Supplier<UUID> noteIds) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.currentStore = null;
        this.originDevice = Objects.requireNonNull(originDevice, "originDevice");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.noteIds = Objects.requireNonNull(noteIds, "noteIds");
    }

    public NoteService(
            CurrentNoteStore currentStore,
            String originDevice,
            Supplier<Instant> clock,
            Supplier<UUID> noteIds) {
        this.repository = null;
        this.currentStore = Objects.requireNonNull(currentStore, "currentStore");
        this.originDevice = Objects.requireNonNull(originDevice, "originDevice");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.noteIds = Objects.requireNonNull(noteIds, "noteIds");
    }

    CurrentNoteStore currentStore() {
        if (currentStore == null) throw new IllegalStateException("legacy repository service");
        return currentStore;
    }

    public static NoteService openApplication(
            File filesRoot,
            String originDevice,
            Supplier<Instant> clock,
            Supplier<UUID> noteIds) {
        Objects.requireNonNull(filesRoot, "filesRoot");
        synchronized (APPLICATION_STORE_LOCK) {
            File active = new File(filesRoot, "reasonnotes-v2");
            File legacyRoot = new File(filesRoot, "probe-repository");
            if (legacyRoot.exists()) {
                LegacyRepositoryMigrator.migrate(
                        ProbeRepository.openForDisplay(legacyRoot),
                        new File(filesRoot, "reasonnotes-v2.staging"),
                        active);
            }
            return new NoteService(
                    CurrentNoteStore.open(active, originDevice),
                    originDevice, clock, noteIds);
        }
    }

    public List<NoteSummary> listNotes(String query) {
        if (currentStore != null) return listCurrentNotes(query);
        String queryKey = searchKey(query == null ? "" : query);
        ArrayList<NoteSummary> summaries = new ArrayList<>();
        // Reconstruct the full revision graph once. The old code reloaded and
        // reparsed every immutable history file separately for every note;
        // on a phone this made even a dozen notes take seconds to appear.
        Map<UUID, List<RevisionEnvelope>> byNote = new LinkedHashMap<>();
        for (RevisionEnvelope head : repository.heads()) {
            byNote.computeIfAbsent(head.noteId(), ignored -> new ArrayList<>()).add(head);
        }
        for (var entry : byNote.entrySet()) {
            UUID noteId = entry.getKey();
            List<RevisionEnvelope> heads = entry.getValue();
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
        if (currentStore != null) {
            return open(currentStore.saveLocal(noteId, title, content, now()));
        }
        String document = NoteDocument.changeContent(NoteDocument.changeTitle("", title), content);
        RevisionEnvelope revision = repository.createInitialRevision(
                noteId, document, originDevice, now());
        return open(revision);
    }

    public OpenNote openNote(UUID noteId) {
        if (currentStore != null) {
            if (currentStore.conflict(noteId) != null) throw new ConflictRequiredException(noteId);
            StateEnvelope state = currentStore.get(noteId);
            if (state == null) throw new NoteNotFoundException(noteId);
            return open(state);
        }
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
        if (currentStore != null) {
            Set<String> actual = currentStore.headIds(noteId);
            if (!actual.equals(expectedHeadIds)) {
                if (currentStore.conflict(noteId) != null) throw new ConflictRequiredException(noteId);
                throw new IllegalStateException("note changed before save");
            }
            StateEnvelope existing = currentStore.get(noteId);
            if (existing == null) throw new NoteNotFoundException(noteId);
            if (existing.title().equals(title) && existing.body().equals(content)) return open(existing);
            return open(currentStore.saveLocal(noteId, title, content, now()));
        }
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

    public void deleteNote(UUID noteId, Set<String> expectedHeadIds) {
        if (currentStore != null) {
            Set<String> actual = currentStore.headIds(noteId);
            if (!actual.equals(expectedHeadIds)) {
                if (currentStore.conflict(noteId) != null) throw new ConflictRequiredException(noteId);
                throw new IllegalStateException("note changed before deletion");
            }
            if (actual.isEmpty()) throw new NoteNotFoundException(noteId);
            currentStore.deleteLocal(noteId, now());
            return;
        }
        List<RevisionEnvelope> heads = repository.requireHeads(noteId, expectedHeadIds);
        if (heads.size() != 1) throw new ConflictRequiredException(noteId);
        if (heads.get(0).deleted()) throw new NoteNotFoundException(noteId);
        repository.createRevision(noteId, expectedHeadIds, "", originDevice, now(), true);
    }

    /** Deliberate irreversible conflict-screen action, never bulk selection. */
    public void permanentlyDeleteConflict(ConflictSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (currentStore != null) {
            requireCurrentSnapshot(snapshot);
            currentStore.deleteLocal(snapshot.noteId(), now());
            return;
        }
        repository.purgeConflictedNote(
                snapshot.noteId(), snapshot.observedHeadIds());
    }

    public BatchDeleteResult deleteNotes(Set<UUID> noteIds) {
        if (currentStore == null) {
            ProbeRepository.BatchDeleteResult result = repository.deleteNotes(noteIds, originDevice, now());
            return new BatchDeleteResult(result.deleted(), result.skipped(), result.failed());
        }
        ArrayList<UUID> deleted = new ArrayList<>();
        ArrayList<UUID> skipped = new ArrayList<>();
        Map<UUID, String> failed = new LinkedHashMap<>();
        for (UUID noteId : new TreeSet<>(noteIds)) {
            try {
                if (currentStore.conflict(noteId) != null || currentStore.get(noteId) == null) {
                    skipped.add(noteId);
                } else {
                    currentStore.deleteLocal(noteId, now());
                    deleted.add(noteId);
                }
            } catch (RuntimeException error) {
                failed.put(noteId, error.getMessage());
            }
        }
        return new BatchDeleteResult(List.copyOf(deleted), List.copyOf(skipped), Map.copyOf(failed));
    }

    public ConflictSnapshot inspectConflict(UUID noteId) {
        if (currentStore != null) {
            CurrentNoteStore.Conflict conflict = currentStore.conflict(noteId);
            if (conflict == null) throw new ConflictRequiredException(noteId);
            ArrayList<ConflictCandidate> candidates = new ArrayList<>();
            TreeSet<String> observed = new TreeSet<>();
            for (StateEnvelope state : conflict.candidates()) {
                observed.add(state.changeId());
                candidates.add(new ConflictCandidate(
                        state.changeId(), state.originDevice(), state.modifiedUtc(), false,
                        NoteDocument.changeContent(NoteDocument.changeTitle("", state.title()), state.body()),
                        state.title().isEmpty() ? "Untitled" : state.title()));
            }
            return new ConflictSnapshot(noteId, Set.copyOf(observed), List.copyOf(candidates));
        }
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

    public OpenNote resolveCurrentConflict(ConflictSnapshot snapshot, String selectedChangeId) {
        Objects.requireNonNull(snapshot, "snapshot");
        requireCurrentSnapshot(snapshot);
        return open(currentStore.resolve(snapshot.noteId(), selectedChangeId, now()));
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

    private OpenNote open(StateEnvelope state) {
        String document = NoteDocument.changeContent(
                NoteDocument.changeTitle("", state.title()), state.body());
        return new OpenNote(
                state.noteId(), state.changeId(), Set.of(state.changeId()), document,
                state.title().isEmpty() ? "Untitled" : state.title(), state.body());
    }

    private List<NoteSummary> listCurrentNotes(String query) {
        String queryKey = searchKey(query == null ? "" : query);
        ArrayList<NoteSummary> summaries = new ArrayList<>();
        Set<UUID> listed = new java.util.HashSet<>();
        for (StateEnvelope state : currentStore.list()) {
            CurrentNoteStore.Conflict conflict = currentStore.conflict(state.noteId());
            String searchable = conflict == null
                    ? state.title() + "\n" + state.body()
                    : conflict.candidates().stream()
                            .map(value -> value.title() + "\n" + value.body())
                            .reduce("", (left, right) -> left + "\n" + right);
            if (!queryKey.isEmpty() && !searchKey(searchable).contains(queryKey)) continue;
            summaries.add(new NoteSummary(
                    state.noteId(), state.title().isEmpty() ? "Untitled" : state.title(),
                    preview(state.body()), conflict != null, state.modifiedUtc()));
            listed.add(state.noteId());
        }
        for (CurrentNoteStore.Conflict conflict : currentStore.conflicts()) {
            if (listed.contains(conflict.noteId())) continue;
            StateEnvelope display = conflict.candidates().get(0);
            String searchable = conflict.candidates().stream()
                    .map(value -> value.title() + "\n" + value.body())
                    .reduce("", (left, right) -> left + "\n" + right);
            if (!queryKey.isEmpty() && !searchKey(searchable).contains(queryKey)) continue;
            Instant modified = conflict.candidates().stream().map(StateEnvelope::modifiedUtc)
                    .max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
            summaries.add(new NoteSummary(
                    display.noteId(), display.title().isEmpty() ? "Untitled" : display.title(),
                    preview(display.body()), true, modified));
        }
        summaries.sort(Comparator.comparing(NoteSummary::modifiedUtc)
                .thenComparing(item -> item.noteId().toString()).reversed());
        return List.copyOf(summaries);
    }

    private void requireCurrentSnapshot(ConflictSnapshot snapshot) {
        if (currentStore == null) throw new IllegalStateException("current store is unavailable");
        Set<String> actual = currentStore.headIds(snapshot.noteId());
        if (!actual.equals(snapshot.observedHeadIds())) {
            ConflictSnapshot refreshed;
            try { refreshed = inspectConflict(snapshot.noteId()); }
            catch (ConflictRequiredException noLongerConflicted) {
                refreshed = new ConflictSnapshot(snapshot.noteId(), actual, List.of());
            }
            throw new StaleConflictException(refreshed, null);
        }
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
