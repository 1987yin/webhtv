package com.fongmi.android.tv.setting;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AppBrandingContractTest {

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
    public void dynamicLauncherShortcutIsNeededForCustomIconOnly() {
        assertTrue(AppBranding.needsPinnedShortcut(AppBranding.ICON_CUSTOM));
        assertTrue(!AppBranding.needsPinnedShortcut(AppBranding.ICON_CURRENT));
        assertTrue(!AppBranding.needsPinnedShortcut(AppBranding.ICON_HISTORY));
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
    public void legacyShortcutFallbackIsPreOOnly() throws Exception {
        String source = read("app/src/main/java/com/fongmi/android/tv/setting/AppBranding.java");

        assertTrue(source.contains("if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return false;"));
    }

    @Test
    public void mainManifestGrantsLegacyLauncherShortcutPermissions() throws Exception {
        String manifest = read("app/src/main/AndroidManifest.xml");

        assertTrue(manifest.contains("com.android.launcher.permission.INSTALL_SHORTCUT"));
        assertTrue(manifest.contains("com.android.launcher.permission.UNINSTALL_SHORTCUT"));
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
    public void sharedBrandingUiIsIconOnly() throws Exception {
        String layout = read("app/src/main/res/layout/activity_app_branding.xml");

        assertTrue(layout.contains("@string/app_branding_icon_select"));
        assertFalse(layout.contains("@+id/nameLayout"));
        assertFalse(layout.contains("@string/app_branding_name_hint"));
        assertFalse(layout.contains("@string/app_branding_summary"));

        String chinese = read("app/src/main/res/values-zh-rCN/strings.xml");
        String english = read("app/src/main/res/values/strings.xml");
        String traditional = read("app/src/main/res/values-zh-rTW/strings.xml");

        assertTrue(english.contains("<string name=\"setting_app_branding\">App icon</string>"));
        assertTrue(chinese.contains("<string name=\"setting_app_branding\">APP 图标</string>"));
        assertTrue(traditional.contains("<string name=\"setting_app_branding\">APP 圖示</string>"));
        assertTrue(chinese.contains("<string name=\"app_name\">默影视</string>"));
        assertTrue(english.contains("<string name=\"app_name\">默影视</string>"));
        assertTrue(traditional.contains("<string name=\"app_name\">默影視</string>"));
        assertFalse(chinese.contains("app_name_history"));
        assertFalse(english.contains("app_name_history"));
        assertFalse(traditional.contains("app_name_history"));
        assertFalse(chinese.contains("app_branding_summary"));
        assertFalse(chinese.contains("app_branding_name_hint"));
        assertFalse(english.contains("app_branding_summary"));
        assertFalse(english.contains("app_branding_name_hint"));
        assertFalse(traditional.contains("app_branding_summary"));
        assertFalse(traditional.contains("app_branding_name_hint"));
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

        assertEquals(2, countOccurrences(mobile, "android:label=\"@string/app_name\""));
        assertEquals(2, countOccurrences(leanback, "android:label=\"@string/app_name\""));
        assertEquals(0, countOccurrences(mobile, "@string/app_name_history"));
        assertEquals(0, countOccurrences(leanback, "@string/app_name_history"));
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

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
