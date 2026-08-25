package com.fongmi.android.tv.player;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class PlayerManagerLifecycleSourceTest {

    @Test
    public void releasedEngineClassificationIsNullSafeForLateCallbacks() throws Exception {
        String source = readPlayerManager();

        assertTrue("isLive must tolerate a late callback after engine release",
                source.contains("public boolean isLive() {\n        return engine != null && engine.isLive();"));
        assertTrue("isVod must tolerate a late callback after engine release",
                source.contains("public boolean isVod() {\n        return engine != null && engine.isVod();"));
    }

    /**
     * 以下三条锁的是实现文本（本仓库 source-text 断言约定）。若日后搬走或改名这些方法，
     * 必须同步改这里的断言 —— 断言变红说明约定被破坏，而不是测试坏了。
     */
    @Test
    public void bufferingStallMustNotHijackAManualKernelSwitch() throws Exception {
        String source = readPlayerManager();
        int start = source.indexOf("private void onBufferingStall(");
        assertTrue("onBufferingStall must exist", start >= 0);
        String body = source.substring(start, source.indexOf("\n    }", start));
        assertTrue("onBufferingStall must report a manual switch instead of auto-falling back",
                body.contains("manualPlayerSwitchPending"));
    }

    @Test
    public void newMediaItemCancelsTheStallWatchdog() throws Exception {
        String source = readPlayerManager();
        int start = source.indexOf("private void setMediaItemNow(");
        assertTrue("setMediaItemNow must exist", start >= 0);
        String body = source.substring(start, source.indexOf("\n    }", start));
        assertTrue("a new media item must invalidate the previous episode baseline",
                body.contains("cancelBufferingStallWatchdog()"));
    }

    @Test
    public void bufferingBranchKeepsTheAlreadyArmedGuard() throws Exception {
        String source = readPlayerManager();
        // Without this guard the seek path's cancel+arm degrades into a repeated re-arm that
        // keeps resetting the baseline, so the stall would never be reported.
        assertTrue("BUFFERING branch must only arm when not already armed",
                source.contains("if (!bufferingStallWatchdog.isArmed()) armBufferingStallWatchdog();"));
    }

    private static String readPlayerManager() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        Path source = root.resolve(Path.of(
                "app", "src", "main", "java", "com", "fongmi", "android", "tv", "player", "PlayerManager.java"));
        if (!Files.exists(source)) {
            source = root.resolve(Path.of(
                    "src", "main", "java", "com", "fongmi", "android", "tv", "player", "PlayerManager.java"));
        }
        return Files.readString(source, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
