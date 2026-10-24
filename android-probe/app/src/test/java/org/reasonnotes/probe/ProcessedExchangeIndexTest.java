package org.reasonnotes.probe;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ProcessedExchangeIndexTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void validatedFingerprintSurvivesRestartAndChangedMetadataIsNotSkipped()
            throws Exception {
        File state = new File(temporary.newFolder("state"), "processed.tsv");
        ProcessedExchangeIndex first = new ProcessedExchangeIndex(state);
        assertFalse(first.contains("carrier.json", 100L, 507L));
        first.record("carrier.json", 100L, 507L);
        first.save();

        ProcessedExchangeIndex reopened = new ProcessedExchangeIndex(state);
        assertTrue(reopened.contains("carrier.json", 100L, 507L));
        assertFalse(reopened.contains("carrier.json", 101L, 507L));
        assertFalse(reopened.contains("carrier.json", 100L, 508L));
        assertFalse(reopened.contains("other.json", 100L, 507L));
    }

    @Test
    public void unreliableProviderMetadataIsNeverTreatedAsProcessed() throws Exception {
        ProcessedExchangeIndex index = new ProcessedExchangeIndex(
                new File(temporary.newFolder("unknown"), "processed.tsv"));
        index.record("carrier.json", 0L, -1L);
        index.save();
        assertFalse(index.contains("carrier.json", 0L, -1L));
    }
}
