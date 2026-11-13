package com.kinoki.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An ordered list of titles (newest first) kept in memory and written to preferences on every change. */
final class TitleStore {

    static final TitleStore FAVORITES = new TitleStore("favorites", Integer.MAX_VALUE);
    /** Titles most recently played, so the user can jump back into what they were watching. */
    static final TitleStore RECENT = new TitleStore("recent", 10);

    private static final String KEY_ITEMS = "items";
    private static final String NAME = "n";
    private static final String URL = "u";
    private static final String POSTER = "p";
    private static final String YEAR = "y";
    private static final String RATING = "r";

    private final String prefsName;
    private final int maxSize;
    /** Page URL to title, in display order. */
    private final Map<String, Site.Title> items = new LinkedHashMap<>();
    private boolean loaded;

    private TitleStore(String prefsName, int maxSize) {
        this.prefsName = prefsName;
        this.maxSize = maxSize;
    }

    synchronized void load(Context context) {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            JSONArray array = new JSONArray(prefs(context).getString(KEY_ITEMS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                String url = item.getString(URL);
                // Entries saved by older versions only have a name and URL; the extras are optional.
                items.put(url, new Site.Title(item.getString(NAME), url, optString(item, POSTER),
                        optString(item, YEAR), optString(item, RATING)));
            }
        } catch (JSONException e) {
            items.clear();
        }
    }

    private static String optString(JSONObject object, String key) {
        return object.isNull(key) ? null : object.optString(key, null);
    }

    synchronized boolean contains(String url) {
        return items.containsKey(url);
    }

    synchronized List<Site.Title> all() {
        return new ArrayList<>(items.values());
    }

    /** Puts the title at the front (moving it there if it was already present) and drops the oldest beyond the limit. */
    synchronized void addFirst(Context context, Site.Title title) {
        Map<String, Site.Title> older = new LinkedHashMap<>(items);
        older.remove(title.url);
        items.clear();
        items.put(title.url, title);
        items.putAll(older);
        Iterator<String> oldest = items.keySet().iterator();
        for (int i = 0; oldest.hasNext(); i++) {
            oldest.next();
            if (i >= maxSize) {
                oldest.remove();
            }
        }
        save(context);
    }

    /** Adds the title if it isn't in the list, removes it if it is. Returns true if it is in the list afterwards. */
    synchronized boolean toggle(Context context, Site.Title title) {
        if (items.containsKey(title.url)) {
            items.remove(title.url);
            save(context);
            return false;
        }
        addFirst(context, title);
        return true;
    }

    private void save(Context context) {
        JSONArray array = new JSONArray();
        try {
            for (Site.Title title : items.values()) {
                array.put(new JSONObject()
                        .put(URL, title.url)
                        .put(NAME, title.name)
                        .put(POSTER, title.poster)
                        .put(YEAR, title.year)
                        .put(RATING, title.rating));
            }
        } catch (JSONException e) {
            return;
        }
        prefs(context).edit().putString(KEY_ITEMS, array.toString()).apply();
    }

    private SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
    }
}
