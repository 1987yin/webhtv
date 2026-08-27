package com.fongmi.android.tv.setting;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AppBrandingContractTest {

    @Test
    public void normalizeNameTrimsAndCapsUnicodeCodePoints() {
        assertEquals("", AppBranding.normalizeName("  \n  "));
        assertEquals("自定义名称", AppBranding.normalizeName("  自定义名称  "));

        String longName = "😀".repeat(40);
        String normalized = AppBranding.normalizeName(longName);
        assertEquals(32, normalized.codePointCount(0, normalized.length()));
    }

    @Test
    public void normalizeIconModeFallsBackToCurrentForUnknownValues() {
        assertEquals(AppBranding.ICON_CURRENT, AppBranding.normalizeIconMode(-1));
        assertEquals(AppBranding.ICON_CURRENT, AppBranding.normalizeIconMode(99));
        assertEquals(AppBranding.ICON_CURRENT, AppBranding.normalizeIconMode(AppBranding.ICON_CURRENT));
        assertEquals(AppBranding.ICON_HISTORY, AppBranding.normalizeIconMode(AppBranding.ICON_HISTORY));
        assertEquals(AppBranding.ICON_CUSTOM, AppBranding.normalizeIconMode(AppBranding.ICON_CUSTOM));
    }

    @Test
    public void launcherAliasUsesActivityNamespaceInsteadOfApplicationId() {
        String homeActivity = "com.fongmi.android.tv.ui.activity.HomeActivity";

        assertEquals(homeActivity + "Current",
                AppBranding.launcherAliasClassName(homeActivity, AppBranding.ICON_CURRENT));
        assertEquals(homeActivity + "History",
                AppBranding.launcherAliasClassName(homeActivity, AppBranding.ICON_HISTORY));
        assertEquals(homeActivity + "Current",
                AppBranding.launcherAliasClassName(homeActivity, AppBranding.ICON_CUSTOM));
    }

    @Test
    public void bothPersonalSettingsExposeAppBrandingEntry() throws Exception {
        String mobile = read("app/src/mobile/res/layout/fragment_setting_personal.xml");
        String leanback = read("app/src/leanback/res/layout/activity_setting_personal.xml");

        assertTrue(mobile.contains("@+id/appBranding"));
        assertTrue(mobile.contains("@+id/appBrandingText"));
        assertTrue(leanback.contains("@+id/appBranding"));
        assertTrue(leanback.contains("@+id/appBrandingText"));
    }

    @Test
    public void bothProductManifestsExposeOnlyAliasLauncherEntries() throws Exception {
        String mobile = read("app/src/mobile/AndroidManifest.xml");
        String leanback = read("app/src/leanback/AndroidManifest.xml");

        assertLauncherAliases(mobile, false);
        assertLauncherAliases(leanback, true);
    }

    private static void assertLauncherAliases(String manifest, boolean leanback) {
        assertTrue(manifest.contains(".ui.activity.HomeActivityCurrent"));
        assertTrue(manifest.contains(".ui.activity.HomeActivityHistory"));
        assertTrue(manifest.contains("android:targetActivity=\".ui.activity.HomeActivity\""));
        assertTrue(manifest.contains("android:enabled=\"true\""));
        assertTrue(manifest.contains("android:enabled=\"false\""));
        if (leanback) assertTrue(manifest.contains("android.intent.category.LEANBACK_LAUNCHER"));
    }

    private static String read(String relative) throws Exception {
        Path path = Path.of(relative);
        if (!Files.exists(path) && relative.startsWith("app/")) path = Path.of(relative.substring(4));
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
