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
    private final ReentrantLock mutationLock;

    private ProbeRepository(File root) {
        this.root = root;
        this.history = new File(root, "history");
        this.state = new File(root, "state");
        this.current = new File(root, "current");
        this.quarantine = new File(root, "quarantine");
        String lockKey;
        try {
            lockKey = root.getCanonicalFile().toPath().normalize().toString();
        } catch (IOException error) {
            lockKey = root.getAbsoluteFile().toPath().normalize().toString();
        }
        this.mutationLock = ROOT_LOCKS.computeIfAbsent(lockKey, ignored -> new ReentrantLock());
    }

    public static ProbeRepository open(File root) {
        ProbeRepository repository = new ProbeRepository(root);
        repository.withLock(() -> {
            repository.ensureDirectories();
            repository.reconcileLocked();
        });
        return repository;
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
        for (File directory : List.of(root, history, state, current, quarantine)) {
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

    private Map<String, RevisionEnvelope> load() {
        Map<String, RevisionEnvelope> revisions = new LinkedHashMap<>();
        File[] noteDirectories = history.listFiles(File::isDirectory);
        if (noteDirectories == null) return revisions;
        java.util.Arrays.sort(noteDirectories, Comparator.comparing(File::getName));
        for (File directory : noteDirectories) {
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
        File path = new File(new File(history, revision.noteId().toString()), revision.revisionId() + ".md");
        boolean duplicate = path.exists();
        try {
            durableWrite(path, data, false);
        } catch (IOException error) {
            throw new IllegalStateException("cannot persist immutable history", error);
        }
        reconcileLocked();
        return new ImportOutcome(duplicate ? "duplicate" : "imported", revision.revisionId(),
                duplicate ? "revision already imported" : "revision imported durably");
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

    public RevisionEnvelope resolveConflict(
            UUID noteId,
            Set<String> expectedHeadIds,
            String selectedRevisionId,
            String resolverDevice,
            Instant modifiedUtc) {
        return withLock(() -> {
            Map<String, RevisionEnvelope> revisions = load();
            RevisionGraph graph = graph(revisions);
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
            durableWrite(new File(state, "index.txt"), index.toString().getBytes(StandardCharsets.UTF_8), true);
            for (UUID noteId : noteIds) {
                RevisionEnvelope choice = selected.get(noteId);
                File idPath = currentIdPath(noteId);
                File view = new File(current, noteId + ".md");
                if (choice == null) {
                    Files.deleteIfExists(idPath.toPath());
                    Files.deleteIfExists(view.toPath());
                    continue;
                }
                durableWrite(idPath, (choice.revisionId() + "\n").getBytes(StandardCharsets.US_ASCII), true);
                if (choice.deleted()) {
                    Files.deleteIfExists(view.toPath());
                } else {
                    durableWrite(view, choice.body().getBytes(StandardCharsets.UTF_8), true);
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("cannot reconcile generated state", error);
        }
    }
}
