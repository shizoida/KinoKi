package com.dmegr.mykadri;

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

    private final String prefsName;
    private final int maxSize;
    /** Page URL to title name, in display order. */
    private final Map<String, String> items = new LinkedHashMap<>();
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
                items.put(item.getString(URL), item.getString(NAME));
            }
        } catch (JSONException e) {
            items.clear();
        }
    }

    synchronized boolean contains(String url) {
        return items.containsKey(url);
    }

    synchronized List<Site.Title> all() {
        List<Site.Title> titles = new ArrayList<>();
        for (Map.Entry<String, String> item : items.entrySet()) {
            titles.add(new Site.Title(item.getValue(), item.getKey()));
        }
        return titles;
    }

    /** Puts the title at the front (moving it there if it was already present) and drops the oldest beyond the limit. */
    synchronized void addFirst(Context context, String name, String url) {
        Map<String, String> older = new LinkedHashMap<>(items);
        older.remove(url);
        items.clear();
        items.put(url, name);
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
    synchronized boolean toggle(Context context, String name, String url) {
        if (items.containsKey(url)) {
            items.remove(url);
            save(context);
            return false;
        }
        addFirst(context, name, url);
        return true;
    }

    private void save(Context context) {
        JSONArray array = new JSONArray();
        try {
            for (Map.Entry<String, String> item : items.entrySet()) {
                array.put(new JSONObject().put(URL, item.getKey()).put(NAME, item.getValue()));
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
