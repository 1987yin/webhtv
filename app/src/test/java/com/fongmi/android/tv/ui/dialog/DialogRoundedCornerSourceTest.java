package com.fongmi.android.tv.ui.dialog;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class DialogRoundedCornerSourceTest {

    @Test
    public void outerDialogsUseTheUnifiedTwentyTwoDpRadius() throws Exception {
        String[] drawables = {
                "src/main/res/drawable/shape_shell_proxy_dialog.xml",
                "src/main/res/drawable/shape_display_dialog_panel.xml",
                "src/main/res/drawable/shape_one_key_sync_dialog.xml",
                "src/leanback/res/drawable/shape_config_history_dialog.xml",
                "src/leanback/res/drawable/shape_episode_dialog_panel.xml",
                "src/main/res/drawable/shape_site_dialog.xml"
        };

        for (String drawable : drawables) {
            Path path = Path.of(drawable);
            String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            assertTrue(drawable + " should use the unified 22dp corner radius",
                    source.contains("<corners android:radius=\"22dp\" />"));
        }
    }
}
