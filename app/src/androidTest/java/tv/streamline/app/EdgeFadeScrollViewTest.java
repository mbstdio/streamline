package tv.streamline.app;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class EdgeFadeScrollViewTest {
    @Test public void edgesRevealBackdropInsteadOfPaintingDarkStrips() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            EdgeFadeScrollView scroll = new EdgeFadeScrollView(
                    InstrumentationRegistry.getInstrumentation().getTargetContext(), 20);
            View content = new View(scroll.getContext());
            content.setBackgroundColor(Color.BLUE);
            scroll.addView(content, new FrameLayout.LayoutParams(300, 80));
            scroll.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY));
            scroll.layout(0, 0, 100, 80);
            for (int offset : new int[]{0, 50}) {
                scroll.scrollTo(offset, 0);
                Bitmap image = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(image);
                canvas.drawColor(Color.MAGENTA);
                canvas.save();
                canvas.translate(-scroll.getScrollX(), 0);
                scroll.draw(canvas);
                canvas.restore();
                assertEquals(Color.BLUE, image.getPixel(50, 40));
                assertTrue(Color.red(image.getPixel(1, 40)) > 200);
                assertTrue(Color.red(image.getPixel(98, 40)) > 200);
                assertEquals(255, Color.blue(image.getPixel(1, 40)));
                image.recycle();
            }
        });
    }
}
