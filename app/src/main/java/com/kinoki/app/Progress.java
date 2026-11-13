package com.kinoki.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Remembers how far into each stream the user got, so playback can resume there. */
final class Progress {

    private static final String PREFS = "progress";
    /** Positions before this are treated as "not started". */
    private static final long MIN_RESUME_MS = 10_000;
    /** Within this distance of the end the title counts as finished and is forgotten. */
    private static final long FINISHED_MARGIN_MS = 60_000;
    private static final String WATCHED_PREFIX = "watched:";
    /** Playing past this fraction of the duration counts as having watched it (the rest is mostly credits). */
    private static final double WATCHED_FRACTION = 0.9;

    private Progress() {
    }

    /** Stream URLs carry a signature that changes on every page load, so only the part before '?' identifies them. */
    private static String key(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    /** Saved position in milliseconds, or 0 if there is none worth resuming. */
    static long get(Context context, String url) {
        long position = prefs(context).getLong(key(url), 0);
        return position >= MIN_RESUME_MS ? position : 0;
    }

    static void save(Context context, String url, long position, long duration) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (position < MIN_RESUME_MS || (duration > 0 && position > duration - FINISHED_MARGIN_MS)) {
            editor.remove(key(url));
        } else {
            editor.putLong(key(url), position);
        }
        editor.apply();
    }

    /**
     * Whether something counts as watched: it was played to nearly the end at some point. {@code watchKey} is the
     * stream URL of an episode, or the page URL of a movie. Stays true even if the user later replays the start.
     */
    static boolean isWatched(Context context, String watchKey) {
        return prefs(context).getBoolean(WATCHED_PREFIX + key(watchKey), false);
    }

    static void markWatchedIfDone(Context context, String watchKey, long position, long duration) {
        if (duration > 0 && position >= duration * WATCHED_FRACTION) {
            prefs(context).edit().putBoolean(WATCHED_PREFIX + key(watchKey), true).apply();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
