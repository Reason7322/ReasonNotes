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

public final class ProbeRepository {
    public record ImportOutcome(String kind, String revisionId, String detail) {}

    private final File root;
    private final File history;
    private final File state;
    private final File current;
    private final File quarantine;

    private ProbeRepository(File root) {
        this.root = root;
        this.history = new File(root, "history");
        this.state = new File(root, "state");
        this.current = new File(root, "current");
        this.quarantine = new File(root, "quarantine");
    }

    public static ProbeRepository open(File root) {
        ProbeRepository repository = new ProbeRepository(root);
        repository.ensureDirectories();
        repository.reconcile();
        return repository;
    }

    public File root() { return root; }

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
        return graph(load()).heads(noteId);
    }

    public List<String> pending(UUID noteId) {
        return graph(load()).pending(noteId);
    }

    private File currentIdPath(UUID noteId) { return new File(state, "current-" + noteId + ".txt"); }

    public RevisionEnvelope current(UUID noteId) {
        File path = currentIdPath(noteId);
        if (!path.isFile()) return null;
        try {
            String id = new String(Files.readAllBytes(path.toPath()), StandardCharsets.US_ASCII).trim();
            return load().get(id);
        } catch (IOException error) {
            throw new IllegalStateException("cannot read current state", error);
        }
    }

    public ImportOutcome importEnvelope(byte[] data, String sourceName) {
        final RevisionEnvelope revision;
        try {
            revision = RevisionEnvelope.parse(data);
        } catch (IllegalArgumentException error) {
            try {
                String safe = sourceName.replaceAll("[^A-Za-z0-9._-]", "_");
                durableWrite(new File(quarantine, "rejected-" + safe), data, false);
            } catch (IOException ignored) {
                // Validation failure remains the primary result.
            }
            return new ImportOutcome("quarantined", null, error.getMessage());
        }
        File path = new File(new File(history, revision.noteId().toString()), revision.revisionId() + ".md");
        boolean duplicate = path.exists();
        try {
            durableWrite(path, data, false);
        } catch (IOException error) {
            throw new IllegalStateException("cannot persist immutable history", error);
        }
        reconcile();
        return new ImportOutcome(duplicate ? "duplicate" : "imported", revision.revisionId(),
                duplicate ? "revision already imported" : "revision imported durably");
    }

    public RevisionEnvelope createProbeRevision(
            UUID noteId, String body, String originDevice, Instant modifiedUtc, boolean deleted) {
        List<RevisionEnvelope> heads = heads(noteId);
        if (heads.size() > 1 || (!pending(noteId).isEmpty() && heads.isEmpty())) {
            throw new IllegalStateException("explicit conflict resolution required");
        }
        RevisionEnvelope revision = RevisionEnvelope.create(
                noteId,
                heads.stream().map(RevisionEnvelope::revisionId).toList(),
                originDevice,
                modifiedUtc,
                deleted,
                body);
        importEnvelope(revision.serialize(), revision.revisionId() + ".md");
        return revision;
    }

    public void reconcile() {
        Map<String, RevisionEnvelope> revisions = load();
        RevisionGraph graph = graph(revisions);
        Set<UUID> noteIds = new TreeSet<>(Comparator.comparing(UUID::toString));
        revisions.values().forEach(revision -> noteIds.add(revision.noteId()));
        StringBuilder index = new StringBuilder("format=1\n");
        Map<UUID, RevisionEnvelope> selected = new LinkedHashMap<>();
        for (UUID noteId : noteIds) {
            List<RevisionEnvelope> heads = graph.heads(noteId);
            RevisionEnvelope choice = null;
            if (heads.size() == 1) {
                choice = heads.get(0);
            } else if (heads.size() > 1) {
                RevisionEnvelope previous = current(noteId);
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
