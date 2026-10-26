package org.reasonnotes.probe;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** One-time staged conversion from immutable legacy history to current-state v2. */
public final class LegacyRepositoryMigrator {
    public record Report(int liveNotes, int conflicts, int deletions) {}

    private record Derived(
            List<StateEnvelope> current,
            List<List<StateEnvelope>> conflicts,
            List<StateEnvelope> deletions) {
        Report report() { return new Report(current.size(), conflicts.size(), deletions.size()); }
    }

    private LegacyRepositoryMigrator() {}

    public static Report migrate(ProbeRepository legacy, File staging, File active) {
        if (legacy == null || staging == null || active == null)
            throw new NullPointerException("migration paths and repository are required");
        validatePaths(legacy.root(), staging, active);
        Derived derived = derive(legacy);

        if (active.exists()) {
            CurrentNoteStore existing = CurrentNoteStore.open(active, "android");
            validate(existing, derived);
            eraseTree(legacy.root());
            return derived.report();
        }

        eraseTree(staging);
        CurrentNoteStore staged = CurrentNoteStore.open(staging, "android");
        for (StateEnvelope state : derived.current()) staged.writeMigratedCurrent(state);
        for (List<StateEnvelope> candidates : derived.conflicts()) {
            staged.writeMigratedConflict(candidates);
        }
        for (StateEnvelope deletion : derived.deletions()) staged.writeMigratedDeletion(deletion);

        CurrentNoteStore reopened = CurrentNoteStore.open(staging, "android");
        validate(reopened, derived);
        moveDirectory(staging, active);
        validate(CurrentNoteStore.open(active, "android"), derived);
        eraseTree(legacy.root());
        return derived.report();
    }

    private static Derived derive(ProbeRepository legacy) {
        validateRawHistory(legacy.root());
        List<RevisionEnvelope> revisions = legacy.revisions();
        Map<UUID, List<RevisionEnvelope>> byNote = new HashMap<>();
        for (RevisionEnvelope revision : revisions) {
            byNote.computeIfAbsent(revision.noteId(), ignored -> new ArrayList<>()).add(revision);
        }

        ArrayList<StateEnvelope> current = new ArrayList<>();
        ArrayList<List<StateEnvelope>> conflicts = new ArrayList<>();
        ArrayList<StateEnvelope> deletions = new ArrayList<>();
        Set<UUID> purged = legacy.purgedNoteIds();
        for (UUID noteId : new TreeSet<>(purged)) {
            deletions.add(StateEnvelope.createDeletion(
                    noteId, List.of(), "legacy-migration", Instant.EPOCH));
        }

        for (UUID noteId : new TreeSet<>(byNote.keySet())) {
            if (purged.contains(noteId)) continue;
            List<String> incomplete = legacy.pending(noteId);
            if (!incomplete.isEmpty())
                throw new IllegalStateException("legacy note has missing ancestry: " + noteId);
            List<RevisionEnvelope> heads = legacy.heads(noteId);
            if (heads.isEmpty()) throw new IllegalStateException("legacy note has no complete head: " + noteId);
            if (heads.size() > 2)
                throw new IllegalStateException("legacy note has more than two heads: " + noteId);

            List<RevisionEnvelope> tombstones = heads.stream().filter(RevisionEnvelope::deleted).toList();
            if (!tombstones.isEmpty()) {
                RevisionEnvelope selected = tombstones.stream()
                        .sorted(Comparator.comparing(RevisionEnvelope::revisionId)).findFirst().orElseThrow();
                deletions.add(StateEnvelope.createDeletion(
                        noteId, legacyParents(selected), selected.originDevice(), selected.modifiedUtc()));
            } else if (heads.size() == 1) {
                current.add(convertLive(heads.get(0)));
            } else {
                conflicts.add(heads.stream().map(LegacyRepositoryMigrator::convertLive)
                        .sorted(Comparator.comparing(StateEnvelope::changeId)).toList());
            }
        }
        current.sort(Comparator.comparing(value -> value.noteId().toString()));
        conflicts.sort(Comparator.comparing(value -> value.get(0).noteId().toString()));
        deletions.sort(Comparator.comparing(value -> value.noteId().toString()));
        return new Derived(List.copyOf(current), List.copyOf(conflicts), List.copyOf(deletions));
    }

    private static StateEnvelope convertLive(RevisionEnvelope revision) {
        NoteDocument.Parts parts = NoteDocument.split(revision.body());
        String title = parts.title().isEmpty() ? "Untitled" : parts.title();
        return StateEnvelope.createUpsert(
                revision.noteId(), legacyParents(revision), revision.originDevice(),
                revision.modifiedUtc(), title, parts.content());
    }

    private static List<String> legacyParents(RevisionEnvelope revision) {
        if (revision.parents().size() > 2)
            throw new IllegalStateException("legacy state has too many parents: " + revision.revisionId());
        return revision.parents();
    }

    private static void validate(CurrentNoteStore store, Derived expected) {
        Map<UUID, StateEnvelope> current = new HashMap<>();
        store.list().forEach(state -> current.put(state.noteId(), state));
        for (StateEnvelope state : expected.current()) {
            if (!state.equals(current.remove(state.noteId())))
                throw new IllegalStateException("staged current state did not reopen exactly");
        }
        if (!current.isEmpty()) throw new IllegalStateException("staged store contains extra live notes");
        for (List<StateEnvelope> candidates : expected.conflicts()) {
            CurrentNoteStore.Conflict conflict = store.conflict(candidates.get(0).noteId());
            if (conflict == null || !new HashSet<>(conflict.candidates()).equals(new HashSet<>(candidates)))
                throw new IllegalStateException("staged conflict did not reopen exactly");
        }
        for (StateEnvelope deletion : expected.deletions()) {
            if (store.isDeleted(deletion.noteId()) || store.get(deletion.noteId()) != null)
                throw new IllegalStateException("staged deleted note was not purged");
        }
    }

    private static void validateRawHistory(File legacyRoot) {
        File history = new File(legacyRoot, "history");
        File[] noteDirectories = history.listFiles(File::isDirectory);
        if (noteDirectories == null) return;
        for (File directory : noteDirectories) {
            try { UUID.fromString(directory.getName()); }
            catch (IllegalArgumentException error) {
                throw new IllegalStateException("malformed legacy note directory", error);
            }
            File[] files = directory.listFiles(file -> file.isFile() && file.getName().endsWith(".md"));
            if (files == null) throw new IllegalStateException("cannot inspect legacy history");
            for (File file : files) {
                try {
                    RevisionEnvelope revision = RevisionEnvelope.parse(Files.readAllBytes(file.toPath()));
                    if (!file.getName().equals(revision.revisionId() + ".md"))
                        throw new IllegalStateException("legacy revision filename disagrees with content");
                } catch (IOException | IllegalArgumentException error) {
                    throw new IllegalStateException("malformed legacy history: " + file, error);
                }
            }
        }
    }

    private static void validatePaths(File legacy, File staging, File active) {
        try {
            File legacyPath = legacy.getCanonicalFile();
            File stagingPath = staging.getCanonicalFile();
            File activePath = active.getCanonicalFile();
            if (stagingPath.equals(activePath) || stagingPath.equals(legacyPath)
                    || activePath.equals(legacyPath)
                    || !stagingPath.getParentFile().equals(activePath.getParentFile())) {
                throw new IllegalArgumentException("migration paths must be distinct siblings");
            }
        } catch (IOException error) {
            throw new IllegalStateException("cannot resolve migration paths", error);
        }
    }

    private static void moveDirectory(File source, File target) {
        try {
            try { Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException error) { Files.move(source.toPath(), target.toPath()); }
        } catch (IOException error) {
            throw new IllegalStateException("cannot activate staged v2 store", error);
        }
    }

    private static void eraseTree(File root) {
        if (!root.exists()) return;
        if (Files.isSymbolicLink(root.toPath()))
            throw new IllegalStateException("migration refuses symbolic-link roots");
        File[] children = root.listFiles();
        if (children == null && root.isDirectory())
            throw new IllegalStateException("cannot inspect migration directory: " + root);
        if (children != null) for (File child : children) eraseTree(child);
        try { Files.delete(root.toPath()); }
        catch (IOException error) { throw new IllegalStateException("cannot retire legacy store", error); }
    }
}
