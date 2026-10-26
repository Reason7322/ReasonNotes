package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class SharedExchangeDirectoryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static final UUID NOTE =
            UUID.fromString("e9af0882-4664-45e2-b9c7-8c1da30dc06c");

    @Test public void importsCanonicalCarrierQueuesAckAndSkipsProcessedReplay()
            throws Exception {
        File exchange = temporary.newFolder("exchange");
        File privateRoot = temporary.newFolder("private");
        File index = new File(privateRoot, "processed.tsv");
        CurrentNoteStore store = CurrentNoteStore.open(
                new File(privateRoot, "reasonnotes-v2"), "android");
        StateEnvelope state = StateEnvelope.createUpsert(
                NOTE, List.of(), "linux", Instant.parse("2026-10-25T12:49:00Z"),
                "sync-v2-roundtrip", "Created on Fedora for physical acceptance.");
        TransportBundle.Carrier carrier = TransportBundle.create(List.of(
                new TransportBundle.Item("state-v2", state.changeId(), state.serialize())));
        Files.write(new File(exchange, carrier.filename()).toPath(), carrier.payload());
        Files.write(new File(exchange, "unrelated.txt").toPath(),
                "ignore".getBytes(StandardCharsets.UTF_8));

        ExchangeDirectory.ScanReport first = SharedExchangeDirectory.scan(
                exchange, index, store, "android", ignored -> {});

        assertEquals(1, first.imported());
        assertEquals(1, first.receiptsQueued());
        assertEquals(1, first.ignored());
        assertTrue(first.errors().isEmpty());
        assertEquals(state, store.get(NOTE));
        assertEquals(1, store.pendingAcks().size());

        ExchangeDirectory.ScanReport second = SharedExchangeDirectory.scan(
                exchange, index, store, "android", ignored -> {});
        assertEquals(0, second.imported());
        assertEquals(0, second.duplicates());
        assertEquals(0, second.receiptsQueued());
        assertTrue(second.errors().isEmpty());

        store.deleteLocal(NOTE);
        ExchangeDirectory.ScanReport afterDeletion = SharedExchangeDirectory.scan(
                exchange, index, store, "android", ignored -> {});
        assertEquals(0, afterDeletion.imported());
        assertNull(store.get(NOTE));
    }

    @Test public void malformedCarrierIsReportedAndNeverCreatesNote() throws Exception {
        File exchange = temporary.newFolder("broken-exchange");
        File privateRoot = temporary.newFolder("broken-private");
        File index = new File(privateRoot, "processed.tsv");
        CurrentNoteStore store = CurrentNoteStore.open(
                new File(privateRoot, "reasonnotes-v2"), "android");
        byte[] invalid = "{}".getBytes(StandardCharsets.UTF_8);
        String name = "reasonnotes-bundle-v1-" + StateEnvelope.sha256(invalid) + ".json";
        Files.write(new File(exchange, name).toPath(), invalid);

        ExchangeDirectory.ScanReport report = SharedExchangeDirectory.scan(
                exchange, index, store, "android", ignored -> {});

        assertEquals(1, report.rejectedRevisions());
        assertEquals(1, report.errors().size());
        assertNull(store.get(NOTE));
        assertEquals(0, store.pendingAcks().size());

        ExchangeDirectory.ScanReport replay = SharedExchangeDirectory.scan(
                exchange, index, store, "android", ignored -> {});
        assertEquals(0, replay.rejectedRevisions());
        assertTrue(replay.errors().isEmpty());
    }
}
