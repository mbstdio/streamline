package tv.streamline.app;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Small software-blurred previews also work on Android TV 11, without RenderEffect. */
final class StreamBackdrop extends Drawable {
    private final CatalogScreen screen;
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fadePaint = new Paint();
    private final RectF area = new RectF();
    private Bitmap previous, current;
    private ValueAnimator transition;
    private Runnable pending;
    private int selection;
    private float progress = 1f;

    StreamBackdrop(CatalogScreen screen) { this.screen = screen; }

    void focus(View card, String url, boolean focused) {
        selection++;
        if (pending != null) screen.main.removeCallbacks(pending);
        screen.host.images.cancel(this);
        if (!focused || url.isEmpty()) return;
        int ticket = selection, generation = screen.generation;
        pending = () -> screen.host.images.load(this, url, 2, bitmap ->
                screen.worker.execute(() -> {
                    Bitmap blurred = blur(bitmap);
                    screen.ui(generation, () -> {
                        if (ticket != selection || !card.hasFocus()) return;
                        if (transition != null) transition.cancel();
                        previous = current;
                        current = blurred;
                        progress = 1f;
                        if (ValueAnimator.areAnimatorsEnabled()) {
                            transition = ValueAnimator.ofFloat(0f, 1f);
                            transition.setDuration(300);
                            transition.addUpdateListener(value -> {
                                progress = (float) value.getAnimatedValue();
                                if (progress == 1f) previous = null;
                                invalidateSelf();
                            });
                            transition.start();
                        } else previous = null;
                        invalidateSelf();
                    });
                }));
        screen.main.postDelayed(pending, 500);
    }

    void close() {
        selection++;
        if (pending != null) screen.main.removeCallbacks(pending);
        screen.host.images.cancel(this);
        if (transition != null) transition.cancel();
        previous = current = null;
    }

    @Override protected void onBoundsChange(Rect bounds) {
        area.set(bounds.left, bounds.top, bounds.right, bounds.top + bounds.height() * .7f);
        fadePaint.setShader(new LinearGradient(0, area.top, 0, area.bottom,
                new int[]{0x00101018, 0x80101018, Screen.BG}, new float[]{0f, .5f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas canvas) {
        canvas.drawColor(Screen.BG);
        int saved = canvas.save();
        canvas.clipRect(area);
        drawImage(canvas, previous, 1f - progress);
        drawImage(canvas, current, progress);
        canvas.drawRect(area, fadePaint);
        canvas.restoreToCount(saved);
    }

    private void drawImage(Canvas canvas, Bitmap bitmap, float alpha) {
        if (bitmap == null) return;
        float scale = Math.max(area.width() / bitmap.getWidth(), area.height() / bitmap.getHeight());
        float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
        imagePaint.setAlpha(Math.round(255 * .4f * alpha));
        canvas.drawBitmap(bitmap, null, new RectF(area.centerX() - width / 2, area.centerY() - height / 2,
                area.centerX() + width / 2, area.centerY() + height / 2), imagePaint);
    }

    static Bitmap blur(Bitmap source) {
        int width = 96, height = Math.max(1, Math.round(96f * source.getHeight() / source.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(source, width, height, true);
        int[] pixels = new int[width * height], output = new int[pixels.length];
        small.getPixels(pixels, 0, width, 0, 0, width, height);
        if (small != source) small.recycle();
        // Three separable box passes approximate a strong Gaussian blur at a tiny fixed resolution.
        for (int pass = 0; pass < 6; pass++) {
            boolean horizontal = pass % 2 == 0;
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int red = 0, green = 0, blue = 0;
                for (int offset = -5; offset <= 5; offset++) {
                    int sx = horizontal ? Math.max(0, Math.min(width - 1, x + offset)) : x;
                    int sy = horizontal ? y : Math.max(0, Math.min(height - 1, y + offset));
                    int color = pixels[sy * width + sx];
                    red += Color.red(color); green += Color.green(color); blue += Color.blue(color);
                }
                output[y * width + x] = Color.rgb(red / 11, green / 11, blue / 11);
            }
            int[] swap = pixels; pixels = output; output = swap;
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter filter) { }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
