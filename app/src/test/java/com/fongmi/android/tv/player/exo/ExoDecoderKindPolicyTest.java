package com.fongmi.android.tv.player.exo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ExoDecoderKindPolicyTest {

    @Test
    public void nextlibFfmpegRenderersAreSoftware() {
        // Observed on device: the video row reported this while the profile said 硬解.
        assertEquals(ExoDecoderKindPolicy.Kind.SOFTWARE,
                ExoDecoderKindPolicy.classify("ffmpegLavc63.3.100-hevc"));
        assertEquals(ExoDecoderKindPolicy.Kind.SOFTWARE,
                ExoDecoderKindPolicy.classify("ffmpegLavc63.3.100-eac3"));
    }

    @Test
    public void platformSoftwareDecodersAreSoftware() {
        assertEquals(ExoDecoderKindPolicy.Kind.SOFTWARE,
                ExoDecoderKindPolicy.classify("OMX.google.h264.decoder"));
        assertEquals(ExoDecoderKindPolicy.Kind.SOFTWARE,
                ExoDecoderKindPolicy.classify("c2.android.hevc.decoder"));
    }

    @Test
    public void vendorDecodersAreHardware() {
        assertEquals(ExoDecoderKindPolicy.Kind.HARDWARE,
                ExoDecoderKindPolicy.classify("c2.mtk.hevc.decoder"));
        assertEquals(ExoDecoderKindPolicy.Kind.HARDWARE,
                ExoDecoderKindPolicy.classify("OMX.amlogic.hevc.decoder"));
        assertEquals(ExoDecoderKindPolicy.Kind.HARDWARE,
                ExoDecoderKindPolicy.classify("c2.goldfish.h264.decoder"));
    }

    @Test
    public void classificationIsCaseInsensitiveAndTrimmed() {
        assertEquals(ExoDecoderKindPolicy.Kind.SOFTWARE,
                ExoDecoderKindPolicy.classify("  FFMPEGLavc63-hevc "));
        assertEquals(ExoDecoderKindPolicy.Kind.SOFTWARE,
                ExoDecoderKindPolicy.classify("omx.GOOGLE.h264.decoder"));
    }

    @Test
    public void missingNameIsUnknownNotHardware() {
        assertEquals(ExoDecoderKindPolicy.Kind.UNKNOWN, ExoDecoderKindPolicy.classify(null));
        assertEquals(ExoDecoderKindPolicy.Kind.UNKNOWN, ExoDecoderKindPolicy.classify(""));
        assertEquals(ExoDecoderKindPolicy.Kind.UNKNOWN, ExoDecoderKindPolicy.classify("   "));
    }

    @Test
    public void mismatchOnlyClaimedWhenProfileIsHardwareAndDecoderIsSoftware() {
        assertTrue(ExoDecoderKindPolicy.isHardwareProfileRunningSoftware(
                true, "ffmpegLavc63.3.100-hevc"));
        assertFalse(ExoDecoderKindPolicy.isHardwareProfileRunningSoftware(
                true, "c2.mtk.hevc.decoder"));
        // A soft-decode profile running software is not a mismatch.
        assertFalse(ExoDecoderKindPolicy.isHardwareProfileRunningSoftware(
                false, "ffmpegLavc63.3.100-hevc"));
    }

    @Test
    public void unknownDecoderNeverProducesAClaim() {
        assertFalse(ExoDecoderKindPolicy.isHardwareProfileRunningSoftware(true, null));
        assertFalse(ExoDecoderKindPolicy.isHardwareProfileRunningSoftware(true, ""));
        assertEquals("硬解", ExoDecoderKindPolicy.decodeLabel("硬解", true, null));
    }

    @Test
    public void labelShowsBothSidesOnlyOnMismatch() {
        assertEquals("硬解→软解",
                ExoDecoderKindPolicy.decodeLabel("硬解", true, "ffmpegLavc63.3.100-hevc"));
        assertEquals("硬解",
                ExoDecoderKindPolicy.decodeLabel("硬解", true, "c2.mtk.hevc.decoder"));
        assertEquals("软解",
                ExoDecoderKindPolicy.decodeLabel("软解", false, "ffmpegLavc63.3.100-hevc"));
    }
}
