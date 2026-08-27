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
    public void customNameTakesPriorityOverHomeAndConfigNames() {
        assertEquals("Custom", AppBranding.resolveDisplayName("Custom", "Home", "Config", "Default"));
        assertEquals("Home", AppBranding.resolveDisplayName("", "Home", "Config", "Default"));
        assertEquals("Config", AppBranding.resolveDisplayName("", "", "Config", "Default"));
        assertEquals("Default", AppBranding.resolveDisplayName("", "", "", "Default"));
    }

    @Test
    public void dynamicLauncherShortcutIsNeededForCustomNameOrIcon() {
        assertTrue(AppBranding.needsPinnedShortcut("Custom", AppBranding.ICON_CURRENT));
        assertTrue(AppBranding.needsPinnedShortcut("", AppBranding.ICON_CUSTOM));
        assertTrue(!AppBranding.needsPinnedShortcut("", AppBranding.ICON_CURRENT));
        assertTrue(!AppBranding.needsPinnedShortcut("", AppBranding.ICON_HISTORY));
    }

    @Test
    public void unsupportedPinnedShortcutFeedbackIsNotOverwritten() {
        assertEquals(com.fongmi.android.tv.R.string.app_branding_shortcut_unsupported,
                AppBranding.saveFeedbackResource(true, false));
        assertEquals(0, AppBranding.saveFeedbackResource(true, true));
        assertEquals(0, AppBranding.saveFeedbackResource(false, false));
    }

    @Test
    public void launcherNameHasLegacyShortcutFallback() throws Exception {
        String source = read("app/src/main/java/com/fongmi/android/tv/setting/AppBranding.java");

        assertTrue(source.contains("com.android.launcher.action.INSTALL_SHORTCUT"));
        assertTrue(source.contains("Intent.EXTRA_SHORTCUT_NAME"));
        assertTrue(source.contains("queryBroadcastReceivers(intent, 0)"));
        assertTrue(source.contains("sendBroadcast"));
    }

    @Test
    public void chineseLauncherNamesDistinguishNewAndOriginalVersions() throws Exception {
        String chinese = read("app/src/main/res/values-zh-rCN/strings.xml");

        assertTrue(chinese.contains("<string name=\"app_name\">默影视新版</string>"));
        assertTrue(chinese.contains("<string name=\"app_name_history\">影视原版</string>"));
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
    public void sharedBrandingLayoutUsesResourcesAvailableToBothFlavors() throws Exception {
        String layout = read("app/src/main/res/layout/activity_app_branding.xml");

        assertTrue(layout.contains("android:background=\"?attr/selectableItemBackground\""));
        assertTrue(layout.contains("@drawable/ic_action_back"));
        assertTrue(layout.contains("@drawable/ic_action_choose"));
        assertTrue(!layout.contains("@drawable/selector_item"));
        assertTrue(!layout.contains("@drawable/ic_detail_back"));
    }

    @Test
    public void mobileManifestRegistersSharedBrandingActivity() throws Exception {
        String mobile = read("app/src/mobile/AndroidManifest.xml");

        assertTrue(mobile.contains("android:name=\".ui.activity.AppBrandingActivity\""));
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
