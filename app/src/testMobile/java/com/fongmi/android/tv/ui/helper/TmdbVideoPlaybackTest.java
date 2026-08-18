package com.fongmi.android.tv.ui.helper;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.TmdbVideo;
import com.google.gson.JsonObject;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class TmdbVideoPlaybackTest {

    @Test
    public void createLaunchUsesPushWithExplicitYoutubeEpisodeAndNoHistoryResume() {
        JsonObject object = new JsonObject();
        object.addProperty("id", "video-id");
        object.addProperty("key", "abc_DEF-1");
        object.addProperty("site", "YouTube");
        object.addProperty("name", "Official Trailer");
        object.addProperty("type", "Trailer");
        TmdbVideo video = TmdbVideo.from(object, TmdbVideo.Scope.EPISODE, 2, 3);

        TmdbVideoPlayback.Launch launch = TmdbVideoPlayback.create(video, "推送");

        assertEquals(SiteApi.PUSH, launch.getKey());
        assertEquals("https://www.youtube.com/watch?v=abc_DEF-1|Official Trailer", launch.getId());
        assertEquals("Official Trailer", launch.getName());
        assertEquals("https://i.ytimg.com/vi/abc_DEF-1/hqdefault.jpg", launch.getPic());
        assertEquals("Trailer · 当前集", launch.getMark());
        assertEquals("推送", launch.getPlayFlag());
        assertEquals("Official Trailer", launch.getPlayEpisodeName());
        assertEquals("https://www.youtube.com/watch?v=abc_DEF-1", launch.getPlayEpisodeUrl());
        assertFalse(launch.isResumeFromHistory());
    }

    @Test
    public void createLaunchRejectsMissingVideo() {
        assertNull(TmdbVideoPlayback.create(null, "推送"));
    }
}
