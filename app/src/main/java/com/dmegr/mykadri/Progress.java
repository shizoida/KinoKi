package com.dmegr.mykadri;

import android.content.Context;
import android.content.SharedPreferences;

/** Remembers how far into each stream the user got, so playback can resume there. */
final class Progress {

    private static final String PREFS = "progress";
    /** Positions before this are treated as "not started". */
    private static final long MIN_RESUME_MS = 10_000;
    /** Within this distance of the end the title counts as finished and is forgotten. */
    private static final long FINISHED_MARGIN_MS = 60_000;

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

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
