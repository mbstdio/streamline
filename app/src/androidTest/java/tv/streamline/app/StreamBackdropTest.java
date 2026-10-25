package tv.streamline.app;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class StreamBackdropTest {
    @Test public void blurSoftensEdgesWithoutChangingSource() {
        Bitmap source = Bitmap.createBitmap(192, 108, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(source);
        canvas.drawColor(Color.BLACK);
        Paint paint = new Paint();
        paint.setColor(Color.WHITE);
        canvas.drawRect(96, 0, 192, 108, paint);
        Bitmap blurred = StreamBackdrop.blur(source);
        assertEquals(96, blurred.getWidth());
        assertEquals(54, blurred.getHeight());
        int edge = Color.red(blurred.getPixel(47, 27));
        assertTrue(edge > 30 && edge < 225);
        assertEquals(Color.BLACK, source.getPixel(95, 54));
        assertEquals(Color.WHITE, source.getPixel(96, 54));
        assertFalse(source.isRecycled());
        blurred.recycle();
        source.recycle();
    }
}
