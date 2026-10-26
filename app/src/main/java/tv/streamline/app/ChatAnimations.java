package tv.streamline.app;

import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.widget.TextView;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

/** Keeps animations only for emotes in the current chat buffer. */
final class ChatAnimations implements AutoCloseable {
    private final Screen screen;
    private final TextView text;
    private final Runnable changed;
    private final int emoteHeight;
    private final Map<String, Drawable> images = new HashMap<>();
    private final Map<String, Call> pending = new HashMap<>();
    private final Set<String> attempted = new HashSet<>();
    private boolean closed;
    private final Drawable.Callback callback = new Drawable.Callback() {
        @Override public void invalidateDrawable(Drawable who) { text.postInvalidate(); }
        @Override public void scheduleDrawable(Drawable who, Runnable what, long when) {
            screen.main.postAtTime(what, who, when);
        }
        @Override public void unscheduleDrawable(Drawable who, Runnable what) { screen.main.removeCallbacks(what, who); }
    };

    ChatAnimations(Screen screen, TextView text, Runnable changed) {
        this.screen = screen;
        this.text = text;
        this.changed = changed;
        emoteHeight = Math.round(text.getTextSize() * 1.4f);
    }

    Drawable get(String id) {
        if (!closed && attempted.add(id)) {
            Call call = screen.api.http.newCall(new Request.Builder()
                    .url("https://static-cdn.jtvnw.net/emoticons/v2/" + id + "/animated/dark/2.0").build());
            pending.put(id, call);
            call.enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException error) { finish(call, null); }
                @Override public void onResponse(Call call, Response response) {
                    Drawable image = null;
                    try (Response value = response) {
                        if (value.isSuccessful() && value.body() != null) {
                            image = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(value.body().bytes())),
                                    (decoder, info, source) -> {
                                        // Android 11 animations do not scale to Drawable bounds.
                                        int width = Math.max(1, Math.round(emoteHeight * (float) info.getSize().getWidth()
                                                / info.getSize().getHeight()));
                                        decoder.setTargetSize(width, emoteHeight);
                                    });
                        }
                    } catch (IOException ignored) { /* Keep the static emote on failure. */ }
                    finish(call, image);
                }
                private void finish(Call call, Drawable image) {
                    screen.main.post(() -> {
                        if (closed || pending.get(id) != call) return;
                        pending.remove(id);
                        if (image == null) return;
                        images.put(id, image);
                        image.setCallback(callback);
                        setVisible(text.isShown());
                        changed.run();
                    });
                }
            });
        }
        return images.get(id);
    }

    void retain(Set<String> ids) {
        for (String id : new HashSet<>(attempted)) {
            if (ids.contains(id)) continue;
            attempted.remove(id);
            Call call = pending.remove(id);
            if (call != null) call.cancel();
            Drawable image = images.remove(id);
            if (image != null) {
                if (image instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) image).stop();
                image.setCallback(null);
                screen.main.removeCallbacksAndMessages(image);
            }
        }
    }

    void setVisible(boolean visible) {
        for (Drawable image : images.values()) {
            if (!(image instanceof AnimatedImageDrawable)) continue;
            AnimatedImageDrawable animation = (AnimatedImageDrawable) image;
            if (visible) {
                animation.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                animation.start();
            } else animation.stop();
        }
    }

    @Override public void close() {
        closed = true;
        retain(java.util.Collections.emptySet());
    }
}
