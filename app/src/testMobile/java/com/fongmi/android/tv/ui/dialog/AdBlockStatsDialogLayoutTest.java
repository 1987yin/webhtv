package com.fongmi.android.tv.ui.dialog;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class AdBlockStatsDialogLayoutTest {

    @Test
    public void statsDialogUsesNearFullScreenScrollableRoot() throws Exception {
        for (String flavor : new String[] {"mobile", "leanback"}) {
            String layout = read(findRepositoryRoot().resolve(Path.of("app", "src", flavor, "res", "layout", "dialog_ad_block_stats.xml")));
            int rootStart = layout.indexOf("<androidx.core.widget.NestedScrollView");
            String root = layout.substring(rootStart, layout.indexOf('>', rootStart) + 1);

            assertTrue(flavor + " statistics dialog should use the available height",
                    root.contains("android:layout_height=\"match_parent\""));
            assertTrue(flavor + " statistics dialog should fill the viewport",
                    root.contains("android:fillViewport=\"true\""));
            assertTrue(flavor + " statistics dialog should retain its vertical scrollbar",
                    root.contains("android:scrollbars=\"vertical\""));
        }
    }

    @Test
    public void statsDialogKeepsAllThreeCoreMetrics() throws Exception {
        for (String flavor : new String[] {"mobile", "leanback"}) {
            String layout = read(findRepositoryRoot().resolve(Path.of("app", "src", flavor, "res", "layout", "dialog_ad_block_stats.xml")));

            assertTrue(layout.contains("android:id=\"@+id/totalBlocked\""));
            assertTrue(layout.contains("android:id=\"@+id/aiFeedbackCount\""));
            assertTrue(layout.contains("android:id=\"@+id/aiSuccessRate\""));
        }
    }

    @Test
    public void statsDialogMakesScrollableContentExplicitlyDiscoverable() throws Exception {
        for (String flavor : new String[] {"mobile", "leanback"}) {
            String layout = read(findRepositoryRoot().resolve(Path.of("app", "src", flavor, "res", "layout", "dialog_ad_block_stats.xml")));

            assertTrue("The statistics dialog should expose a persistent vertical scroll indicator",
                    layout.contains("android:scrollbars=\"vertical\""));
            assertTrue("The statistics dialog should keep the scroll indicator visible long enough to reveal more content",
                    layout.contains("android:fadeScrollbars=\"false\""));
        }
    }

    @Test
    public void statsDialogKeepsAllThreeRankingDimensionsInTheScrollableContent() throws Exception {
        for (String flavor : new String[] {"mobile", "leanback"}) {
            String layout = read(findRepositoryRoot().resolve(Path.of("app", "src", flavor, "res", "layout", "dialog_ad_block_stats.xml")));

            assertTrue(layout.contains("android:id=\"@+id/siteRankRecycler\""));
            assertTrue(layout.contains("android:id=\"@+id/ruleRankRecycler\""));
            assertTrue(layout.contains("android:id=\"@+id/pipelineRankRecycler\""));
        }
    }

    @Test
    public void mobileStatsDialogUsesReadableTextOnWhiteSurface() throws Exception {
        Path root = findRepositoryRoot();
        String dialog = read(root.resolve(Path.of("app", "src", "mobile", "res", "layout", "dialog_ad_block_stats.xml")));
        String item = read(root.resolve(Path.of("app", "src", "mobile", "res", "layout", "adapter_ad_stats_item.xml")));

        assertTrue("White dialog surface must not use white primary text",
                !dialog.contains("android:textColor=\"@color/white\"")
                        && !item.contains("android:textColor=\"@color/white\""));
        assertTrue("White dialog surface must not use translucent white secondary text",
                !dialog.contains("android:textColor=\"@color/white_50\"")
                        && !item.contains("android:textColor=\"@color/white_50\""));
    }

    @Test
    public void simplifiedChineseStatsDialogLocalizesPipelineRankingTitle() throws Exception {
        String strings = read(findRepositoryRoot().resolve(Path.of("app", "src", "main", "res", "values-zh-rCN", "strings.xml")));

        assertTrue("The pipeline ranking title should be localized for the Chinese TV/mobile UI",
                strings.contains("<string name=\"ad_pipeline_rank\">播放链路排行</string>"));
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve(Path.of("app", "src", "mobile", "res", "layout", "dialog_ad_block_stats.xml")))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("Repository root not found");
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
