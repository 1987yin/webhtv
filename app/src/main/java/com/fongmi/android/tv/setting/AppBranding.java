package com.fongmi.android.tv.setting;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.github.catvod.utils.Prefers;

/** Owns the two supported built-in app branding choices. */
public final class AppBranding {

    public static final int ICON_CURRENT = 0;
    public static final int ICON_HISTORY = 1;

    private static final String ICON_KEY = "app_branding_icon";
    private static final String ALIAS_SUFFIX_CURRENT = "Current";
    private static final String ALIAS_SUFFIX_HISTORY = "History";

    private AppBranding() {
    }

    /** Old custom mode values intentionally migrate to the default built-in icon. */
    public static int normalizeIconMode(int mode) {
        return mode == ICON_HISTORY ? ICON_HISTORY : ICON_CURRENT;
    }

    @NonNull
    public static String getName(@NonNull Context context) {
        return context.getString(R.string.app_name);
    }

    @NonNull
    public static String getDisplayName(@NonNull Context context, @Nullable String homeName, @Nullable String configName) {
        return getName(context);
    }

    public static int getIconMode() {
        return normalizeIconMode(Prefers.getInt(ICON_KEY, ICON_CURRENT));
    }

    public static int getIconMode(@NonNull Context context) {
        return getIconMode();
    }

    public static void putIconMode(int mode) {
        Prefers.put(ICON_KEY, normalizeIconMode(mode));
    }

    @NonNull
    public static String getSummary(@NonNull Context context) {
        return context.getString(iconLabelResource(getIconMode(context)));
    }

    /** Uses the selected built-in icon in the app UI with a circular presentation. */
    public static void applyLogo(@NonNull ImageView view) {
        int resource = getIconMode(view.getContext()) == ICON_HISTORY
                ? R.drawable.ic_launcher_history : R.mipmap.ic_launcher;
        Glide.with(view).load(resource).circleCrop().into(view);
    }

    public static int iconLabelResource(int mode) {
        return normalizeIconMode(mode) == ICON_HISTORY
                ? R.string.app_branding_icon_history : R.string.app_branding_icon_current;
    }

    public static void applyLauncherIcon(@NonNull Context context) {
        boolean history = getIconMode(context) == ICON_HISTORY;
        String homeActivity = HomeActivity.class.getName();
        setComponentEnabled(context, launcherAliasClassName(homeActivity, ICON_CURRENT), !history);
        setComponentEnabled(context, launcherAliasClassName(homeActivity, ICON_HISTORY), history);
    }

    static String launcherAliasClassName(@NonNull String homeActivityClassName, int mode) {
        return homeActivityClassName + (normalizeIconMode(mode) == ICON_HISTORY
                ? ALIAS_SUFFIX_HISTORY : ALIAS_SUFFIX_CURRENT);
    }

    @NonNull
    public static Intent launcherIntent(@NonNull Context context) {
        String alias = launcherAliasClassName(HomeActivity.class.getName(), getIconMode(context));
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(context.getPackageName(), alias));
    }

    private static void setComponentEnabled(@NonNull Context context, @NonNull String className, boolean enabled) {
        try {
            context.getPackageManager().setComponentEnabledSetting(
                    new ComponentName(context.getPackageName(), className),
                    enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (RuntimeException ignored) {
            // The next app launch retries the alias synchronization.
        }
    }
}
