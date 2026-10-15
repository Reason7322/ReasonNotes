package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class EditorAutosaveControllerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final UUID NOTE = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final String BASE = "a".repeat(64);
    private static final String SAVED_ONE = "b".repeat(64);
    private static final String SAVED_TWO = "c".repeat(64);
    private static final String REMOTE = "d".repeat(64);

    private static final class ManualScheduler implements EditorAutosaveController.Scheduler {
        private final ArrayList<Scheduled> scheduled = new ArrayList<>();
        private long now;

        @Override public EditorAutosaveController.Cancellable schedule(Runnable action, long delayMillis) {
            Scheduled item = new Scheduled(now + delayMillis, action);
            scheduled.add(item);
            return () -> item.cancelled = true;
        }

        void advance(long millis) {
            now += millis;
            boolean progressed;
            do {
                progressed = false;
                for (Scheduled item : List.copyOf(scheduled)) {
                    if (!item.cancelled && !item.ran && item.when <= now) {
                        item.ran = true;
                        item.action.run();
                        progressed = true;
                    }
                }
            } while (progressed);
        }

        private static final class Scheduled {
            final long when;
            final Runnable action;
            boolean cancelled;
            boolean ran;
            Scheduled(long when, Runnable action) { this.when = when; this.action = action; }
        }
    }

    @Test
    public void editsCoalesceForFiveHundredMilliseconds() throws Exception {
        DraftStore store = new DraftStore(temporary.newFolder("drafts"));
        ManualScheduler scheduler = new ManualScheduler();
        ArrayList<DraftStore.Draft> saved = new ArrayList<>();
        AtomicReference<String> head = new AtomicReference<>(BASE);
        EditorAutosaveController controller = new EditorAutosaveController(
                store, NOTE, BASE, scheduler, Runnable::run,
                draft -> { saved.add(draft); head.set(SAVED_ONE); return SAVED_ONE; },
                head::get);

        controller.edit("one", "body 1");
        scheduler.advance(300);
        controller.edit("two", "body 2");
        scheduler.advance(499);
        assertTrue(saved.isEmpty());
        scheduler.advance(1);

        assertEquals(1, saved.size());
        assertEquals("two", saved.get(0).title());
        assertEquals(EditorAutosaveController.Status.SAVED, controller.status());
        assertNull(store.current(NOTE));
    }

    @Test
    public void overlappingSavePreservesNewerDraftAndFollowsUpSerially() throws Exception {
        File root = temporary.newFolder("drafts");
        DraftStore store = new DraftStore(root);
        ManualScheduler scheduler = new ManualScheduler();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> head = new AtomicReference<>(BASE);
        ArrayList<DraftStore.Draft> inputs = new ArrayList<>();
        try {
            EditorAutosaveController controller = new EditorAutosaveController(
                    store, NOTE, BASE, scheduler, executor,
                    draft -> {
                        synchronized (inputs) { inputs.add(draft); }
                        int call = calls.incrementAndGet();
                        if (call == 1) {
                            firstStarted.countDown();
                            assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
                            head.set(SAVED_ONE);
                            return SAVED_ONE;
                        }
                        head.set(SAVED_TWO);
                        return SAVED_TWO;
                    },
                    head::get);

            controller.edit("first", "body one");
            scheduler.advance(500);
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            controller.edit("second", "newer exact body");
            CompletableFuture<EditorAutosaveController.Status> flushed = controller.flush();
            DraftStore.Draft newerBeforeCompletion = new DraftStore(root).current(NOTE);
            assertEquals("second", newerBeforeCompletion.title());
            releaseFirst.countDown();

            assertEquals(EditorAutosaveController.Status.SAVED, flushed.get(10, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
            synchronized (inputs) {
                assertEquals("first", inputs.get(0).title());
                assertEquals("second", inputs.get(1).title());
                assertEquals("newer exact body", inputs.get(1).content());
                assertEquals(SAVED_ONE, inputs.get(1).baseRevisionId());
            }
            assertNull(new DraftStore(root).current(NOTE));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void failedSaveAndStaleBaseKeepExactDraft() throws Exception {
        DraftStore failureStore = new DraftStore(temporary.newFolder("failure"));
        ManualScheduler failureScheduler = new ManualScheduler();
        EditorAutosaveController failure = new EditorAutosaveController(
                failureStore, NOTE, BASE, failureScheduler, Runnable::run,
                draft -> { throw new IllegalStateException("disk failed"); },
                () -> BASE);
        failure.edit("unsaved", "failure body");
        assertEquals(EditorAutosaveController.Status.ERROR, failure.flush().get(5, TimeUnit.SECONDS));
        assertEquals("failure body", failureStore.current(NOTE).content());
        assertNotNull(failure.lastError());

        DraftStore staleStore = new DraftStore(temporary.newFolder("stale"));
        ManualScheduler staleScheduler = new ManualScheduler();
        AtomicReference<String> head = new AtomicReference<>(BASE);
        AtomicInteger calls = new AtomicInteger();
        EditorAutosaveController stale = new EditorAutosaveController(
                staleStore, NOTE, BASE, staleScheduler, Runnable::run,
                draft -> { calls.incrementAndGet(); head.set(REMOTE); return SAVED_ONE; },
                head::get);
        stale.edit("newer", "stale body");
        stale.edit("latest", "must remain");
        assertEquals(EditorAutosaveController.Status.STALE, stale.flush().get(5, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
        assertEquals("latest", staleStore.current(NOTE).title());
        assertEquals(BASE, staleStore.current(NOTE).baseRevisionId());
    }

    @Test
    public void processRestartExposesSafeAndStaleRecoveryWithoutSaving() throws Exception {
        File root = temporary.newFolder("drafts");
        DraftStore first = new DraftStore(root);
        first.write(NOTE, BASE, "restart", "preserve me");
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger calls = new AtomicInteger();

        EditorAutosaveController safe = new EditorAutosaveController(
                new DraftStore(root), NOTE, BASE, scheduler, Runnable::run,
                draft -> { calls.incrementAndGet(); return SAVED_ONE; }, () -> BASE);
        assertTrue(safe.recovery().safeToApply());
        EditorAutosaveController stale = new EditorAutosaveController(
                new DraftStore(root), NOTE, REMOTE, scheduler, Runnable::run,
                draft -> { calls.incrementAndGet(); return SAVED_ONE; }, () -> REMOTE);
        assertFalse(stale.recovery().safeToApply());
        assertEquals("preserve me", stale.recovery().draft().content());
        assertEquals(0, calls.get());
    }
}
