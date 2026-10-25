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
    UpdateCatalog updates;
    private okhttp3.Call updateCheck;
    private Screen current;
    private volatile boolean foreground;
    private boolean sessionErrorShown;

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
                            Toast.makeText(MainActivity.this, Screen.friendly(MainActivity.this, failure), Toast.LENGTH_LONG).show();
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
        if (!channel.isEmpty()) current = new PlayerScreen(this, channel, state.getString("title", ""), catalog.stream(channel));
        else showHome();
        checkUpdates();
    }

    private void checkUpdates() {
        // One silent check per activity launch, not on resume or when returning to the home screen.
        updateCheck = api.http.newBuilder().followRedirects(false).followSslRedirects(false).build()
                .newCall(new okhttp3.Request.Builder().url(UpdateCatalog.URL).build());
        updateCheck.enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, java.io.IOException failure) { /* Manual retry remains available in settings. */ }
            @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) {
                try (okhttp3.Response closeable = response) {
                    UpdateCatalog result = UpdateCatalog.read(closeable);
                    main.post(() -> { if (!isDestroyed()) setUpdates(result); });
                } catch (Exception ignored) { /* An update check must never interrupt browsing. */ }
            }
        });
    }

    void setUpdates(UpdateCatalog result) {
        updates = result;
        catalog.updateAvailable();
    }

    boolean hasUpdate() {
        if (updates == null) return false;
        try {
            return UpdateCatalog.compare(updates.stable.version,
                    getPackageManager().getPackageInfo(getPackageName(), 0).versionName) > 0;
        } catch (Exception ignored) { return false; }
    }

    Screen currentScreen() { return current; }
    boolean isForeground() { return foreground; }

    private void navigate(Screen next) {
        if (current != null) current.leave();
        current = next;
        current.show();
    }

    void showHome() { navigate(catalog); }
    void showSettings() { navigate(new SettingsScreen(this)); }
    void showUpdates() { showUpdates(false); }
    void showUpdates(boolean checkNow) { navigate(new UpdateScreen(this, checkNow)); }
    void showLogin() { navigate(new LoginScreen(this)); }

    void showPlayer(String channel, String title) {
        navigate(new PlayerScreen(this, channel, title, catalog.stream(channel)));
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
        if (updateCheck != null) updateCheck.cancel();
        if (current != null) current.leave();
        main.removeCallbacksAndMessages(null);
        // Public-client refresh tokens are single-use: let an in-flight exchange persist its replacement.
        worker.shutdown();
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
