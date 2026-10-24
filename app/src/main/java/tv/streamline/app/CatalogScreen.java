package tv.streamline.app;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

final class CatalogScreen extends Screen {
    final CatalogState state = new CatalogState();
    private final CatalogRepository repository;
    private final CatalogCards cardRenderer;
    private final Map<String, ImageButton> refreshButtons = new HashMap<>();
    private final Set<String> loadingShelves = new HashSet<>();
    private LinearLayout list;
    private JSONObject profile;
    private boolean searchEntry, focusFirstSearchResult, focusFirstHomeList;

    CatalogScreen(MainActivity host) {
        super(host);
        repository = new CatalogRepository(api);
        cardRenderer = new CatalogCards(this);
    }

    @Override void leave() {
        super.leave();
        list = null;
        refreshButtons.clear();
        loadingShelves.clear();
    }

    @Override void show() {
        searchEntry = false;
        base(true);
        root.setPadding(0, dp(22), 0, dp(20));
        focusFirstHomeList = api.signedIn() && state.tab == CatalogState.Tab.HOME && state.selectedCard.isEmpty();
        refreshButtons.clear();
        loadingShelves.clear();
        LinearLayout header = row();
        header.setPadding(dp(38), 0, dp(38), 0);
        View initialFocus;
        if (state.tab == CatalogState.Tab.SEARCH) {
            header.addView(searchBackButton(host::onBackPressed));
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
            header.addView(heading("Streamline"), new LinearLayout.LayoutParams(0, -2, 1));
            ImageButton search = iconButton(R.drawable.ri_search_line, "Rechercher", this::showSearch);
            header.addView(search);
            header.addView(iconButton(R.drawable.ri_settings_line, "Réglages", host::showSettings));
            ImageButton avatar = iconButton(R.drawable.ri_user_line, api.signedIn() ? "Compte Twitch" : "Connexion Twitch",
                    () -> { if (api.signedIn()) showAccountMenu(); else host.showLogin(); });
            avatar.setClipToOutline(true);
            StateListDrawable border = new StateListDrawable();
            border.addState(new int[]{android.R.attr.state_focused}, surface(Color.TRANSPARENT, 0xffd4bfff));
            border.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            avatar.setForeground(border);
            header.addView(avatar);
            if (api.signedIn()) loadProfile(avatar);
            root.addView(header);
            initialFocus = search;
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, 0, 1);
        params.topMargin = dp(12);
        scroll.setFillViewport(false);
        list = column();
        scroll.addView(list);
        root.addView(scroll, params);
        int restoreY = state.catalogScrollY;
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> state.catalogScrollY = y);
        initialFocus.requestFocus();
        if (!api.signedIn()) {
            list.setPadding(dp(38), 0, dp(38), 0);
            list.addView(text("Connecte ton compte Twitch pour afficher les chaînes, rechercher et lire le chat.", 16, MUTED));
            list.addView(button("Se connecter", host::showLogin));
        } else if (state.tab == CatalogState.Tab.SEARCH) {
            addShelf("search:live:" + state.query, "Lives");
            addShelf("search:categories:" + state.query, "Catégories");
            addShelf("search:channels:" + state.query, "Chaînes");
        } else if (state.tab == CatalogState.Tab.GAME) addShelf("game:" + state.gameId, "Directs · " + state.gameName);
        else {
            addShelf("followed", "Suivis en direct");
            addShelf("browse", "Chaînes live");
            addShelf("games", "Parcourir");
        }
        LinearLayout catalog = list;
        catalog.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            int count = catalog.getChildCount();
            if (count < 2 || !(catalog.getChildAt(count - 1) instanceof HorizontalScrollView)) return;
            int bottomSpace = Math.max(0, scroll.getHeight() - catalog.getChildAt(count - 2).getHeight() - catalog.getChildAt(count - 1).getHeight());
            if (catalog.getPaddingBottom() != bottomSpace) catalog.setPadding(0, 0, 0, bottomSpace);
        });
        catalog.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            View ancestor = newFocus;
            while (ancestor != null && ancestor != catalog) {
                if (ancestor instanceof HorizontalScrollView && ancestor.getParent() == catalog) {
                    HorizontalScrollView shelf = (HorizontalScrollView) ancestor;
                    View card = cardFor(shelf, newFocus);
                    if (card != null && card.getTag() instanceof String) {
                        state.shelfSelections.put((String) shelf.getTag(), (String) card.getTag());
                        linkShelfFocus();
                    }
                    shelf.post(() -> { if (newFocus.hasFocus()) alignShelfFocus(shelf, newFocus); });
                    maybeLoadMore(shelf, newFocus);
                    View title = catalog.getChildAt(catalog.indexOfChild(shelf) - 1);
                    scroll.post(() -> { if (newFocus.hasFocus()) scroll.smoothScrollTo(0, title.getTop()); });
                    break;
                }
                ancestor = ancestor.getParent() instanceof View ? (View) ancestor.getParent() : null;
            }
        });
        scroll.post(() -> scroll.scrollTo(0, restoreY));
    }

    private void showAccountMenu() {
        new AlertDialog.Builder(host).setTitle(profile == null ? "Compte Twitch" : profile.optString("display_name", "Compte Twitch"))
                .setItems(new String[]{"Se déconnecter"}, (menu, index) -> new AlertDialog.Builder(host)
                        .setTitle("Déconnecter ce compte ?").setMessage("La session enregistrée sur cette box sera effacée.")
                        .setNegativeButton("Annuler", null).setPositiveButton("Déconnecter", (dialog, which) -> {
                            int request = generation;
                            worker.execute(() -> {
                                try {
                                    api.clear();
                                    ui(request, () -> { profile = null; state.clear(); host.showHome(); });
                                } catch (Exception failure) { ui(request, () -> toast(friendly(failure))); }
                            });
                        }).show()).setNegativeButton("Fermer", null).show();
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
            } catch (Exception ignored) { /* Keep the fallback account icon. */ }
        });
    }

    private void addShelf(String key, String label) {
        LinearLayout header = row();
        header.setPadding(dp(38), list.getChildCount() > 0 ? dp(12) : 0, dp(38), 0);
        header.addView(heading(label), new LinearLayout.LayoutParams(0, -2, 1));
        list.addView(header);
        HorizontalScrollView scroll = new EdgeFadeScrollView(this, BG, dp(28));
        scroll.setTag(key);
        scroll.setFocusable(false);
        int restoreX = state.shelfPositions.getOrDefault(key, 0);
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> state.shelfPositions.put(key, x));
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(43), dp(5), dp(43), dp(10));
        LinearLayout cards = row();
        cards.setGravity(Gravity.TOP);
        ImageButton refresh = iconButton(R.drawable.ri_refresh_line, "Actualiser · " + label, () -> {
            state.shelfItems.remove(key);
            state.shelfCursors.remove(key);
            state.shelfPositions.put(key, 0);
            if (state.selectedCard.startsWith(key + ":")) state.selectedCard = "";
            scroll.scrollTo(0, 0);
            cards.removeAllViews();
            loadShelf(key, cards, false, false);
        });
        refreshButtons.put(key, refresh);
        header.addView(refresh);
        scroll.addView(cards);
        list.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        List<JSONObject> cached = state.shelfItems.get(key);
        if (cached == null) loadShelf(key, cards, false, false);
        else {
            for (JSONObject item : cached) cardRenderer.add(cards, item, key);
            shelfFooter(key, cards);
        }
        int request = generation;
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if (generation != request) return;
                scroll.scrollTo(restoreX, 0);
                if (state.shelfItems.containsKey(key) && focusFirstResult(key, cards)) return;
                for (int i = 0; i < cards.getChildCount(); i++) {
                    View card = cards.getChildAt(i);
                    if (state.selectedCard.equals(card.getTag())) {
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

    private View cardFor(HorizontalScrollView shelf, View focused) {
        ViewGroup cards = (ViewGroup) shelf.getChildAt(0);
        View card = focused;
        while (card != null && card.getParent() != cards) card = card.getParent() instanceof View ? (View) card.getParent() : null;
        return card;
    }

    private void alignShelfFocus(HorizontalScrollView shelf, View focused) {
        View card = cardFor(shelf, focused);
        if (card == null) return;
        ViewGroup cards = (ViewGroup) shelf.getChildAt(0);
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
                if (card.isFocusable()) { focusFirstHomeList = false; return card.requestFocus(); }
            }
            if (!state.shelfItems.containsKey((String) child.getTag())) return false;
        }
        return false;
    }

    private void linkShelfFocus() {
        List<View> previous = null;
        View previousRefresh = null, previousEntry = null;
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
                current.get(i).setNextFocusUpId(refresh.getId());
            }
            View entry = current.isEmpty() ? null : current.get(0);
            String remembered = state.shelfSelections.get((String) child.getTag());
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
        android.graphics.drawable.GradientDrawable shape = surface(0xff353140, 0xff353140, 6);
        if (channel) shape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        preview.setBackground(shape);
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

    private void maybeLoadMore(HorizontalScrollView shelf, View focused) {
        if (!host.settings.getBoolean("auto_load_lists", false)) return;
        String key = (String) shelf.getTag();
        if (loadingShelves.contains(key) || state.shelfCursors.getOrDefault(key, "").isEmpty()) return;
        LinearLayout cards = (LinearLayout) shelf.getChildAt(0);
        View item = cardFor(shelf, focused);
        if (item == null || !(item.getTag() instanceof String)) return;
        int remaining = 0;
        for (int i = cards.indexOfChild(item) + 1; i < cards.getChildCount(); i++) if (cards.getChildAt(i).getTag() instanceof String) remaining++;
        if (remaining > 2) return;
        View last = cards.getChildAt(cards.getChildCount() - 1);
        if (last instanceof Button) cards.removeView(last);
        loadShelf(key, cards, true, true);
    }

    private void loadShelf(String key, LinearLayout cards, boolean more, boolean automatic) {
        if (!loadingShelves.add(key)) return;
        int request = generation;
        ImageButton refresh = refreshButtons.get(key);
        if (refresh != null) { refresh.setClickable(false); refresh.setAlpha(.4f); }
        List<View> loading = new ArrayList<>();
        boolean categories = key.equals("games") || key.startsWith("search:categories:");
        boolean channels = key.startsWith("search:channels:");
        for (int i = 0; i < (categories || channels ? 5 : 3); i++) loading.add(loadingCard(cards, categories, channels));
        linkShelfFocus();
        String after = more ? state.shelfCursors.getOrDefault(key, "") : "";
        worker.execute(() -> {
            try {
                CatalogRepository.Page page = repository.load(key, after);
                ui(request, () -> {
                    finishLoading(key, cards, refresh, loading);
                    List<JSONObject> all = more ? new ArrayList<>(state.shelfItems.getOrDefault(key, new ArrayList<>())) : new ArrayList<>();
                    all.addAll(page.items);
                    state.shelfItems.put(key, all);
                    state.shelfCursors.put(key, page.cursor);
                    int firstNew = cards.getChildCount();
                    for (JSONObject item : page.items) cardRenderer.add(cards, item, key);
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
                    finishLoading(key, cards, refresh, loading);
                    TextView error = text(friendly(failure), 16, MUTED);
                    error.setMaxWidth(dp(300));
                    cards.addView(error);
                    cards.addView(button("Réessayer", () -> { cards.removeAllViews(); loadShelf(key, cards, false, false); }));
                    linkShelfFocus();
                    if (focusFirstHomeList) cards.post(() -> { if (generation == request && focusFirstHomeList) focusHomeList(); });
                });
            }
        });
    }

    private void finishLoading(String key, LinearLayout cards, ImageButton refresh, List<View> loading) {
        loadingShelves.remove(key);
        if (refresh != null) { refresh.setClickable(true); refresh.setAlpha(1f); }
        for (View placeholder : loading) cards.removeView(placeholder);
    }

    private void shelfFooter(String key, LinearLayout cards) {
        if (state.shelfItems.getOrDefault(key, new ArrayList<>()).isEmpty()) cards.addView(text("Aucun résultat", 18, MUTED));
        if (!state.shelfCursors.getOrDefault(key, "").isEmpty()) {
            Button more = button("Afficher la suite", () -> {});
            more.setOnClickListener(v -> { cards.removeView(more); loadShelf(key, cards, true, false); });
            cards.addView(more);
        }
    }

    private void showSearch() {
        searchEntry = true;
        base(true);
        refreshButtons.clear();
        loadingShelves.clear();
        list = null;
        LinearLayout header = row();
        header.addView(searchBackButton(this::show));
        header.addView(heading("Recherche"));
        root.addView(header);
        LinearLayout bar = row();
        bar.setPadding(0, dp(8), 0, dp(4));
        EditText input = searchInput();
        bar.addView(input, new LinearLayout.LayoutParams(0, dp(56), 1));
        bar.addView(searchSubmitButton(input));
        root.addView(bar);
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
        input.setText(state.query);
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
        state.query = value;
        state.tab = CatalogState.Tab.SEARCH;
        state.selectedCard = "";
        state.catalogScrollY = 0;
        for (String type : new String[]{"live", "categories", "channels"}) state.shelfPositions.put("search:" + type + ":" + value, 0);
        focusFirstSearchResult = true;
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(), 0);
        show();
    }

    void openGame(String id, String name, boolean fromSearch) {
        state.gameId = id;
        state.gameName = name;
        state.gameFromSearch = fromSearch;
        state.tab = CatalogState.Tab.GAME;
        state.selectedCard = "";
        state.catalogScrollY = 0;
        state.shelfPositions.put("game:" + id, 0);
        focusFirstSearchResult = true;
        show();
    }

    JSONObject stream(String channel) {
        for (List<JSONObject> items : state.shelfItems.values()) for (JSONObject item : items) {
            if (channel.equalsIgnoreCase(item.optString("user_login", item.optString("broadcaster_login")))) return item;
        }
        return null;
    }

    @Override boolean keyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) focusFirstHomeList = false;
        return false;
    }

    @Override boolean back() {
        if (searchEntry) { show(); return true; }
        if (state.tab == CatalogState.Tab.SEARCH || state.tab == CatalogState.Tab.GAME) {
            boolean fromGame = state.tab == CatalogState.Tab.GAME;
            if (fromGame && state.gameFromSearch) {
                state.tab = CatalogState.Tab.SEARCH;
                state.selectedCard = "search:categories:" + state.query + ":" + state.gameId;
            } else {
                state.tab = CatalogState.Tab.HOME;
                state.query = "";
                state.selectedCard = fromGame ? "games:" + state.gameId : "";
            }
            state.catalogScrollY = 0;
            show();
            return true;
        }
        new AlertDialog.Builder(host).setTitle("Quitter Streamline TV ?")
                .setNegativeButton("Annuler", null).setPositiveButton("Quitter", (dialog, which) -> host.finish()).show();
        return true;
    }
}
