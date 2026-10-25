package tv.streamline.app;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ThumbnailLoaderTest {
    @Test public void sharesDownloadKeepsOtherOwnerAndReusesBitmapOnReturn() throws Exception {
        Bitmap source = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        source.compress(Bitmap.CompressFormat.PNG, 100, bytes);
        source.recycle();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), delivered = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger(), cancelledCallbacks = new AtomicInteger();
        AtomicReference<Bitmap> received = new AtomicReference<>(), restored = new AtomicReference<>();
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet();
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Fixture timed out");
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create(bytes.toByteArray(), MediaType.get("image/png"))).build();
        }).build();
        Object firstScreen = new Object(), secondScreen = new Object();
        String url = "https://images.example.test/preview.png";
        try (ThumbnailLoader loader = new ThumbnailLoader(http)) {
            loader.load(firstScreen, url, 1, bitmap -> cancelledCallbacks.incrementAndGet());
            assertTrue(started.await(5, TimeUnit.SECONDS));
            loader.load(secondScreen, url, 1, bitmap -> { received.set(bitmap); delivered.countDown(); });
            loader.cancel(firstScreen);
            release.countDown();
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertNotNull(received.get());
            assertEquals(0, cancelledCallbacks.get());
            loader.cancel(secondScreen);
            assertSame(received.get(), loader.cached(url, 1));
            loader.load(firstScreen, url, 1, restored::set);
            assertSame(received.get(), restored.get());
            assertEquals(1, requests.get());
        } finally { release.countDown(); }
    }
}
