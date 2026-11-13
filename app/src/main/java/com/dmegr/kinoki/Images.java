package com.dmegr.kinoki;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;
import okhttp3.Response;

/** Loads poster thumbnails into ImageViews, caching decoded bitmaps in memory. */
final class Images {

    private static final int THREADS = 3;
    private static final int CACHE_DIVISOR = 8;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(THREADS);
    /** Sized in kilobytes: an eighth of the app's heap. */
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(
            (int) (Runtime.getRuntime().maxMemory() / 1024 / CACHE_DIVISOR)) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount() / 1024;
        }
    };

    private Images() {
    }

    /**
     * Shows the image at {@code url} in the view, or clears it while it loads. The view's tag records which URL it is
     * waiting for, so a recycled list row never shows a poster that belongs to another title.
     */
    static void load(ImageView view, String url) {
        view.setTag(url);
        if (url == null) {
            view.setImageDrawable(null);
            return;
        }
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        EXECUTOR.execute(() -> {
            Bitmap bitmap = download(url);
            if (bitmap == null) {
                return;
            }
            CACHE.put(url, bitmap);
            MAIN.post(() -> {
                if (url.equals(view.getTag())) {
                    view.setImageBitmap(bitmap);
                }
            });
        });
    }

    private static Bitmap download(String url) {
        Request request = new Request.Builder().url(url).header("User-Agent", Site.USER_AGENT).build();
        try (Response response = Site.execute(request)) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            byte[] bytes = response.body().bytes();
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (IOException e) {
            return null;
        }
    }
}
