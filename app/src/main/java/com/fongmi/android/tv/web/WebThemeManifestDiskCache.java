package com.fongmi.android.tv.web;

import android.content.Context;

import java.io.ByteArrayInputStream;
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
    private static final String CACHE_MAGIC = "WEBHTV_THEME_MANIFEST_CACHE_V2\n";
    private static final int MAX_CACHE_METADATA_BYTES = 4096;
    private static final int MAX_CACHE_BYTES = WebThemeManifest.MAX_MANIFEST_BYTES + MAX_CACHE_METADATA_BYTES;
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
    public WebThemeManifestLoader.StoredManifest read(String cacheKey) throws IOException {
        synchronized (LOCK) {
            ensureDirectory();
            File target = dataFile(cacheKey);
            recover(target);
            if (!target.isFile()) return null;
            String payload = WebThemeManifestLoader.read(new FileInputStream(target), MAX_CACHE_BYTES);
            WebThemeManifestLoader.StoredManifest stored = decode(payload);
            target.setLastModified(System.currentTimeMillis());
            return stored;
        }
    }

    @Override
    public void write(String cacheKey, WebThemeManifestLoader.StoredManifest stored) throws IOException {
        byte[] bytes = encode(stored);
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

    private static byte[] encode(WebThemeManifestLoader.StoredManifest stored) throws IOException {
        if (stored == null) throw new IOException("Theme manifest cache entry is missing");
        byte[] manifest = stored.json().getBytes(StandardCharsets.UTF_8);
        if (manifest.length > WebThemeManifest.MAX_MANIFEST_BYTES) {
            throw new IOException("Theme manifest is too large");
        }
        String payload = CACHE_MAGIC + stored.validatedAt() + "\n"
                + encodeHex(stored.etag()) + "\n" + stored.json();
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CACHE_BYTES) {
            throw new IOException("Theme manifest cache metadata is too large");
        }
        return bytes;
    }

    private static WebThemeManifestLoader.StoredManifest decode(String payload) throws IOException {
        if (!payload.startsWith(CACHE_MAGIC)) {
            if (payload.getBytes(StandardCharsets.UTF_8).length > WebThemeManifest.MAX_MANIFEST_BYTES) {
                throw new IOException("Theme manifest is too large");
            }
            return new WebThemeManifestLoader.StoredManifest(payload, "", 0);
        }
        int validatedEnd = payload.indexOf('\n', CACHE_MAGIC.length());
        int etagEnd = validatedEnd < 0 ? -1 : payload.indexOf('\n', validatedEnd + 1);
        if (validatedEnd < 0 || etagEnd < 0
                || etagEnd + 1 - CACHE_MAGIC.length() > MAX_CACHE_METADATA_BYTES) {
            throw new IOException("Invalid theme manifest cache metadata");
        }
        long validatedAt;
        try {
            validatedAt = Long.parseLong(payload.substring(CACHE_MAGIC.length(), validatedEnd));
        } catch (NumberFormatException e) {
            throw new IOException("Invalid theme manifest cache timestamp", e);
        }
        if (validatedAt < 0) throw new IOException("Invalid theme manifest cache timestamp");
        String etag = decodeHex(payload.substring(validatedEnd + 1, etagEnd));
        String json = payload.substring(etagEnd + 1);
        if (json.getBytes(StandardCharsets.UTF_8).length > WebThemeManifest.MAX_MANIFEST_BYTES) {
            throw new IOException("Theme manifest is too large");
        }
        return new WebThemeManifestLoader.StoredManifest(json, etag, validatedAt);
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
            return encodeHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String encodeHex(String value) {
        return encodeHex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String encodeHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte part : bytes) {
            hex.append(Character.forDigit((part >>> 4) & 0x0f, 16));
            hex.append(Character.forDigit(part & 0x0f, 16));
        }
        return hex.toString();
    }

    private static String decodeHex(String value) throws IOException {
        if ((value.length() & 1) != 0) throw new IOException("Invalid theme manifest cache ETag");
        byte[] bytes = new byte[value.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            int high = Character.digit(value.charAt(index * 2), 16);
            int low = Character.digit(value.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IOException("Invalid theme manifest cache ETag");
            bytes[index] = (byte) ((high << 4) | low);
        }
        return WebThemeManifestLoader.read(new ByteArrayInputStream(bytes), bytes.length);
    }
}
