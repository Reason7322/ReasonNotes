package org.reasonnotes.probe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public final class ProbeRepository {
    public record ImportOutcome(String kind, String revisionId, String detail) {}

    public static final class StaleHeadsException extends IllegalStateException {
        private final Set<String> expected;
        private final Set<String> observed;

        private StaleHeadsException(Set<String> expected, Set<String> observed) {
            super("note heads changed: expected " + expected + ", observed " + observed);
            this.expected = Set.copyOf(expected);
            this.observed = Set.copyOf(observed);
        }

        public Set<String> expected() { return expected; }
        public Set<String> observed() { return observed; }
    }

    private static final ConcurrentMap<String, ReentrantLock> ROOT_LOCKS = new ConcurrentHashMap<>();

    private final File root;
    private final File history;
    private final File state;
    private final File current;
    private final File quarantine;
    private final File purged;
    private final ReentrantLock mutationLock;

    private ProbeRepository(File root) {
        this.root = root;
        this.history = new File(root, "history");
        this.state = new File(root, "state");
        this.current = new File(root, "current");
        this.quarantine = new File(root, "quarantine");
        this.purged = new File(root, "purged");
        String lockKey;
        try {
            lockKey = root.getCanonicalFile().toPath().normalize().toString();
        } catch (IOException error) {
            lockKey = root.getAbsoluteFile().toPath().normalize().toString();
        }
        this.mutationLock = ROOT_LOCKS.computeIfAbsent(lockKey, ignored -> new ReentrantLock());
    }

    /** Open for immediate history-backed listing/editing, without writing the
     * regenerated working views on the UI's critical path. The caller may
     * invoke reconcile() asynchronously; immutable history remains the source
     * of truth for notes and updates. */
    public static ProbeRepository openForDisplay(File root) {
        ProbeRepository repository = new ProbeRepository(root);
        repository.withLock(repository::ensureDirectories);
        return repository;
    }

    public static ProbeRepository open(File root) {
        ProbeRepository repository = new ProbeRepository(root);
        repository.withLock(() -> {
            repository.ensureDirectories();
            repository.reconcileLocked();
        });
        return repository;
    }

    /** Serialize editor-draft writes with permanent purges on this repository. */
    public static <T> T withRepositoryLock(File root, java.util.function.Supplier<T> action) {
        String key;
        try { key = root.getCanonicalFile().toPath().normalize().toString(); }
        catch (IOException error) { key = root.getAbsoluteFile().toPath().normalize().toString(); }
        ReentrantLock lock = ROOT_LOCKS.computeIfAbsent(key, ignored -> new ReentrantLock());
        lock.lock();
        try { return action.get(); }
        finally { lock.unlock(); }
    }

    public File root() { return root; }

    private <T> T withLock(Supplier<T> action) {
        mutationLock.lock();
        try {
            return action.get();
        } finally {
            mutationLock.unlock();
        }
    }

    private void withLock(Runnable action) {
        withLock(() -> {
            action.run();
            return null;
        });
    }

    private void ensureDirectories() {
        for (File directory : List.of(root, history, state, current, quarantine, purged)) {
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IllegalStateException("cannot create repository directory: " + directory);
            }
        }
    }

    private static void durableWrite(File target, byte[] data, boolean replace) throws IOException {
        if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) {
            throw new IOException("cannot create parent directory");
        }
        File temporary = File.createTempFile("." + target.getName() + ".", ".tmp", target.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(data);
                output.flush();
                output.getFD().sync();
            }
            if (!replace && target.exists()) {
                if (!java.util.Arrays.equals(Files.readAllBytes(target.toPath()), data)) {
                    throw new IOException("history revision collision");
                }
                return;
            }
            try {
                if (replace) {
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (AtomicMoveNotSupportedException error) {
                if (replace) {
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temporary.toPath(), target.toPath());
                }
            }
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    /** Generated views are derived from immutable history; rewriting an
     * identical view on every reconciliation adds costly flash syncs. */
    private static void durableWriteIfChanged(File target, byte[] data) throws IOException {
        if (target.isFile() && java.util.Arrays.equals(
                Files.readAllBytes(target.toPath()), data)) return;
        durableWrite(target, data, true);
    }

    private File purgeMarker(UUID noteId) {
        return new File(purged, noteId + ".id");
    }

    public boolean isPurged(UUID noteId) {
        return withLock(() -> purgeMarker(noteId).isFile());
    }

    public Set<UUID> purgedNoteIds() {
        return withLock(this::purgedIdsLocked);
    }

    private Set<UUID> purgedIdsLocked() {
        TreeSet<UUID> result = new TreeSet<>();
        File[] files = purged.listFiles(file -> file.isFile() && file.getName().endsWith(".id"));
        if (files == null) return Set.of();
        for (File file : files) {
            try {
                UUID id = UUID.fromString(file.getName().substring(0, file.getName().length() - 3));
                if ((id + ".id").equals(file.getName())) result.add(id);
            } catch (IllegalArgumentException ignored) {
                // Malformed fences never authorize erasure.
            }
        }
        return Set.copyOf(result);
    }

    private java.util.Set<String> noteRevisionIdsLocked(UUID noteId) throws IOException {
        java.util.Set<String> revisionIds = new java.util.HashSet<>();
        File directory = new File(history, noteId.toString());
        if (!directory.isDirectory()) return revisionIds;
        File[] files = directory.listFiles(file -> file.isFile()
                && file.getName().endsWith(".md"));
        if (files == null) throw new IOException("cannot inspect purge history");
        for (File file : files) {
            String name = file.getName();
            String revisionId = name.substring(0, name.length() - 3);
            if (revisionId.matches("[0-9a-f]{64}")) revisionIds.add(revisionId);
        }
        return revisionIds;
    }

    private void retirePurgedQueueLocked(java.util.Set<String> revisionIds) {
        if (revisionIds.isEmpty()) return;
        new DeliveryLedger(new File(root.getParentFile(), "delivery-state"), 3)
                .forgetRevisions(revisionIds);
        new ReceiptStore(root, 3).forgetRevisions(revisionIds);
    }

    private void finishPurgeLocked(UUID noteId) {
        finishPurgeLocked(noteId, false);
    }

    private void finishPurgeLocked(UUID noteId, boolean queuesRetired) {
        if (!purgeMarker(noteId).isFile()) return;
        File directory = new File(history, noteId.toString());
        try {
            if (!queuesRetired) {
                // Recovery may run after a crash. Retire only the affected
                // revisions before removing their history-to-note mapping.
                retirePurgedQueueLocked(noteRevisionIdsLocked(noteId));
            }
            if (directory.isDirectory()) {
                File[] children = directory.listFiles();
                if (children == null) throw new IOException("cannot inspect note history");
                for (File item : children) {
                    if (!item.isFile() || !item.delete()) {
                        throw new IOException("cannot delete note history member");
                    }
                }
                if (!directory.delete()) throw new IOException("cannot delete history folder");
            }
            Files.deleteIfExists(new File(current, noteId + ".md").toPath());
            Files.deleteIfExists(currentIdPath(noteId).toPath());
            Files.deleteIfExists(new File(root.getParentFile(), "drafts/" + noteId + ".draft").toPath());
        } catch (IOException error) {
            throw new IllegalStateException("cannot permanently purge note", error);
        }
    }

    private void finishAllPurgesLocked() {
        for (UUID noteId : purgedIdsLocked()) finishPurgeLocked(noteId);
    }

    /** Stage all fences before any content removal, retire the corresponding
     * delivery and receipt state once, then physically erase each note. */
    private void purgeManyLocked(Set<UUID> notes) {
        if (notes.isEmpty()) return;
        try {
            for (UUID id : new TreeSet<>(notes)) {
                File marker = purgeMarker(id);
                if (!marker.isFile()) {
                    durableWrite(marker, (id + "\n").getBytes(StandardCharsets.US_ASCII), false);
                }
            }
            java.util.Set<String> revisionIds = new java.util.HashSet<>();
            for (UUID id : notes) revisionIds.addAll(noteRevisionIdsLocked(id));
            retirePurgedQueueLocked(revisionIds);
            for (UUID id : new TreeSet<>(notes)) finishPurgeLocked(id, true);
        } catch (IOException error) {
            throw new IllegalStateException("cannot finish permanent deletion batch", error);
        }
    }

    private void purgeNoteLocked(UUID noteId) {
        File marker = purgeMarker(noteId);
        try {
            if (!marker.isFile()) {
                durableWrite(marker, (noteId + "\n").getBytes(StandardCharsets.US_ASCII), false);
            }
        } catch (IOException error) {
            throw new IllegalStateException("cannot store permanent deletion fence", error);
        }
        finishPurgeLocked(noteId);
    }

    public void purgeNote(UUID noteId) {
        withLock(() -> {
            purgeNoteLocked(noteId);
            reconcileLocked();
        });
    }

    /** Explicit conflict-screen action only. The exact observed heads are
     * checked under the repository lock before committing the durable fence.
     * Ordinary batch deletion continues to skip unresolved conflicts. */
    public void purgeConflictedNote(UUID noteId, Set<String> expectedHeadIds) {
        withLock(() -> {
            RevisionGraph snapshot = graph(load());
            Set<String> actual = headIds(snapshot, noteId);
            if (actual.size() < 2 || !actual.equals(expectedHeadIds)) {
                throw new StaleHeadsException(expectedHeadIds, actual);
            }
            purgeNoteLocked(noteId);
            reconcileLocked();
        });
    }

    /** Accept a deletion fence without requiring this note to be present. */
    public void applyRemotePurges(Set<UUID> noteIds) {
        withLock(() -> {
            purgeManyLocked(noteIds);
            if (!noteIds.isEmpty()) reconcileLocked();
        });
    }

    private Map<String, RevisionEnvelope> load() {
        Map<String, RevisionEnvelope> revisions = new LinkedHashMap<>();
        Set<UUID> purgedIds = purgedIdsLocked();
        File[] noteDirectories = history.listFiles(File::isDirectory);
        if (noteDirectories == null) return revisions;
        java.util.Arrays.sort(noteDirectories, Comparator.comparing(File::getName));
        for (File directory : noteDirectories) {
            try {
                UUID id = UUID.fromString(directory.getName());
                if (purgedIds.contains(id)) continue;
            } catch (IllegalArgumentException invalidHistoryFolder) {
                // Ignore unexpected unrelated folders, never treat them as notes.
                continue;
            }
            File[] files = directory.listFiles(file -> file.isFile() && file.getName().endsWith(".md"));
            if (files == null) continue;
            java.util.Arrays.sort(files, Comparator.comparing(File::getName));
            for (File file : files) {
                try {
                    RevisionEnvelope revision = RevisionEnvelope.parse(Files.readAllBytes(file.toPath()));
                    revisions.put(revision.revisionId(), revision);
                } catch (IOException | IllegalArgumentException ignored) {
                    // Invalid history cannot become current; immutable bytes stay available for diagnosis.
                }
            }
        }
        return revisions;
    }

    private static RevisionGraph graph(Map<String, RevisionEnvelope> revisions) {
        RevisionGraph graph = new RevisionGraph();
        revisions.values().forEach(graph::add);
        return graph;
    }

    public List<RevisionEnvelope> heads(UUID noteId) {
        return withLock(() -> graph(load()).heads(noteId));
    }

    public List<RevisionEnvelope> heads() {
        return withLock(() -> {
            Map<String, RevisionEnvelope> revisions = load();
            RevisionGraph graph = graph(revisions);
            Set<UUID> noteIds = noteIds(revisions);
            ArrayList<RevisionEnvelope> heads = new ArrayList<>();
            noteIds.forEach(noteId -> heads.addAll(graph.heads(noteId)));
            return List.copyOf(heads);
        });
    }

    public List<String> pending(UUID noteId) {
        return withLock(() -> graph(load()).pending(noteId));
    }

    public RevisionEnvelope revision(String revisionId) {
        return withLock(() -> load().get(revisionId));
    }

    public List<RevisionEnvelope> revisions() {
        return withLock(() -> load().values().stream()
                .sorted(Comparator.comparing(RevisionEnvelope::modifiedUtc)
                        .thenComparing(RevisionEnvelope::revisionId))
                .toList());
    }

    public Set<UUID> noteIds() {
        return withLock(() -> Set.copyOf(noteIds(load())));
    }

    private static Set<UUID> noteIds(Map<String, RevisionEnvelope> revisions) {
        Set<UUID> noteIds = new TreeSet<>(Comparator.comparing(UUID::toString));
        revisions.values().forEach(revision -> noteIds.add(revision.noteId()));
        return noteIds;
    }

    public Set<String> headIds(UUID noteId) {
        return withLock(() -> headIds(graph(load()), noteId));
    }

    public List<RevisionEnvelope> requireHeads(UUID noteId, Set<String> expectedHeadIds) {
        return withLock(() -> {
            RevisionGraph graph = graph(load());
            requireExpectedHeads(graph, noteId, expectedHeadIds);
            return graph.heads(noteId);
        });
    }

    private static Set<String> headIds(RevisionGraph graph, UUID noteId) {
        TreeSet<String> values = new TreeSet<>();
        graph.heads(noteId).forEach(revision -> values.add(revision.revisionId()));
        return Set.copyOf(values);
    }

    public Set<String> ancestorRevisionIdsInclusive(Set<String> revisionIds) {
        return withLock(() -> {
            Map<String, RevisionEnvelope> revisions = load();
            TreeSet<String> result = new TreeSet<>();
            ArrayList<String> remaining = new ArrayList<>(revisionIds);
            while (!remaining.isEmpty()) {
                String id = remaining.remove(remaining.size() - 1);
                RevisionEnvelope revision = revisions.get(id);
                if (revision == null || !result.add(id)) continue;
                remaining.addAll(revision.parents());
            }
            return Set.copyOf(result);
        });
    }

    private File currentIdPath(UUID noteId) { return new File(state, "current-" + noteId + ".txt"); }

    public RevisionEnvelope current(UUID noteId) {
        return withLock(() -> currentLocked(noteId, load()));
    }

    private RevisionEnvelope currentLocked(UUID noteId, Map<String, RevisionEnvelope> revisions) {
        File path = currentIdPath(noteId);
        if (!path.isFile()) return null;
        try {
            String id = new String(Files.readAllBytes(path.toPath()), StandardCharsets.US_ASCII).trim();
            return revisions.get(id);
        } catch (IOException error) {
            throw new IllegalStateException("cannot read current state", error);
        }
    }

    public ImportOutcome importEnvelope(byte[] data, String sourceName) {
        final RevisionEnvelope revision;
        try {
            revision = RevisionEnvelope.parse(data);
        } catch (IllegalArgumentException error) {
            withLock(() -> {
                try {
                    String safe = sourceName.replaceAll("[^A-Za-z0-9._-]", "_");
                    durableWrite(new File(quarantine, "rejected-" + safe), data, false);
                } catch (IOException ignored) {
                    // Validation failure remains the primary result.
                }
            });
            return new ImportOutcome("quarantined", null, error.getMessage());
        }
        return withLock(() -> persistImportedLocked(revision, data));
    }

    private ImportOutcome persistImportedLocked(RevisionEnvelope revision, byte[] data) {
        if (purgeMarker(revision.noteId()).isFile()) {
            return new ImportOutcome("purged", revision.revisionId(), "permanently deleted note");
        }
        File path = new File(new File(history, revision.noteId().toString()), revision.revisionId() + ".md");
        // Incoming documents are scanned repeatedly on foreground/resume.
        // A verified duplicate must not create/fsync a temporary history file
        // or reconcile the entire repository. Startup already repairs any
        // interrupted working-view changes from the immutable history.
        try {
            if (path.isFile()) {
                if (!java.util.Arrays.equals(Files.readAllBytes(path.toPath()), data)) {
                    throw new IllegalStateException("existing history disagrees with revision ID");
                }
                return new ImportOutcome("duplicate", revision.revisionId(),
                        "revision already imported");
            }
            durableWrite(path, data, false);
        } catch (IOException error) {
            throw new IllegalStateException("cannot persist immutable history", error);
        }
        reconcileLocked();
        return new ImportOutcome("imported", revision.revisionId(),
                "revision imported durably");
    }

    public RevisionEnvelope createRevision(
            UUID noteId,
            Set<String> expectedHeadIds,
            String body,
            String originDevice,
            Instant modifiedUtc,
            boolean deleted) {
        return withLock(() -> {
            Map<String, RevisionEnvelope> revisions = load();
            RevisionGraph graph = graph(revisions);
            if (purgeMarker(noteId).isFile()) {
                throw new IllegalStateException("note has been permanently deleted");
            }
            requireExpectedHeads(graph, noteId, expectedHeadIds);
            if (!graph.pending(noteId).isEmpty()) {
                throw new IllegalStateException("incomplete history requires synchronization");
            }
            RevisionEnvelope revision = RevisionEnvelope.create(
                    noteId, new ArrayList<>(expectedHeadIds), originDevice, modifiedUtc, deleted, body);
            persistImportedLocked(revision, revision.serialize());
            return revision;
        });
    }

    /** Explicit permanent deletion of selected notes, including any conflicts.
     * Each fence is written before history removal. A partial I/O failure
     * is reported; startup will finish purging fenced IDs before rendering. */
    public record BatchDeleteResult(
            List<UUID> deleted, List<UUID> skipped, Map<UUID, String> failed) {}

    public BatchDeleteResult deleteNotes(
            Set<UUID> selectedNoteIds, String originDevice, Instant modifiedUtc) {
        if (selectedNoteIds.isEmpty()) return new BatchDeleteResult(List.of(), List.of(), Map.of());
        return withLock(() -> {
            Map<String, RevisionEnvelope> existing = load();
            Set<UUID> existingIds = noteIds(existing);
            RevisionGraph snapshot = graph(existing);
            ArrayList<UUID> deleted = new ArrayList<>();
            ArrayList<UUID> skipped = new ArrayList<>();
            Map<UUID, String> failed = new LinkedHashMap<>();
            for (UUID id : new TreeSet<>(selectedNoteIds)) {
                if (!existingIds.contains(id) && !purgeMarker(id).isFile()) {
                    skipped.add(id);
                } else if (purgeMarker(id).isFile()) {
                    deleted.add(id); // idempotent retry of a prior deletion
                } else {
                    List<RevisionEnvelope> heads = snapshot.heads(id);
                    if (heads.size() != 1 || heads.get(0).deleted()
                            || !snapshot.pending(id).isEmpty()) {
                        // Previous UX contract: conflicting notes are skipped,
                        // not silently erased in bulk.
                        skipped.add(id);
                    } else {
                        deleted.add(id);
                    }
                }
            }
            try {
                purgeManyLocked(Set.copyOf(deleted));
                if (!deleted.isEmpty()) reconcileLocked();
            } catch (RuntimeException error) {
                for (UUID id : deleted) failed.put(id,
                        "Deletion marker saved but cleanup needs retry: " + error.getMessage());
                deleted.clear();
            }
            return new BatchDeleteResult(List.copyOf(deleted),
                    List.copyOf(skipped), Map.copyOf(failed));
        });
    }

    public RevisionEnvelope resolveConflict(
            UUID noteId,
            Set<String> expectedHeadIds,
            String selectedRevisionId,
            String resolverDevice,
            Instant modifiedUtc) {
        return withLock(() -> {
            Map<String, RevisionEnvelope> revisions = load();
            RevisionGraph graph = graph(revisions);
            if (purgeMarker(noteId).isFile()) {
                throw new IllegalStateException("note has been permanently deleted");
            }
            requireExpectedHeads(graph, noteId, expectedHeadIds);
            if (expectedHeadIds.size() < 2) throw new IllegalStateException("note is not conflicted");
            RevisionEnvelope selected = revisions.get(selectedRevisionId);
            if (selected == null || !selected.noteId().equals(noteId)
                    || !expectedHeadIds.contains(selectedRevisionId)) {
                throw new IllegalArgumentException("selected revision is not an observed head");
            }
            RevisionEnvelope resolution = RevisionEnvelope.create(
                    noteId,
                    new ArrayList<>(expectedHeadIds),
                    resolverDevice,
                    modifiedUtc,
                    selected.deleted(),
                    selected.body());
            persistImportedLocked(resolution, resolution.serialize());
            return resolution;
        });
    }

    private static void requireExpectedHeads(
            RevisionGraph graph, UUID noteId, Set<String> expectedHeadIds) {
        Set<String> expected = Set.copyOf(expectedHeadIds);
        Set<String> observed = headIds(graph, noteId);
        if (!expected.equals(observed)) throw new StaleHeadsException(expected, observed);
    }

    public RevisionEnvelope createProbeRevision(
            UUID noteId, String body, String originDevice, Instant modifiedUtc, boolean deleted) {
        return withLock(() -> {
            Map<String, RevisionEnvelope> revisions = load();
            RevisionGraph graph = graph(revisions);
            List<RevisionEnvelope> heads = graph.heads(noteId);
            if (heads.size() > 1 || !graph.pending(noteId).isEmpty()) {
                throw new IllegalStateException("explicit conflict resolution required");
            }
            return createRevision(
                    noteId,
                    heads.stream().map(RevisionEnvelope::revisionId).collect(java.util.stream.Collectors.toSet()),
                    body,
                    originDevice,
                    modifiedUtc,
                    deleted);
        });
    }

    public void reconcile() {
        withLock(this::reconcileLocked);
    }

    private void reconcileLocked() {
        finishAllPurgesLocked();
        Map<String, RevisionEnvelope> revisions = load();
        RevisionGraph graph = graph(revisions);
        Set<UUID> noteIds = noteIds(revisions);
        StringBuilder index = new StringBuilder("format=1\n");
        Map<UUID, RevisionEnvelope> selected = new LinkedHashMap<>();
        for (UUID noteId : noteIds) {
            List<RevisionEnvelope> heads = graph.heads(noteId);
            RevisionEnvelope choice = null;
            if (heads.size() == 1) {
                choice = heads.get(0);
            } else if (heads.size() > 1) {
                RevisionEnvelope previous = currentLocked(noteId, revisions);
                if (previous != null) choice = previous;
            }
            selected.put(noteId, choice);
            index.append(noteId).append('|')
                    .append(String.join(",", heads.stream().map(RevisionEnvelope::revisionId).toList()))
                    .append('|').append(String.join(",", graph.pending(noteId))).append('\n');
        }
        try {
            durableWriteIfChanged(new File(state, "index.txt"), index.toString().getBytes(StandardCharsets.UTF_8));
            for (UUID noteId : noteIds) {
                RevisionEnvelope choice = selected.get(noteId);
                File idPath = currentIdPath(noteId);
                File view = new File(current, noteId + ".md");
                if (choice == null) {
                    Files.deleteIfExists(idPath.toPath());
                    Files.deleteIfExists(view.toPath());
                    continue;
                }
                durableWriteIfChanged(idPath, (choice.revisionId() + "\n").getBytes(StandardCharsets.US_ASCII));
                if (choice.deleted()) {
                    Files.deleteIfExists(view.toPath());
                } else {
                    durableWriteIfChanged(view, choice.body().getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("cannot reconcile generated state", error);
        }
    }
}
