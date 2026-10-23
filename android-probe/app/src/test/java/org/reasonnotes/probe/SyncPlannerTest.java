package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class SyncPlannerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final UUID NOTE_A = UUID.fromString("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa");
    private static final UUID NOTE_B = UUID.fromString("bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb");

    private record Fixture(
            ProbeRepository repository,
            DeliveryLedger ledger,
            ReceiptStore receipts,
            SyncPlanner planner) {}

    private Fixture fixture() throws Exception {
        File root = temporary.newFolder("repo");
        ProbeRepository repository = ProbeRepository.open(root);
        DeliveryLedger ledger = new DeliveryLedger(new File(root, "delivery-state"), 3);
        ReceiptStore receipts = new ReceiptStore(root, 3);
        return new Fixture(
                repository,
                ledger,
                receipts,
                new SyncPlanner(repository, ledger, receipts, "android", "linux"));
    }

    private static Instant at(int second) {
        return Instant.parse(String.format("2026-10-15T07:00:%02dZ", second));
    }

    @Test
    public void allAndSelectedModesFilterOrdinaryOutgoingNotesWithoutForce() throws Exception {
        Fixture fixture = fixture();
        RevisionEnvelope a1 = fixture.repository().createRevision(
                NOTE_A, Set.of(), "# A1", "android", at(0), false);
        RevisionEnvelope a2 = fixture.repository().createRevision(
                NOTE_A, Set.of(a1.revisionId()), "# A2", "android", at(1), false);
        RevisionEnvelope b1 = fixture.repository().createRevision(
                NOTE_B, Set.of(), "# B1", "android", at(2), false);

        SyncPlanner.Plan all = fixture.planner().plan(OutgoingSelection.all(), false);
        SyncPlanner.Plan selected = fixture.planner().plan(
                OutgoingSelection.selected(Set.of(NOTE_A)), false);
        SyncPlanner.Plan empty = fixture.planner().plan(
                OutgoingSelection.selected(Set.of()), false);

        assertEquals(Set.of(a1.revisionId(), a2.revisionId(), b1.revisionId()), Set.copyOf(all.revisionIds()));
        assertEquals(Set.of(a1.revisionId(), a2.revisionId()), Set.copyOf(selected.revisionIds()));
        assertEquals(List.of(), empty.revisionIds());
        assertEquals(List.of(b1.revisionId()), selected.skippedRevisionIds());
    }

    @Test
    public void selectedDeletedNoteSendsNeitherTombstoneNorOriginalHistory() throws Exception {
        Fixture fixture = fixture();
        RevisionEnvelope ancestor = fixture.repository().createRevision(
                NOTE_A, Set.of(), "# Shared", "android", at(0), false);
        RevisionEnvelope tombstone = fixture.repository().createRevision(
                NOTE_A, Set.of(ancestor.revisionId()), "", "android", at(1), true);
        RevisionEnvelope other = fixture.repository().createRevision(
                NOTE_B, Set.of(), "# Other", "android", at(2), false);

        SyncPlanner.Plan plan = fixture.planner().plan(
                OutgoingSelection.selected(Set.of(NOTE_A)), false);

        assertEquals(List.of(), plan.revisionIds());
        assertFalse(plan.revisionIds().contains(other.revisionId()));
        assertEquals(List.of(), fixture.planner().plan(
                OutgoingSelection.all(), true).revisionIds().stream()
                .filter(id -> id.equals(ancestor.revisionId()) || id.equals(tombstone.revisionId()))
                .toList());
        // The original tombstone history remains stored as evidence; this
        // change affects selection/outgoing note files, not stored history.
        assertTrue(fixture.repository().revisions().contains(tombstone));
    }

    @Test
    public void childWaitsForUnconfirmedParentUnlessBoundedRetryIsRequested() throws Exception {
        Fixture fixture = fixture();
        RevisionEnvelope parent = fixture.repository().createRevision(
                NOTE_A, Set.of(), "# Parent", "android", at(0), false);
        fixture.planner().plan(OutgoingSelection.all(), false);
        fixture.ledger().recordRequested(parent.revisionId(), "linux", at(1));
        RevisionEnvelope child = fixture.repository().createRevision(
                NOTE_A, Set.of(parent.revisionId()), "# Child", "android", at(2), false);

        SyncPlanner.Plan ordinary = fixture.planner().plan(
                OutgoingSelection.selected(Set.of(NOTE_A)), false);
        SyncPlanner.Plan retry = fixture.planner().plan(
                OutgoingSelection.selected(Set.of(NOTE_A)), true);

        assertEquals(List.of(), ordinary.revisionIds());
        assertTrue(ordinary.blockedByAncestry().contains(child.revisionId()));
        assertEquals(Set.of(parent.revisionId(), child.revisionId()), Set.copyOf(retry.revisionIds()));
    }

    @Test
    public void pendingDescendantRecoversExhaustedAncestorWithoutBlockingOtherNotes()
            throws Exception {
        Fixture fixture = fixture();
        RevisionEnvelope ancestor = fixture.repository().createRevision(
                NOTE_A, Set.of(), "# Old", "android", at(0), false);
        fixture.planner().plan(OutgoingSelection.all(), false);
        fixture.ledger().recordRequested(ancestor.revisionId(), "linux", at(1));
        fixture.ledger().recordRequested(ancestor.revisionId(), "linux", at(2));
        fixture.ledger().recordRequested(ancestor.revisionId(), "linux", at(3));
        RevisionEnvelope descendant = fixture.repository().createRevision(
                NOTE_A, Set.of(ancestor.revisionId()), "# Current", "android", at(4), false);
        RevisionEnvelope independent = fixture.repository().createRevision(
                NOTE_B, Set.of(), "# Independent", "android", at(5), false);

        SyncPlanner.Plan plan = fixture.planner().plan(OutgoingSelection.all(), false);

        assertEquals(Set.of(ancestor.revisionId(), descendant.revisionId(),
                        independent.revisionId()), Set.copyOf(plan.revisionIds()));
        assertEquals(List.of(), plan.blockedByAncestry());
    }

    @Test
    public void deletedNoteIsNeverResentEvenWithConfirmedAncestor() throws Exception {
        Fixture fixture = fixture();
        RevisionEnvelope ancestor = fixture.repository().createRevision(
                NOTE_A, Set.of(), "# Shared", "android", at(0), false);
        fixture.planner().plan(OutgoingSelection.all(), false);
        fixture.ledger().recordRequested(ancestor.revisionId(), "linux", at(1));
        fixture.ledger().recordConfirmed(ancestor.revisionId(), "linux", "f".repeat(64), at(2));
        RevisionEnvelope tombstone = fixture.repository().createRevision(
                NOTE_A, Set.of(ancestor.revisionId()), "", "android", at(3), true);

        SyncPlanner.Plan plan = fixture.planner().plan(
                OutgoingSelection.selected(Set.of(NOTE_A)), false);

        assertEquals(List.of(), plan.revisionIds());
        assertTrue(fixture.repository().revisions().contains(tombstone));
    }

    @Test
    public void receiptsRemainEligibleForEmptySelection() throws Exception {
        Fixture fixture = fixture();
        RevisionEnvelope remote = RevisionEnvelope.create(
                NOTE_B, List.of(), "linux", at(0), false, "# Incoming");
        fixture.repository().importEnvelope(remote.serialize(), "incoming.md");
        ReceiptEnvelope receipt = fixture.receipts().queue(remote, "android", at(1));

        SyncPlanner.Plan plan = fixture.planner().plan(
                OutgoingSelection.selected(Set.of()), false);

        assertEquals(List.of(), plan.revisionIds());
        assertEquals(List.of(receipt.receiptId()), plan.receiptIds());
    }

    @Test
    public void selectedRetryIncludesOnlyReceiptsForTheSelectedNote() throws Exception {
        Fixture fixture = fixture();
        RevisionEnvelope remoteA = RevisionEnvelope.create(
                NOTE_A, List.of(), "linux", at(0), false, "# Incoming A");
        RevisionEnvelope remoteB = RevisionEnvelope.create(
                NOTE_B, List.of(), "linux", at(1), false, "# Incoming B");
        fixture.repository().importEnvelope(remoteA.serialize(), "incoming-a.md");
        fixture.repository().importEnvelope(remoteB.serialize(), "incoming-b.md");
        ReceiptEnvelope receiptA = fixture.receipts().queue(remoteA, "android", at(2));
        ReceiptEnvelope receiptB = fixture.receipts().queue(remoteB, "android", at(3));
        fixture.receipts().recordRequested(receiptA.receiptId(), "linux", at(4));
        fixture.receipts().recordRequested(receiptB.receiptId(), "linux", at(5));

        SyncPlanner.Plan selectedRetry = fixture.planner().plan(
                OutgoingSelection.selected(Set.of(NOTE_A)), true);

        assertEquals(List.of(receiptA.receiptId()), selectedRetry.receiptIds());
    }

    @Test
    public void forceRetryIsSeparateFromOrdinarySelectionAndStillBoundedExplicitly() throws Exception {
        Fixture fixture = fixture();
        RevisionEnvelope a = fixture.repository().createRevision(
                NOTE_A, Set.of(), "# A", "android", at(0), false);
        RevisionEnvelope b = fixture.repository().createRevision(
                NOTE_B, Set.of(), "# B", "android", at(1), false);
        fixture.planner().plan(OutgoingSelection.all(), false);
        fixture.ledger().recordRequested(b.revisionId(), "linux", at(2));
        fixture.ledger().recordRequested(b.revisionId(), "linux", at(3));
        fixture.ledger().recordRequested(b.revisionId(), "linux", at(4));

        SyncPlanner.Plan selected = fixture.planner().plan(
                OutgoingSelection.selected(Set.of(NOTE_A)), false);
        SyncPlanner.Plan forced = fixture.planner().planForced(
                List.of(b.revisionId()), List.of());

        assertEquals(List.of(a.revisionId()), selected.revisionIds());
        assertEquals(List.of(b.revisionId()), forced.revisionIds());
    }

    @Test
    public void largeCatalogueUsesSingleHistoryAndDeliverySnapshots() throws Exception {
        Fixture fixture = fixture();
        java.util.LinkedHashMap<UUID, String> expected = new java.util.LinkedHashMap<>();
        Instant base = Instant.parse("2026-10-15T07:00:00Z");
        for (int i = 0; i < 18; i++) {
            UUID id = UUID.nameUUIDFromBytes(("perf-note-" + i)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.util.Set<String> parents = Set.of();
            for (int revision = 0; revision < 7; revision++) {
                String name = "Note " + i;
                RevisionEnvelope saved = fixture.repository().createRevision(
                        id, parents, "# " + name + "\n\nrevision " + revision,
                        "android", base.plusSeconds(i * 8L + revision), false);
                parents = Set.of(saved.revisionId());
            }
            expected.put(id, "Note " + i);
        }

        long began = System.nanoTime();
        List<SyncPlanner.CatalogueItem> catalogue = fixture.planner().catalogue();
        long ms = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - began);
        System.out.println("ANDROID_SYNC_PICKER_18_NOTES_126_REVISIONS_JVM_MS=" + ms);
        assertEquals(18, catalogue.size());
        for (SyncPlanner.CatalogueItem item : catalogue) {
            assertEquals(expected.get(item.noteId()), item.title());
            assertEquals("pending", item.deliveryState());
            assertFalse(item.conflicted());
            assertFalse(item.deleted());
        }
        assertEquals(126, fixture.ledger().entries("linux").size());
        assertTrue("Catalogue regression: " + ms + "ms", ms < 2500);
        assertEquals(catalogue, fixture.planner().catalogue());
    }

    @Test
    public void catalogueHidesDeletedTombstonesEvenWhenTheirDeliveryIsPending() throws Exception {
        Fixture fixture = fixture();
        RevisionEnvelope a = fixture.repository().createRevision(
                NOTE_A, Set.of(), "# Original title\n\nbody", "android", at(0), false);
        RevisionEnvelope deleted = fixture.repository().createRevision(
                NOTE_A, Set.of(a.revisionId()), "", "android", at(1), true);
        RevisionEnvelope baseB = fixture.repository().createRevision(
                NOTE_B, Set.of(), "# B\n\nbase", "android", at(2), false);
        RevisionEnvelope linux = RevisionEnvelope.create(
                NOTE_B, List.of(baseB.revisionId()), "linux", at(3), false, "# Linux\n\nedit");
        RevisionEnvelope android = RevisionEnvelope.create(
                NOTE_B, List.of(baseB.revisionId()), "android", at(4), false, "# Android\n\nedit");
        fixture.repository().importEnvelope(linux.serialize(), "linux.md");
        fixture.repository().importEnvelope(android.serialize(), "android.md");

        List<SyncPlanner.CatalogueItem> items = fixture.planner().catalogue();

        assertFalse(items.stream().anyMatch(item -> item.noteId().equals(NOTE_A)));
        assertEquals(1, items.size());
        assertTrue(items.get(0).conflicted());
        assertFalse(items.get(0).deleted());
        assertTrue(fixture.ledger().entry(deleted.revisionId(), "linux") != null);
        assertTrue(fixture.repository().revisions().contains(deleted));
    }
}
