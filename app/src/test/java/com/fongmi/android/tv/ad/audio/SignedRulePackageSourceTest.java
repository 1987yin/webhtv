package com.fongmi.android.tv.ad.audio;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SignedRulePackageSourceTest {

    private static final String PACKAGE_ID = "official.audio.ads";
    private static final String DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    public void verifiedPackageMapsToStableSignedSnapshotIdentity() {
        VerifiedRulePackage verified = verifiedPackage(42);
        SignedRulePackageSource source = new SignedRulePackageSource(
                PACKAGE_ID,
                () -> SignedRulePackageStore.LoadResult.success(
                        verified, false, 42, List.of()));

        AdAudioRuleSnapshot snapshot = source.load();

        assertEquals("signed:" + PACKAGE_ID, snapshot.sourceId());
        assertEquals("42:0123456789abcdef", snapshot.version());
        assertEquals(verified.ruleSet(), snapshot.ruleSet());
        assertTrue(snapshot.warnings().isEmpty());
        assertFalse(snapshot.hasError());
    }

    @Test
    public void recoveredPreviousAddsCacheRecoveredWarningExactlyOnce() {
        SignedRulePackageSource source = new SignedRulePackageSource(
                PACKAGE_ID,
                () -> SignedRulePackageStore.LoadResult.success(
                        verifiedPackage(7), true, 9, List.of("CACHE_RECOVERED")));

        AdAudioRuleSnapshot snapshot = source.load();

        assertEquals(List.of("CACHE_RECOVERED"), snapshot.warnings());
    }

    @Test
    public void unavailableCacheMapsItsSpecificErrorToAnEmptySnapshot() {
        SignedRulePackageSource source = new SignedRulePackageSource(
                PACKAGE_ID,
                () -> SignedRulePackageStore.LoadResult.failure(
                        8, SignedRulePackageException.Code.CACHE_IO_FAILED));

        AdAudioRuleSnapshot snapshot = source.load();

        assertEquals("signed:" + PACKAGE_ID, snapshot.sourceId());
        assertEquals("", snapshot.version());
        assertFalse(snapshot.hasRules());
        assertEquals("CACHE_IO_FAILED", snapshot.lastError());
    }

    private static VerifiedRulePackage verifiedPackage(long revision) {
        return new VerifiedRulePackage(
                PACKAGE_ID,
                revision,
                1_000L,
                2_000L,
                DIGEST,
                new byte[32],
                "{}",
                AudioFingerprintRuleSet.empty(),
                "test-key",
                "ED25519");
    }
}
