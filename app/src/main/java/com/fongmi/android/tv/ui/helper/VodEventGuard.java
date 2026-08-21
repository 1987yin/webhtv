package com.fongmi.android.tv.ui.helper;

import android.text.TextUtils;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.utils.PushParser;

public final class VodEventGuard {

    public static boolean matches(Vod item, String currentSiteKey, String currentId) {
        if (item == null) return false;
        String id = item.getId();
        String siteKey = item.getSiteKey();
        if (!TextUtils.isEmpty(id) && !TextUtils.equals(normalizeId(id, currentSiteKey), normalizeId(currentId, currentSiteKey))) return false;
        return TextUtils.isEmpty(siteKey) || TextUtils.equals(siteKey, currentSiteKey);
    }

    private static String normalizeId(String id, String siteKey) {
        String value = stripPageSuffix(id);
        return SiteApi.PUSH.equals(siteKey) ? PushParser.fromId(value).getUrl() : value;
    }

    static String stripPageSuffix(String id) {
        if (TextUtils.isEmpty(id)) return id;
        id = id.trim();
        if (id.startsWith("/") && hasUriScheme(id.substring(1))) id = id.substring(1);
        if (hasUriScheme(id)) return id;
        int slash = id.indexOf('/');
        return slash > 0 ? id.substring(0, slash) : id;
    }

    private static boolean hasUriScheme(String value) {
        int separator = value == null ? -1 : value.indexOf("://");
        if (separator <= 0 || !Character.isLetter(value.charAt(0))) return false;
        for (int i = 1; i < separator; i++) {
            char c = value.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '+' && c != '-' && c != '.') return false;
        }
        return true;
    }

    private VodEventGuard() {
    }
}
