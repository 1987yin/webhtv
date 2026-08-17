package com.fongmi.android.tv.ad.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

public class AdAudioRuleSourceContractTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void localStoreImplementsStableSourceContract() throws Exception {
        Path directory = temporaryFolder.newFolder("rules").toPath();
        AdAudioRuleSource source = new AdAudioRuleStore(directory);

        AdAudioRuleSnapshot snapshot = source.load();

        assertEquals("local", snapshot.sourceId());
        assertFalse(snapshot.hasRules());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.warnings().add("mutable"));
    }
}
