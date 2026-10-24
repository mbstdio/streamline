package tv.streamline.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Activity-scoped cache; callers decide whether a result still belongs to their screen. */
final class ThumbnailLoader implements AutoCloseable {
    private final OkHttpClient http;
    private final ExecutorService worker = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private volatile boolean closed;

    ThumbnailLoader(OkHttpClient http) { this.http = http; }

    void load(String url, int sampleSize, Consumer<Bitmap> result) {
        if (closed || !url.startsWith("https://")) return;
        String key = url + "#" + sampleSize;
        Bitmap cached = cache.get(key);
        if (cached != null) { result.accept(cached); return; }
        worker.execute(() -> {
            try (Response response = http.newCall(new Request.Builder().url(url).build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) return;
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sampleSize;
                Bitmap bitmap = BitmapFactory.decodeStream(response.body().byteStream(), null, options);
                synchronized (this) {
                    if (closed || bitmap == null) return;
                    cache.put(key, bitmap);
                }
                result.accept(bitmap);
            } catch (Exception ignored) { /* Keep the placeholder when a preview is unavailable. */ }
        });
    }

    @Override public synchronized void close() {
        closed = true;
        worker.shutdownNow();
        cache.evictAll();
    }
}
