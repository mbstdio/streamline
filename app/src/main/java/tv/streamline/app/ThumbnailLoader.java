package tv.streamline.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Activity-scoped cache; callers decide whether a result still belongs to their screen. */
final class ThumbnailLoader implements AutoCloseable {
    private final OkHttpClient http;
    private final ThreadPoolExecutor worker = (ThreadPoolExecutor) Executors.newFixedThreadPool(5);
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(32 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private final Map<String, Download> pending = new HashMap<>();
    private boolean closed;

    ThumbnailLoader(OkHttpClient http) { this.http = http; }

    synchronized Bitmap cached(String url, int sampleSize) {
        return closed ? null : cache.get(url + "#" + sampleSize);
    }

    synchronized void load(Object owner, String url, int sampleSize, Consumer<Bitmap> result) {
        if (closed || !url.startsWith("https://")) return;
        String key = url + "#" + sampleSize;
        Bitmap cached = cache.get(key);
        if (cached != null) { result.accept(cached); return; }
        Download download = pending.get(key);
        boolean start = download == null;
        if (start) {
            download = new Download(key, url, sampleSize);
            pending.put(key, download);
        }
        download.listeners.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(result);
        if (start) worker.execute(download);
    }

    synchronized void cancel(Object owner) {
        Iterator<Download> iterator = pending.values().iterator();
        while (iterator.hasNext()) {
            Download download = iterator.next();
            download.listeners.remove(owner);
            if (download.listeners.isEmpty()) {
                download.call.cancel();
                worker.remove(download);
                iterator.remove();
            }
        }
    }

    private final class Download implements Runnable {
        final String key;
        final int sampleSize;
        final Call call;
        final Map<Object, List<Consumer<Bitmap>>> listeners = new IdentityHashMap<>();

        Download(String key, String url, int sampleSize) {
            this.key = key;
            this.sampleSize = sampleSize;
            call = http.newCall(new Request.Builder().url(url).build());
        }

        @Override public void run() {
            Bitmap bitmap = null;
            try (Response response = call.execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = sampleSize;
                    bitmap = BitmapFactory.decodeStream(response.body().byteStream(), null, options);
                }
            } catch (Exception ignored) { /* Keep the placeholder when a preview is unavailable. */ }
            List<Consumer<Bitmap>> results = new ArrayList<>();
            synchronized (ThumbnailLoader.this) {
                // A cancelled download must not remove a newer request for the same image.
                if (pending.get(key) != this) return;
                pending.remove(key);
                if (closed || bitmap == null) return;
                cache.put(key, bitmap);
                for (List<Consumer<Bitmap>> group : listeners.values()) results.addAll(group);
            }
            for (Consumer<Bitmap> result : results) result.accept(bitmap);
        }
    }

    @Override public synchronized void close() {
        closed = true;
        for (Download download : pending.values()) download.call.cancel();
        pending.clear();
        worker.shutdownNow();
        cache.evictAll();
    }
}
