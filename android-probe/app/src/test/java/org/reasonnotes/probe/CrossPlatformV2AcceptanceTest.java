package org.reasonnotes.probe;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class CrossPlatformV2AcceptanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static final UUID NOTE =
            UUID.fromString("dddddddd-4444-4444-8444-dddddddddddd");
    private static final UUID SECOND =
            UUID.fromString("eeeeeeee-5555-4555-8555-eeeeeeeeeeee");
    private static final Instant T0 = Instant.parse("2026-10-25T18:00:00Z");

    private record Transcript(
            StateEnvelope created,
            StateEnvelope fedoraEdit,
            StateEnvelope androidEdit,
            StateEnvelope resolution,
            StateEnvelope androidDeletion,
            StateAckEnvelope fedoraAck,
            StateEnvelope secondaryCreate,
            StateEnvelope fedoraDeletion) {}

    private static Transcript transcript() {
        StateEnvelope created = StateEnvelope.createUpsert(
                NOTE, List.of(), "android", T0, "Shared", "base");
        StateEnvelope fedoraEdit = StateEnvelope.createUpsert(
                NOTE, List.of(created.changeId()), "linux", T0.plusSeconds(60),
                "Shared", "Fedora edit");
        StateEnvelope androidEdit = StateEnvelope.createUpsert(
                NOTE, List.of(created.changeId()), "android", T0.plusSeconds(120),
                "Shared", "Android edit");
        StateEnvelope resolution = StateEnvelope.createUpsert(
                NOTE, List.of(fedoraEdit.changeId(), androidEdit.changeId()),
                "linux", T0.plusSeconds(180), "Shared", "Android edit");
        StateEnvelope androidDeletion = StateEnvelope.createDeletion(
                NOTE, List.of(resolution.changeId()), "android", T0.plusSeconds(240));
        StateAckEnvelope fedoraAck = StateAckEnvelope.create(
                NOTE, androidDeletion.changeId(), "linux", "android",
                T0.plusSeconds(300));
        StateEnvelope secondaryCreate = StateEnvelope.createUpsert(
                SECOND, List.of(), "android", T0.plusSeconds(360),
                "Erase both", "Never retain");
        StateEnvelope fedoraDeletion = StateEnvelope.createDeletion(
                SECOND, List.of(secondaryCreate.changeId()), "linux",
                T0.plusSeconds(420));
        return new Transcript(created, fedoraEdit, androidEdit, resolution,
                androidDeletion, fedoraAck, secondaryCreate, fedoraDeletion);
    }

    private static byte[] carrier(String kind, String identity, byte[] envelope) {
        return TransportBundle.create(List.of(
                new TransportBundle.Item(kind, identity, envelope))).payload();
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream input = CrossPlatformV2AcceptanceTest.class.getResourceAsStream(
                "/sync-v2-cross-platform/" + name)) {
            if (input == null) throw new IOException("missing fixture " + name);
            return input.readAllBytes();
        }
    }

    private static void writeGeneratedFixture(String name, byte[] expected)
            throws IOException {
        String output = System.getenv("REASONNOTES_ANDROID_FIXTURE_OUTPUT");
        if (output == null) return;
        File directory = new File(output);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("cannot create fixture output");
        }
        Files.write(new File(directory, name).toPath(), expected);
    }

    private static void assertFixture(String name, byte[] expected) throws IOException {
        assertArrayEquals(name, expected, resource(name));
    }

    private static CurrentTransport.ScanReport applyFixture(
            String name, CurrentNoteStore store) throws IOException {
        byte[] payload = resource(name);
        String filename = "reasonnotes-bundle-v1-"
                + StateEnvelope.sha256(payload) + ".json";
        return CurrentTransport.applyBundle(payload, filename, store, "android");
    }

    @Test public void platformGeneratedCarriersAreByteIdentical() throws Exception {
        Transcript values = transcript();
        byte[] androidCreate = carrier(
                "state-v2", values.created.changeId(), values.created.serialize());
        byte[] androidConflict = carrier(
                "state-v2", values.androidEdit.changeId(), values.androidEdit.serialize());
        byte[] androidDelete = carrier(
                "state-v2", values.androidDeletion.changeId(),
                values.androidDeletion.serialize());
        byte[] androidSecondaryCreate = carrier(
                "state-v2", values.secondaryCreate.changeId(),
                values.secondaryCreate.serialize());
        writeGeneratedFixture("android-create.carrier.json", androidCreate);
        writeGeneratedFixture("android-conflict.carrier.json", androidConflict);
        writeGeneratedFixture("android-delete.carrier.json", androidDelete);
        writeGeneratedFixture("android-secondary-create.carrier.json", androidSecondaryCreate);

        assertFixture("android-create.carrier.json", androidCreate);
        assertFixture("fedora-edit.carrier.json", carrier(
                "state-v2", values.fedoraEdit.changeId(), values.fedoraEdit.serialize()));
        assertFixture("android-conflict.carrier.json", androidConflict);
        assertFixture("fedora-resolution.carrier.json", carrier(
                "state-v2", values.resolution.changeId(), values.resolution.serialize()));
        assertFixture("android-delete.carrier.json", androidDelete);
        assertFixture("fedora-ack.carrier.json", carrier(
                "ack-v2", values.fedoraAck.ackId(), values.fedoraAck.serialize()));
        assertFixture("android-secondary-create.carrier.json", androidSecondaryCreate);
        assertFixture("fedora-delete.carrier.json", carrier(
                "state-v2", values.fedoraDeletion.changeId(),
                values.fedoraDeletion.serialize()));
    }

    @Test public void fedoraCarriersResolveAConflictDeleteAndConfirmOnAndroid()
            throws Exception {
        Transcript values = transcript();
        CurrentNoteStore store = CurrentNoteStore.open(temporary.newFolder("android"));
        assertEquals(values.created, store.saveLocal(
                NOTE, "Shared", "base", T0));
        store.acknowledge(values.created.changeId());
        assertEquals(values.androidEdit, store.saveLocal(
                NOTE, "Shared", "Android edit", T0.plusSeconds(120)));

        CurrentTransport.ScanReport editReport = applyFixture(
                "fedora-edit.carrier.json", store);
        assertEquals(1, editReport.imported());
        assertEquals(1, editReport.conflicts());
        assertTrue(editReport.errors().isEmpty());
        assertEquals(2, store.conflict(NOTE).candidates().size());

        CurrentTransport.ScanReport resolutionReport = applyFixture(
                "fedora-resolution.carrier.json", store);
        assertEquals(1, resolutionReport.imported());
        assertTrue(resolutionReport.errors().isEmpty());
        assertNull(store.conflict(NOTE));
        assertEquals("Android edit", store.get(NOTE).body());

        store.deleteLocal(NOTE);
        CurrentTransport.ScanReport ackReport = applyFixture(
                "fedora-ack.carrier.json", store);
        assertEquals(1, ackReport.confirmed());
        assertTrue(ackReport.errors().isEmpty());
        assertTrue(store.pendingStates().stream()
                .noneMatch(state -> state.noteId().equals(NOTE)));
        assertEquals("rejected-deleted", store.apply(values.created).kind());
        assertNull(store.get(NOTE));

        assertEquals(values.secondaryCreate, store.saveLocal(
                SECOND, "Erase both", "Never retain", T0.plusSeconds(360)));
        CurrentTransport.ScanReport deletionReport = applyFixture(
                "fedora-delete.carrier.json", store);
        assertEquals(1, deletionReport.imported());
        assertTrue(deletionReport.errors().isEmpty());
        assertNull(store.get(SECOND));

        ArrayList<byte[]> files = new ArrayList<>();
        try (var paths = Files.walk(new File(temporary.getRoot(), "android").toPath())) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                try { files.add(Files.readAllBytes(path)); }
                catch (IOException error) { throw new IllegalStateException(error); }
            });
        }
        byte[] combined = String.join("", files.stream()
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8)).toList())
                .getBytes(StandardCharsets.UTF_8);
        assertFalse(new String(combined, StandardCharsets.UTF_8).contains("Android edit"));
        assertFalse(new String(combined, StandardCharsets.UTF_8).contains("Fedora edit"));
        assertFalse(new String(combined, StandardCharsets.UTF_8).contains("Erase both"));
        assertFalse(new String(combined, StandardCharsets.UTF_8).contains("Never retain"));
    }
}
