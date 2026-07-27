package com.fongmi.android.tv.bean;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class TmdbItem implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int tmdbId;
    private final String mediaType;
    private final String title;
    private final String subtitle;
    private final String overview;
    private final String posterUrl;
    private final String backdropUrl;
    private final String credit;
    private final double rating;
    private final String originalLanguage;
    private final String originCountry;
    private final List<Integer> genreIds;
    private final String department;

    public TmdbItem(int tmdbId, String mediaType, String title, String subtitle, String overview, String posterUrl, String backdropUrl) {
        this(tmdbId, mediaType, title, subtitle, overview, posterUrl, backdropUrl, "", 0.0);
    }

    public TmdbItem(int tmdbId, String mediaType, String title, String subtitle, String overview, String posterUrl, String backdropUrl, String credit) {
        this(tmdbId, mediaType, title, subtitle, overview, posterUrl, backdropUrl, credit, 0.0);
    }

    public TmdbItem(int tmdbId, String mediaType, String title, String subtitle, String overview, String posterUrl, String backdropUrl, String credit, double rating) {
        this(tmdbId, mediaType, title, subtitle, overview, posterUrl, backdropUrl, credit, rating, "", "");
    }

    public TmdbItem(int tmdbId, String mediaType, String title, String subtitle, String overview, String posterUrl, String backdropUrl, String credit, double rating, String originalLanguage, String originCountry) {
        this(tmdbId, mediaType, title, subtitle, overview, posterUrl, backdropUrl, credit, rating, originalLanguage, originCountry, new ArrayList<>());
    }

    public TmdbItem(int tmdbId, String mediaType, String title, String subtitle, String overview, String posterUrl, String backdropUrl, String credit, double rating, String originalLanguage, String originCountry, List<Integer> genreIds) {
        this(tmdbId, mediaType, title, subtitle, overview, posterUrl, backdropUrl, credit, rating, originalLanguage, originCountry, genreIds, "");
    }

    public TmdbItem(int tmdbId, String mediaType, String title, String subtitle, String overview, String posterUrl, String backdropUrl, String credit, double rating, String originalLanguage, String originCountry, List<Integer> genreIds, String department) {
        this.tmdbId = tmdbId;
        this.mediaType = mediaType;
        this.title = title;
        this.subtitle = subtitle;
        this.overview = overview;
        this.posterUrl = posterUrl;
        this.backdropUrl = backdropUrl;
        this.credit = credit;
        this.rating = rating;
        this.originalLanguage = originalLanguage;
        this.originCountry = originCountry;
        this.genreIds = genreIds == null ? new ArrayList<>() : new ArrayList<>(genreIds);
        this.department = department;
    }

    public int getTmdbId() {
        return tmdbId;
    }

    public String getMediaType() {
        return isEmpty(mediaType) ? "" : mediaType;
    }

    public String getTitle() {
        return isEmpty(title) ? "" : title;
    }

    public String getSubtitle() {
        return isEmpty(subtitle) ? "" : subtitle;
    }

    public String getOverview() {
        return isEmpty(overview) ? "" : overview;
    }

    public String getPosterUrl() {
        return isEmpty(posterUrl) ? "" : posterUrl;
    }

    public String getBackdropUrl() {
        return isEmpty(backdropUrl) ? "" : backdropUrl;
    }

    public String getCredit() {
        return isEmpty(credit) ? "" : credit;
    }

    public double getRating() {
        return rating;
    }

    public String getOriginalLanguage() {
        return isEmpty(originalLanguage) ? "" : originalLanguage;
    }

    public String getOriginCountry() {
        return isEmpty(originCountry) ? "" : originCountry;
    }

    public List<Integer> getGenreIds() {
        return new ArrayList<>(genreIds);
    }

    public String getDepartment() {
        return isEmpty(department) ? "" : department;
    }

    public boolean isTv() {
        return "tv".equals(mediaType);
    }

    public boolean isMovie() {
        return "movie".equals(mediaType);
    }

    private boolean isEmpty(String value) {
        return value == null || value.length() == 0;
    }
}
