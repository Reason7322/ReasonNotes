package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class EditorActivityContractTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final UUID NOTE = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final String BASE = "a".repeat(64);
    private static final String SAVED = "b".repeat(64);

    @Test
    public void oneHeadIsEditableAndConflictUsesAStableNonHeadMarker() {
        assertEquals(BASE, EditorActivity.singleHeadOrConflictMarker(Set.of(BASE)));
        assertEquals("0".repeat(64), EditorActivity.singleHeadOrConflictMarker(Set.of(BASE, SAVED)));
        assertEquals("0".repeat(64), EditorActivity.singleHeadOrConflictMarker(Set.of()));
    }

    @Test
    public void editorCanRenderDirtySavingAndSavedWithoutClaimingEarlySuccess() throws Exception {
        File root = temporary.newFolder("drafts");
        DraftStore drafts = new DraftStore(root);
        ArrayList<EditorAutosaveController.Status> observed = new ArrayList<>();
        ArrayList<Runnable> delayed = new ArrayList<>();
        AtomicReference<String> head = new AtomicReference<>(BASE);
        EditorAutosaveController controller = new EditorAutosaveController(
                drafts,
                NOTE,
                BASE,
                (action, delay) -> { delayed.add(action); return () -> delayed.remove(action); },
                Runnable::run,
                draft -> { head.set(SAVED); return SAVED; },
                head::get,
                observed::add);

        controller.edit("Title", "Body");
        assertEquals(EditorAutosaveController.Status.DIRTY, observed.get(observed.size() - 1));
        assertEquals(EditorAutosaveController.Status.SAVED, controller.flush().get(5, TimeUnit.SECONDS));

        assertEquals(
                List.of(
                        EditorAutosaveController.Status.DIRTY,
                        EditorAutosaveController.Status.SAVING,
                        EditorAutosaveController.Status.SAVED),
                observed);
    }
}
