package com.fongmi.android.tv.setting;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.transition.Transition;
import com.bumptech.glide.request.target.CustomTarget;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.github.catvod.utils.Prefers;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Owns the user-facing app name/icon settings and their launcher integration. */
public final class AppBranding {

    public static final int ICON_CURRENT = 0;
    public static final int ICON_HISTORY = 1;
    public static final int ICON_CUSTOM = 2;

    private static final String ICON_KEY = "app_branding_icon";
    private static final String CUSTOM_ICON_FILE = "app_branding_icon.png";
    private static final String CUSTOM_ICON_TEMP_FILE = ".app_branding_icon.tmp";
    private static final String CUSTOM_SHORTCUT_ID = "app_branding_custom";
    private static final String LEGACY_SHORTCUT_NAME_KEY = "app_branding_legacy_shortcut_name";
    private static final String LEGACY_SHORTCUT_MODE_KEY = "app_branding_legacy_shortcut_mode";
    private static final String ACTION_INSTALL_SHORTCUT = "com.android.launcher.action.INSTALL_SHORTCUT";
    private static final String ACTION_UNINSTALL_SHORTCUT = "com.android.launcher.action.UNINSTALL_SHORTCUT";
    private static final String ALIAS_SUFFIX_CURRENT = "Current";
    private static final String ALIAS_SUFFIX_HISTORY = "History";

    private static final int MAX_ICON_BYTES = 5 * 1024 * 1024;
    private static final int MAX_ICON_DIMENSION = 4096;
    private static final int ICON_SIZE = 512;

    private AppBranding() {
    }

    public static int normalizeIconMode(int mode) {
        return mode == ICON_HISTORY || mode == ICON_CUSTOM ? mode : ICON_CURRENT;
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

            int side = Math.min(source.getWidth(), source.getHeight());
            int left = (source.getWidth() - side) / 2;
            int top = (source.getHeight() - side) / 2;
            Bitmap square = Bitmap.createBitmap(source, left, top, side, side);
            Bitmap normalized = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(normalized);
            canvas.drawBitmap(square, null, new RectF(0, 0, ICON_SIZE, ICON_SIZE),
                    new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
            source.recycle();
            square.recycle();

            boolean written = writeCustomIcon(context, normalized);
            normalized.recycle();
            return written;
        } catch (Exception ignored) {
            return false;
        }
    }

    @NonNull
    public static String getSummary(@NonNull Context context) {
        return context.getString(iconLabelResource(getIconMode(context)));
    }

    /** Applies the selected launcher branding to the in-app home logo. */
    public static void applyLogo(@NonNull ImageView view) {
        int mode = getIconMode(view.getContext());
        if (mode == ICON_CUSTOM) {
            Bitmap bitmap = loadCustomIcon(view.getContext());
            if (bitmap != null) {
                Glide.with(view).load(circularBitmap(cropVisibleSquare(bitmap)))
                        .dontTransform()
                        .into(view);
                return;
            }
            mode = ICON_CURRENT;
        }
        int resource = mode == ICON_HISTORY ? R.drawable.ic_launcher_history : R.mipmap.ic_launcher;
        Glide.with(view).asBitmap().load(resource).into(new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(@NonNull Bitmap bitmap,
                                        @Nullable Transition<? super Bitmap> transition) {
                Glide.with(view).load(circularBitmap(bitmap)).dontTransform().into(view);
            }

            @Override
            public void onLoadCleared(@Nullable Drawable placeholder) {
            }
        });
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

    public static boolean needsPinnedShortcut(int iconMode) {
        // Custom bitmaps are in-app branding only; manifest aliases always use built-in icons.
        return false;
    }

    public static int saveFeedbackResource(boolean shortcutNeeded, boolean shortcutAdded) {
        return shortcutNeeded && !shortcutAdded ? R.string.app_branding_shortcut_unsupported : 0;
    }

    /** Requests a desktop shortcut when a dynamic icon cannot be represented by a manifest alias. */
    public static boolean requestPinnedShortcut(@NonNull Activity activity) {
        int iconMode = getIconMode(activity);
        if (!needsPinnedShortcut(iconMode)) return false;

        IconCompat icon = shortcutIcon(activity, iconMode);
        if (icon == null) return false;

        Intent launch = launcherIntent(activity, iconMode);
        ShortcutInfoCompat info = new ShortcutInfoCompat.Builder(activity, CUSTOM_SHORTCUT_ID)
                .setShortLabel(getName(activity))
                .setLongLabel(getName(activity))
                .setIcon(icon)
                .setIntent(launch)
                .build();
        if (ShortcutManagerCompat.isRequestPinShortcutSupported(activity)) {
            return ShortcutManagerCompat.requestPinShortcut(activity, info, null);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return false;
        return requestLegacyShortcut(activity, iconMode, getName(activity), launch);
    }

    private static boolean requestLegacyShortcut(@NonNull Activity activity, int iconMode,
                                                 @NonNull String name, @NonNull Intent launch) {
        Intent action = new Intent(ACTION_INSTALL_SHORTCUT);
        ResolveInfo receiver = findLegacyShortcutReceiver(activity, action);
        if (receiver == null || receiver.activityInfo == null) return false;

        String previousName = Prefers.getString(LEGACY_SHORTCUT_NAME_KEY);
        if (!previousName.isEmpty()) {
            int previousMode = Prefers.getInt(LEGACY_SHORTCUT_MODE_KEY, iconMode);
            Intent uninstall = new Intent(ACTION_UNINSTALL_SHORTCUT)
                    .putExtra(Intent.EXTRA_SHORTCUT_NAME, previousName)
                    .putExtra(Intent.EXTRA_SHORTCUT_INTENT, launcherIntent(activity, previousMode))
                    .setComponent(new ComponentName(receiver.activityInfo.packageName, receiver.activityInfo.name));
            try {
                activity.sendBroadcast(uninstall);
            } catch (SecurityException ignored) {
                // A launcher may expose the receiver but deny legacy shortcut removal.
            }
        }

        action.setComponent(new ComponentName(receiver.activityInfo.packageName, receiver.activityInfo.name))
                .putExtra(Intent.EXTRA_SHORTCUT_NAME, name)
                .putExtra(Intent.EXTRA_SHORTCUT_INTENT, launch);
        putLegacyShortcutIcon(activity, action, iconMode);
        try {
            activity.sendBroadcast(action);
            Prefers.put(LEGACY_SHORTCUT_NAME_KEY, name);
            Prefers.put(LEGACY_SHORTCUT_MODE_KEY, iconMode);
            return true;
        } catch (SecurityException ignored) {
            return false;
        }
    }

    @Nullable
    private static ResolveInfo findLegacyShortcutReceiver(@NonNull Context context, @NonNull Intent intent) {
        List<ResolveInfo> receivers = context.getPackageManager().queryBroadcastReceivers(intent, 0);
        return receivers.isEmpty() ? null : receivers.get(0);
    }

    private static void putLegacyShortcutIcon(@NonNull Context context, @NonNull Intent intent, int iconMode) {
        if (iconMode == ICON_CUSTOM) {
            Bitmap bitmap = loadCustomIcon(context);
            if (bitmap != null) intent.putExtra(Intent.EXTRA_SHORTCUT_ICON, bitmap);
            return;
        }
        int resource = iconMode == ICON_HISTORY ? R.drawable.ic_launcher_history : R.mipmap.ic_launcher;
        intent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, Intent.ShortcutIconResource.fromContext(context, resource));
    }

    @NonNull
    public static Intent launcherIntent(@NonNull Context context) {
        return launcherIntent(context, getIconMode(context));
    }

    @NonNull
    private static Intent launcherIntent(@NonNull Context context, int iconMode) {
        String alias = launcherAliasClassName(HomeActivity.class.getName(), iconMode);
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(context.getPackageName(), alias));
    }

    @Nullable
    private static IconCompat shortcutIcon(@NonNull Context context, int iconMode) {
        if (iconMode == ICON_CUSTOM) {
            Bitmap bitmap = loadCustomIcon(context);
            return bitmap == null ? null : IconCompat.createWithBitmap(bitmap);
        }
        int resource = iconMode == ICON_HISTORY ? R.drawable.ic_launcher_history : R.mipmap.ic_launcher;
        return IconCompat.createWithResource(context, resource);
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

    private static Bitmap cropVisibleSquare(@NonNull Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((pixels[y * width + x] >>> 24) == 0) continue;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        if (maxX < 0) return bitmap;
        int contentWidth = maxX - minX + 1;
        int contentHeight = maxY - minY + 1;
        int side = Math.min(contentWidth, contentHeight);
        int left = minX + (contentWidth - side) / 2;
        int top = minY + (contentHeight - side) / 2;
        return Bitmap.createBitmap(bitmap, left, top, side, side);
    }

    private static Bitmap circularBitmap(@NonNull Bitmap source) {
        int side = Math.min(source.getWidth(), source.getHeight());
        int left = (source.getWidth() - side) / 2;
        int top = (source.getHeight() - side) / 2;
        Bitmap square = Bitmap.createBitmap(source, left, top, side, side);
        Bitmap output = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setShader(new BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float center = side / 2f;
        canvas.drawCircle(center, center, center, paint);
        paint.setShader(null);
        if (square != source) square.recycle();
        return output;
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
