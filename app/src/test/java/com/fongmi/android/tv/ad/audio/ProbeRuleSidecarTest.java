package com.fongmi.android.tv.ad.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class ProbeRuleSidecarTest {

    private static final long REVISION = 8L;

    @Test
    public void validSidecarBindsToSignedPayload() throws Exception {
        byte[] sourceDigest = digest("signed-v2");
        byte[] canonicalRules = rules();
        ProbeRuleSidecar sidecar = sidecar(sourceDigest, canonicalRules);

        sidecar.requireBoundTo(REVISION, sourceDigest);

        assertEquals(REVISION, sidecar.sourceRevision());
        assertEquals(ProbeRuleSidecar.ALGORITHM_ID, sidecar.algorithm());
        assertEquals("converter-1", sidecar.converterVersion());
        assertArrayEquals(canonicalRules, sidecar.canonicalRules());
    }

    @Test
    public void bindingRejectsRevisionMismatch() throws Exception {
        byte[] sourceDigest = digest("signed-v2");
        ProbeRuleSidecar sidecar = sidecar(sourceDigest, rules());

        assertThrows(IllegalArgumentException.class,
                () -> sidecar.requireBoundTo(REVISION + 1L, sourceDigest));
    }

    @Test
    public void bindingRejectsSourceDigestMismatch() throws Exception {
        ProbeRuleSidecar sidecar = sidecar(digest("signed-v2"), rules());

        assertThrows(IllegalArgumentException.class,
                () -> sidecar.requireBoundTo(REVISION, digest("other-v2")));
    }

    @Test
    public void verifiedRejectsSidecarDigestMismatch() throws Exception {
        byte[] canonicalRules = rules();

        assertThrows(IllegalArgumentException.class,
                () -> ProbeRuleSidecar.verified(REVISION, digest("signed-v2"),
                        ProbeRuleSidecar.ALGORITHM_ID, "converter-1", canonicalRules,
                        digest("different-sidecar")));
    }

    @Test
    public void verifiedRejectsUnsupportedAlgorithm() throws Exception {
        byte[] canonicalRules = rules();

        assertThrows(IllegalArgumentException.class,
                () -> ProbeRuleSidecar.verified(REVISION, digest("signed-v2"),
                        "spectral-sequence-v2", "converter-1", canonicalRules,
                        digest(canonicalRules)));
    }

    @Test
    public void verifiedRejectsOversizedCanonicalRules() throws Exception {
        byte[] oversized = new byte[AdAudioRuleStore.MAX_IMPORT_BYTES + 1];

        assertThrows(IllegalArgumentException.class,
                () -> ProbeRuleSidecar.verified(REVISION, digest("signed-v2"),
                        ProbeRuleSidecar.ALGORITHM_ID, "converter-1", oversized,
                        digest(oversized)));
    }

    @Test
    public void byteArraysAreDefensivelyCopied() throws Exception {
        byte[] sourceDigest = digest("signed-v2");
        byte[] canonicalRules = rules();
        byte[] sidecarDigest = digest(canonicalRules);
        ProbeRuleSidecar sidecar = ProbeRuleSidecar.verified(REVISION, sourceDigest,
                ProbeRuleSidecar.ALGORITHM_ID, "converter-1", canonicalRules, sidecarDigest);

        sourceDigest[0] ^= 1;
        canonicalRules[0] ^= 1;
        sidecarDigest[0] ^= 1;

        byte[] first = sidecar.canonicalRules();
        byte[] second = sidecar.canonicalRules();
        assertNotSame(first, second);
        assertArrayEquals(rules(), first);
        assertArrayEquals(digest("signed-v2"), sidecar.sourceDigest());
        assertArrayEquals(digest(rules()), sidecar.sidecarDigest());
    }

    private static ProbeRuleSidecar sidecar(byte[] sourceDigest, byte[] canonicalRules)
            throws Exception {
        return ProbeRuleSidecar.verified(REVISION, sourceDigest,
                ProbeRuleSidecar.ALGORITHM_ID, "converter-1", canonicalRules,
                digest(canonicalRules));
    }

    private static byte[] rules() {
        return ("{\"format\":\"ad-audio-probe-rules\",\"schemaVersion\":1,"
                + "\"revision\":8,\"rules\":[]}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] digest(String value) throws Exception {
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] digest(byte[] value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }
}
