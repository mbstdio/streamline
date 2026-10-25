package tv.streamline.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.widget.HorizontalScrollView;

/** Fades at the viewport edges, independently of the content's inset and scroll position. */
final class EdgeFadeScrollView extends HorizontalScrollView {
    private final Paint leftFade = new Paint();
    private final Paint rightFade = new Paint();
    private final int fadeWidth;

    EdgeFadeScrollView(Context context, int fadeWidth) {
        super(context);
        setHorizontalScrollBarEnabled(false);
        this.fadeWidth = fadeWidth;
        leftFade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        rightFade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        leftFade.setShader(new LinearGradient(0, 0, fadeWidth, 0, 0xff000000, 0x00000000, Shader.TileMode.CLAMP));
        rightFade.setShader(new LinearGradient(width - fadeWidth, 0, width, 0, 0x00000000, 0xff000000, Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas canvas) {
        // Isolate the list so the alpha masks erase its pixels, never the backdrop behind it.
        int save = canvas.saveLayer(getScrollX(), getScrollY(),
                getScrollX() + getWidth(), getScrollY() + getHeight(), null);
        super.draw(canvas);
        canvas.translate(getScrollX(), getScrollY());
        canvas.drawRect(0, 0, fadeWidth, getHeight(), leftFade);
        canvas.drawRect(getWidth() - fadeWidth, 0, getWidth(), getHeight(), rightFade);
        canvas.restoreToCount(save);
    }
}
