package com.fongmi.android.tv.ui.activity;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class SpeechAdSettingSourceTest {
    @Test
    public void leanbackExposesAllSpeechAdControls() throws Exception {
        String java = read("app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingEnhanceActivity.java");
        String xml = read("app/src/leanback/res/layout/activity_setting_enhance.xml");
        String presenter = read("app/src/main/java/com/fongmi/android/tv/ui/dialog/AdSkipPromptPresenter.java");
        assertTrue(xml.contains("@+id/speechAdEnabled"));
        assertTrue(xml.contains("@+id/speechAdKeywords"));
        assertTrue(xml.contains("@+id/speechAdSkipSeconds"));
        assertTrue(xml.contains("@+id/speechAdSkipMode"));
        assertTrue(java.contains("SpeechAdSetting.setEnabled"));
        assertTrue(java.contains("SpeechAdSetting.setKeywords"));
        assertTrue(java.contains("SpeechAdSetting.setSkipSeconds"));
        assertTrue(java.contains("SpeechAdSetting.setMode"));
        assertTrue(java.contains("reloadAdAudioSettings"));
        assertTrue(presenter.contains("SpeechAdSignalProvider.RULE_ID"));
        assertTrue(presenter.contains("ad_audio_speech_candidate_message"));
        assertTrue(!presenter.contains("prompt.ruleId(), prompt.skipDurationSeconds()"));
    }

    private static String read(String path) throws Exception {
        Path direct = Path.of(path);
        if (Files.exists(direct)) return Files.readString(direct, StandardCharsets.UTF_8);
        String modulePath = path.startsWith("app/") ? path.substring(4) : path;
        return Files.readString(Path.of(modulePath), StandardCharsets.UTF_8);
    }
}
