package tv.streamline.app;

import android.os.Bundle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/** Session state only: no views, Activity, or network resources. */
final class CatalogState {
    enum Tab { HOME, SEARCH, GAME }
    Tab tab = Tab.HOME;
    String query = "", gameId = "", gameName = "", selectedCard = "";
    boolean gameFromSearch;
    int catalogScrollY;
    final Map<String, List<JSONObject>> shelfItems = new HashMap<>();
    final Map<String, String> shelfCursors = new HashMap<>();
    final Map<String, Integer> shelfPositions = new HashMap<>();
    final Map<String, String> shelfSelections = new HashMap<>();

    void restore(Bundle state) {
        if (state == null) return;
        String savedTab = state.getString("tab", "followed");
        tab = "search".equals(savedTab) ? Tab.SEARCH : "game".equals(savedTab) ? Tab.GAME : Tab.HOME;
        query = state.getString("query", "");
        gameId = state.getString("game_id", "");
        gameName = state.getString("game_name", "");
        gameFromSearch = state.getBoolean("game_from_search", false);
    }

    void save(Bundle state) {
        state.putString("tab", tab == Tab.SEARCH ? "search" : tab == Tab.GAME ? "game" : "followed");
        state.putString("query", query);
        state.putString("game_id", gameId);
        state.putString("game_name", gameName);
        state.putBoolean("game_from_search", gameFromSearch);
    }

    void clear() {
        shelfItems.clear();
        shelfCursors.clear();
        shelfPositions.clear();
        shelfSelections.clear();
        selectedCard = "";
        catalogScrollY = 0;
    }
}
