package tv.streamline.app;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

final class LoginScreen extends Screen {
    LoginScreen(MainActivity host) { super(host); }

    @Override void show() {
        base(true);
        root.addView(heading(getString(R.string.login_title)));
        LinearLayout details = row();
        ImageView qr = new ImageView(this);
        qr.setContentDescription(getString(R.string.login_qr));
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qr.setVisibility(View.GONE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(210), dp(210));
        params.setMargins(0, dp(12), dp(26), dp(12));
        details.addView(qr, params);
        TextView instructions = text(getString(R.string.login_preparing), 24, TEXT);
        details.addView(instructions, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(details);
        root.addView(text(getString(R.string.login_instructions), 18, MUTED));
        root.addView(button(getString(R.string.cancel), host::showHome));
        int request = generation;
        worker.execute(() -> {
            try {
                JSONObject device = api.startDeviceLogin();
                String code = device.getString("device_code");
                int interval = Math.max(5, device.optInt("interval", 5));
                long expires = System.currentTimeMillis() + device.getLong("expires_in") * 1000;
                String url = device.optString("verification_uri", "https://www.twitch.tv/activate");
                String qrUrl = device.optString("verification_uri_complete");
                if (qrUrl.isEmpty()) qrUrl = url;
                Bitmap bitmap = null;
                try { bitmap = loginQrCode(qrUrl); }
                catch (com.google.zxing.WriterException ignored) { /* The URL and code remain usable. */ }
                Bitmap qrBitmap = bitmap;
                ui(request, () -> {
                    instructions.setText(getString(R.string.login_code, url, device.optString("user_code")));
                    if (qrBitmap != null) { qr.setImageBitmap(qrBitmap); qr.setVisibility(View.VISIBLE); }
                });
                schedulePoll(request, code, interval, expires, instructions, qr);
            } catch (Exception failure) { ui(request, () -> showFailure(instructions, qr, failure)); }
        });
    }

    static Bitmap loginQrCode(String url) throws com.google.zxing.WriterException {
        Map<com.google.zxing.EncodeHintType, Object> hints = new HashMap<>();
        hints.put(com.google.zxing.EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(com.google.zxing.EncodeHintType.MARGIN, 4);
        com.google.zxing.common.BitMatrix matrix = new com.google.zxing.qrcode.QRCodeWriter()
                .encode(url, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512, hints);
        int[] pixels = new int[512 * 512];
        for (int y = 0; y < 512; y++) for (int x = 0; x < 512; x++) {
            pixels[y * 512 + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
        }
        return Bitmap.createBitmap(pixels, 512, 512, Bitmap.Config.ARGB_8888);
    }

    private void showFailure(TextView instructions, ImageView qr, Exception failure) {
        qr.setVisibility(View.GONE);
        instructions.setText(friendly(failure));
        root.addView(button(getString(R.string.retry), this::show));
    }

    private void schedulePoll(int request, String code, int interval, long expires, TextView instructions, ImageView qr) {
        worker.schedule(() -> ui(request, () -> worker.execute(() -> {
            try {
                if (System.currentTimeMillis() >= expires) throw new Exception(getString(R.string.login_code_expired));
                if (api.pollDeviceLogin(code, () -> generation == request && host.isForeground())) ui(request, host::showHome);
                else schedulePoll(request, code, interval, expires, instructions, qr);
            } catch (Exception failure) {
                if ("slow_down".equals(failure.getMessage())) schedulePoll(request, code, interval + 5, expires, instructions, qr);
                else ui(request, () -> showFailure(instructions, qr, failure));
            }
        })), interval, TimeUnit.SECONDS);
    }
}
