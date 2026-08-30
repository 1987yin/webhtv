package com.fongmi.android.tv.node;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

import okhttp3.Response;

/** 猫源 bundle 的下载、校验和本地缓存。 */
public final class NodeBundle {

    private static final String SUFFIX = ".md5";
    private static final String MARKER = "index.js.md5";
    private static final String SOURCE_STAMP = "source.key";
    private static final long MAX_ENTRY_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_METADATA_BYTES = 4096;
    private static final int MAX_SOURCE_KEY_BYTES = 16 * 1024;

    private static final java.util.Set<String> MEMBERS = new java.util.HashSet<>(java.util.Arrays.asList(
            "index.js", MARKER, "index.config.js", "index.config.js.md5"));

    private NodeBundle() {
    }

    public static boolean isLocal(String url) {
        try {
            File root = Path.root();
            return localDir(url, root) != null || localZip(url, root) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean isMd5(String value) {
        if (value == null || value.length() != 32) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
        }
        return true;
    }

    /** 来源身份包含本地包内容指纹，避免不同来源共用运行缓存。 */
    static String sourceKey(String url) {
        if (TextUtils.isEmpty(url)) return "";
        String value = url.trim();
        try {
            File local = localDir(value, null);
            if (local == null) local = localDir(value, Path.root());
            if (local != null) {
                String bundle = contentSignature(new File(local, "index.js"));
                String config = contentSignature(new File(local, "index.config.js"));
                if (TextUtils.isEmpty(bundle) || TextUtils.isEmpty(config)) return "";
                return localSourceKey(local,
                        bundle,
                        config);
            }
            File zip = localZip(value, null);
            if (zip == null) zip = localZip(value, Path.root());
            if (zip != null) return zipSourceKey(zip);
        } catch (Throwable ignored) {
        }
        return isRemote(value) ? "remote:" + bundleUrl(value) : "";
    }

    static File localDir(String url, File root) {
        File target = target(url, root);
        if (target == null) return null;
        if (target.isDirectory()) return new File(target, MARKER).isFile() ? target : null;
        if (!MEMBERS.contains(lower(target.getName()))) return null;
        File parent = target.getParentFile();
        return parent != null && new File(parent, MARKER).isFile() ? parent : null;
    }

    static File localZip(String url, File root) {
        File target = target(url, root);
        if (target == null || !target.isFile() || !lower(target.getName()).endsWith(".zip")) return null;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(target)) {
            java.util.zip.ZipEntry marker = zip.getEntry(MARKER);
            return marker != null && !marker.isDirectory() ? target : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    static boolean isRemote(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String value = lower(url.trim());
        return value.startsWith("http://") || value.startsWith("https://");
    }

    private static File target(String url, File root) {
        if (TextUtils.isEmpty(url) || isRemote(url)) return null;
        String path = url.trim();
        String value = lower(path);
        if (value.startsWith("file://")) path = path.substring(7);
        else if (value.startsWith("file:/")) path = path.substring(6);
        return resolve(path, root);
    }

    private static File resolve(String path, File root) {
        if (TextUtils.isEmpty(path)) return null;
        if (root != null) {
            File relative = new File(root, path);
            if (relative.exists()) return relative;
        }
        File absolute = new File(path);
        if (absolute.exists()) return absolute;
        File rooted = new File("/" + path);
        return rooted.exists() ? rooted : null;
    }

    public static String bundleUrl(String url) {
        if (TextUtils.isEmpty(url)) return "";
        String trimmed = url.trim();
        return lower(trimmed).endsWith(SUFFIX) ? trimmed.substring(0, trimmed.length() - SUFFIX.length()) : trimmed;
    }

    public static String md5Url(String url) {
        if (TextUtils.isEmpty(url)) return "";
        String trimmed = url.trim();
        return lower(trimmed).endsWith(SUFFIX) ? trimmed : trimmed + SUFFIX;
    }

    public static File dir(Context context) {
        File dir = new File(context.getFilesDir(), "node/bundle");
        dir.mkdirs();
        return dir;
    }

    public static File file(Context context) {
        return new File(dir(context), "index.js");
    }

    public static File config(Context context) {
        return new File(dir(context), "index.config.js");
    }

    static String installedSourceKey(Context context) {
        return read(sourceStamp(context));
    }

    private static File stamp(Context context) {
        return new File(dir(context), "index.js.md5");
    }

    private static File configStamp(Context context) {
        return new File(dir(context), "index.config.js.md5");
    }

    private static File sourceStamp(Context context) {
        return new File(dir(context), SOURCE_STAMP);
    }

    private static String configUrl(String url) {
        String bundle = bundleUrl(url);
        int slash = bundle.lastIndexOf('/');
        return slash < 0 ? bundle : bundle.substring(0, slash + 1) + "index.config.js";
    }

    public static synchronized String ensure(Context context, String url) {
        File root = Path.root();
        File source = localDir(url, root);
        if (source != null) return ensureLocal(context, source);
        File zip = localZip(url, root);
        if (zip != null) return ensureZip(context, zip);
        if (!isRemote(url)) return "猫源地址无法访问，本地包可能已被移动：" + url;
        return ensureRemote(context, url);
    }

    private static String ensureLocal(Context context, File source) {
        File bundle = new File(source, "index.js");
        File config = new File(source, "index.config.js");
        if (!bundle.isFile() || bundle.length() == 0) return "本地包缺少 index.js，请选择整个包（zip 或解压后的文件夹）";
        if (!config.isFile() || config.length() == 0) return "本地包缺少 index.config.js";
        if (same(bundle, file(context)) || same(config, config(context))) return "本地包不能指向 Node 运行目录";

        File staging = null;
        try {
            staging = stagingDir(context);
            PreparedFile preparedBundle = prepareFile(bundle, new File(staging, "index.js"));
            PreparedFile preparedConfig = prepareFile(config, new File(staging, "index.config.js"));
            String key = localSourceKey(source, preparedBundle.md5(), preparedConfig.md5());
            return install(context, preparedBundle, preparedConfig, key);
        } catch (Exception e) {
            SpiderDebug.log("node", e);
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            cleanup(staging);
        }
    }

    private static String ensureZip(Context context, File zip) {
        File staging = null;
        try (java.util.zip.ZipFile file = new java.util.zip.ZipFile(zip)) {
            String bundleExpected = readEntry(file, MARKER);
            if (!isMd5(bundleExpected)) throw new IOException("本地包 index.js.md5 无效");
            String configExpected = readEntry(file, "index.config.js.md5");
            if (!TextUtils.isEmpty(configExpected) && !isMd5(configExpected)) throw new IOException("本地包 index.config.js.md5 无效");
            staging = stagingDir(context);
            PreparedFile bundle = prepareZipEntry(file, "index.js", new File(staging, "index.js"), bundleExpected);
            PreparedFile config = prepareZipEntry(file, "index.config.js", new File(staging, "index.config.js"), configExpected);
            return install(context, bundle, config, localSourceKey(zip, bundle.md5(), config.md5()));
        } catch (Exception e) {
            SpiderDebug.log("node", e);
            return "本地包解压失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            cleanup(staging);
        }
    }

    private static String ensureRemote(Context context, String url) {
        String key = sourceKey(url);
        String expectedBundle = remoteMd5(url);
        String expectedConfig = remoteMd5(configUrl(url));
        boolean bundleCached = isCached(file(context), stamp(context), sourceStamp(context), key, expectedBundle);
        boolean configCached = isCached(config(context), configStamp(context), sourceStamp(context), key, expectedConfig);
        if (bundleCached && configCached) return null;
        if (!bundleCached && !isMd5(expectedBundle)) return "bundle 校验值不可用，无法安全下载";
        if (!configCached && !isMd5(expectedConfig)) return "猫源配置校验值不可用，无法安全下载";

        File staging = null;
        try {
            staging = stagingDir(context);
            PreparedFile bundle = bundleCached
                    ? prepareFile(file(context), new File(staging, "index.js"))
                    : download(bundleUrl(url), new File(staging, "index.js"), expectedBundle);
            PreparedFile config = configCached
                    ? prepareFile(config(context), new File(staging, "index.config.js"))
                    : download(configUrl(url), new File(staging, "index.config.js"), expectedConfig);
            return install(context, bundle, config, key);
        } catch (Exception e) {
            SpiderDebug.log("node", e);
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            cleanup(staging);
        }
    }

    private static String remoteMd5(String url) {
        try (Response response = OkHttp.newCall(md5Url(url), "node-bundle").execute()) {
            if (!response.isSuccessful() || response.body() == null) return "";
            if (response.body().contentLength() > MAX_METADATA_BYTES) return "";
            try (InputStream in = response.body().byteStream()) {
                String value = readLimited(in, MAX_METADATA_BYTES).trim();
                return isMd5(value) ? value : "";
            }
        } catch (Exception ignored) {
            return "";
        }
    }

    private static PreparedFile prepareFile(File source, File target) throws IOException {
        try (InputStream in = new FileInputStream(source)) {
            return new PreparedFile(target, copyAndDigest(in, target));
        }
    }

    private static boolean same(File a, File b) {
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (IOException e) {
            return a.getAbsoluteFile().equals(b.getAbsoluteFile());
        }
    }

    private static PreparedFile prepareZipEntry(java.util.zip.ZipFile zip, String name, File target, String expected) throws IOException {
        java.util.zip.ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) throw new IOException("本地包缺少 " + name);
        if (entry.getSize() > MAX_ENTRY_BYTES) throw new IOException("本地包 " + name + " 超过大小限制");
        try (InputStream in = zip.getInputStream(entry)) {
            String actual = copyAndDigest(in, target);
            if (isMd5(expected) && !expected.equalsIgnoreCase(actual)) throw new IOException("本地包 " + name + " 校验失败");
            return new PreparedFile(target, actual);
        }
    }

    private static PreparedFile download(String url, File target, String expected) throws IOException {
        if (!isMd5(expected)) throw new IOException("bundle 校验值不可用");
        try (Response response = OkHttp.newCall(url, "node-bundle").execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("bundle 下载失败 HTTP " + response.code());
            try (InputStream in = response.body().byteStream()) {
                String actual = copyAndDigest(in, target);
                if (isMd5(expected) && !expected.equalsIgnoreCase(actual)) throw new IOException("bundle 校验失败");
                return new PreparedFile(target, actual);
            }
        }
    }

    /** 正式文件只在准备完成后替换，失败时恢复旧缓存。 */
    private static String install(Context context, PreparedFile bundle, PreparedFile config, String sourceKey) {
        File bundleTarget = file(context);
        File configTarget = config(context);
        File bundleBackup = null;
        File configBackup = null;
        boolean bundlePublished = false;
        boolean configPublished = false;
        File stamp = stamp(context);
        File configStamp = configStamp(context);
        File sourceStamp = sourceStamp(context);
        boolean oldStamp = stamp.isFile();
        boolean oldConfigStamp = configStamp.isFile();
        boolean oldSourceStamp = sourceStamp.isFile();
        String oldStampText = read(stamp);
        String oldConfigStampText = read(configStamp);
        String oldSourceStampText = read(sourceStamp);
        try {
            if (bundleTarget.isFile()) bundleBackup = backup(bundleTarget);
            if (configTarget.isFile()) configBackup = backup(configTarget);
            move(bundle.file(), bundleTarget);
            bundlePublished = true;
            move(config.file(), configTarget);
            configPublished = true;
            writeAtomic(stamp, bundle.md5());
            writeAtomic(configStamp, config.md5());
            writeAtomic(sourceStamp, sourceKey);
            deleteQuietly(bundleBackup);
            deleteQuietly(configBackup);
            return null;
        } catch (Exception e) {
            if (bundleBackup != null) restore(bundleBackup, bundleTarget);
            else if (bundlePublished) deleteQuietly(bundleTarget);
            if (configBackup != null) restore(configBackup, configTarget);
            else if (configPublished) deleteQuietly(configTarget);
            restoreText(stamp, oldStamp, oldStampText);
            restoreText(configStamp, oldConfigStamp, oldConfigStampText);
            restoreText(sourceStamp, oldSourceStamp, oldSourceStampText);
            SpiderDebug.log("node", e);
            return "本地包写入失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static boolean isCached(File target, File stamp, File sourceStamp, String sourceKey, String expected) {
        if (TextUtils.isEmpty(sourceKey) || !target.isFile() || target.length() == 0 || target.length() > MAX_ENTRY_BYTES) return false;
        if (!sourceKey.equals(read(sourceStamp))) return false;
        String local = read(stamp);
        if (!isMd5(local)) return false;
        String actual = contentSignature(target);
        return isMd5(actual) && actual.equalsIgnoreCase(local) && (!isMd5(expected) || expected.equalsIgnoreCase(actual));
    }

    private static String readEntry(java.util.zip.ZipFile zip, String name) throws IOException {
        java.util.zip.ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory() || entry.getSize() > 4096) return "";
        try (InputStream in = zip.getInputStream(entry)) {
            return readLimited(in, 4096).trim();
        }
    }

    private static String zipSourceKey(File zip) {
        try (java.util.zip.ZipFile file = new java.util.zip.ZipFile(zip)) {
            if (!isMd5(readEntry(file, MARKER))) return "";
            String bundle = entryDigest(file, "index.js");
            String config = entryDigest(file, "index.config.js");
            String configMarker = readEntry(file, "index.config.js.md5");
            if (!isMd5(bundle) || !isMd5(config) || (!TextUtils.isEmpty(configMarker) && !isMd5(configMarker))) return "";
            return localSourceKey(zip, bundle, config);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String entryDigest(java.util.zip.ZipFile zip, String name) throws IOException {
        java.util.zip.ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory() || entry.getSize() > MAX_ENTRY_BYTES) return "";
        try (InputStream in = zip.getInputStream(entry)) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[65536];
            long total = 0;
            int length;
            while ((length = in.read(buffer)) != -1) {
                total += length;
                if (total > MAX_ENTRY_BYTES) return "";
                digest.update(buffer, 0, length);
            }
            return total == 0 ? "" : hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("MD5 不可用", e);
        }
    }

    private static String copyAndDigest(InputStream in, File target) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (Exception e) {
            throw new IOException("MD5 不可用", e);
        }
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        long total = 0;
        try (OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[65536];
            int length;
            while ((length = in.read(buffer)) != -1) {
                total += length;
                if (total > MAX_ENTRY_BYTES) throw new IOException("文件超过大小限制");
                digest.update(buffer, 0, length);
                out.write(buffer, 0, length);
            }
        }
        if (total == 0) throw new IOException("文件为空");
        return hex(digest.digest());
    }

    private static String md5(File file) {
        String value = com.github.catvod.utils.Util.md5(file);
        return value == null ? "" : value;
    }

    private static File stagingDir(Context context) throws IOException {
        File staging = new File(dir(context), ".stage-" + UUID.randomUUID());
        if (!staging.mkdirs()) throw new IOException("无法创建本地包临时目录");
        return staging;
    }

    private static File backup(File target) throws IOException {
        File backup = File.createTempFile(target.getName() + ".backup-", ".tmp", target.getParentFile());
        try {
            prepareFile(target, backup);
            return backup;
        } catch (Exception e) {
            deleteQuietly(backup);
            throw e;
        }
    }

    private static void restore(File backup, File target) {
        if (backup == null || !backup.exists()) return;
        try {
            move(backup, target);
        } catch (Exception e) {
            SpiderDebug.log("node", e);
        }
    }

    private static void restoreText(File target, boolean existed, String text) {
        try {
            if (existed) writeAtomic(target, text);
            else deleteQuietly(target);
        } catch (Exception e) {
            SpiderDebug.log("node", e);
        }
    }

    private static void writeAtomic(File target, String text) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        File temporary = File.createTempFile(target.getName() + ".", ".tmp", parent);
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            }
            move(temporary, target);
        } finally {
            deleteQuietly(temporary);
        }
    }

    private static void move(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String localSourceKey(File source, String bundleDigest, String configDigest) {
        return "local:" + canonical(source) + ":" + bundleDigest + ":" + configDigest;
    }

    private static String contentSignature(File file) {
        return file.isFile() ? digestFile(file) : "";
    }

    private static String digestFile(File file) {
        if (file.length() <= 0 || file.length() > MAX_ENTRY_BYTES) return "";
        try (InputStream in = new FileInputStream(file)) {
            return digestStream(in, MAX_ENTRY_BYTES);
        } catch (IOException e) {
            return "";
        }
    }

    private static String digestStream(InputStream in, long maxBytes) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (Exception e) {
            throw new IOException("MD5 不可用", e);
        }
        byte[] buffer = new byte[65536];
        long total = 0;
        int length;
        while ((length = in.read(buffer)) != -1) {
            total += length;
            if (total > maxBytes) return "";
            digest.update(buffer, 0, length);
        }
        return total == 0 ? "" : hex(digest.digest());
    }

    private static String canonical(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return value.toString();
    }

    private static String read(File file) {
        if (file == null || !file.isFile()) return "";
        try (InputStream in = new FileInputStream(file)) {
            return readLimited(in, MAX_SOURCE_KEY_BYTES).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static String readLimited(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 256));
        byte[] buffer = new byte[256];
        int total = 0;
        int length;
        while ((length = in.read(buffer)) != -1) {
            total += length;
            if (total > maxBytes) throw new IOException("文本超过大小限制");
            output.write(buffer, 0, length);
        }
        return total == 0 ? "" : output.toString(StandardCharsets.UTF_8);
    }

    private static void cleanup(File directory) {
        if (directory == null) return;
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) deleteQuietly(file);
        deleteQuietly(directory);
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) file.deleteOnExit();
    }

    private record PreparedFile(File file, String md5) {
    }
}
