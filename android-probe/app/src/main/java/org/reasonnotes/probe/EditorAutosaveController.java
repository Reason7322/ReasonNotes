package org.reasonnotes.probe;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.function.Consumer;

/** Serializes debounced editor saves without sacrificing a newer local draft. */
public final class EditorAutosaveController {
    public static final long DEBOUNCE_MILLIS = 500;

    public enum Status { SAVED, DIRTY, SAVING, ERROR, STALE }

    @FunctionalInterface public interface Cancellable { void cancel(); }
    @FunctionalInterface public interface Scheduler {
        Cancellable schedule(Runnable action, long delayMillis);
    }
    @FunctionalInterface public interface SaveOperation {
        String save(DraftStore.Draft draft) throws Exception;
    }

    private final DraftStore drafts;
    private final UUID noteId;
    private final Scheduler scheduler;
    private final Executor executor;
    private final SaveOperation saveOperation;
    private final Supplier<String> currentHead;
    private final Consumer<Status> statusListener;
    private final ArrayList<CompletableFuture<Status>> flushWaiters = new ArrayList<>();

    private String activeBaseRevisionId;
    private Status status = Status.SAVED;
    private Throwable lastError;
    private Cancellable scheduled;
    private boolean saveInFlight;

    public EditorAutosaveController(
            DraftStore drafts,
            UUID noteId,
            String baseRevisionId,
            Scheduler scheduler,
            Executor executor,
            SaveOperation saveOperation,
            Supplier<String> currentHead) {
        this(drafts, noteId, baseRevisionId, scheduler, executor, saveOperation, currentHead, ignored -> {});
    }

    public EditorAutosaveController(
            DraftStore drafts,
            UUID noteId,
            String baseRevisionId,
            Scheduler scheduler,
            Executor executor,
            SaveOperation saveOperation,
            Supplier<String> currentHead,
            Consumer<Status> statusListener) {
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.noteId = Objects.requireNonNull(noteId, "noteId");
        this.activeBaseRevisionId = Objects.requireNonNull(baseRevisionId, "baseRevisionId");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.saveOperation = Objects.requireNonNull(saveOperation, "saveOperation");
        this.currentHead = Objects.requireNonNull(currentHead, "currentHead");
        this.statusListener = Objects.requireNonNull(statusListener, "statusListener");
        DraftStore.Draft restored = drafts.current(noteId);
        if (restored != null) status = restored.baseRevisionId().equals(baseRevisionId) ? Status.DIRTY : Status.STALE;
    }

    public synchronized DraftStore.Draft edit(String title, String content) {
        DraftStore.Draft existing = drafts.current(noteId);
        String base = existing == null ? activeBaseRevisionId : existing.baseRevisionId();
        DraftStore.Draft draft = drafts.write(noteId, base, title, content);
        setStatusLocked(Status.DIRTY);
        lastError = null;
        cancelScheduledLocked();
        scheduled = scheduler.schedule(this::debounceElapsed, DEBOUNCE_MILLIS);
        return draft;
    }

    public synchronized CompletableFuture<Status> flush() {
        CompletableFuture<Status> result = new CompletableFuture<>();
        flushWaiters.add(result);
        cancelScheduledLocked();
        startSaveLocked();
        return result;
    }

    public synchronized DraftStore.Recovery recovery() {
        return drafts.recovery(noteId, currentHead.get());
    }

    public synchronized Status status() { return status; }
    public synchronized Throwable lastError() { return lastError; }

    private void debounceElapsed() {
        synchronized (this) {
            scheduled = null;
            startSaveLocked();
        }
    }

    private void startSaveLocked() {
        if (saveInFlight) return;
        DraftStore.Draft draft = drafts.current(noteId);
        if (draft == null) {
            finishLocked(Status.SAVED, null);
            return;
        }
        if (!draft.baseRevisionId().equals(currentHead.get())) {
            finishLocked(Status.STALE, null);
            return;
        }
        saveInFlight = true;
        setStatusLocked(Status.SAVING);
        executor.execute(() -> runSave(draft));
    }

    private void runSave(DraftStore.Draft draft) {
        try {
            String savedRevisionId = saveOperation.save(draft);
            String observedHead = currentHead.get();
            synchronized (this) {
                drafts.completeSave(
                        noteId,
                        draft.generation(),
                        draft.baseRevisionId(),
                        savedRevisionId,
                        observedHead);
                activeBaseRevisionId = savedRevisionId;
                saveInFlight = false;
                DraftStore.Draft remaining = drafts.current(noteId);
                if (remaining == null) {
                    finishLocked(Status.SAVED, null);
                } else if (remaining.baseRevisionId().equals(savedRevisionId)
                        && observedHead.equals(savedRevisionId)) {
                    cancelScheduledLocked();
                    startSaveLocked();
                } else {
                    finishLocked(Status.STALE, null);
                }
            }
        } catch (Throwable error) {
            synchronized (this) {
                saveInFlight = false;
                if (error instanceof ProbeRepository.StaleHeadsException
                        || error instanceof NoteService.ConflictRequiredException) {
                    finishLocked(Status.STALE, error);
                } else {
                    finishLocked(Status.ERROR, error);
                }
            }
        }
    }

    private void finishLocked(Status finished, Throwable error) {
        setStatusLocked(finished);
        lastError = error;
        List<CompletableFuture<Status>> waiting = List.copyOf(flushWaiters);
        flushWaiters.clear();
        waiting.forEach(waiter -> waiter.complete(finished));
    }

    private void cancelScheduledLocked() {
        if (scheduled != null) scheduled.cancel();
        scheduled = null;
    }

    private void setStatusLocked(Status value) {
        status = value;
        statusListener.accept(value);
    }
}
