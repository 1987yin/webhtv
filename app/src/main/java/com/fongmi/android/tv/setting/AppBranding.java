package com.fongmi.android.tv.setting;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.github.catvod.utils.Prefers;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Owns the user-facing app name/icon settings and their launcher integration. */
public final class AppBranding {

    public static final int ICON_CURRENT = 0;
    public static final int ICON_HISTORY = 1;
    public static final int ICON_CUSTOM = 2;

    private static final String NAME_KEY = "app_branding_name";
    private static final String ICON_KEY = "app_branding_icon";
    private static final String CUSTOM_ICON_FILE = "app_branding_icon.png";
    private static final String CUSTOM_ICON_TEMP_FILE = ".app_branding_icon.tmp";
   private static final String CUSTOM_SHORTCUT_ID = "app_branding_custom";
    private static final String ALIAS_SUFFIX_CURRENT = "Current";
    private static final String ALIAS_SUFFIX_HISTORY = "History";

   private static final int MAX_NAME_CODE_POINTS = 32;
    private static final int MAX_ICON_BYTES = 5 * 1024 * 1024;
    private static final int MAX_ICON_DIMENSION = 4096;
    private static final int ICON_SIZE = 512;

    private AppBranding() {
    }

    @NonNull
    public static String normalizeName(@Nullable String value) {
        if (value == null) return "";
        String name = value.trim();
        if (name.isEmpty()) return "";
        int codePoints = name.codePointCount(0, name.length());
        if (codePoints <= MAX_NAME_CODE_POINTS) return name;
        return name.substring(0, name.offsetByCodePoints(0, MAX_NAME_CODE_POINTS));
    }

    public static int normalizeIconMode(int mode) {
        return mode == ICON_HISTORY || mode == ICON_CUSTOM ? mode : ICON_CURRENT;
    }

    @NonNull
    public static String getCustomName() {
        return normalizeName(Prefers.getString(NAME_KEY));
    }

    @NonNull
    public static String getName(@NonNull Context context) {
        String name = getCustomName();
        return name.isEmpty() ? context.getString(R.string.app_name) : name;
    }

    public static void putName(@Nullable String name) {
        Prefers.put(NAME_KEY, normalizeName(name));
    }

    public static int getIconMode() {
        return normalizeIconMode(Prefers.getInt(ICON_KEY, ICON_CURRENT));
    }

    public static int getIconMode(@NonNull Context context) {
        int mode = getIconMode();
        return mode == ICON_CUSTOM && !hasCustomIcon(context) ? ICON_CURRENT : mode;
    }

    public static void putIconMode(int mode) {
        Prefers.put(ICON_KEY, normalizeIconMode(mode));
    }

    public static boolean hasCustomIcon(@NonNull Context context) {
        File file = customIconFile(context);
        return file.isFile() && file.length() > 0 && loadCustomIcon(context) != null;
    }

    @Nullable
    public static Bitmap loadCustomIcon(@NonNull Context context) {
        File file = customIconFile(context);
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_ICON_BYTES) return null;

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (!validBounds(bounds.outWidth, bounds.outHeight)) return null;

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    /** Copies and normalizes a user-selected image without retaining the source URI. */
    public static boolean copyCustomIcon(@NonNull Context context, @Nullable Uri uri) {
        if (uri == null) return false;
        try {
            ContentResolver resolver = context.getContentResolver();
            String mime = resolver.getType(uri);
            if (mime != null && !mime.startsWith("image/")) return false;

            byte[] data = readLimited(resolver, uri);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (!validBounds(bounds.outWidth, bounds.outHeight)) return false;

            int sample = 1;
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            while (largest / sample > ICON_SIZE) sample *= 2;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inSampleSize = sample;
            Bitmap source = BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (source == null) return false;

            Bitmap normalized = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(normalized);
            float scale = Math.min((float) ICON_SIZE / source.getWidth(), (float) ICON_SIZE / source.getHeight());
            float width = source.getWidth() * scale;
            float height = source.getHeight() * scale;
            RectF destination = new RectF((ICON_SIZE - width) / 2, (ICON_SIZE - height) / 2,
                    (ICON_SIZE + width) / 2, (ICON_SIZE + height) / 2);
            canvas.drawBitmap(source, null, destination, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
            source.recycle();

            boolean written = writeCustomIcon(context, normalized);
            normalized.recycle();
            return written;
        } catch (Exception ignored) {
            return false;
        }
    }

    @NonNull
    public static String getSummary(@NonNull Context context) {
        return context.getString(R.string.app_branding_summary, getName(context),
                context.getString(iconLabelResource(getIconMode(context))));
    }

    public static int iconLabelResource(int mode) {
        return switch (normalizeIconMode(mode)) {
            case ICON_HISTORY -> R.string.app_branding_icon_history;
            case ICON_CUSTOM -> R.string.app_branding_icon_custom;
            default -> R.string.app_branding_icon_current;
        };
    }

    /** Synchronizes the static launcher aliases with the persisted built-in choice. */
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

    /** Requests a desktop shortcut for the custom icon/name pair. */
    public static boolean requestCustomShortcut(@NonNull Activity activity) {
        if (!hasCustomIcon(activity) || !ShortcutManagerCompat.isRequestPinShortcutSupported(activity)) return false;
        Bitmap bitmap = loadCustomIcon(activity);
        if (bitmap == null) return false;

        Intent launch = new Intent(Intent.ACTION_MAIN)
                .setComponent(new ComponentName(activity, HomeActivity.class));
        ShortcutInfoCompat info = new ShortcutInfoCompat.Builder(activity, CUSTOM_SHORTCUT_ID)
                .setShortLabel(getName(activity))
                .setLongLabel(getName(activity))
                .setIcon(IconCompat.createWithBitmap(bitmap))
                .setIntent(launch)
                .build();
        return ShortcutManagerCompat.requestPinShortcut(activity, info, null);
    }

   private static void setComponentEnabled(@NonNull Context context, @NonNull String className, boolean enabled) {
       try {
           context.getPackageManager().setComponentEnabledSetting(
                    new ComponentName(context.getPackageName(), className),
                   enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                   PackageManager.DONT_KILL_APP);
       } catch (RuntimeException ignored) {
            // Older launchers may not expose alias state immediately; the next launch retries.
        }
    }

    private static boolean validBounds(int width, int height) {
        return width > 0 && height > 0 && width <= MAX_ICON_DIMENSION && height <= MAX_ICON_DIMENSION
                && (long) width * height <= (long) MAX_ICON_DIMENSION * MAX_ICON_DIMENSION;
    }

    @NonNull
    private static byte[] readLimited(@NonNull ContentResolver resolver, @NonNull Uri uri) throws IOException {
        try (InputStream input = resolver.openInputStream(uri); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("image stream unavailable");
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_ICON_BYTES) throw new IOException("image too large");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static boolean writeCustomIcon(@NonNull Context context, @NonNull Bitmap bitmap) throws IOException {
        File directory = context.getFilesDir();
        if (!directory.exists() && !directory.mkdirs()) return false;
        File target = customIconFile(context);
        File temp = new File(directory, CUSTOM_ICON_TEMP_FILE);
        if (temp.exists() && !temp.delete()) return false;

        try (FileOutputStream output = new FileOutputStream(temp)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return false;
        }
        if (target.exists() && !target.delete()) {
            temp.delete();
            return false;
        }
        if (!temp.renameTo(target)) {
            temp.delete();
            return false;
        }
        return true;
    }

    @NonNull
    private static File customIconFile(@NonNull Context context) {
        return new File(context.getFilesDir(), CUSTOM_ICON_FILE);
    }
}
