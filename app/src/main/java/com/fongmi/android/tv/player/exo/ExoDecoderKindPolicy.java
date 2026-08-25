package com.fongmi.android.tv.player.exo;

import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Classifies a decoder by its reported name, so the UI can show what is actually decoding
 * instead of only what the user selected.
 *
 * <p>The configured decode profile and the running decoder can legitimately disagree: in the
 * hard-decode profile Exo still installs the FFmpeg renderer as a fallback for codecs
 * MediaCodec refuses, so a session labelled "硬解" can be decoding in software. Reporting only
 * the configured value hides exactly the case a user needs to see when playback is slow.
 */
public final class ExoDecoderKindPolicy {

    /**
     * Known software decoder name prefixes. {@code OMX.google.} and {@code c2.android.} are
     * the platform's own software implementations, and {@code ffmpeg} covers the nextlib
     * extension renderers (for example {@code ffmpegLavc63.3.100-hevc}).
     */
    private static final String[] SOFTWARE_PREFIXES = {
            "omx.google.",
            "c2.android.",
    };

    /**
     * Matched anywhere in the name rather than only as a prefix. Some devices expose an OMX
     * wrapper around FFmpeg as {@code OMX.ffmpeg.*}, which a prefix test would miss and
     * report as hardware. No vendor hardware decoder carries this token, so substring
     * matching cannot produce a false software claim.
     */
    private static final String[] SOFTWARE_TOKENS = {
            "ffmpeg",
            "libvpx",
            "libgav1",
    };

    private ExoDecoderKindPolicy() {
    }

    public enum Kind {
        /** No decoder name reported yet; nothing can be concluded. */
        UNKNOWN,
        HARDWARE,
        SOFTWARE,
    }

    public static Kind classify(@Nullable String decoderName) {
        if (decoderName == null || decoderName.isBlank()) return Kind.UNKNOWN;
        String name = decoderName.trim().toLowerCase(Locale.US);
        for (String prefix : SOFTWARE_PREFIXES) {
            if (name.startsWith(prefix)) return Kind.SOFTWARE;
        }
        for (String token : SOFTWARE_TOKENS) {
            if (name.contains(token)) return Kind.SOFTWARE;
        }
        // Default to hardware so an unrecognized name under-reports rather than showing a
        // wrong "running software" claim.
        return Kind.HARDWARE;
    }

    /**
     * Returns whether a session configured for hardware decode is in fact decoding in
     * software. Only a positively identified software decoder counts; an unknown name never
     * produces a claim.
     */
    public static boolean isHardwareProfileRunningSoftware(
            boolean hardwareProfile, @Nullable String decoderName) {
        return hardwareProfile && classify(decoderName) == Kind.SOFTWARE;
    }

    /**
     * Decode label that reflects reality. When the configured profile and the running decoder
     * disagree, both are shown so the mismatch is visible at a glance rather than requiring
     * the user to cross-read the video row's decoder name.
     */
    public static String decodeLabel(
            String configuredLabel, boolean hardwareProfile, @Nullable String decoderName) {
        if (!isHardwareProfileRunningSoftware(hardwareProfile, decoderName)) return configuredLabel;
        return configuredLabel + "→软解";
    }
}
