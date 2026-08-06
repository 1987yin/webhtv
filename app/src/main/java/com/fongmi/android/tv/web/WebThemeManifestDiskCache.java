package com.fongmi.android.tv.web;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

final class WebThemeManifestDiskCache implements WebThemeManifestLoader.PersistentCache {

    private static final String DIRECTORY_NAME = "webtheme-manifests-v1";
    private static final Object LOCK = new Object();

    private final File directory;

    WebThemeManifestDiskCache(File directory) {
        this.directory = directory;
    }

    static WebThemeManifestDiskCache create(Context context) {
        Context application = context.getApplicationContext();
        Context owner = application == null ? context : application;
        File root = owner.getNoBackupFilesDir();
        if (root == null) root = owner.getFilesDir();
        return new WebThemeManifestDiskCache(new File(root, DIRECTORY_NAME));
    }

    @Override
    public String read(String cacheKey) throws IOException {
        synchronized (LOCK) {
            ensureDirectory();
            File target = dataFile(cacheKey);
            recover(target);
            if (!target.isFile()) return null;
            String json = WebThemeManifestLoader.read(
                    new FileInputStream(target), WebThemeManifest.MAX_MANIFEST_BYTES);
            target.setLastModified(System.currentTimeMillis());
            return json;
        }
    }

    @Override
    public void write(String cacheKey, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > WebThemeManifest.MAX_MANIFEST_BYTES) {
            throw new IOException("Theme manifest is too large");
        }
        synchronized (LOCK) {
            ensureDirectory();
            File target = dataFile(cacheKey);
            File temporary = companion(target, ".tmp");
            File backup = companion(target, ".bak");
            recover(target);
            delete(temporary);
            delete(backup);
            writeAndSync(temporary, bytes);
            boolean backedUp = target.isFile();
            if (backedUp && !target.renameTo(backup)) {
                delete(temporary);
                throw new IOException("Unable to back up theme manifest cache");
            }
            if (!temporary.renameTo(target)) {
                if (backedUp) backup.renameTo(target);
                delete(temporary);
                throw new IOException("Unable to publish theme manifest cache");
            }
            delete(backup);
            target.setLastModified(System.currentTimeMillis());
            prune(target);
        }
    }

    @Override
    public void remove(String cacheKey) {
        synchronized (LOCK) {
            File target = dataFile(cacheKey);
            delete(target);
            delete(companion(target, ".tmp"));
            delete(companion(target, ".bak"));
        }
    }

    private void ensureDirectory() throws IOException {
        if (directory.isDirectory()) return;
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Unable to create theme manifest cache");
        }
    }

    private File dataFile(String cacheKey) {
        return new File(directory, sha256(cacheKey) + ".json");
    }

    private void recover(File target) throws IOException {
        File temporary = companion(target, ".tmp");
        File backup = companion(target, ".bak");
        if (target.isFile()) {
            delete(temporary);
            delete(backup);
            return;
        }
        if (backup.isFile() && !backup.renameTo(target)) {
            throw new IOException("Unable to restore theme manifest cache");
        }
        delete(temporary);
    }

    private void prune(File current) {
        File[] files = directory.listFiles((parent, name) -> name.endsWith(".json"));
        if (files == null || files.length <= WebThemeManifestLoader.MAX_CACHE_ENTRIES) return;
        Arrays.sort(files, (first, second) -> {
            if (first.equals(current)) return second.equals(current) ? 0 : 1;
            if (second.equals(current)) return -1;
            int modified = Long.compare(first.lastModified(), second.lastModified());
            return modified != 0 ? modified : first.getName().compareTo(second.getName());
        });
        int removeCount = files.length - WebThemeManifestLoader.MAX_CACHE_ENTRIES;
        for (int index = 0; index < removeCount; index++) {
            File target = files[index];
            delete(target);
            delete(companion(target, ".tmp"));
            delete(companion(target, ".bak"));
        }
    }

    private static void writeAndSync(File file, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
    }

    private static File companion(File target, String suffix) {
        return new File(target.getParentFile(), target.getName() + suffix);
    }

    private static void delete(File file) {
        if (file.exists()) file.delete();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte part : digest) {
                hex.append(Character.forDigit((part >>> 4) & 0x0f, 16));
                hex.append(Character.forDigit(part & 0x0f, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
