package com.fongmi.android.tv.web;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class WebThemeRuntimeWiringTest {

    @Test
    public void controllerKeepsCompatibilityFacadeOverTheExtractedRuntimePieces() throws Exception {
        String controller = read("HomeWebController.java");
        String localBridge = read("HomeWebBridge.java");
        String bridge = read("WebHomeThemeBridge.java");

        assertTrue(controller.contains("private final WebThemeManifestResolver manifestResolver;"));
        assertTrue(controller.contains("private final WebThemePageHost pageHost;"));
        assertTrue(controller.contains("private final WebThemeSession themeSession;"));
        assertTrue(controller.contains("private final Object themeStateLock = new Object();"));
        assertTrue(controller.contains("manifestResolver.resolvePage(configured, page, force)"));
        assertTrue(controller.contains("ThemeRuntimeSnapshot getThemeRuntimeSnapshot()"));
        assertTrue(localBridge.contains("HomeWebController.ThemeRuntimeSnapshot runtime = controller.getThemeRuntimeSnapshot()"));
        assertTrue(bridge.contains("HomeWebController.ThemeRuntimeSnapshot runtime = controller.getThemeRuntimeSnapshot()"));
        assertTrue(bridge.contains("new CallContext(runtime)"));
        assertTrue(bridge.contains("private final WebThemeCallRouter callRouter;"));
        assertTrue(bridge.contains("callRouter.invoke(method, payload, context.target, active"));
        assertTrue(bridge.contains("private String dispatch(WebThemeCallRouter.Api api"));
    }

    @Test
    public void lifecycleCancelsOldCallsAndReplacesReferencesOnlyAtDocumentBoundaries() throws Exception {
        String controller = read("HomeWebController.java");
        String pageStarted = section(controller, "public void onPageStarted", "public void onPageFinished");
        String pageFinished = section(controller, "public void onPageFinished", "public void onReceivedError");
        String pageFinishGuard = section(controller, "private boolean isCurrentPageFinish",
                "private WebViewClient client()");
        String pause = section(controller, "public void onPause()", "public boolean beginInlineEvaluation");
        String resume = section(controller, "public void onResume()", "public void onPause()");
        String reload = section(controller, "public void reload()", "public void reloadExtensions()");
        String destroy = section(controller, "public void destroy()", "private void cancelPendingNativePlaybacks()");
        String recreate = section(controller, "private boolean recreateWebView()", "private void recoverAfterResume()");
        String failure = section(controller, "private void handleMainFrameFailure", "private WebChromeClient chrome()");

        assertTrue(pageStarted.contains("if (destroyed || view != webView) return;"));
        assertTrue(ordered(pageStarted, "bridgeReady = false;", "themeSession.invalidate();"));
        assertTrue(ordered(pageStarted, "themeSession.invalidate();", "rotateRemoteDocumentNonce();"));
        assertTrue(pageFinished.contains("if (!isCurrentPageFinish(view, url)) return;"));
        assertFalse(pageFinishGuard.contains("pageStartUrl"));
        assertFalse(pageFinishGuard.contains("view.getOriginalUrl()"));
        assertTrue(pageFinishGuard.contains("return !destroyed && view == webView && isCurrentPageFinish(url, view.getUrl());"));
        assertTrue(ordered(pause, "paused = true;", "themeSession.cancelPending();"));
        assertTrue(ordered(resume, "themeSession.cancelPending();", "paused = false;"));
        assertTrue(reload.contains("themeSession.invalidate();"));
        assertTrue(ordered(destroy, "themeSession.invalidate();", "webView.destroy();"));
        assertTrue(ordered(recreate, "themeSession.invalidate();", "if (parent == null) return false;"));
        assertTrue(ordered(failure, "bridgeReady = false;", "themeSession.invalidate();"));
    }

    @Test
    public void pageFinishGuardAcceptsRedirectedCurrentDocumentAndRejectsStaleDocument() {
        assertTrue(HomeWebController.isCurrentPageFinish(
                "https://theme.example/final", "https://theme.example/final"));
        assertFalse(HomeWebController.isCurrentPageFinish(
                "https://theme.example/old", "https://theme.example/final"));
    }

    @Test
    public void pageAndSessionStateArePublishedAtomically() throws Exception {
        String controller = read("HomeWebController.java");
        String setDetail = section(controller, "void setDetailVod", "WebThemeDetailMetadata getDetailMetadata");
        String loadResolved = section(controller, "private boolean loadResolved", "public void reload()");
        String snapshot = section(controller, "ThemeRuntimeSnapshot getThemeRuntimeSnapshot()",
                "Vod getDetailVod()");

        assertTrue(setDetail.contains("synchronized (themeStateLock)"));
        assertTrue(setDetail.contains("boolean setDetailVodIfCurrent(ThemeRuntimeSnapshot expected"));
        assertTrue(setDetail.contains("expected.page().target() != pageHost.target()"));
        assertTrue(setDetail.contains("!themeSession.isCurrent(expected.session().generation())"));
        assertTrue(loadResolved.contains("synchronized (themeStateLock)"));
        assertTrue(snapshot.contains("synchronized (themeStateLock)"));
        assertTrue(snapshot.contains("pageHost.snapshot()"));
        assertTrue(snapshot.contains("themeSession.snapshot()"));
    }

    @Test
    public void remoteCallsArePinnedToBothDocumentAndThemeGenerations() throws Exception {
        String controller = read("HomeWebController.java");
        String remote = section(controller, "private void handleRemoteMessage", "private boolean isRemoteSession");

        assertTrue(remote.contains("int generation, int themeGeneration"));
        assertTrue(remote.contains("isRemoteThemeSession(expectedOrigin, generation, requestNonce, themeGeneration)"));
        assertTrue(remote.contains("bridge.invoke(method, payload"));
        assertTrue(controller.contains("&& isRemoteBridgeSessionActive(themeGeneration)"));
    }

    private static boolean ordered(String source, String first, String second) {
        int firstIndex = source.indexOf(first);
        int secondIndex = source.indexOf(second, Math.max(0, firstIndex + first.length()));
        return firstIndex >= 0 && secondIndex > firstIndex;
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("Missing source token: " + start, from >= 0);
        assertTrue("Missing source token after " + start + ": " + end, to > from);
        return source.substring(from, to);
    }

    private static String read(String name) throws Exception {
        Path root = Path.of("src", "main", "java", "com", "fongmi", "android", "tv", "web");
        if (!Files.exists(root)) root = Path.of("app", "src", "main", "java", "com", "fongmi", "android", "tv", "web");
        return Files.readString(root.resolve(name), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
