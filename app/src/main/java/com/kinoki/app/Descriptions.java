package com.kinoki.app;

import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Synopses (and posters, for saved titles that predate them) for the list rows. The listing pages don't carry these, so
 * each is fetched from the title's own page the first time its row is shown, then kept for the rest of the session.
 */
final class Descriptions {

    private static final int THREADS = 2;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(THREADS);
    private static final Map<String, Site.Details> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();

    private Descriptions() {
    }

    /** The details if they have been fetched already, otherwise null. */
    static Site.Details get(String url) {
        return CACHE.get(url);
    }

    /** Starts fetching the details if needed, and runs {@code onLoaded} on the main thread once they arrive. */
    static void request(String url, Runnable onLoaded) {
        if (CACHE.containsKey(url) || !PENDING.add(url)) {
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                CACHE.put(url, Site.fetchDetails(url));
                MAIN.post(onLoaded);
            } catch (IOException e) {
                // Not cached, so the next time the row is shown it will try again.
            } finally {
                PENDING.remove(url);
            }
        });
    }
}
