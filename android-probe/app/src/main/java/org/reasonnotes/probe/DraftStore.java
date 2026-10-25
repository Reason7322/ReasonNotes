package org.reasonnotes.probe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.function.Supplier;

/** Atomic, device-local editor recovery snapshots. */
public final class DraftStore {
    public record Draft(
            UUID noteId,
            long generation,
            String baseRevisionId,
            String title,
            String content) {}

    public record Recovery(Draft draft, boolean safeToApply) {}

    public static final class CorruptDraftException extends IllegalStateException {
        private CorruptDraftException(File path, Throwable cause) {
            super("draft is corrupt and was preserved: " + path, cause);
        }
    }

    private static final Pattern REVISION_ID = Pattern.compile("[0-9a-f]{64}");
    private static final ConcurrentMap<String, ReentrantLock> ROOT_LOCKS = new ConcurrentHashMap<>();

    private final File root;
    private final ReentrantLock lock;

    public DraftStore(File root) {
        this.root = Objects.requireNonNull(root, "root");
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IllegalStateException("cannot create drafts directory: " + root);
        }
        String key;
        try {
            key = root.getCanonicalPath();
        } catch (IOException error) {
            key = root.getAbsolutePath();
        }
        lock = ROOT_LOCKS.computeIfAbsent(key, ignored -> new ReentrantLock());
    }

    public Draft write(UUID noteId, String baseRevisionId, String title, String content) {
        validateBase(baseRevisionId);
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(content, "content");
        return ProbeRepository.withRepositoryLock(
                new File(root.getParentFile(), "reasonnotes-v2"), () -> withLock(() -> {
            if (isPurged(noteId)) {
                throw new IllegalStateException("note was permanently deleted");
            }
            Draft current = readLocked(noteId);
            long generation = current == null ? 1 : Math.addExact(current.generation(), 1);
            Draft draft = new Draft(noteId, generation, baseRevisionId, title, content);
            writeLocked(draft);
            return draft;
        }));
    }

    public Draft current(UUID noteId) {
        return withLock(() -> readLocked(noteId));
    }

    public Recovery recovery(UUID noteId, String currentHeadId) {
        Draft draft = current(noteId);
        return new Recovery(draft, draft != null && draft.baseRevisionId().equals(currentHeadId));
    }

    public void completeSave(
            UUID noteId,
            long completedGeneration,
            String capturedBaseRevisionId,
            String savedRevisionId,
            String observedCurrentHeadId) {
        validateBase(capturedBaseRevisionId);
        validateBase(savedRevisionId);
        validateBase(observedCurrentHeadId);
        ProbeRepository.withRepositoryLock(
                new File(root.getParentFile(), "reasonnotes-v2"), () -> withLock(() -> {
            if (isPurged(noteId)) {
                try { Files.deleteIfExists(path(noteId).toPath()); }
                catch (IOException error) {
                    throw new IllegalStateException("cannot retire purged draft", error);
                }
                return null;
            }
            Draft current = readLocked(noteId);
            if (current == null
                    || current.generation() < completedGeneration
                    || !current.baseRevisionId().equals(capturedBaseRevisionId)
                    || !savedRevisionId.equals(observedCurrentHeadId)) {
                return null;
            }
            if (current.generation() == completedGeneration) {
                try {
                    Files.deleteIfExists(path(noteId).toPath());
                } catch (IOException error) {
                    throw new IllegalStateException("cannot clear saved draft", error);
                }
                return null;
            }
            writeLocked(new Draft(
                    current.noteId(),
                    current.generation(),
                    savedRevisionId,
                    current.title(),
                    current.content()));
            return null;
        }));
    }

    private boolean isPurged(UUID noteId) {
        return new File(new File(root.getParentFile(), "reasonnotes-v2/deleted"),
                noteId + ".json").isFile()
                || new File(new File(root.getParentFile(), "probe-repository/purged"),
                        noteId + ".id").isFile();
    }

    private File path(UUID noteId) { return new File(root, noteId + ".draft"); }

    private Draft readLocked(UUID noteId) {
        File path = path(noteId);
        if (!path.isFile()) return null;
        try {
            List<String> lines = Files.readAllLines(path.toPath(), StandardCharsets.UTF_8);
            if (lines.size() != 6
                    || !lines.get(0).equals("format=1")
                    || !lines.get(1).startsWith("note-id=")
                    || !lines.get(2).startsWith("generation=")
                    || !lines.get(3).startsWith("base-revision-id=")
                    || !lines.get(4).startsWith("title-base64=")
                    || !lines.get(5).startsWith("content-base64=")) {
                throw new IllegalArgumentException("noncanonical draft fields");
            }
            UUID parsedNote = UUID.fromString(lines.get(1).substring("note-id=".length()));
            long generation = Long.parseLong(lines.get(2).substring("generation=".length()));
            String base = lines.get(3).substring("base-revision-id=".length());
            if (!parsedNote.equals(noteId) || generation < 1) {
                throw new IllegalArgumentException("invalid draft identity");
            }
            validateBase(base);
            Base64.Decoder decoder = Base64.getDecoder();
            String title = new String(
                    decoder.decode(lines.get(4).substring("title-base64=".length())),
                    StandardCharsets.UTF_8);
            String content = new String(
                    decoder.decode(lines.get(5).substring("content-base64=".length())),
                    StandardCharsets.UTF_8);
            return new Draft(parsedNote, generation, base, title, content);
        } catch (IOException | IllegalArgumentException error) {
            throw new CorruptDraftException(path, error);
        }
    }

    private void writeLocked(Draft draft) {
        Base64.Encoder encoder = Base64.getEncoder();
        String serialized = "format=1\n"
                + "note-id=" + draft.noteId() + "\n"
                + "generation=" + draft.generation() + "\n"
                + "base-revision-id=" + draft.baseRevisionId() + "\n"
                + "title-base64=" + encoder.encodeToString(draft.title().getBytes(StandardCharsets.UTF_8)) + "\n"
                + "content-base64=" + encoder.encodeToString(draft.content().getBytes(StandardCharsets.UTF_8)) + "\n";
        File target = path(draft.noteId());
        try {
            File temporary = File.createTempFile("." + target.getName() + ".", ".tmp", root);
            try {
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    output.write(serialized.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    output.getFD().sync();
                }
                try {
                    Files.move(temporary.toPath(), target.toPath(),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException error) {
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary.toPath());
            }
        } catch (IOException error) {
            throw new IllegalStateException("cannot persist draft", error);
        }
    }

    private static void validateBase(String value) {
        if (value == null || !REVISION_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid base revision ID");
        }
    }

    private <T> T withLock(Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
