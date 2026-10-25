package tv.streamline.app;

import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.concurrent.ScheduledExecutorService;

/** A screen owns its views and invalidates asynchronous results when it leaves. */
abstract class Screen extends ContextWrapper {
    static final int BG = Color.rgb(16, 16, 24);
    static final int TEXT = Color.rgb(241, 238, 250);
    static final int MUTED = Color.rgb(177, 173, 193);
    final MainActivity host;
    final TwitchApi api;
    final Handler main;
    final ScheduledExecutorService worker;
    LinearLayout root;
    volatile int generation;

    Screen(MainActivity host) {
        super(host);
        this.host = host;
        api = host.api;
        main = host.main;
        worker = host.worker;
    }

    abstract void show();

    void leave() {
        generation++;
        if (root != null) root.animate().cancel();
        root = null;
    }
    boolean keyEvent(KeyEvent event) { return false; }
    boolean back() { return false; }

    final void base(boolean padded) {
        generation++;
        if (root != null) root.animate().cancel();
        boolean animate = host.isForeground() && android.animation.ValueAnimator.areAnimatorsEnabled();
        root = column();
        root.setBackgroundColor(BG);
        if (padded) root.setPadding(dp(38), dp(22), dp(38), dp(20));
        if (animate) {
            root.setAlpha(0f);
            root.setTranslationY(dp(6));
        }
        host.setContentView(root);
        if (animate) root.animate().alpha(1f).translationY(0f).setDuration(160)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    final void ui(int request, Runnable action) {
        main.post(() -> {
            if (!host.isDestroyed() && host.currentScreen() == this && generation == request) action.run();
        });
    }

    final void loadThumbnail(ImageView view, String url) { loadThumbnail(view, url, 2); }

    final void loadThumbnail(ImageView view, String url, int sampleSize) {
        int request = generation;
        host.images.load(url, sampleSize, bitmap -> ui(request, () -> view.setImageBitmap(bitmap)));
    }

    final int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    final LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    final LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    final TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(dp(4), dp(6), dp(4), dp(6));
        return view;
    }

    final TextView heading(String title) {
        TextView view = text(title, 28, TEXT);
        view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    final GradientDrawable surface(int fill, int stroke) { return surface(fill, stroke, 8); }

    final GradientDrawable surface(int fill, int stroke, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(2), stroke);
        return drawable;
    }

    final StateListDrawable buttonBackground() {
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, surface(0xff594083, 0xffd4bfff));
        background.addState(new int[]{android.R.attr.state_pressed}, surface(0xff594083, 0xffd4bfff));
        background.addState(new int[]{}, surface(0xff252330, 0xff353140));
        return background;
    }

    final Button button(String title, Runnable action) {
        Button view = new Button(this);
        view.setText(title);
        view.setTextSize(16);
        view.setTextColor(TEXT);
        view.setAllCaps(false);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(16), dp(8), dp(16), dp(8));
        view.setBackground(buttonBackground());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(dp(3), dp(4), dp(3), dp(4));
        view.setLayoutParams(params);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    final ImageButton iconButton(int icon, String label, Runnable action) {
        ImageButton view = new ImageButton(this);
        controlIcon(view, icon, label);
        view.setFocusable(true);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
        view.setBackground(buttonBackground());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(48));
        params.setMargins(dp(5), dp(4), dp(5), dp(4));
        view.setLayoutParams(params);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    final void controlIcon(ImageButton button, int icon, String label) {
        button.setImageResource(icon);
        button.setContentDescription(label);
        button.setTooltipText(label);
    }

    final void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    static String friendly(Exception error) {
        if (error instanceof java.net.UnknownHostException) return "Connexion Internet indisponible.";
        if (error instanceof java.net.SocketTimeoutException) return "Twitch met trop de temps à répondre. Réessaie.";
        return error.getMessage() == null ? "Une erreur réseau est survenue. Réessaie." : error.getMessage();
    }
}
