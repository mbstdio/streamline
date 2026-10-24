package tv.streamline.app;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Hides Helix endpoint selection and enrichment from catalog rendering. */
final class CatalogRepository {
    static final class Page {
        final List<JSONObject> items;
        final String cursor;
        Page(List<JSONObject> items, String cursor) { this.items = items; this.cursor = cursor; }
    }

    private final TwitchApi api;
    CatalogRepository(TwitchApi api) { this.api = api; }

    Page load(String key, String after) throws Exception {
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
                logins.add("user_login"); logins.add(item.optString("broadcaster_login"));
            }
            if (!logins.isEmpty()) {
                List<JSONObject> streams = TwitchApi.items(api.helix("streams", logins.toArray(new String[0])));
                for (JSONObject item : items) for (JSONObject stream : streams) {
                    if (!item.optString("broadcaster_login").equalsIgnoreCase(stream.optString("user_login"))) continue;
                    for (String field : new String[]{"thumbnail_url", "viewer_count", "title", "game_name"}) {
                        if (stream.has(field)) item.put(field, stream.get(field));
                    }
                }
            }
        }
        JSONObject pagination = result.optJSONObject("pagination");
        return new Page(items, pagination == null ? "" : pagination.optString("cursor"));
    }
}
