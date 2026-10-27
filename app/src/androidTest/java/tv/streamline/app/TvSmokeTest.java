package tv.streamline.app;

import static org.junit.Assert.*;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.widget.Button;
import androidx.lifecycle.Lifecycle;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@androidx.media3.common.util.UnstableApi
@RunWith(AndroidJUnit4.class)
public class TvSmokeTest {
    @Test public void loginQrCodeDecodesToOriginalUrl() throws Exception {
        String url = "https://www.twitch.tv/activate?device-code=TEST123&source=tv";
        android.graphics.Bitmap bitmap = LoginScreen.loginQrCode(url);
        try {
            int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
            bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
            com.google.zxing.RGBLuminanceSource source = new com.google.zxing.RGBLuminanceSource(bitmap.getWidth(), bitmap.getHeight(), pixels);
            com.google.zxing.Result result = new com.google.zxing.qrcode.QRCodeReader().decode(
                    new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(source)));
            assertEquals(url, result.getText());
        } finally { bitmap.recycle(); }
    }

    @Test public void clientIdIsAlreadyConfiguredAtStartup() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                String saved = activity.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                        .getString("client_id", "").trim();
                String expected = saved.isEmpty() ? "d59kavqvrz92fz7jpll8a94qnsasr2" : saved;
                assertEquals(expected, ((TwitchApi) field(activity, "api")).clientId);
                assertEquals("home", field(activity, "screen"));
            });
        }
    }

    private static Object field(MainActivity activity, String name) {
        if (name.equals("api")) return activity.api;
        if (name.equals("screen")) return activity.currentScreen() instanceof PlayerScreen ? "player"
                : activity.currentScreen() instanceof SettingsScreen ? "settings"
                : activity.currentScreen() instanceof LoginScreen ? "login" : "home";
        if (name.equals("player")) return ((PlayerScreen) activity.currentScreen()).playback.player();
        if (name.equals("shelfItems")) return activity.catalog.state.shelfItems;
        if (name.equals("selectedCard")) return activity.catalog.state.selectedCard;
        Object owner = name.equals("list") ? activity.catalog : activity.currentScreen();
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static void invoke(MainActivity activity, String name, Class<?>[] types, Object... args) {
        if (name.equals("showSettings")) { activity.showSettings(); return; }
        if (name.equals("showHome")) { activity.showHome(); return; }
        if (name.equals("showPlayer")) { activity.showPlayer((String) args[0], (String) args[1]); return; }
        if (name.equals("showQuality")) { ((PlayerScreen) activity.currentScreen()).showQuality(); return; }
        try {
            Method method = CatalogScreen.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(activity.catalog, args);
        } catch (Exception error) { throw new AssertionError(error); }
    }

    @Test public void settingsAreNavigableWithDpad() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                invoke(activity, "showSettings", new Class<?>[0]);
                assertTrue(activity.getCurrentFocus() instanceof Button);
                String[] labels = {"Toujours la meilleure", "Automatique", "1080p", "720p", "480p", "360p", "160p"};
                assertEquals(labels[SettingsScreen.preferredQualityIndex(activity.settings)],
                        ((Button) activity.getCurrentFocus()).getText().toString());
            });
            // setContentView requests a layout; don't inject keys before the new focus tree is measured.
            AtomicBoolean laidOut = new AtomicBoolean();
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (!laidOut.get() && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity(activity -> laidOut.set(activity.hasWindowFocus()
                        && activity.getCurrentFocus() != null && activity.getCurrentFocus().getWidth() > 0
                        && !activity.getCurrentFocus().isLayoutRequested()));
                SystemClock.sleep(50);
            }
            assertTrue("Settings window must be focused and laid out", laidOut.get());
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> assertEquals(activity.settings.getBoolean("auto_load_lists", false) ? "Activé" : "Désactivé",
                    ((Button) activity.getCurrentFocus()).getText().toString()));
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> assertEquals("home", field(activity, "screen")));
        }
    }

    @Test public void optionalLivePlaybackAndBackgroundRelease() {
        String channel = InstrumentationRegistry.getArguments().getString("liveChannel", "");
        Assume.assumeTrue("Pass -Pandroid.testInstrumentationRunnerArguments.liveChannel=<live login> to run this network test", !channel.isEmpty());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> invoke(activity, "showPlayer", new Class<?>[]{String.class, String.class}, channel, "Test HLS · " + channel));
            awaitPlayback(scenario);
            scenario.onActivity(activity -> {
                ExoPlayer player = (ExoPlayer) field(activity, "player");
                assertTrue(player.getCurrentTracks().getGroups().size() > 0);
                player.pause();
                assertFalse(player.getPlayWhenReady());
                player.play();
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
                assertTrue("Opening controls must not pause the stream", player.getPlayWhenReady());
                invoke(activity, "showQuality", new Class<?>[0]);
            });
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertEquals("player", field(activity, "screen"));
                assertEquals(android.view.View.VISIBLE, ((android.view.View) field(activity, "playerControls")).getVisibility());
            });
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.onActivity(activity -> assertNull(field(activity, "player")));
            scenario.moveToState(Lifecycle.State.RESUMED);
            awaitPlayback(scenario);
        }
    }

    @Test public void playerControlsRequireCenterAndBackHasTwoSecondGuard() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                invoke(activity, "showPlayer", new Class<?>[]{String.class, String.class}, "streamline_ui_test", "Test contrôles");
                assertEquals(android.view.View.GONE, ((android.view.View) field(activity, "playerControls")).getVisibility());
                assertFalse(((androidx.media3.ui.PlayerView) field(activity, "playerView")).getUseController());
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
                assertEquals(android.view.View.GONE, ((android.view.View) field(activity, "playerControls")).getVisibility());
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
                assertEquals(android.view.View.VISIBLE, ((android.view.View) field(activity, "playerControls")).getVisibility());
                assertEquals(field(activity, "playButton"), activity.getCurrentFocus());
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            saveScreenshot("ui-player.png");
            scenario.onActivity(activity -> {
                activity.onBackPressed();
                assertEquals(android.view.View.GONE, ((android.view.View) field(activity, "playerControls")).getVisibility());
                activity.onBackPressed();
                assertEquals("player", field(activity, "screen"));
            });
            SystemClock.sleep(2100);
            scenario.onActivity(activity -> {
                activity.onBackPressed();
                assertEquals("home", field(activity, "screen"));
            });
        }
    }

    @Test @SuppressWarnings("unchecked") public void horizontalCardsRestoreFocusAndMarqueeSelection() throws Exception {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            org.json.JSONObject item = new org.json.JSONObject()
                    .put("user_login", "test").put("user_name", "Test")
                    .put("title", "Un titre très long pour vérifier le défilement de la carte sélectionnée sur Android TV")
                    .put("game_name", "Catégorie").put("viewer_count", 1234);
            java.util.List<org.json.JSONObject> items = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                items.add(new org.json.JSONObject(item.toString()).put("user_login", "test" + i));
            }
            scenario.onActivity(activity -> {
                java.util.Map<String, java.util.List<org.json.JSONObject>> cached =
                        (java.util.Map<String, java.util.List<org.json.JSONObject>>) field(activity, "shelfItems");
                cached.put("fixture", items);
                cached.put("fixture2", items);
                ((android.widget.LinearLayout) field(activity, "list")).removeAllViews();
                invoke(activity, "addShelf", new Class<?>[]{String.class, String.class}, "fixture", "Test cartes");
                android.widget.LinearLayout list = (android.widget.LinearLayout) field(activity, "list");
                android.widget.HorizontalScrollView shelf = (android.widget.HorizontalScrollView) list.getChildAt(list.getChildCount() - 1);
                android.widget.LinearLayout card = (android.widget.LinearLayout) ((android.view.ViewGroup) shelf.getChildAt(0)).getChildAt(0);
                card.requestFocus();
                assertTrue(((android.widget.TextView) card.getChildAt(2)).isSelected());
                assertEquals("fixture:test0", field(activity, "selectedCard"));
                ((android.view.ViewGroup) shelf.getChildAt(0)).getChildAt(5).requestFocus();
                invoke(activity, "showHome", new Class<?>[0]);
                ((android.widget.LinearLayout) field(activity, "list")).removeAllViews();
                invoke(activity, "addShelf", new Class<?>[]{String.class, String.class}, "fixture", "Test cartes");
                invoke(activity, "addShelf", new Class<?>[]{String.class, String.class}, "fixture2", "Explorer les lives");
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                android.view.View focus = activity.getCurrentFocus();
                assertTrue(focus instanceof android.widget.LinearLayout);
                assertTrue(((android.widget.TextView) ((android.view.ViewGroup) focus).getChildAt(2)).isSelected());
                assertEquals("fixture:test5", field(activity, "selectedCard"));
                android.graphics.Rect visible = new android.graphics.Rect();
                assertTrue("Restored card must be visible: " + focus.getWidth() + "px", focus.getGlobalVisibleRect(visible));
            });
            saveScreenshot("ui-catalog.png");
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> assertEquals("fixture:test6", field(activity, "selectedCard")));
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            // Moving between shelves visits the next section's refresh action before its cards.
            scenario.onActivity(activity -> assertEquals("Actualiser · Explorer les lives",
                    activity.getCurrentFocus().getContentDescription().toString()));
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> assertTrue(((String) field(activity, "selectedCard")).startsWith("fixture2:")));
            saveScreenshot("ui-catalog.png");
        }
    }

    private static void saveScreenshot(String name) {
        android.graphics.Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        if (bitmap == null) return;
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(
                InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(), name))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        } catch (java.io.IOException error) { throw new AssertionError(error); }
        finally { bitmap.recycle(); }
    }

    @Test public void optionalSignedInCatalogLoadsPreviews() {
        AtomicBoolean signedIn = new AtomicBoolean();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> signedIn.set(((TwitchApi) field(activity, "api")).signedIn()));
            Assume.assumeTrue("Requires an account authorized by the user", signedIn.get());
            AtomicBoolean previewReady = new AtomicBoolean();
            long deadline = SystemClock.elapsedRealtime() + 40000;
            while (!previewReady.get() && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity(activity -> {
                    android.widget.LinearLayout list = (android.widget.LinearLayout) field(activity, "list");
                    for (int section = 0; section < list.getChildCount(); section++) {
                        android.view.View view = list.getChildAt(section);
                        if (!(view instanceof android.widget.HorizontalScrollView)) continue;
                        android.view.ViewGroup cards = (android.view.ViewGroup) ((android.widget.HorizontalScrollView) view).getChildAt(0);
                        for (int i = 0; i < cards.getChildCount(); i++) {
                            android.view.View card = cards.getChildAt(i);
                            if (!(card instanceof android.widget.LinearLayout)) continue;
                            android.view.View first = ((android.view.ViewGroup) card).getChildAt(0);
                            if (first instanceof android.widget.ImageView && ((android.widget.ImageView) first).getDrawable() != null) previewReady.set(true);
                        }
                    }
                });
                SystemClock.sleep(150);
            }
            assertTrue("A real stream preview should load", previewReady.get());
        }
    }

    private static void awaitPlayback(ActivityScenario<MainActivity> scenario) {
        AtomicBoolean ready = new AtomicBoolean();
        AtomicReference<String> failure = new AtomicReference<>();
        long deadline = SystemClock.elapsedRealtime() + 60000;
        while (!ready.get() && failure.get() == null && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity(activity -> {
                ExoPlayer player = (ExoPlayer) field(activity, "player");
                if (player != null) {
                    ready.set(player.getPlaybackState() == Player.STATE_READY);
                    if (player.getPlayerError() != null) failure.set(player.getPlayerError().getErrorCodeName());
                }
            });
            SystemClock.sleep(250);
        }
        assertNull("Media3 playback error", failure.get());
        assertTrue("HLS should reach STATE_READY within 60 seconds; the channel must be live", ready.get());
    }
}
