package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class LegacyRepositoryMigratorTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final UUID LIVE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CONFLICT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID DELETED = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant NOW = Instant.parse("2026-10-24T14:00:00Z");

    @Test public void migratesLiveNoteConflictAndDeletionThenErasesLegacyHistory() throws Exception {
        File legacyRoot = temporary.newFolder("legacy");
        ProbeRepository legacy = ProbeRepository.open(legacyRoot);
        legacy.createInitialRevision(LIVE, "# Live title\n\nLive body", "android", NOW);
        RevisionEnvelope base = legacy.createInitialRevision(
                CONFLICT, "# Base\n\nbase", "android", NOW);
        importRevision(legacy, RevisionEnvelope.create(
                CONFLICT, List.of(base.revisionId()), "android", NOW.plusSeconds(1),
                false, "# Local\n\nlocal body"));
        importRevision(legacy, RevisionEnvelope.create(
                CONFLICT, List.of(base.revisionId()), "linux", NOW.plusSeconds(2),
                false, "# Remote\n\nremote body"));
        RevisionEnvelope deletedBase = legacy.createInitialRevision(
                DELETED, "# Erase me\n\nsecret", "android", NOW);
        importRevision(legacy, RevisionEnvelope.create(
                DELETED, List.of(deletedBase.revisionId()), "android", NOW.plusSeconds(3),
                true, ""));

        File parent = temporary.newFolder("migration");
        File staging = new File(parent, "v2.staging");
        File active = new File(parent, "v2");
        LegacyRepositoryMigrator.Report report =
                LegacyRepositoryMigrator.migrate(legacy, staging, active);

        assertEquals(1, report.liveNotes());
        assertEquals(1, report.conflicts());
        assertEquals(1, report.deletions());
        CurrentNoteStore migrated = CurrentNoteStore.open(active, "android");
        assertEquals("Live title", migrated.get(LIVE).title());
        assertEquals("Live body", migrated.get(LIVE).body());
        assertEquals(2, migrated.conflict(CONFLICT).candidates().size());
        assertNull(migrated.get(DELETED));
        StateEnvelope stale = StateEnvelope.createUpsert(
                DELETED, List.of(), "linux", NOW, "Erase me", "secret");
        assertEquals("rejected-deleted", migrated.apply(stale).kind());
        assertFalse(legacyRoot.exists());
    }

    @Test public void incompleteStagingIsRebuiltAndDoesNotBecomeActiveEarly() throws Exception {
        File legacyRoot = temporary.newFolder("legacy-interrupted");
        ProbeRepository legacy = ProbeRepository.open(legacyRoot);
        legacy.createInitialRevision(LIVE, "# Title\n\nBody", "android", NOW);
        File parent = temporary.newFolder("interrupted");
        File staging = new File(parent, "v2.staging");
        assertTrue(staging.mkdirs());
        Files.write(new File(staging, "partial.txt").toPath(),
                "incomplete".getBytes(StandardCharsets.UTF_8));
        File active = new File(parent, "v2");

        LegacyRepositoryMigrator.migrate(legacy, staging, active);

        assertTrue(active.isDirectory());
        assertFalse(staging.exists());
        assertFalse(new File(active, "partial.txt").exists());
        assertNotNull(CurrentNoteStore.open(active, "android").get(LIVE));
    }

    @Test public void missingAncestryRefusesWithoutSwitchingOrRemovingLegacy() throws Exception {
        File legacyRoot = temporary.newFolder("legacy-missing");
        ProbeRepository legacy = ProbeRepository.open(legacyRoot);
        importRevision(legacy, RevisionEnvelope.create(
                LIVE, List.of("f".repeat(64)), "android", NOW, false, "# Title\n\nBody"));
        File parent = temporary.newFolder("missing");
        File active = new File(parent, "v2");

        assertThrows(IllegalStateException.class, () -> LegacyRepositoryMigrator.migrate(
                legacy, new File(parent, "v2.staging"), active));

        assertFalse(active.exists());
        assertTrue(legacyRoot.isDirectory());
    }

    @Test public void malformedHistoryRefusesWithoutSwitching() throws Exception {
        File legacyRoot = temporary.newFolder("legacy-malformed");
        ProbeRepository legacy = ProbeRepository.open(legacyRoot);
        File directory = new File(legacyRoot, "history/" + LIVE);
        assertTrue(directory.mkdirs());
        Files.write(new File(directory, "a".repeat(64) + ".md").toPath(),
                "not a revision".getBytes(StandardCharsets.UTF_8));
        File parent = temporary.newFolder("malformed");

        assertThrows(IllegalStateException.class, () -> LegacyRepositoryMigrator.migrate(
                legacy, new File(parent, "v2.staging"), new File(parent, "v2")));

        assertTrue(legacyRoot.isDirectory());
    }

    @Test public void moreThanTwoLiveHeadsRefusesWithoutSwitching() throws Exception {
        File legacyRoot = temporary.newFolder("legacy-three-heads");
        ProbeRepository legacy = ProbeRepository.open(legacyRoot);
        RevisionEnvelope base = legacy.createInitialRevision(LIVE, "# Base\n\nbase", "android", NOW);
        for (int index = 0; index < 3; index++) {
            importRevision(legacy, RevisionEnvelope.create(
                    LIVE, List.of(base.revisionId()), "device-" + index,
                    NOW.plusSeconds(index + 1), false, "# Head " + index + "\n\nbody " + index));
        }
        File parent = temporary.newFolder("three-heads");

        assertThrows(IllegalStateException.class, () -> LegacyRepositoryMigrator.migrate(
                legacy, new File(parent, "v2.staging"), new File(parent, "v2")));

        assertTrue(legacyRoot.isDirectory());
    }

    private static void importRevision(ProbeRepository repository, RevisionEnvelope revision) {
        ProbeRepository.ImportOutcome outcome = repository.importEnvelope(
                revision.serialize(), revision.revisionId() + ".md");
        assertTrue(Set.of("imported", "duplicate").contains(outcome.kind()));
    }
}
