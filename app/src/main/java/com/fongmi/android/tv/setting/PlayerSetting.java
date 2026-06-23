package com.fongmi.android.tv.setting;

import android.content.Intent;
import android.provider.Settings;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.github.catvod.utils.Prefers;

public class PlayerSetting {

    public static final int EXO = 0;
    public static final int IJK = 1;
    public static final int SYSTEM = 2;
    public static final int NONE = -1;
    private static final String ABI_ARM64_V8A = "arm64_v8a";
    private static final String IJK_NATIVE_PACKAGE = "com.fongmi.android.tv";

    public static int getPlayer() {
        int player = Prefers.getInt("player", EXO);
        if (isPlayer(player)) return player;
        putPlayer(EXO);
        return EXO;
    }

    public static void putPlayer(int player) {
        Prefers.put("player", sanitizePlayer(player));
    }

    public static boolean isPlayer(int player) {
        return player == EXO || player == IJK || player == SYSTEM;
    }

    public static int sanitizePlayer(int player) {
        return isPlayer(player) ? player : EXO;
    }

    public static int resolvePlayer(int player) {
        return isPlayer(player) ? player : getPlayer();
    }

    public static boolean isPlayerAvailable(int player) {
        return player != IJK || isIjkAvailable();
    }

    public static boolean isIjkAvailable() {
        return IJK_NATIVE_PACKAGE.equals(BuildConfig.APPLICATION_ID) || ABI_ARM64_V8A.equals(BuildConfig.FLAVOR_abi);
    }

    public static int getRender() {
        return Prefers.getInt("render", 0);
    }

    public static void putRender(int render) {
        Prefers.put("render", render);
    }

    public static int getSize() {
        return Prefers.getInt("size", 2);
    }

    public static void putSize(int size) {
        Prefers.put("size", size);
    }

    public static int getScale() {
        return Prefers.getInt("scale");
    }

    public static void putScale(int scale) {
        Prefers.put("scale", scale);
    }

    public static int getBuffer() {
        return Math.min(Math.max(Prefers.getInt("buffer"), 1), 10);
    }

    public static void putBuffer(int buffer) {
        Prefers.put("buffer", buffer);
    }

    public static int getBufferBytesOption() {
        return Math.min(Math.max(Prefers.getInt("buffer_bytes"), 0), 3);
    }

    public static void putBufferBytesOption(int option) {
        Prefers.put("buffer_bytes", Math.min(Math.max(option, 0), 3));
    }

    public static int getBufferBytes() {
        return switch (getBufferBytesOption()) {
            case 1 -> 64 * 1024 * 1024;
            case 2 -> 128 * 1024 * 1024;
            case 3 -> 256 * 1024 * 1024;
            default -> 0;
        };
    }

    public static int getBackBufferOption() {
        return Math.min(Math.max(Prefers.getInt("back_buffer"), 0), 3);
    }

    public static void putBackBufferOption(int option) {
        Prefers.put("back_buffer", Math.min(Math.max(option, 0), 3));
    }

    public static int getBackBufferMs() {
        return switch (getBackBufferOption()) {
            case 1 -> 15_000;
            case 2 -> 30_000;
            case 3 -> 60_000;
            default -> 0;
        };
    }

    public static int getPlayCacheOption() {
        return Math.min(Math.max(Prefers.getInt("play_cache"), 0), 4);
    }

    public static void putPlayCacheOption(int option) {
        Prefers.put("play_cache", Math.min(Math.max(option, 0), 4));
    }

    public static long getPlayCacheSize() {
        return switch (getPlayCacheOption()) {
            case 1 -> 100L * 1024 * 1024;
            case 2 -> 500L * 1024 * 1024;
            case 3 -> 1024L * 1024 * 1024;
            case 4 -> 2L * 1024 * 1024 * 1024;
            default -> 0L;
        };
    }

    public static boolean isAutoChange() {
        return Prefers.getBoolean("player_auto_change", true);
    }

    public static void putAutoChange(boolean autoChange) {
        Prefers.put("player_auto_change", autoChange);
    }

    public static int getBackground() {
        return Prefers.getInt("background", 2);
    }

    public static void putBackground(int background) {
        Prefers.put("background", background);
    }

    public static boolean isMusicNotification() {
        return Prefers.getBoolean("audio_music_notification", true);
    }

    public static void putMusicNotification(boolean notification) {
        Prefers.put("audio_music_notification", notification);
    }

    public static boolean isAudioBookNotification() {
        return Prefers.getBoolean("audio_book_notification", true);
    }

    public static void putAudioBookNotification(boolean notification) {
        Prefers.put("audio_book_notification", notification);
    }

    public static boolean isBackgroundOff() {
        return getBackground() == 0;
    }

    public static boolean isBackgroundOn() {
        return getBackground() == 1 || getBackground() == 2;
    }

    public static boolean isBackgroundPiP() {
        return getBackground() == 2;
    }

    public static float getSpeed() {
        return Math.min(Math.max(Prefers.getFloat("speed", 3), 2), 5);
    }

    public static void putSpeed(float speed) {
        Prefers.put("speed", speed);
    }

    public static float getDefaultSpeed() {
        return Math.min(Math.max(Prefers.getFloat("play_speed", 1), 0.5f), 5);
    }

    public static void putDefaultSpeed(float speed) {
        Prefers.put("play_speed", Math.min(Math.max(speed, 0.5f), 5));
    }

    public static boolean isDisplayTime() {
        return Prefers.getBoolean("display_time");
    }

    public static void putDisplayTime(boolean displayTime) {
        Prefers.put("display_time", displayTime);
    }

    public static boolean isDisplayTraffic() {
        return Prefers.getBoolean("display_traffic");
    }

    public static void putDisplayTraffic(boolean displayTraffic) {
        Prefers.put("display_traffic", displayTraffic);
    }

    public static boolean isDisplaySize() {
        return Prefers.getBoolean("display_size", true);
    }

    public static void putDisplaySize(boolean displaySize) {
        Prefers.put("display_size", displaySize);
    }

    public static boolean isDisplayProgress() {
        return Prefers.getBoolean("display_progress", true);
    }

    public static void putDisplayProgress(boolean displayProgress) {
        Prefers.put("display_progress", displayProgress);
    }

    public static boolean isDisplayMini() {
        return Prefers.getBoolean("display_mini");
    }

    public static void putDisplayMini(boolean displayMini) {
        Prefers.put("display_mini", displayMini);
    }

    public static boolean isDisplayTitle() {
        return Prefers.getBoolean("display_title", true);
    }

    public static void putDisplayTitle(boolean displayTitle) {
        Prefers.put("display_title", displayTitle);
    }

    public static boolean isCaption() {
        return Prefers.getBoolean("caption");
    }

    public static void putCaption(boolean caption) {
        Prefers.put("caption", caption);
    }

    public static boolean hasCaption() {
        return new Intent(Settings.ACTION_CAPTIONING_SETTINGS).resolveActivity(App.get().getPackageManager()) != null;
    }

    public static boolean isTunnel() {
        return Prefers.getBoolean("tunnel");
    }

    public static void putTunnel(boolean tunnel) {
        Prefers.put("tunnel", tunnel);
    }

    public static boolean isAudioPrefer() {
        return Prefers.getBoolean("audio_prefer");
    }

    public static void putAudioPrefer(boolean audioPrefer) {
        Prefers.put("audio_prefer", audioPrefer);
    }

    public static boolean isVideoPrefer() {
        return Prefers.getBoolean("video_prefer");
    }

    public static void putVideoPrefer(boolean videoPrefer) {
        Prefers.put("video_prefer", videoPrefer);
    }

    public static boolean isPreferAAC() {
        return Prefers.getBoolean("prefer_aac");
    }

    public static void putPreferAAC(boolean preferAAC) {
        Prefers.put("prefer_aac", preferAAC);
    }

    public static float getSubtitleTextSize() {
        return Prefers.getFloat("subtitle_text_size");
    }

    public static void putSubtitleTextSize(float value) {
        Prefers.put("subtitle_text_size", value);
    }

    public static float getSubtitlePosition() {
        return Prefers.getFloat("subtitle_position");
    }

    public static void putSubtitlePosition(float value) {
        Prefers.put("subtitle_position", value);
    }

    public static boolean isOsdTitle() {
        return Prefers.getBoolean("player_osd_title");
    }

    public static void putOsdTitle(boolean value) {
        Prefers.put("player_osd_title", value);
    }

    public static boolean isOsdTime() {
        return Prefers.getBoolean("player_osd_time");
    }

    public static void putOsdTime(boolean value) {
        Prefers.put("player_osd_time", value);
    }

    public static boolean isOsdProgress() {
        return Prefers.getBoolean("player_osd_progress");
    }

    public static void putOsdProgress(boolean value) {
        Prefers.put("player_osd_progress", value);
    }

    public static boolean isOsdTraffic() {
        return Prefers.getBoolean("player_osd_traffic");
    }

    public static void putOsdTraffic(boolean value) {
        Prefers.put("player_osd_traffic", value);
    }

    public static boolean isOsdMini() {
        return Prefers.getBoolean("player_osd_mini");
    }

    public static void putOsdMini(boolean value) {
        Prefers.put("player_osd_mini", value);
    }

    public static boolean isOsdEnabled() {
        return isOsdTitle() || isOsdTime() || isOsdProgress() || isOsdTraffic() || isOsdMini();
    }
}
