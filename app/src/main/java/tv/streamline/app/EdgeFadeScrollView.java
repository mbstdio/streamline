package tv.streamline.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.widget.HorizontalScrollView;

/** Fades at the viewport edges, independently of the content's inset and scroll position. */
final class EdgeFadeScrollView extends HorizontalScrollView {
    private final Paint leftFade = new Paint();
    private final Paint rightFade = new Paint();
    private final int color;
    private final int fadeWidth;

    EdgeFadeScrollView(Context context, int color, int fadeWidth) {
        super(context);
        setHorizontalScrollBarEnabled(false);
        this.color = color;
        this.fadeWidth = fadeWidth;
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        int transparent = color & 0x00ffffff;
        leftFade.setShader(new LinearGradient(0, 0, fadeWidth, 0, color, transparent, Shader.TileMode.CLAMP));
        rightFade.setShader(new LinearGradient(width - fadeWidth, 0, width, 0, transparent, color, Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas canvas) {
        super.draw(canvas);
        int save = canvas.save();
        canvas.translate(getScrollX(), getScrollY());
        canvas.drawRect(0, 0, fadeWidth, getHeight(), leftFade);
        canvas.drawRect(getWidth() - fadeWidth, 0, getWidth(), getHeight(), rightFade);
        canvas.restoreToCount(save);
    }
}
