package com.fongmi.android.tv.setting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MpvOutputModePolicyTest {

    @Test
    public void directModeRequiresHardwareDecode() {
        assertTrue(MpvPerformanceSetting.resolveSurfaceDirect(MpvPerformanceSetting.OUTPUT_SURFACE_DIRECT, false, true, true));
        assertFalse(MpvPerformanceSetting.resolveSurfaceDirect(MpvPerformanceSetting.OUTPUT_SURFACE_DIRECT, true, true, false));
    }

    @Test
    public void gpuModeNeverUsesSurfaceDirect() {
        assertFalse(MpvPerformanceSetting.resolveSurfaceDirect(MpvPerformanceSetting.OUTPUT_GPU, true, true, true));
    }

    @Test
    public void autoModeStaysOnGpuDuringStabilityGuard() {
        assertFalse(MpvPerformanceSetting.resolveSurfaceDirect(MpvPerformanceSetting.OUTPUT_AUTO, true, true, true));
        assertFalse(MpvPerformanceSetting.resolveSurfaceDirect(MpvPerformanceSetting.OUTPUT_AUTO, false, true, true));
        assertFalse(MpvPerformanceSetting.resolveSurfaceDirect(MpvPerformanceSetting.OUTPUT_AUTO, true, false, true));
    }

    @Test
    public void stabilityGuardDisablesSurfaceFrameRateSwitching() {
        assertEquals(MpvPerformanceSetting.FRAME_RATE_OFF, MpvPerformanceSetting.resolveFrameRateMode(MpvPerformanceSetting.FRAME_RATE_SEAMLESS));
        assertEquals(MpvPerformanceSetting.FRAME_RATE_OFF, MpvPerformanceSetting.resolveFrameRateMode(MpvPerformanceSetting.FRAME_RATE_OFF));
    }
}
