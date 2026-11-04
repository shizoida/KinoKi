package com.dmegr.mykadri;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Titles the user starred, newest first. Kept in memory and written to preferences on every change. */
final class Favorites {

    private static final String PREFS = "favorites";
    private static final String KEY_ITEMS = "items";
    private static final String NAME = "n";
    private static final String URL = "u";

    /** Page URL to title name, in display order. */
    private static final Map<String, String> items = new LinkedHashMap<>();
    private static boolean loaded;

    private Favorites() {
    }

    static synchronized void load(Context context) {
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

    static synchronized boolean contains(String url) {
        return items.containsKey(url);
    }

    static synchronized List<Site.Title> all() {
        List<Site.Title> titles = new ArrayList<>();
        for (Map.Entry<String, String> item : items.entrySet()) {
            titles.add(new Site.Title(item.getValue(), item.getKey()));
        }
        return titles;
    }

    /** Adds the title if it isn't a favorite, removes it if it is. Returns true if it is a favorite afterwards. */
    static synchronized boolean toggle(Context context, String name, String url) {
        boolean added = !items.containsKey(url);
        if (added) {
            Map<String, String> older = new LinkedHashMap<>(items);
            items.clear();
            items.put(url, name);
            items.putAll(older);
        } else {
            items.remove(url);
        }
        save(context);
        return added;
    }

    private static void save(Context context) {
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

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
