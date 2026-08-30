package com.fongmi.android.tv.utils;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import androidx.activity.result.ActivityResultLauncher;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.ui.activity.FileActivity;
import com.github.catvod.utils.Path;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class FileChooser {

    private final ActivityResultLauncher<Intent> launcher;

    public static FileChooser from(ActivityResultLauncher<Intent> launcher) {
        return new FileChooser(launcher);
    }

    public FileChooser(ActivityResultLauncher<Intent> launcher) {
        this.launcher = launcher;
    }

    public void show() {
        show("*/*");
    }

    public void show(String mimeType) {
        show(mimeType, new String[]{"*/*"});
    }

    public void show(String[] mimeTypes) {
        show("*/*", mimeTypes);
    }

    public void show(String mimeType, String[] mimeTypes) {
        show(mimeType, mimeTypes, false);
    }

    public void show(String mimeType, String[] mimeTypes, boolean allowMultiple) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType(mimeType);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple);
        intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
        List<ResolveInfo> resolveInfos = App.get().getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (Util.isLeanback() || resolveInfos.isEmpty() || resolveInfos.get(0).activityInfo.packageName.contains("frameworkpackagestubs")) {
            launcher.launch(new Intent(App.get(), FileActivity.class));
        } else {
            launcher.launch(Intent.createChooser(intent, ""));
        }
    }

    public void showDirectory() {
        launcher.launch(new Intent(App.get(), FileActivity.class).putExtra("select_dir", true));
    }

    public static boolean isValid(Context context, Uri uri) {
        try {
            return DocumentsContract.isDocumentUri(context, uri) || ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()) || ContentResolver.SCHEME_FILE.equalsIgnoreCase(uri.getScheme());
        } catch (Exception e) {
            return false;
        }
    }

    public static String getPathFromUri(Uri uri) {
        return getPathFromUri(App.get(), uri);
    }

    /** 为长期保存的配置导入项返回持久路径，避免系统 cache 清理或同名文件覆盖。 */
    public static String getPersistentPathFromUri(Uri uri) {
        return getPathFromUri(App.get(), uri, true);
    }

    public static List<String> getPathsFromIntent(Intent intent) {
        List<String> paths = new ArrayList<>();
        if (intent == null) return paths;
        ClipData clipData = intent.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) addPath(paths, clipData.getItemAt(i).getUri());
        }
        addPath(paths, intent.getData());
        return paths;
    }

    private static void addPath(List<String> paths, Uri uri) {
        String path = getPathFromUri(uri);
        if (path != null && !path.isEmpty() && !paths.contains(path)) paths.add(path);
    }

    private static String getPathFromUri(Context context, Uri uri) {
        return getPathFromUri(context, uri, false);
    }

    private static String getPathFromUri(Context context, Uri uri, boolean persistent) {
        if (uri == null) return null;
        String path = null;
        if (DocumentsContract.isDocumentUri(context, uri)) path = getPathFromDocumentUri(context, uri);
        else if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) path = getDataColumn(context, uri);
        else if (ContentResolver.SCHEME_FILE.equalsIgnoreCase(uri.getScheme())) path = uri.getPath();
        if (path != null) {
            String decoded = decodePath(path);
            if (!persistent || new File(decoded).exists()) return decoded;
        }
        return createFileFromUri(context, uri, persistent);
    }

    private static String decodePath(String path) {
        try {
            return URLDecoder.decode(path, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return path;
        }
    }

    private static String getPathFromDocumentUri(Context context, Uri uri) {
        String docId = DocumentsContract.getDocumentId(uri);
        String[] split = docId.split(":");
        if (isExternalStorageDocument(uri)) return getPath(docId, split);
        else if (isDownloadsDocument(uri)) return getPath(context, uri, docId);
        else if (isMediaDocument(uri)) return getPath(context, split);
        else return null;
    }

    private static String getPath(String docId, String[] split) {
        if ("primary".equalsIgnoreCase(split[0])) {
            return split.length > 1 ? Environment.getExternalStorageDirectory() + "/" + split[1] : Environment.getExternalStorageDirectory() + "/";
        } else {
            return "/storage/" + docId.replace(":", "/");
        }
    }

    private static String getPath(Context context, Uri uri, String docId) {
        String fileName = getNameColumn(context, uri);
        if (docId.startsWith("raw:")) {
            return docId.replaceFirst("raw:", "");
        } else if (fileName != null) {
            return Environment.getExternalStorageDirectory() + "/Download/" + fileName;
        } else {
            return getDataColumn(context, ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), Long.parseLong(docId)));
        }
    }

    private static String getPath(Context context, String[] split) {
        return switch (split[0]) {
            case "image" -> getDataColumn(context, ContentUris.withAppendedId(getImageUri(), Long.parseLong(split[1])));
            case "video" -> getDataColumn(context, ContentUris.withAppendedId(getVideoUri(), Long.parseLong(split[1])));
            case "audio" -> getDataColumn(context, ContentUris.withAppendedId(getAudioUri(), Long.parseLong(split[1])));
            default -> getDataColumn(context, ContentUris.withAppendedId(getFilesUri(), Long.parseLong(split[1])));
        };
    }

    private static String createFileFromUri(Context context, Uri uri) {
        return createFileFromUri(context, uri, false);
    }

    private static String createFileFromUri(Context context, Uri uri, boolean persistent) {
        String[] projection = {MediaStore.MediaColumns.DISPLAY_NAME};
        try (Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            int column = cursor.getColumnIndexOrThrow(projection[0]);
            String name = cursor.getString(column);
            File file = persistent ? persistentImportFile(name) : Path.cache(name);
            File target = persistent ? file : file;
            File temp = persistent ? File.createTempFile(".import-", ".tmp", target.getParentFile()) : target;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                if (is == null) return null;
                if (persistent) {
                    try (FileOutputStream out = new FileOutputStream(temp)) {
                        byte[] buffer = new byte[65536];
                        int length;
                        long total = 0;
                        while ((length = is.read(buffer)) != -1) {
                            total += length;
                            if (total > 32L * 1024L * 1024L) throw new IOException("导入文件超过大小限制");
                            out.write(buffer, 0, length);
                        }
                        if (total == 0) throw new IOException("导入文件为空");
                    }
                    moveImport(temp, target);
                } else {
                    Path.copy(is, target);
                }
                return target.isFile() && target.length() > 0 ? target.getAbsolutePath() : null;
            } catch (Exception e) {
                if (persistent && temp.exists()) temp.delete();
                throw e;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static File persistentImportFile(String name) {
        File dir = Path.files("cat_source_imports");
        dir.mkdirs();
        String base = name == null ? "import" : new File(name).getName();
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        return new File(dir, UUID.randomUUID() + "_" + base);
    }

    private static void moveImport(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String getDataColumn(Context context, Uri uri) {
        String[] projection = {MediaStore.MediaColumns.DATA};
        try (Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            return cursor.getString(cursor.getColumnIndexOrThrow(projection[0]));
        } catch (Exception e) {
            return null;
        }
    }

    private static String getNameColumn(Context context, Uri uri) {
        String[] projection = {MediaStore.MediaColumns.DISPLAY_NAME};
        try (Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            return cursor.getString(cursor.getColumnIndexOrThrow(projection[0]));
        } catch (Exception e) {
            return null;
        }
    }

    private static Uri getImageUri() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        } else {
            return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        }
    }

    private static Uri getVideoUri() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        } else {
            return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        }
    }

    private static Uri getAudioUri() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        } else {
            return MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        }
    }

    public static Uri getFilesUri() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        } else {
            return MediaStore.Files.getContentUri("external");
        }
    }

    private static boolean isExternalStorageDocument(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    private static boolean isDownloadsDocument(Uri uri) {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    private static boolean isMediaDocument(Uri uri) {
        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }
}
