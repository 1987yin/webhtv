package com.fongmi.android.tv.player;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class MpvStabilityGuardSourceTest {

    @Test
    public void runtimeAutoEvaluationAppliesStabilityGuardBeforeTransition() throws Exception {
        String method = methodBody(readPlayerManager(), "private boolean evaluateMpvAutoOutput()", "private boolean shouldLeaveAutoSurfaceDirectForSubtitle");

        assertTrue(method.contains("boolean effectiveEligible = MpvPerformanceSetting.isAutoSurfaceDirectEnabled() && decision.eligible();"));
        assertTrue(method.contains("MpvAutoOutputPolicy.transition(effectiveEligible, currentlyDirect)"));
    }

    @Test
    public void stickyAutoDirectRequiresStabilityGuardPermission() throws Exception {
        String method = methodBody(readPlayerManager(), "private void prepareMpvOutputForNewItem()", "private void resetMpvOutputRuntime()");

        assertTrue(method.contains("automaticOutput && MpvPerformanceSetting.isAutoSurfaceDirectEnabled()"));
    }

    private static String readPlayerManager() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        Path source = root.resolve(Path.of("app", "src", "main", "java", "com", "fongmi", "android", "tv", "player", "PlayerManager.java"));
        if (!Files.exists(source)) source = root.resolve(Path.of("src", "main", "java", "com", "fongmi", "android", "tv", "player", "PlayerManager.java"));
        return Files.readString(source, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String methodBody(String source, String startToken, String endToken) {
        int start = source.indexOf(startToken);
        int end = source.indexOf(endToken, start);
        assertTrue("Missing source token: " + startToken, start >= 0);
        assertTrue("Missing source token after " + startToken + ": " + endToken, end > start);
        return source.substring(start, end);
    }
}
