package tv.streamline.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.ui.PlayerView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

@androidx.media3.common.util.UnstableApi
public final class MainActivity extends Activity {
    private static final String DEFAULT_CLIENT_ID = "bz7ogkanwaw3wiokunxw1zutwe7nj0";
    private static final String[] QUALITY_VALUES = {"best", "auto", "1080", "720", "480", "360", "160"};
    private static final String[] QUALITY_LABELS = {"Toujours la meilleure", "Automatique", "1080p", "720p", "480p", "360p", "160p"};
    private static final int BG = Color.rgb(16, 16, 24), TEXT = Color.rgb(241, 238, 250), MUTED = Color.rgb(177, 173, 193);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private SharedPreferences settings;
    private TwitchApi api;
    private volatile int generation;
    private String screen = "", tab = "followed", query = "", currentChannel = "", currentTitle = "";
    private String gameId = "", gameName = "";
    private boolean gameFromSearch;
    private volatile boolean foreground;
    private LinearLayout root, list, chatPanel;
    private TextView chatStatus, chatText, playbackStatus;
    private ScrollView chatScroll;
    private final List<IrcMessage> messages = new ArrayList<>();
    private boolean chatRenderQueued;
    private ExoPlayer player;
    private PlayerView playerView;
    private ChatClient chat;
    private boolean chatVisible = true;
    private final Map<String, List<JSONObject>> shelfItems = new HashMap<>();
    private final Map<String, String> shelfCursors = new HashMap<>();
    private final Map<String, Integer> shelfPositions = new HashMap<>();
    private final Map<String, String> shelfSelections = new HashMap<>();
    private final Map<String, ImageButton> shelfRefreshButtons = new HashMap<>();
    private final java.util.Set<String> loadingShelves = new java.util.HashSet<>();
    private JSONObject profile;
    private int catalogScrollY;
    private final java.util.concurrent.ExecutorService images = Executors.newFixedThreadPool(3);
    private final android.util.LruCache<String, Bitmap> thumbnails = new android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private String selectedCard = "";
    private boolean focusFirstSearchResult;
    private boolean focusFirstHomeList;
    private LinearLayout playerControls;
    private LinearLayout playerHeading;
    private TextView playerName, playerStreamTitle, playerCategory;
    private ImageView channelAvatar;
    private Button viewerButton;
    private ImageButton playButton, muteButton, chatButton;
    private long controlsHiddenAt;
    private boolean consumeCenterUp;
    private boolean sessionErrorShown;
    private final Runnable sessionCheck = new Runnable() {
        @Override public void run() {
            if (!foreground || isDestroyed()) return;
            TwitchApi activeApi = api;
            worker.execute(() -> {
                if (!activeApi.signedIn()) return;
                try {
                    activeApi.validateSession();
                    main.post(() -> sessionErrorShown = false);
                } catch (Exception failure) {
                    main.post(() -> {
                        if (!foreground || isDestroyed() || api != activeApi) return;
                        if (!sessionErrorShown) { toast(friendly(failure)); sessionErrorShown = true; }
                        if (!activeApi.signedIn() && chat != null) {
                            chat.close();
                            chat = null;
                            chatStatus.setText("Session expirée · reconnecte ton compte");
                        }
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
        if (state != null) {
            tab = state.getString("tab", "followed");
            query = state.getString("query", "");
            currentChannel = state.getString("channel", "");
            currentTitle = state.getString("title", "");
            gameId = state.getString("game_id", "");
            gameName = state.getString("game_name", "");
            gameFromSearch = state.getBoolean("game_from_search", false);
        }
        if (!currentChannel.isEmpty()) { screen = "player"; }
        else if (api.clientId.isEmpty()) showSettings();
        else showHome();
    }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
        if (screen.equals("player")) showPlayer(currentChannel, currentTitle);
        main.post(sessionCheck);
    }

    @Override protected void onStop() {
        foreground = false;
        main.removeCallbacks(sessionCheck);
        if (screen.equals("player")) { generation++; releasePlayback(); }
        if (screen.equals("login")) { generation++; screen = "home"; showHome(); }
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("tab", tab);
        state.putString("query", query);
        state.putString("channel", screen.equals("player") ? currentChannel : "");
        state.putString("title", currentTitle);
        state.putString("game_id", gameId);
        state.putString("game_name", gameName);
        state.putBoolean("game_from_search", gameFromSearch);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        generation++;
        releasePlayback();
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        images.shutdownNow();
        thumbnails.evictAll();
        super.onDestroy();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(dp(4), dp(6), dp(4), dp(6));
        return view;
    }

    private GradientDrawable surface(int fill, int stroke) {
        return surface(fill, stroke, 8);
    }

    private GradientDrawable surface(int fill, int stroke, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(2), stroke);
        return drawable;
    }

    private Button button(String title, Runnable action) {
        Button view = new Button(this);
        view.setText(title);
        view.setTextSize(16);
        view.setTextColor(TEXT);
        view.setAllCaps(false);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(16), dp(8), dp(16), dp(8));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, surface(0xff594083, 0xffd4bfff));
        background.addState(new int[]{android.R.attr.state_pressed}, surface(0xff594083, 0xffd4bfff));
        background.addState(new int[]{}, surface(0xff252330, 0xff353140));
        view.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(3), dp(4), dp(3), dp(4));
        view.setLayoutParams(params);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private ImageButton iconButton(int icon, String label, Runnable action) {
        ImageButton view = new ImageButton(this);
        view.setImageResource(icon);
        view.setContentDescription(label);
        view.setTooltipText(label);
        view.setFocusable(true);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, surface(0xff594083, 0xffd4bfff));
        background.addState(new int[]{android.R.attr.state_pressed}, surface(0xff594083, 0xffd4bfff));
        background.addState(new int[]{}, surface(0xff252330, 0xff353140));
        view.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(48));
        params.setMargins(dp(5), dp(4), dp(5), dp(4));
        view.setLayoutParams(params);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private void controlIcon(ImageButton button, int icon, String label) {
        button.setImageResource(icon);
        button.setContentDescription(label);
        button.setTooltipText(label);
    }

    private void showAccountMenu() {
        new AlertDialog.Builder(this).setTitle(profile == null ? "Compte Twitch" : profile.optString("display_name", "Compte Twitch"))
                .setItems(new String[]{"Se déconnecter"}, (menu, index) -> {
                    new AlertDialog.Builder(this).setTitle("Déconnecter ce compte ?")
                            .setMessage("La session enregistrée sur cette box sera effacée.")
                            .setNegativeButton("Annuler", null).setPositiveButton("Déconnecter", (dialog, which) -> {
                                int request = generation;
                                worker.execute(() -> {
                                    try {
                                        api.clear();
                                        ui(request, () -> {
                                            profile = null;
                                            shelfItems.clear();
                                            shelfCursors.clear();
                                            shelfPositions.clear();
                                            shelfSelections.clear();
                                            selectedCard = "";
                                            catalogScrollY = 0;
                                            showHome();
                                        });
                                    } catch (Exception failure) { ui(request, () -> toast(friendly(failure))); }
                                });
                            }).show();
                }).setNegativeButton("Fermer", null).show();
    }

    private void loadProfile(ImageButton avatar) {
        if (profile != null) {
            avatar.setPadding(0, 0, 0, 0);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            loadThumbnail(avatar, profile.optString("profile_image_url"));
            avatar.setContentDescription("Compte Twitch · " + profile.optString("display_name"));
            return;
        }
        int request = generation;
        worker.execute(() -> {
            try {
                List<JSONObject> users = TwitchApi.items(api.helix("users"));
                if (!users.isEmpty()) ui(request, () -> { profile = users.get(0); loadProfile(avatar); });
            } catch (Exception ignored) { /* The account menu remains available with its fallback icon. */ }
        });
    }

    private void base(String name, boolean padded) {
        generation++;
        releasePlayback();
        screen = name;
        root = column();
        root.setBackgroundColor(BG);
        if (padded) root.setPadding(dp(38), dp(22), dp(38), dp(20));
        setContentView(root);
    }

    private TextView heading(String title) {
        TextView view = text(title, 28, TEXT);
        view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    private void showSettings() {
        base("settings", true);
        LinearLayout header = row();
        header.addView(iconButton(R.drawable.ri_arrow_left_line, "Retour", this::showHome));
        header.addView(heading("Streamline · Réglages"));
        root.addView(header);
        root.addView(text("Qualité préférée", 20, TEXT));
        Button quality = button(QUALITY_LABELS[preferredQualityIndex()], () -> {});
        quality.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Qualité préférée")
                .setSingleChoiceItems(QUALITY_LABELS, preferredQualityIndex(), (dialog, which) -> {
                    settings.edit().putString("preferred_quality", QUALITY_VALUES[which]).apply();
                    quality.setText(QUALITY_LABELS[which]);
                    dialog.dismiss();
                }).setNegativeButton("Annuler", null).show());
        root.addView(quality);
        root.addView(text("Chargement automatique des listes", 20, TEXT));
        Button autoLoad = button(settings.getBoolean("auto_load_lists", false) ? "Activé" : "Désactivé", () -> {});
        autoLoad.setOnClickListener(v -> {
            boolean enabled = !settings.getBoolean("auto_load_lists", false);
            settings.edit().putBoolean("auto_load_lists", enabled).apply();
            autoLoad.setText(enabled ? "Activé" : "Désactivé");
        });
        root.addView(autoLoad);
        root.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            root.addView(text("Version " + version, 14, MUTED));
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { /* Own package is always installed. */ }
        quality.requestFocus();
    }

    private int preferredQualityIndex() {
        String value = settings.getString("preferred_quality", "best");
        for (int i = 0; i < QUALITY_VALUES.length; i++) if (QUALITY_VALUES[i].equals(value)) return i;
        return 0;
    }

    private void showHome() {
        base("home", true);
        root.setPadding(0, dp(22), 0, dp(20));
        currentChannel = "";
        focusFirstHomeList = api.signedIn() && !tab.equals("search") && !tab.equals("game") && selectedCard.isEmpty();
        shelfRefreshButtons.clear();
        loadingShelves.clear();
        LinearLayout header = row();
        header.setPadding(dp(38), 0, dp(38), 0);
        View initialFocus;
        if (tab.equals("search")) {
            header.addView(searchBackButton(this::onBackPressed));
            header.addView(heading("Recherche"));
            root.addView(header);
            LinearLayout searchBar = row();
            searchBar.setPadding(dp(38), dp(8), dp(38), dp(4));
            EditText input = searchInput();
            searchBar.addView(input, new LinearLayout.LayoutParams(0, dp(56), 1));
            searchBar.addView(searchSubmitButton(input));
            root.addView(searchBar);
            initialFocus = input;
        } else {
            TextView title = heading("Streamline");
            header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            ImageButton search = iconButton(R.drawable.ri_search_line, "Rechercher", this::showSearch);
            header.addView(search);
            header.addView(iconButton(R.drawable.ri_settings_line, "Réglages", this::showSettings));
            ImageButton avatar = iconButton(R.drawable.ri_user_line, api.signedIn() ? "Compte Twitch" : "Connexion Twitch",
                    () -> { if (api.signedIn()) showAccountMenu(); else showLogin(); });
            avatar.setClipToOutline(true);
            StateListDrawable avatarBorder = new StateListDrawable();
            avatarBorder.addState(new int[]{android.R.attr.state_focused}, surface(Color.TRANSPARENT, 0xffd4bfff));
            avatarBorder.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            avatar.setForeground(avatarBorder);
            header.addView(avatar);
            if (api.signedIn()) loadProfile(avatar);
            root.addView(header);
            initialFocus = search;
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams catalogParams = new LinearLayout.LayoutParams(-1, 0, 1);
        catalogParams.topMargin = dp(12);
        scroll.setFillViewport(false);
        list = column();
        scroll.addView(list);
        root.addView(scroll, catalogParams);
        int restoreY = catalogScrollY;
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> catalogScrollY = y);
        initialFocus.requestFocus();
        if (!api.signedIn()) {
            list.setPadding(dp(38), 0, dp(38), 0);
            list.addView(text("Connecte ton compte Twitch pour afficher les chaînes, rechercher et lire le chat.", 16, MUTED));
            list.addView(button("Se connecter", this::showLogin));
        } else if (tab.equals("search")) {
            addShelf("search:live:" + query, "Lives");
            addShelf("search:categories:" + query, "Catégories");
            addShelf("search:channels:" + query, "Chaînes");
        }
        else if (tab.equals("game")) addShelf("game:" + gameId, "Directs · " + gameName);
        else {
            addShelf("followed", "Suivis en direct");
            addShelf("browse", "Chaînes live");
            addShelf("games", "Parcourir");
        }
        LinearLayout catalog = list;
        catalog.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            int count = catalog.getChildCount();
            if (count < 2 || !(catalog.getChildAt(count - 1) instanceof HorizontalScrollView)) return;
            View lastTitle = catalog.getChildAt(count - 2);
            View lastRow = catalog.getChildAt(count - 1);
            int bottomSpace = Math.max(0, scroll.getHeight() - lastTitle.getHeight() - lastRow.getHeight());
            if (catalog.getPaddingBottom() != bottomSpace) catalog.setPadding(0, 0, 0, bottomSpace);
        });
        catalog.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            View ancestor = newFocus;
            while (ancestor != null && ancestor != catalog) {
                if (ancestor instanceof HorizontalScrollView && ancestor.getParent() == catalog) {
                    HorizontalScrollView shelf = (HorizontalScrollView) ancestor;
                    ViewGroup cards = (ViewGroup) shelf.getChildAt(0);
                    View card = newFocus;
                    while (card != null && card.getParent() != cards) card = card.getParent() instanceof View ? (View) card.getParent() : null;
                    if (card != null && card.getTag() instanceof String) {
                        shelfSelections.put((String) shelf.getTag(), (String) card.getTag());
                        linkShelfFocus();
                    }
                    shelf.post(() -> { if (newFocus.hasFocus()) alignShelfFocus(shelf, newFocus); });
                    maybeLoadMore((HorizontalScrollView) ancestor, newFocus);
                    int index = catalog.indexOfChild(ancestor);
                    View sectionTitle = catalog.getChildAt(index - 1);
                    scroll.post(() -> {
                        if (newFocus.hasFocus()) scroll.smoothScrollTo(0, sectionTitle.getTop());
                    });
                    break;
                }
                ancestor = ancestor.getParent() instanceof View ? (View) ancestor.getParent() : null;
            }
        });
        scroll.post(() -> scroll.scrollTo(0, restoreY));
    }

    private void addShelf(String key, String label) {
        LinearLayout sectionHeader = row();
        sectionHeader.setPadding(dp(38), list.getChildCount() > 0 ? dp(12) : 0, dp(38), 0);
        sectionHeader.addView(heading(label), new LinearLayout.LayoutParams(0, -2, 1));
        list.addView(sectionHeader);
        HorizontalScrollView scroll = new EdgeFadeScrollView(this, BG, dp(28));
        scroll.setTag(key);
        scroll.setFocusable(false);
        int restoreX = shelfPositions.getOrDefault(key, 0);
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> shelfPositions.put(key, x));
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(43), dp(5), dp(43), dp(10));
        LinearLayout cards = row();
        cards.setGravity(Gravity.TOP);
        ImageButton refresh = iconButton(R.drawable.ri_refresh_line, "Actualiser · " + label, () -> {
            shelfItems.remove(key);
            shelfCursors.remove(key);
            shelfPositions.put(key, 0);
            if (selectedCard.startsWith(key + ":")) selectedCard = "";
            scroll.scrollTo(0, 0);
            cards.removeAllViews();
            loadShelf(key, cards, false);
        });
        shelfRefreshButtons.put(key, refresh);
        sectionHeader.addView(refresh);
        scroll.addView(cards);
        list.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        List<JSONObject> cached = shelfItems.get(key);
        if (cached == null) loadShelf(key, cards, false);
        else {
            for (JSONObject item : cached) addStreamCard(cards, item, key);
            shelfFooter(key, cards);
        }
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                scroll.scrollTo(restoreX, 0);
                if (shelfItems.containsKey(key) && focusFirstResult(key, cards)) return;
                for (int i = 0; i < cards.getChildCount(); i++) {
                    View card = cards.getChildAt(i);
                    if (selectedCard.equals(card.getTag())) {
                        card.requestFocus();
                        android.graphics.Rect bounds = new android.graphics.Rect();
                        card.getDrawingRect(bounds);
                        card.requestRectangleOnScreen(bounds, true);
                        break;
                    }
                }
            }
        });
        linkShelfFocus();
    }

    private void alignShelfFocus(HorizontalScrollView shelf, View focused) {
        ViewGroup cards = (ViewGroup) shelf.getChildAt(0);
        View card = focused;
        while (card != null && card.getParent() != cards) card = card.getParent() instanceof View ? (View) card.getParent() : null;
        if (card == null) return;
        int left = cards.getLeft() + card.getLeft();
        int right = left + card.getWidth();
        int visibleLeft = shelf.getScrollX() + shelf.getPaddingLeft();
        int visibleRight = shelf.getScrollX() + shelf.getWidth() - shelf.getPaddingRight();
        if (right > visibleRight) shelf.smoothScrollBy(right - visibleRight, 0);
        else if (left < visibleLeft) shelf.smoothScrollBy(left - visibleLeft, 0);
    }

    private boolean focusFirstResult(String key, LinearLayout cards) {
        if (focusFirstHomeList) return focusHomeList();
        if (focusFirstSearchResult && key.startsWith("search:")) {
            boolean focused = focusHomeList();
            if (focused) focusFirstSearchResult = false;
            return focused;
        }
        if (!focusFirstSearchResult || !(key.startsWith("search:") || key.startsWith("game:"))) return false;
        focusFirstSearchResult = false;
        for (int i = 0; i < cards.getChildCount(); i++) {
            View card = cards.getChildAt(i);
            if (card.getTag() instanceof String && card.isFocusable()) return card.requestFocus();
        }
        return false;
    }

    private boolean focusHomeList() {
        for (int section = 0; section < list.getChildCount(); section++) {
            View child = list.getChildAt(section);
            if (!(child instanceof HorizontalScrollView)) continue;
            ViewGroup cards = (ViewGroup) ((HorizontalScrollView) child).getChildAt(0);
            for (int i = 0; i < cards.getChildCount(); i++) {
                View card = cards.getChildAt(i);
                if (card.isFocusable()) {
                    focusFirstHomeList = false;
                    return card.requestFocus();
                }
            }
            if (!shelfItems.containsKey((String) child.getTag())) return false;
        }
        return false;
    }

    private void linkShelfFocus() {
        List<View> previous = null;
        View previousRefresh = null;
        View previousEntry = null;
        for (int section = 0; section < list.getChildCount(); section++) {
            View child = list.getChildAt(section);
            if (!(child instanceof HorizontalScrollView)) continue;
            ViewGroup cards = (ViewGroup) ((HorizontalScrollView) child).getChildAt(0);
            ViewGroup header = (ViewGroup) list.getChildAt(section - 1);
            View refresh = header.getChildAt(header.getChildCount() - 1);
            if (refresh.getId() == View.NO_ID) refresh.setId(View.generateViewId());
            List<View> current = new ArrayList<>();
            for (int i = 0; i < cards.getChildCount(); i++) {
                View card = cards.getChildAt(i);
                if (!card.isFocusable()) continue;
                if (card.getId() == View.NO_ID) card.setId(View.generateViewId());
                current.add(card);
            }
            for (int i = 0; i < current.size(); i++) {
                current.get(i).setNextFocusRightId(current.get(Math.min(i + 1, current.size() - 1)).getId());
                current.get(i).setNextFocusLeftId(current.get(Math.max(0, i - 1)).getId());
            }
            for (View card : current) card.setNextFocusUpId(refresh.getId());
            View entry = current.isEmpty() ? null : current.get(0);
            String remembered = shelfSelections.get((String) child.getTag());
            for (View card : current) if (remembered != null && remembered.equals(card.getTag())) { entry = card; break; }
            refresh.setNextFocusDownId(entry == null ? View.NO_ID : entry.getId());
            refresh.setNextFocusLeftId(entry == null ? View.NO_ID : entry.getId());
            if (previousRefresh != null) {
                refresh.setNextFocusUpId(previousEntry == null ? previousRefresh.getId() : previousEntry.getId());
                for (View card : previous) card.setNextFocusDownId(refresh.getId());
            }
            previous = current;
            previousRefresh = refresh;
            previousEntry = entry;
        }
    }

    private View loadingCard(LinearLayout cards, boolean game, boolean channel) {
        LinearLayout card = column();
        card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        card.setBackground(surface(0xff252330, 0xff353140));
        if (channel) card.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(game || channel ? 160 : 280), -2);
        params.setMargins(dp(4), dp(4), dp(10), dp(4));
        cards.addView(card, params);
        View preview = new View(this);
        preview.setBackground(surface(0xff353140, 0xff353140, 6));
        if (channel) {
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(0xff353140);
            preview.setBackground(circle);
        }
        preview.setClipToOutline(true);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(channel ? dp(128) : -1, dp(channel ? 128 : game ? 197 : 151));
        previewParams.bottomMargin = dp(8);
        card.addView(preview, previewParams);
        for (int width : game || channel ? new int[]{120} : new int[]{120, 245, 175}) {
            View line = new View(this);
            line.setBackground(surface(0xff353140, 0xff353140, 6));
            LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(dp(width), dp(15));
            lineParams.setMargins(dp(4), dp(7), dp(4), dp(3));
            card.addView(line, lineParams);
        }
        return card;
    }

    private void loadShelf(String key, LinearLayout cards, boolean more) {
        loadShelf(key, cards, more, false);
    }

    private void maybeLoadMore(HorizontalScrollView shelf, View focused) {
        if (!settings.getBoolean("auto_load_lists", false)) return;
        String key = (String) shelf.getTag();
        if (loadingShelves.contains(key) || shelfCursors.getOrDefault(key, "").isEmpty()) return;
        LinearLayout cards = (LinearLayout) shelf.getChildAt(0);
        View item = focused;
        while (item != null && item.getParent() != cards) item = item.getParent() instanceof View ? (View) item.getParent() : null;
        if (item == null || !(item.getTag() instanceof String)) return;
        int remaining = 0;
        for (int i = cards.indexOfChild(item) + 1; i < cards.getChildCount(); i++) {
            if (cards.getChildAt(i).getTag() instanceof String) remaining++;
        }
        if (remaining > 2) return;
        View last = cards.getChildAt(cards.getChildCount() - 1);
        if (last instanceof Button) cards.removeView(last);
        loadShelf(key, cards, true, true);
    }

    private void loadShelf(String key, LinearLayout cards, boolean more, boolean automatic) {
        if (!loadingShelves.add(key)) return;
        int request = generation;
        ImageButton refresh = shelfRefreshButtons.get(key);
        if (refresh != null) { refresh.setClickable(false); refresh.setAlpha(.4f); }
        List<View> loading = new ArrayList<>();
        boolean categories = key.equals("games") || key.startsWith("search:categories:");
        boolean channels = key.startsWith("search:channels:");
        for (int i = 0; i < (categories || channels ? 5 : 3); i++) loading.add(loadingCard(cards, categories, channels));
        linkShelfFocus();
        String after = more ? shelfCursors.getOrDefault(key, "") : "";
        worker.execute(() -> {
            try {
                JSONObject result = key.equals("followed") ? api.followed(after)
                        : key.equals("games") ? api.helix("games/top", "first", "30", "after", after)
                        : key.startsWith("game:") ? api.helix("streams", "game_id", key.substring(5), "first", "30", "after", after)
                        : key.startsWith("search:categories:") ? api.helix("search/categories", "query", key.substring("search:categories:".length()), "first", "30", "after", after)
                        : key.startsWith("search:") ? api.helix("search/channels", "query", key.substring(key.indexOf(':', 7) + 1),
                                "live_only", key.startsWith("search:live:") ? "true" : "false", "first", "30", "after", after)
                        : api.helix("streams", "first", "30", "after", after);
                List<JSONObject> items = TwitchApi.items(result);
                if (key.startsWith("search:categories:") && !items.isEmpty()) {
                    List<String> ids = new ArrayList<>();
                    for (JSONObject item : items) { ids.add("id"); ids.add(item.getString("id")); }
                    List<JSONObject> games = TwitchApi.items(api.helix("games", ids.toArray(new String[0])));
                    for (JSONObject item : items) for (JSONObject game : games) {
                        if (item.optString("id").equals(game.optString("id"))) item.put("box_art_url", game.optString("box_art_url"));
                    }
                }
                if (key.startsWith("search:live:")) {
                    List<String> logins = new ArrayList<>();
                    for (JSONObject item : items) if (item.optBoolean("is_live")) {
                        logins.add("user_login");
                        logins.add(item.optString("broadcaster_login"));
                    }
                    if (!logins.isEmpty()) {
                        List<JSONObject> liveStreams = TwitchApi.items(api.helix("streams", logins.toArray(new String[0])));
                        for (JSONObject item : items) for (JSONObject live : liveStreams) {
                            if (!item.optString("broadcaster_login").equalsIgnoreCase(live.optString("user_login"))) continue;
                            for (String field : new String[]{"thumbnail_url", "viewer_count", "title", "game_name"}) {
                                if (live.has(field)) item.put(field, live.get(field));
                            }
                        }
                    }
                }
                JSONObject pagination = result.optJSONObject("pagination");
                String next = pagination == null ? "" : pagination.optString("cursor");
                ui(request, () -> {
                    loadingShelves.remove(key);
                    if (refresh != null) { refresh.setClickable(true); refresh.setAlpha(1f); }
                    for (View placeholder : loading) cards.removeView(placeholder);
                    List<JSONObject> all = more ? new ArrayList<>(shelfItems.getOrDefault(key, new ArrayList<>())) : new ArrayList<>();
                    all.addAll(items);
                    shelfItems.put(key, all);
                    shelfCursors.put(key, next);
                    int firstNew = cards.getChildCount();
                    for (JSONObject item : items) addStreamCard(cards, item, key);
                    shelfFooter(key, cards);
                    linkShelfFocus();
                    if (more && !automatic && cards.getChildCount() > firstNew) cards.getChildAt(firstNew).requestFocus();
                    if (!more && focusFirstSearchResult && (key.startsWith("search:") || key.startsWith("game:"))) {
                        cards.post(() -> { if (generation == request) focusFirstResult(key, cards); });
                    }
                    if (focusFirstHomeList) cards.post(() -> { if (generation == request && focusFirstHomeList) focusHomeList(); });
                });
            } catch (Exception failure) {
                ui(request, () -> {
                    loadingShelves.remove(key);
                    if (refresh != null) { refresh.setClickable(true); refresh.setAlpha(1f); }
                    for (View placeholder : loading) cards.removeView(placeholder);
                    TextView error = text(friendly(failure), 16, MUTED);
                    error.setMaxWidth(dp(300));
                    cards.addView(error);
                    cards.addView(button("Réessayer", () -> { cards.removeAllViews(); loadShelf(key, cards, false); }));
                    linkShelfFocus();
                    if (focusFirstHomeList) cards.post(() -> { if (generation == request && focusFirstHomeList) focusHomeList(); });
                });
            }
        });
    }

    private void shelfFooter(String key, LinearLayout cards) {
        if (shelfItems.getOrDefault(key, new ArrayList<>()).isEmpty()) cards.addView(text("Aucun résultat", 18, MUTED));
        if (!shelfCursors.getOrDefault(key, "").isEmpty()) {
            Button more = button("Afficher la suite", () -> {});
            more.setOnClickListener(v -> { cards.removeView(more); loadShelf(key, cards, true); });
            cards.addView(more);
        }
    }

    private void addStreamCard(LinearLayout cards, JSONObject item, String key) {
        if (key.equals("games") || key.startsWith("search:categories:")) { addGameCard(cards, item, key); return; }
        if (key.startsWith("search:channels:")) { addChannelCard(cards, item, key); return; }
        boolean search = key.startsWith("search:");
        String login = item.optString(search ? "broadcaster_login" : "user_login");
        if (login.isEmpty()) return;
        String name = item.optString(search ? "display_name" : "user_name", login);
        boolean live = !search || item.optBoolean("is_live");
        String title = item.optString("title");
        String identity = key + ":" + login;
        LinearLayout card = column();
        card.setTag(identity);
        card.setFocusable(true);
        card.setClickable(true);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, surface(0xff42315d, 0xffd4bfff));
        background.addState(new int[]{}, surface(0xff252330, 0xff353140));
        card.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(280), -2);
        params.setMargins(dp(4), dp(4), dp(10), dp(4));
        cards.addView(card, params);
        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setBackground(surface(0xff17151e, 0xff17151e, 6));
        preview.setClipToOutline(true);
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, dp(151));
        previewParams.bottomMargin = dp(8);
        card.addView(preview, previewParams);
        loadThumbnail(preview, item.optString("thumbnail_url").replace("{width}", "560").replace("{height}", "315"));
        TextView channel = text((live ? "● " : "Hors ligne · ") + name, 15, live ? 0xffd4bfff : MUTED);
        channel.setPadding(dp(4), dp(2), dp(4), dp(2));
        channel.setIncludeFontPadding(false);
        channel.setSingleLine(true);
        channel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(channel);
        TextView titleView = text(title, 17, TEXT);
        titleView.setPadding(dp(4), dp(2), dp(4), dp(2));
        titleView.setIncludeFontPadding(false);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        titleView.setMarqueeRepeatLimit(-1);
        titleView.setHorizontallyScrolling(true);
        card.addView(titleView);
        LinearLayout details = row();
        LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(-1, -2);
        detailsParams.topMargin = Math.max(0, dp(4) - 3);
        details.setLayoutParams(detailsParams);
        TextView category = text(item.optString("game_name"), 14, MUTED);
        category.setPadding(dp(4), dp(2), dp(4), dp(2));
        category.setIncludeFontPadding(false);
        category.setSingleLine(true);
        category.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.addView(category, new LinearLayout.LayoutParams(0, -2, 1));
        if (item.has("viewer_count")) {
            ImageView viewerIcon = new ImageView(this);
            viewerIcon.setImageResource(R.drawable.ri_viewer_line);
            viewerIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            details.addView(viewerIcon, new LinearLayout.LayoutParams(dp(16), dp(16)));
            TextView viewers = text(java.text.NumberFormat.getIntegerInstance(java.util.Locale.FRANCE).format(item.optInt("viewer_count")), 14, MUTED);
            viewers.setPadding(dp(4), dp(2), dp(4), dp(2));
            viewers.setIncludeFontPadding(false);
            viewers.setContentDescription(item.optInt("viewer_count") + " spectateurs");
            details.addView(viewers);
        }
        card.addView(details);
        card.setContentDescription(name + ", " + title + ", " + item.optString("game_name"));
        card.setOnFocusChangeListener((v, focused) -> {
            titleView.setSelected(focused);
            if (focused) selectedCard = identity;
        });
        card.setOnClickListener(v -> {
            selectedCard = identity;
            if (live) showPlayer(login, name + " · " + title);
            else toast("Cette chaîne est actuellement hors ligne.");
        });
    }

    private void addChannelCard(LinearLayout cards, JSONObject item, String key) {
        String login = item.optString("broadcaster_login");
        if (login.isEmpty()) return;
        String name = item.optString("display_name", login);
        LinearLayout card = column();
        card.setTag(key + ":" + login);
        card.setFocusable(true);
        card.setClickable(true);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, surface(0xff42315d, 0xffd4bfff));
        background.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        card.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(160), -2);
        params.setMargins(dp(4), dp(4), dp(10), dp(4));
        cards.addView(card, params);
        ImageView avatar = new ImageView(this);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xff252330);
        avatar.setBackground(circle);
        avatar.setClipToOutline(true);
        avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(128), dp(128));
        avatarParams.bottomMargin = dp(8);
        card.addView(avatar, avatarParams);
        loadThumbnail(avatar, item.optString("thumbnail_url"), 1);
        TextView label = text(name, 16, TEXT);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        label.setMarqueeRepeatLimit(-1);
        label.setHorizontallyScrolling(true);
        card.addView(label, new LinearLayout.LayoutParams(-1, -2));
        card.setContentDescription(name);
        card.setOnFocusChangeListener((v, focused) -> {
            label.setSelected(focused);
            if (focused) selectedCard = key + ":" + login;
        });
        card.setOnClickListener(v -> {
            selectedCard = key + ":" + login;
            if (item.optBoolean("is_live")) showPlayer(login, name + " · " + item.optString("title"));
            else toast("Cette chaîne est actuellement hors ligne.");
        });
    }

    private void addGameCard(LinearLayout cards, JSONObject item, String key) {
        String id = item.optString("id"), name = item.optString("name");
        if (id.isEmpty()) return;
        LinearLayout card = column();
        card.setTag(key + ":" + id);
        card.setFocusable(true);
        card.setClickable(true);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, surface(0xff42315d, 0xffd4bfff));
        background.addState(new int[]{}, surface(0xff252330, 0xff353140));
        card.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(160), -2);
        params.setMargins(dp(4), dp(4), dp(10), dp(4));
        cards.addView(card, params);
        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(surface(0xff17151e, 0xff17151e, 6));
        cover.setClipToOutline(true);
        cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(-1, dp(197));
        coverParams.bottomMargin = dp(8);
        card.addView(cover, coverParams);
        loadThumbnail(cover, item.optString("box_art_url").replace("{width}", "300").replace("{height}", "400"), 1);
        TextView title = text(name, 16, TEXT);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        title.setHorizontallyScrolling(true);
        card.addView(title);
        card.setContentDescription(name);
        card.setOnFocusChangeListener((v, focused) -> {
            title.setSelected(focused);
            if (focused) selectedCard = key + ":" + id;
        });
        card.setOnClickListener(v -> {
            gameId = id;
            gameName = name;
            gameFromSearch = key.startsWith("search:");
            tab = "game";
            selectedCard = "";
            catalogScrollY = 0;
            shelfPositions.put("game:" + id, 0);
            focusFirstSearchResult = true;
            showHome();
        });
    }

    private void loadThumbnail(ImageView view, String url) {
        loadThumbnail(view, url, 2);
    }

    private void loadThumbnail(ImageView view, String url, int sampleSize) {
        if (!url.startsWith("https://")) return;
        String cacheKey = url + "#" + sampleSize;
        Bitmap cached = thumbnails.get(cacheKey);
        if (cached != null) { view.setImageBitmap(cached); return; }
        int request = generation;
        images.execute(() -> {
            try (okhttp3.Response response = api.http.newCall(new okhttp3.Request.Builder().url(url).build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) return;
                android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
                options.inSampleSize = sampleSize;
                Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(response.body().byteStream(), null, options);
                if (bitmap == null) return;
                thumbnails.put(cacheKey, bitmap);
                ui(request, () -> view.setImageBitmap(bitmap));
            } catch (Exception ignored) { /* Keep the placeholder when a preview is unavailable. */ }
        });
    }

    private void showSearch() {
        base("search", true);
        LinearLayout header = row();
        header.addView(searchBackButton(this::showHome));
        header.addView(heading("Recherche"));
        root.addView(header);
        LinearLayout searchBar = row();
        searchBar.setPadding(0, dp(8), 0, dp(4));
        EditText input = searchInput();
        searchBar.addView(input, new LinearLayout.LayoutParams(0, dp(56), 1));
        searchBar.addView(searchSubmitButton(input));
        root.addView(searchBar);
        root.addView(text("Recherche des lives, des catégories et des chaînes sur Twitch.", 16, MUTED));
        input.requestFocus();
    }

    private ImageButton searchBackButton(Runnable action) {
        ImageButton back = iconButton(R.drawable.ri_arrow_left_line, "Retour", action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(48));
        params.rightMargin = dp(8);
        back.setLayoutParams(params);
        return back;
    }

    private ImageButton searchSubmitButton(EditText input) {
        ImageButton submit = iconButton(R.drawable.ri_search_line, "Lancer la recherche", () -> submitSearch(input));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(56), dp(56));
        params.leftMargin = dp(8);
        submit.setLayoutParams(params);
        return submit;
    }

    private EditText searchInput() {
        EditText input = new EditText(this);
        input.setTextColor(TEXT);
        input.setTextSize(18);
        input.setHintTextColor(MUTED);
        input.setHint("Chaîne ou catégorie");
        input.setSingleLine(true);
        input.setText(query);
        input.setSelectAllOnFocus(true);
        input.setPadding(dp(16), dp(10), dp(16), dp(10));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, surface(0xff252330, 0xffd4bfff));
        background.addState(new int[]{}, surface(0xff252330, 0xff353140));
        input.setBackground(background);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        input.setOnEditorActionListener((v, action, event) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { submitSearch(input); return true; }
            return false;
        });
        return input;
    }

    private void submitSearch(EditText input) {
        String value = input.getText().toString().trim();
        if (value.isEmpty()) { toast("Saisis une chaîne ou une catégorie."); return; }
        query = value;
        tab = "search";
        selectedCard = "";
        catalogScrollY = 0;
        for (String type : new String[]{"live", "categories", "channels"}) shelfPositions.put("search:" + type + ":" + query, 0);
        focusFirstSearchResult = true;
        hideKeyboard(input);
        showHome();
    }

    private void showLogin() {
        if (api.clientId.isEmpty()) { showSettings(); return; }
        base("login", true);
        root.addView(heading("Connecter ton compte Twitch"));
        LinearLayout loginDetails = row();
        ImageView qr = new ImageView(this);
        qr.setContentDescription("QR code de l’adresse de connexion Twitch");
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qr.setVisibility(View.GONE);
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(dp(210), dp(210));
        qrParams.setMargins(0, dp(12), dp(26), dp(12));
        loginDetails.addView(qr, qrParams);
        TextView instructions = text("Préparation du code…", 24, TEXT);
        loginDetails.addView(instructions, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(loginDetails);
        root.addView(text("Scanne le QR code avec ton téléphone, ou ouvre l’adresse et valide le code.\nNe communique jamais ton mot de passe à cette application.", 18, MUTED));
        root.addView(button("Annuler", this::showHome));
        int request = generation;
        worker.execute(() -> {
            try {
                JSONObject device = api.startDeviceLogin();
                String code = device.getString("device_code");
                int interval = Math.max(5, device.optInt("interval", 5));
                long expires = System.currentTimeMillis() + device.getLong("expires_in") * 1000;
                String verificationUrl = device.optString("verification_uri", "https://www.twitch.tv/activate");
                String qrUrl = device.optString("verification_uri_complete");
                if (qrUrl.isEmpty()) qrUrl = verificationUrl;
                Bitmap bitmap = null;
                try { bitmap = loginQrCode(qrUrl); }
                catch (com.google.zxing.WriterException ignored) { /* The URL and code remain usable without a QR. */ }
                Bitmap qrBitmap = bitmap;
                ui(request, () -> {
                    instructions.setText(verificationUrl + "\n\nCode : " + device.optString("user_code"));
                    if (qrBitmap != null) {
                        qr.setImageBitmap(qrBitmap);
                        qr.setVisibility(View.VISIBLE);
                    }
                });
                schedulePoll(request, code, interval, expires, instructions, qr);
            } catch (Exception failure) {
                ui(request, () -> { instructions.setText(friendly(failure)); root.addView(button("Réessayer", this::showLogin)); });
            }
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

    private void schedulePoll(int request, String code, int interval, long expires, TextView instructions, ImageView qr) {
        worker.schedule(() -> {
            // Check the screen on the UI thread before starting the next network request.
            ui(request, () -> worker.execute(() -> {
                try {
                    if (System.currentTimeMillis() >= expires) throw new Exception("Code expiré. Relance la connexion.");
                    if (api.pollDeviceLogin(code, () -> generation == request && foreground)) ui(request, this::showHome);
                    else schedulePoll(request, code, interval, expires, instructions, qr);
                } catch (Exception failure) {
                    if ("slow_down".equals(failure.getMessage())) schedulePoll(request, code, interval + 5, expires, instructions, qr);
                    else ui(request, () -> { qr.setVisibility(View.GONE); instructions.setText(friendly(failure)); root.addView(button("Réessayer", this::showLogin)); });
                }
            }));
        }, interval, TimeUnit.SECONDS);
    }

    private void showPlayer(String channel, String title) {
        base("player", false);
        currentChannel = channel;
        currentTitle = title;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout body = row();
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        FrameLayout video = new FrameLayout(this);
        playerView = new PlayerView(this);
        playerView.setKeepContentOnPlayerReset(false);
        playerView.setUseController(false);
        playerView.setShowNextButton(false);
        playerView.setShowPreviousButton(false);
        playerView.setShowFastForwardButton(false);
        playerView.setShowRewindButton(false);
        playerView.setFocusable(true);
        video.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        playbackStatus = text("Récupération du flux Twitch…", 18, TEXT);
        playbackStatus.setGravity(Gravity.CENTER);
        playbackStatus.setBackgroundColor(0xb0101018);
        video.addView(playbackStatus, new FrameLayout.LayoutParams(-1, -1));
        playerHeading = row();
        playerHeading.setPadding(dp(20), dp(12), dp(20), dp(12));
        playerHeading.setBackgroundColor(0xcc101018);
        FrameLayout avatarFrame = new FrameLayout(this);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        avatarParams.rightMargin = dp(12);
        playerHeading.addView(avatarFrame, avatarParams);
        channelAvatar = new ImageView(this);
        channelAvatar.setImageResource(R.drawable.ri_user_line);
        channelAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xff594083);
        channelAvatar.setBackground(circle);
        channelAvatar.setClipToOutline(true);
        avatarFrame.addView(channelAvatar, new FrameLayout.LayoutParams(-1, -1));
        ImageButton back = iconButton(R.drawable.ri_arrow_left_line, "Retour au catalogue", this::showHome);
        StateListDrawable backImage = new StateListDrawable();
        backImage.addState(new int[]{android.R.attr.state_focused}, getDrawable(R.drawable.ri_arrow_left_line));
        backImage.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        back.setImageDrawable(backImage);
        StateListDrawable backSurface = new StateListDrawable();
        backSurface.addState(new int[]{android.R.attr.state_focused}, surface(0xcc101018, 0xffd4bfff));
        backSurface.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        back.setBackground(backSurface);
        avatarFrame.addView(back, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout metadata = column();
        playerName = text(channel, 18, TEXT);
        playerName.setTypeface(null, Typeface.BOLD);
        String fallbackTitle = title.contains(" · ") ? title.substring(title.indexOf(" · ") + 3) : title;
        playerStreamTitle = text(fallbackTitle, 15, TEXT);
        playerCategory = text("", 13, MUTED);
        for (TextView line : new TextView[]{playerName, playerStreamTitle, playerCategory}) {
            line.setSingleLine(true);
            line.setEllipsize(android.text.TextUtils.TruncateAt.END);
            line.setPadding(0, dp(2), dp(8), dp(2));
            line.setIncludeFontPadding(false);
            metadata.addView(line);
        }
        playerCategory.setVisibility(View.GONE);
        playerHeading.addView(metadata, new LinearLayout.LayoutParams(0, -2, 1));
        viewerButton = button("—", this::loadPlayerMetadata);
        viewerButton.setTextColor(0xffff8280);
        viewerButton.setTextSize(14);
        viewerButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ri_group_line, 0, 0, 0);
        viewerButton.setCompoundDrawablePadding(dp(6));
        viewerButton.setContentDescription("Actualiser le nombre de spectateurs");
        playerHeading.addView(viewerButton);
        for (List<JSONObject> items : shelfItems.values()) for (JSONObject item : items) {
            if (channel.equalsIgnoreCase(item.optString("user_login", item.optString("broadcaster_login")))) {
                playerName.setText(item.optString("user_name", item.optString("display_name", channel)));
                applyPlayerStream(item);
                break;
            }
        }
        video.addView(playerHeading, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        playerControls = row();
        playerControls.setPadding(dp(14), dp(10), dp(14), dp(10));
        playerControls.setBackgroundColor(0xe6101018);
        playButton = iconButton(R.drawable.ri_pause_fill, "Pause", () -> { if (player != null) { if (player.getPlayWhenReady()) player.pause(); else player.play(); } });
        playerControls.addView(playButton);
        playerControls.addView(iconButton(R.drawable.ri_refresh_line, "Recharger le flux", () -> showPlayer(currentChannel, currentTitle)));
        muteButton = iconButton(R.drawable.ri_volume_up_line, "Couper le son", () -> {
            if (player != null) {
                player.setVolume(player.getVolume() == 0 ? 1 : 0);
                controlIcon(muteButton, player.getVolume() == 0 ? R.drawable.ri_volume_mute_line : R.drawable.ri_volume_up_line,
                        player.getVolume() == 0 ? "Activer le son" : "Couper le son");
            }
        });
        playerControls.addView(muteButton);
        playerControls.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        playerControls.addView(iconButton(R.drawable.ri_hd_line, "Qualité du direct", this::showQuality));
        chatButton = iconButton(chatVisible ? R.drawable.ri_chat_off_line : R.drawable.ri_chat_line,
                chatVisible ? "Masquer le chat" : "Afficher le chat", () -> {
            chatVisible = !chatVisible;
            chatPanel.setVisibility(chatVisible ? View.VISIBLE : View.GONE);
            controlIcon(chatButton, chatVisible ? R.drawable.ri_chat_off_line : R.drawable.ri_chat_line,
                    chatVisible ? "Masquer le chat" : "Afficher le chat");
        });
        playerControls.addView(chatButton);
        video.addView(playerControls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        playerControls.setVisibility(View.GONE);
        playerHeading.setVisibility(View.GONE);
        controlsHiddenAt = android.os.SystemClock.elapsedRealtime();
        consumeCenterUp = false;
        body.addView(video, new LinearLayout.LayoutParams(0, -1, 1));
        chatPanel = column();
        chatPanel.setPadding(dp(10), dp(4), dp(12), dp(8));
        chatPanel.setBackgroundColor(0xff1c1926);
        chatStatus = text("Connexion au chat…", 14, MUTED);
        chatScroll = new ScrollView(this);
        chatScroll.setFocusable(false);
        chatScroll.setContentDescription("Messages du chat, lecture seule");
        chatText = text("", 16, TEXT);
        chatScroll.addView(chatText);
        chatPanel.addView(chatScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        body.addView(chatPanel, new LinearLayout.LayoutParams(dp(275), -1));
        chatPanel.setVisibility(chatVisible ? View.VISIBLE : View.GONE);
        playerView.requestFocus();
        int request = generation;
        worker.execute(() -> {
            try {
                String playlist = api.playlist(channel);
                ui(request, () -> startPlayback(playlist, request));
            } catch (Exception failure) { ui(request, () -> playbackStatus.setText(friendly(failure) + "\nAppuie sur OK pour afficher Recharger, ou Retour pour quitter.")); }
        });
        if (api.signedIn()) loadPlayerMetadata();
        chat = new ChatClient(api.http, channel, () -> new String[]{api.login(), api.accessToken()}, new ChatClient.Listener() {
            @Override public void status(String value) { ui(request, () -> chatStatus.setText(value)); }
            @Override public void message(IrcMessage value) { ui(request, () -> appendChat(value)); }
        });
        chat.start();
    }

    private void applyPlayerStream(JSONObject stream) {
        playerStreamTitle.setText(stream.optString("title", currentTitle));
        String category = stream.optString("game_name");
        playerCategory.setText(category);
        playerCategory.setVisibility(category.isEmpty() ? View.GONE : View.VISIBLE);
        if (stream.has("viewer_count")) viewerButton.setText(java.text.NumberFormat.getIntegerInstance(java.util.Locale.FRANCE)
                .format(stream.optInt("viewer_count")));
    }

    private void loadPlayerMetadata() {
        int request = generation;
        String channel = currentChannel;
        worker.execute(() -> {
            try {
                List<JSONObject> users = TwitchApi.items(api.helix("users", "login", channel));
                if (!users.isEmpty()) ui(request, () -> {
                    JSONObject user = users.get(0);
                    playerName.setText(user.optString("display_name", channel));
                    channelAvatar.setContentDescription("Photo de profil de " + user.optString("display_name", channel));
                    loadThumbnail(channelAvatar, user.optString("profile_image_url"));
                });
                List<JSONObject> streams = TwitchApi.items(api.helix("streams", "user_login", channel));
                ui(request, () -> {
                    if (streams.isEmpty()) viewerButton.setText("Hors ligne");
                    else applyPlayerStream(streams.get(0));
                });
            } catch (Exception ignored) { /* Keep the metadata already supplied by the catalog. */ }
        });
    }

    private void startPlayback(String playlist, int request) {
        if (!foreground) return;
        Map<String, String> headers = new HashMap<>();
        headers.put("Origin", "https://www.twitch.tv");
        headers.put("Referer", "https://www.twitch.tv/");
        DefaultHttpDataSource.Factory source = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 StreamlineTV/0.1")
                .setConnectTimeoutMs(15000).setReadTimeoutMs(20000).setDefaultRequestProperties(headers);
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            private boolean preferredApplied;
            @Override public void onTracksChanged(Tracks tracks) {
                if (request != generation || preferredApplied || !tracks.containsType(C.TRACK_TYPE_VIDEO)) return;
                preferredApplied = true;
                applyPreferredQuality(tracks);
            }
            @Override public void onPlayWhenReadyChanged(boolean ready, int reason) {
                if (request == generation) controlIcon(playButton, ready ? R.drawable.ri_pause_fill : R.drawable.ri_play_fill, ready ? "Pause" : "Lire");
            }
            @Override public void onPlaybackStateChanged(int state) {
                if (request != generation) return;
                if (state == Player.STATE_READY) playbackStatus.setVisibility(View.GONE);
                else if (state == Player.STATE_BUFFERING) {
                    playbackStatus.setText("Chargement du direct…");
                    playbackStatus.setVisibility(View.VISIBLE);
                } else if (state == Player.STATE_ENDED) {
                    playbackStatus.setText("Le direct est terminé.");
                    playbackStatus.setVisibility(View.VISIBLE);
                }
            }
            @Override public void onPlayerError(PlaybackException error) {
                playbackStatus.setText("Lecture impossible : " + error.getErrorCodeName()
                        + "\nLa chaîne est peut-être hors ligne, restreinte ou le flux a expiré.\nAppuie sur OK pour recharger ou choisir une autre qualité.");
                playbackStatus.setVisibility(View.VISIBLE);
            }
        });
        player.setMediaSource(new HlsMediaSource.Factory(source).createMediaSource(MediaItem.fromUri(playlist)));
        player.prepare();
        player.play();
    }

    private void showQuality() {
        if (player == null) { toast("Le lecteur n’est pas encore prêt."); return; }
        List<String> labels = new ArrayList<>();
        List<TrackSelectionOverride> choices = new ArrayList<>();
        labels.add("Automatique (recommandé)");
        choices.add(null);
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int index = 0; index < group.length; index++) {
                if (!group.isTrackSupported(index)) continue;
                Format format = group.getTrackFormat(index);
                labels.add((format.height > 0 ? format.height + "p" : "Vidéo")
                        + (format.frameRate > 0 ? " · " + Math.round(format.frameRate) + " fps" : "")
                        + (format.codecs != null ? " · " + format.codecs : ""));
                choices.add(new TrackSelectionOverride(group.getMediaTrackGroup(), index));
            }
        }
        labels.add("Audio uniquement");
        new AlertDialog.Builder(this).setTitle("Qualité du direct")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (player == null) return;
                    androidx.media3.common.TrackSelectionParameters.Builder params = player.getTrackSelectionParameters()
                            .buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, which == labels.size() - 1);
                    if (which > 0 && which < choices.size()) params.setOverrideForType(choices.get(which));
                    player.setTrackSelectionParameters(params.build());
                }).setNegativeButton("Fermer", null).show();
    }

    private void applyPreferredQuality(Tracks tracks) {
        String preference = QUALITY_VALUES[preferredQualityIndex()];
        if (preference.equals("auto") || player == null) return;
        int target = preference.equals("best") ? -1 : Integer.parseInt(preference);
        Format best = null;
        TrackSelectionOverride choice = null;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int index = 0; index < group.length; index++) {
                if (!group.isTrackSupported(index)) continue;
                Format format = group.getTrackFormat(index);
                if (target > 0 && format.height != target) continue;
                if (best == null || format.height > best.height
                        || (format.height == best.height && format.frameRate > best.frameRate)) {
                    best = format;
                    choice = new TrackSelectionOverride(group.getMediaTrackGroup(), index);
                }
            }
        }
        if (choice != null) player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO).setOverrideForType(choice).build());
    }

    private void appendChat(IrcMessage message) {
        if (message.command.equals("CLEARCHAT")) {
            if (message.text.isEmpty()) messages.clear();
            else messages.removeIf(item -> item.prefix.split("!", 2)[0].equalsIgnoreCase(message.text));
        } else if (message.command.equals("CLEARMSG")) {
            String id = message.tags.get("target-msg-id");
            if (id != null) messages.removeIf(item -> id.equals(item.tags.get("id")));
        } else {
            messages.add(message);
            if (messages.size() > 150) messages.remove(0);
        }
        if (chatRenderQueued) return;
        chatRenderQueued = true;
        int request = generation;
        main.postDelayed(() -> {
            if (request != generation) return;
            chatRenderQueued = false;
            renderChat();
        }, 200);
    }

    private void renderChat() {
        boolean atBottom = chatText.getHeight() - chatScroll.getHeight() - chatScroll.getScrollY() < dp(70);
        android.text.SpannableStringBuilder content = new android.text.SpannableStringBuilder();
        for (IrcMessage item : messages) {
            int start = content.length();
            content.append(item.name()).append(" : ");
            int color = 0xffc6afff;
            try {
                String raw = item.tags.get("color");
                if (raw != null && raw.matches("#[0-9A-Fa-f]{6}")) {
                    int candidate = Color.parseColor(raw);
                    if (Color.luminance(candidate) >= .25f) color = candidate;
                }
            } catch (IllegalArgumentException ignored) { }
            content.setSpan(new android.text.style.ForegroundColorSpan(color), start, content.length(), 0);
            content.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), start, content.length(), 0);
            String body = item.text;
            if (body.startsWith("\u0001ACTION ") && body.endsWith("\u0001")) body = body.substring(8, body.length() - 1);
            content.append(body).append("\n\n");
        }
        chatText.setText(content);
        if (atBottom) chatScroll.post(() -> chatScroll.scrollTo(0, chatText.getHeight()));
    }

    private void releasePlayback() {
        if (chat != null) { chat.close(); chat = null; }
        if (playerView != null) { playerView.setPlayer(null); playerView = null; }
        if (player != null) { player.release(); player = null; }
        playerControls = null;
        playerHeading = null;
        messages.clear();
        chatRenderQueued = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void ui(int request, Runnable action) {
        main.post(() -> { if (!isDestroyed() && generation == request) action.run(); });
    }

    private String friendly(Exception error) {
        if (error instanceof java.net.UnknownHostException) return "Connexion Internet indisponible.";
        if (error instanceof java.net.SocketTimeoutException) return "Twitch met trop de temps à répondre. Réessaie.";
        return error.getMessage() == null ? "Une erreur réseau est survenue. Réessaie." : error.getMessage();
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    private void hideKeyboard(View view) {
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (screen.equals("home") && event.getAction() == KeyEvent.ACTION_DOWN) focusFirstHomeList = false;
        if (screen.equals("player") && playerControls != null) {
            int key = event.getKeyCode();
            boolean center = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER;
            if (center && consumeCenterUp) {
                if (event.getAction() == KeyEvent.ACTION_UP) consumeCenterUp = false;
                return true;
            }
            if (playerControls.getVisibility() != View.VISIBLE) {
                if (center) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                        playerControls.setVisibility(View.VISIBLE);
                        playerHeading.setVisibility(View.VISIBLE);
                        playButton.requestFocus();
                        consumeCenterUp = true;
                    }
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                        || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            }
        }
        if (screen.equals("player") && player != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                if (player.isPlaying()) player.pause(); else player.play();
                return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY) { player.play(); return true; }
            if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PAUSE) { player.pause(); return true; }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onBackPressed() {
        if (screen.equals("player") && playerControls != null) {
            if (playerControls.getVisibility() == View.VISIBLE) {
                playerControls.setVisibility(View.GONE);
                playerHeading.setVisibility(View.GONE);
                controlsHiddenAt = android.os.SystemClock.elapsedRealtime();
                playerView.requestFocus();
                return;
            }
            if (android.os.SystemClock.elapsedRealtime() - controlsHiddenAt < 1000) return;
        }
        if (screen.equals("home")) {
            if (tab.equals("search") || tab.equals("game")) {
                boolean fromGame = tab.equals("game");
                if (fromGame && gameFromSearch) {
                    tab = "search";
                    selectedCard = "search:categories:" + query + ":" + gameId;
                    catalogScrollY = 0;
                    showHome();
                    return;
                }
                tab = "followed";
                query = "";
                selectedCard = fromGame ? "games:" + gameId : "";
                catalogScrollY = 0;
                showHome();
                return;
            }
            new AlertDialog.Builder(this).setTitle("Quitter Streamline TV ?")
                    .setNegativeButton("Annuler", null).setPositiveButton("Quitter", (dialog, which) -> finish()).show();
        } else showHome();
    }
}
