package org.reasonnotes.probe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Durable current-state storage for simplified synchronization v2. */
public final class CurrentNoteStore {
    public record ApplyOutcome(String kind, String changeId) {}
    public record Conflict(UUID noteId, List<StateEnvelope> candidates) {}

    private final File root;
    private final String deviceId;
    private final File current;
    private final File conflicts;
    private final File deleted;
    private final File pending;
    private final File outgoingAcks;

    private CurrentNoteStore(File root, String deviceId) {
        this.root = root;
        this.deviceId = deviceId;
        this.current = new File(root, "current");
        this.conflicts = new File(root, "conflicts");
        this.deleted = new File(root, "deleted");
        this.pending = new File(root, "pending");
        this.outgoingAcks = new File(root, "outgoing-acks");
    }

    public static CurrentNoteStore open(File root) {
        return open(root, "android");
    }

    public static CurrentNoteStore open(File root, String deviceId) {
        Objects.requireNonNull(root, "root");
        StateEnvelope.validateDevice(deviceId);
        CurrentNoteStore store = new CurrentNoteStore(root, deviceId);
        store.ensureDirectories();
        store.recoverInterruptedPending();
        store.finishDurableDeletions();
        store.validateAll();
        return store;
    }

    public synchronized List<StateEnvelope> list() {
        return readStates(current).stream()
                .sorted(Comparator.comparing(value -> value.noteId().toString()))
                .toList();
    }

    public synchronized StateEnvelope get(UUID noteId) {
        Objects.requireNonNull(noteId, "noteId");
        File file = stateFile(current, noteId);
        return file.isFile() ? readState(file) : null;
    }

    public synchronized Set<String> headIds(UUID noteId) {
        Conflict value = conflict(noteId);
        if (value != null) {
            TreeSet<String> ids = new TreeSet<>();
            value.candidates().forEach(candidate -> ids.add(candidate.changeId()));
            return Set.copyOf(ids);
        }
        StateEnvelope state = get(noteId);
        return state == null ? Set.of() : Set.of(state.changeId());
    }

    public synchronized StateEnvelope saveLocal(
            UUID noteId, String title, String body, Instant modifiedUtc) {
        if (fenceFile(noteId).isFile()) throw new IllegalStateException("deleted note cannot be edited");
        if (conflict(noteId) != null) throw new IllegalStateException("conflict must be resolved first");
        StateEnvelope existing = get(noteId);
        List<String> parents = localParents(noteId, existing);
        StateEnvelope state = StateEnvelope.createUpsert(
                noteId, parents, deviceId, modifiedUtc, title, body);
        commitLocal(state);
        return state;
    }

    public synchronized ApplyOutcome apply(StateEnvelope incoming) {
        Objects.requireNonNull(incoming, "incoming");
        UUID noteId = incoming.noteId();
        StateEnvelope fence = readOptional(fenceFile(noteId));
        if (fence != null) {
            if (incoming.operation().equals("delete")
                    && incoming.changeId().equals(fence.changeId())) {
                queueAck(incoming);
                return new ApplyOutcome("duplicate", incoming.changeId());
            }
            return new ApplyOutcome("rejected-deleted", incoming.changeId());
        }
        if (incoming.operation().equals("delete")) {
            persistDeletion(incoming, false);
            queueAck(incoming);
            return new ApplyOutcome("deleted", incoming.changeId());
        }

        StateEnvelope local = get(noteId);
        if (local == null) {
            durableWrite(stateFile(current, noteId), incoming.serialize());
            queueAck(incoming);
            return new ApplyOutcome("applied", incoming.changeId());
        }
        if (local.changeId().equals(incoming.changeId())) {
            queueAck(incoming);
            return new ApplyOutcome("duplicate", incoming.changeId());
        }
        if (incoming.parentChangeIds().contains(local.changeId())) {
            durableWrite(stateFile(current, noteId), incoming.serialize());
            delete(stateFile(pending, noteId));
            clearConflict(noteId);
            queueAck(incoming);
            return new ApplyOutcome("applied", incoming.changeId());
        }
        if (local.operation().equals("upsert")
                && local.parentChangeIds().equals(incoming.parentChangeIds())) {
            writeConflict(local, incoming);
            queueAck(incoming);
            return new ApplyOutcome("conflict", incoming.changeId());
        }
        writeConflict(local, incoming);
        queueAck(incoming);
        return new ApplyOutcome("conflict", incoming.changeId());
    }

    public synchronized StateEnvelope resolve(
            UUID noteId, String selectedChangeId, Instant modifiedUtc) {
        Conflict value = conflict(noteId);
        if (value == null) throw new IllegalStateException("note is not conflicted");
        StateEnvelope selected = value.candidates().stream()
                .filter(item -> item.changeId().equals(selectedChangeId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown conflict candidate"));
        StateEnvelope resolution = StateEnvelope.createUpsert(
                noteId,
                value.candidates().stream().map(StateEnvelope::changeId).sorted().toList(),
                deviceId,
                modifiedUtc,
                selected.title(),
                selected.body());
        commitLocal(resolution);
        clearConflict(noteId);
        return resolution;
    }

    public synchronized StateEnvelope deleteLocal(UUID noteId, Instant modifiedUtc) {
        StateEnvelope live = get(noteId);
        Conflict value = conflict(noteId);
        ArrayList<String> parents = new ArrayList<>();
        if (value != null) value.candidates().forEach(item -> parents.add(item.changeId()));
        else if (live != null) parents.addAll(localParents(noteId, live));
        StateEnvelope deletion = StateEnvelope.createDeletion(
                noteId, parents.stream().distinct().sorted().toList(), deviceId, modifiedUtc);
        persistDeletion(deletion, true);
        return deletion;
    }

    public synchronized List<StateEnvelope> pendingStates() {
        return readStates(pending).stream()
                .sorted(Comparator.comparing(value -> value.noteId().toString()))
                .toList();
    }

    public synchronized List<StateAckEnvelope> pendingAcks() {
        File[] files = outgoingAcks.listFiles(file -> file.isFile() && file.getName().endsWith(".json"));
        if (files == null) throw new IllegalStateException("cannot inspect outgoing acknowledgments");
        java.util.Arrays.sort(files, Comparator.comparing(File::getName));
        ArrayList<StateAckEnvelope> result = new ArrayList<>();
        for (File file : files) {
            try { result.add(StateAckEnvelope.parse(Files.readAllBytes(file.toPath()))); }
            catch (IOException error) { throw new IllegalStateException("cannot read acknowledgment", error); }
        }
        return List.copyOf(result);
    }

    public synchronized void acknowledge(String changeId) {
        StateEnvelope.validateHash(changeId, "acknowledged change ID");
        for (StateEnvelope state : pendingStates()) {
            if (state.changeId().equals(changeId)) delete(stateFile(pending, state.noteId()));
        }
    }

    public synchronized boolean acknowledge(StateAckEnvelope ack) {
        Objects.requireNonNull(ack, "ack");
        if (!ack.destinationDevice().equals(deviceId))
            throw new IllegalArgumentException("acknowledgment has wrong destination");
        for (StateEnvelope state : pendingStates()) {
            if (!state.changeId().equals(ack.acknowledgedChangeId())) continue;
            if (!state.noteId().equals(ack.noteId()))
                throw new IllegalArgumentException("acknowledgment note does not match change");
            delete(stateFile(pending, state.noteId()));
            return true;
        }
        return false;
    }

    public synchronized void markAcksRequested(List<StateAckEnvelope> acknowledgments) {
        for (StateAckEnvelope ack : acknowledgments) {
            delete(ackForChange(ack.acknowledgedChangeId()));
        }
    }

    File pendingStateFile(UUID noteId) { return stateFile(pending, noteId); }
    File outgoingAckFile(StateAckEnvelope ack) {
        return ackForChange(ack.acknowledgedChangeId());
    }

    public synchronized Conflict conflict(UUID noteId) {
        File directory = conflictDirectory(noteId);
        if (!directory.isDirectory()) return null;
        List<StateEnvelope> candidates = readStates(directory).stream()
                .sorted(Comparator.comparing(StateEnvelope::changeId)).toList();
        return candidates.isEmpty() ? null : new Conflict(noteId, candidates);
    }

    public synchronized List<Conflict> conflicts() {
        File[] directories = conflicts.listFiles(File::isDirectory);
        if (directories == null) throw new IllegalStateException("cannot inspect conflicts");
        java.util.Arrays.sort(directories, Comparator.comparing(File::getName));
        ArrayList<Conflict> result = new ArrayList<>();
        for (File directory : directories) {
            Conflict value = conflict(UUID.fromString(directory.getName()));
            if (value != null) result.add(value);
        }
        return List.copyOf(result);
    }

    void writeInterruptedLocalForTest(StateEnvelope state) {
        synchronized (this) {
            File staged = pendingTemporary(state.noteId());
            durableWrite(staged, state.serialize());
            durableWrite(stateFile(current, state.noteId()), state.serialize());
        }
    }

    synchronized void writeMigratedCurrent(StateEnvelope state) {
        if (!state.operation().equals("upsert"))
            throw new IllegalArgumentException("migrated current state must be live");
        durableWrite(stateFile(current, state.noteId()), state.serialize());
        durableWrite(stateFile(pending, state.noteId()), state.serialize());
    }

    synchronized void writeMigratedConflict(List<StateEnvelope> candidates) {
        if (candidates.size() != 2
                || !candidates.get(0).noteId().equals(candidates.get(1).noteId())
                || candidates.stream().anyMatch(item -> !item.operation().equals("upsert"))) {
            throw new IllegalArgumentException("migration conflict must contain two live candidates");
        }
        writeConflict(candidates.get(0), candidates.get(1));
    }

    synchronized void writeMigratedDeletion(StateEnvelope deletion) {
        if (!deletion.operation().equals("delete"))
            throw new IllegalArgumentException("migrated fence must be a deletion");
        persistDeletion(deletion, true);
    }

    synchronized boolean isDeleted(UUID noteId) { return fenceFile(noteId).isFile(); }

    private void commitLocal(StateEnvelope state) {
        File staged = pendingTemporary(state.noteId());
        durableWrite(staged, state.serialize());
        durableWrite(stateFile(current, state.noteId()), state.serialize());
        move(staged, stateFile(pending, state.noteId()));
    }

    private List<String> localParents(UUID noteId, StateEnvelope currentState) {
        StateEnvelope pendingState = readOptional(stateFile(pending, noteId));
        if (pendingState != null) return pendingState.parentChangeIds();
        return currentState == null ? List.of() : List.of(currentState.changeId());
    }

    private void persistDeletion(StateEnvelope deletion, boolean outgoing) {
        File staged = pendingTemporary(deletion.noteId());
        if (outgoing) durableWrite(staged, deletion.serialize());
        durableWrite(fenceFile(deletion.noteId()), deletion.serialize());
        eraseLiveContent(deletion.noteId());
        if (outgoing) move(staged, stateFile(pending, deletion.noteId()));
        else delete(stateFile(pending, deletion.noteId()));
    }

    private void finishDurableDeletions() {
        for (StateEnvelope fence : readStates(deleted)) eraseLiveContent(fence.noteId());
    }

    private void eraseLiveContent(UUID noteId) {
        delete(stateFile(current, noteId));
        delete(new File(new File(root.getParentFile(), "drafts"), noteId + ".draft"));
        clearConflict(noteId);
        File pendingState = stateFile(pending, noteId);
        if (pendingState.isFile()) {
            StateEnvelope value = readState(pendingState);
            if (!value.operation().equals("delete")) delete(pendingState);
        }
    }

    private void writeConflict(StateEnvelope left, StateEnvelope right) {
        File directory = conflictDirectory(left.noteId());
        ensureDirectory(directory);
        durableWrite(new File(directory, left.changeId() + ".json"), left.serialize());
        durableWrite(new File(directory, right.changeId() + ".json"), right.serialize());
    }

    private void clearConflict(UUID noteId) {
        File directory = conflictDirectory(noteId);
        if (!directory.exists()) return;
        File[] files = directory.listFiles();
        if (files == null) throw new IllegalStateException("cannot inspect conflict directory");
        for (File file : files) delete(file);
        if (!directory.delete()) throw new IllegalStateException("cannot remove conflict directory");
    }

    private void queueAck(StateEnvelope incoming) {
        File existing = ackForChange(incoming.changeId());
        if (existing.isFile()) return;
        StateAckEnvelope ack = StateAckEnvelope.create(
                incoming.noteId(), incoming.changeId(), deviceId, incoming.originDevice(),
                Instant.now().truncatedTo(ChronoUnit.SECONDS));
        durableWrite(existing, ack.serialize());
    }

    private void recoverInterruptedPending() {
        File[] files = pending.listFiles(file -> file.isFile() && file.getName().endsWith(".pending.tmp"));
        if (files == null) throw new IllegalStateException("cannot inspect pending recovery files");
        for (File file : files) {
            StateEnvelope staged = readState(file);
            StateEnvelope committed = get(staged.noteId());
            if (staged.equals(committed)) move(file, stateFile(pending, staged.noteId()));
            else delete(file);
        }
    }

    private void validateAll() {
        list();
        pendingStates();
        pendingAcks();
        for (StateEnvelope fence : readStates(deleted)) {
            if (!fence.operation().equals("delete"))
                throw new IllegalStateException("deletion fence is not a deletion");
        }
        File[] directories = conflicts.listFiles(File::isDirectory);
        if (directories == null) throw new IllegalStateException("cannot inspect conflicts");
        for (File directory : directories) {
            UUID noteId;
            try { noteId = UUID.fromString(directory.getName()); }
            catch (IllegalArgumentException error) { throw new IllegalStateException("invalid conflict note", error); }
            Conflict value = conflict(noteId);
            if (value == null || value.candidates().size() != 2)
                throw new IllegalStateException("conflict must contain exactly two candidates");
        }
    }

    private List<StateEnvelope> readStates(File directory) {
        File[] files = directory.listFiles(file -> file.isFile()
                && file.getName().endsWith(".json") && !file.getName().endsWith(".pending.tmp"));
        if (files == null) throw new IllegalStateException("cannot inspect state directory");
        java.util.Arrays.sort(files, Comparator.comparing(File::getName));
        ArrayList<StateEnvelope> result = new ArrayList<>();
        for (File file : files) result.add(readState(file));
        return List.copyOf(result);
    }

    private StateEnvelope readOptional(File file) { return file.isFile() ? readState(file) : null; }

    private StateEnvelope readState(File file) {
        try { return StateEnvelope.parse(Files.readAllBytes(file.toPath())); }
        catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException("cannot read state: " + file, error);
        }
    }

    private void ensureDirectories() {
        ensureDirectory(root);
        for (File directory : List.of(current, conflicts, deleted, pending, outgoingAcks)) {
            ensureDirectory(directory);
        }
    }

    private static void ensureDirectory(File directory) {
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IllegalStateException("cannot create directory: " + directory);
    }

    private static void durableWrite(File target, byte[] data) {
        ensureDirectory(target.getParentFile());
        File temporary;
        try {
            temporary = File.createTempFile("." + target.getName() + ".", ".tmp", target.getParentFile());
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(data);
                output.flush();
                output.getFD().sync();
            }
            move(temporary, target);
        } catch (IOException error) {
            throw new IllegalStateException("cannot durably write state", error);
        }
    }

    private static void move(File source, File target) {
        try {
            try {
                Files.move(source.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new IllegalStateException("cannot atomically replace state", error);
        }
    }

    private static void delete(File file) {
        try { Files.deleteIfExists(file.toPath()); }
        catch (IOException error) { throw new IllegalStateException("cannot delete state", error); }
    }

    private File stateFile(File directory, UUID noteId) { return new File(directory, noteId + ".json"); }
    private File fenceFile(UUID noteId) { return stateFile(deleted, noteId); }
    private File pendingTemporary(UUID noteId) { return new File(pending, noteId + ".pending.tmp"); }
    private File conflictDirectory(UUID noteId) { return new File(conflicts, noteId.toString()); }
    private File ackForChange(String changeId) { return new File(outgoingAcks, changeId + ".json"); }
}
