package tv.streamline.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Toast;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Android lifecycle and navigation only; each screen owns its rendering and resources. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class MainActivity extends Activity {
    private static final String DEFAULT_CLIENT_ID = "bz7ogkanwaw3wiokunxw1zutwe7nj0";
    final Handler main = new Handler(Looper.getMainLooper());
    final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    SharedPreferences settings;
    TwitchApi api;
    ThumbnailLoader images;
    CatalogScreen catalog;
    private Screen current;
    private volatile boolean foreground;
    private boolean chatVisible = true, sessionErrorShown;

    private final Runnable sessionCheck = new Runnable() {
        @Override public void run() {
            if (!foreground || isDestroyed()) return;
            worker.execute(() -> {
                if (!api.signedIn()) return;
                try {
                    api.validateSession();
                    main.post(() -> sessionErrorShown = false);
                } catch (Exception failure) {
                    main.post(() -> {
                        if (!foreground || isDestroyed()) return;
                        if (!sessionErrorShown) {
                            Toast.makeText(MainActivity.this, Screen.friendly(failure), Toast.LENGTH_LONG).show();
                            sessionErrorShown = true;
                        }
                        if (!api.signedIn() && current instanceof PlayerScreen) ((PlayerScreen) current).sessionExpired();
                    });
                }
            });
            main.postDelayed(this, 60000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        settings = getSharedPreferences("settings", MODE_PRIVATE);
        settings.edit().remove("recommendation_device_id").remove("default_sort").apply();
        api = new TwitchApi(DEFAULT_CLIENT_ID, new SessionStore(this));
        images = new ThumbnailLoader(api.http);
        catalog = new CatalogScreen(this);
        catalog.state.restore(state);
        String channel = state == null ? "" : state.getString("channel", "");
        if (!channel.isEmpty()) current = new PlayerScreen(this, channel, state.getString("title", ""), chatVisible, catalog.stream(channel));
        else showHome();
    }

    Screen currentScreen() { return current; }
    boolean isForeground() { return foreground; }

    private void navigate(Screen next) {
        if (current instanceof PlayerScreen) chatVisible = ((PlayerScreen) current).chatVisible();
        if (current != null) current.leave();
        current = next;
        current.show();
    }

    void showHome() { navigate(catalog); }
    void showSettings() { navigate(new SettingsScreen(this)); }
    void showLogin() { navigate(new LoginScreen(this)); }

    void showPlayer(String channel, String title) {
        if (current instanceof PlayerScreen) chatVisible = ((PlayerScreen) current).chatVisible();
        navigate(new PlayerScreen(this, channel, title, chatVisible, catalog.stream(channel)));
    }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
        if (current instanceof PlayerScreen) current.show();
        main.post(sessionCheck);
    }

    @Override protected void onStop() {
        foreground = false;
        main.removeCallbacks(sessionCheck);
        if (current instanceof PlayerScreen) current.leave();
        if (current instanceof LoginScreen) showHome();
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        catalog.state.save(state);
        if (current instanceof PlayerScreen) {
            PlayerScreen player = (PlayerScreen) current;
            state.putString("channel", player.channel);
            state.putString("title", player.title);
        }
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        if (current != null) current.leave();
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        images.close();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        return current != null && current.keyEvent(event) || super.dispatchKeyEvent(event);
    }

    @Override public void onBackPressed() {
        if (current != null && current.back()) return;
        showHome();
    }
}
