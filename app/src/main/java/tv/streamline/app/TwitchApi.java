package tv.streamline.app;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

final class TwitchApi {
    interface Store {
        JSONObject load();
        void save(JSONObject tokens) throws Exception;
        default void diagnostic(String event) {}
    }

    static final String SCOPES = "user:read:follows chat:read";
    // Ported from streamline/src/lib/twitch.ts; intentionally separate from our OAuth client ID.
    static final String PLAYBACK_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko";
    static final String PLAYBACK_HASH = "ed230aa1e33e07eebb8928504583da78a5173989fadfb1ac94be06a04f3cdbe9";
    final OkHttpClient http;
    final String clientId;
    private final Store store;
    private final String identityBase;
    private volatile JSONObject tokens;
    private long lastValidation;
    private String userId = "";
    private String login = "";

    TwitchApi(String clientId, Store store) {
        this(clientId, store, new OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build(), "https://id.twitch.tv/");
    }

    TwitchApi(String clientId, Store store, OkHttpClient http, String identityBase) {
        this.clientId = clientId;
        this.store = store;
        this.http = http;
        this.identityBase = identityBase;
        tokens = store.load();
    }

    // Immutable after publication: UI checks must never wait behind a network request.
    boolean signedIn() { return !tokens.optString("access_token").isEmpty(); }

    synchronized void clear() throws Exception {
        clear("signed_out");
    }

    private void clear(String reason) throws Exception {
        store.save(new JSONObject());
        tokens = new JSONObject();
        userId = "";
        login = "";
        lastValidation = 0;
        store.diagnostic(reason);
    }

    JSONObject startDeviceLogin() throws Exception {
        if (!clientId.matches("[a-zA-Z0-9]{10,100}")) throw new IOException("Renseigne un Client ID Twitch valide dans les réglages.");
        return send(new Request.Builder().url(identityBase + "oauth2/device").post(new FormBody.Builder()
                .add("client_id", clientId).add("scopes", SCOPES).build()).build());
    }

    synchronized boolean pollDeviceLogin(String deviceCode) throws Exception {
        return pollDeviceLogin(deviceCode, () -> true);
    }

    synchronized boolean pollDeviceLogin(String deviceCode, java.util.function.BooleanSupplier active) throws Exception {
        JSONObject result = send(new Request.Builder().url(identityBase + "oauth2/token").post(new FormBody.Builder()
                .add("client_id", clientId).add("scopes", SCOPES).add("device_code", deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code").build()).build(), true);
        String message = result.optString("message", result.optString("error"));
        if (message.equals("authorization_pending")) return false;
        if (message.equals("slow_down")) throw new IOException("slow_down");
        if (!result.has("access_token")) throw new IOException("Connexion refusée ou expirée. Relance la connexion.");
        if (!active.getAsBoolean()) return false;
        saveTokens(result);
        return true;
    }

    private void saveTokens(JSONObject result) throws Exception {
        result.put("expires_at", System.currentTimeMillis() + result.optLong("expires_in", 3600) * 1000);
        store.save(result);
        tokens = result;
        lastValidation = 0;
    }

    private void refresh() throws Exception {
        String refresh = tokens.optString("refresh_token");
        if (refresh.isEmpty()) {
            store.diagnostic("refresh_token_missing");
            throw new IOException("Session expirée. Reconnecte ton compte.");
        }
        JSONObject result;
        try {
            result = send(new Request.Builder().url(identityBase + "oauth2/token").post(new FormBody.Builder()
                    .add("client_id", clientId).add("grant_type", "refresh_token").add("refresh_token", refresh).build()).build(), true);
        } catch (Exception failure) {
            store.diagnostic("refresh_failed:" + failure.getClass().getSimpleName());
            throw failure;
        }
        int status = result.optInt("status", 200);
        if (status == 400 || status == 401) {
            clear("refresh_rejected_http_" + status);
            throw new IOException("Session expirée ou révoquée. Reconnecte ton compte.");
        }
        if (status >= 400) throw new IOException(apiError(result));
        saveTokens(result);
    }

    synchronized String accessToken() throws Exception {
        validateSession();
        return tokens.getString("access_token");
    }

    synchronized String login() throws Exception { validateSession(); return login; }

    synchronized void validateSession() throws Exception {
        if (!signedIn()) throw new IOException("Connecte ton compte Twitch.");
        long now = System.currentTimeMillis();
        if (tokens.optLong("expires_at") < now + 60000) refresh();
        if (lastValidation != 0 && now - lastValidation < 3600000) return;
        JSONObject identity = validateToken();
        if (identity.optInt("status") == 401) {
            refresh();
            identity = validateToken();
        }
        int status = identity.optInt("status", 200);
        if (status >= 400 && status != 401) throw new IOException(apiError(identity));
        if (!clientId.equals(identity.optString("client_id")) || identity.optString("user_id").isEmpty()) {
            clear("identity_invalid");
            throw new IOException("Session Twitch invalide. Reconnecte ton compte.");
        }
        JSONArray scopes = identity.optJSONArray("scopes");
        boolean follows = false, chat = false;
        if (scopes != null) for (int i = 0; i < scopes.length(); i++) {
            follows |= scopes.optString(i).equals("user:read:follows");
            chat |= scopes.optString(i).equals("chat:read");
        }
        if (!follows || !chat) throw new IOException("Autorisations manquantes : reconnecte ton compte Twitch.");
        userId = identity.getString("user_id");
        login = identity.getString("login");
        lastValidation = now;
    }

    private JSONObject validateToken() throws Exception {
        return send(new Request.Builder().url(identityBase + "oauth2/validate")
                .header("Authorization", "OAuth " + tokens.getString("access_token")).build(), true);
    }

    synchronized JSONObject helix(String path, String... query) throws Exception {
        validateSession();
        HttpUrl.Builder url = HttpUrl.get("https://api.twitch.tv/helix/" + path).newBuilder();
        for (int i = 0; i < query.length; i += 2) url.addQueryParameter(query[i], query[i + 1]);
        JSONObject response = helixRequest(url.build());
        if (response.optInt("status") == 401) {
            refresh();
            validateSession();
            response = helixRequest(url.build());
        }
        if (response.optInt("status", 200) >= 400) throw new IOException(apiError(response));
        return response;
    }

    private JSONObject helixRequest(HttpUrl url) throws Exception {
        return send(new Request.Builder().url(url).header("Client-Id", clientId)
                .header("Authorization", "Bearer " + tokens.getString("access_token")).build(), true);
    }

    synchronized JSONObject followed(String cursor) throws Exception {
        validateSession();
        return helix("streams/followed", "user_id", userId, "first", "100", "after", cursor);
    }

    static String channel(String channel) {
        if (channel == null || !channel.matches("[a-zA-Z0-9_]{1,100}")) throw new IllegalArgumentException("Nom de chaîne invalide.");
        return channel.toLowerCase(Locale.ROOT);
    }

    String playlist(String channel) throws Exception {
        String login = channel(channel);
        JSONObject variables = new JSONObject().put("isLive", true).put("login", login).put("isVod", false)
                .put("vodID", "").put("playerType", "embed").put("platform", "site");
        JSONObject body = new JSONObject().put("operationName", "PlaybackAccessToken").put("variables", variables)
                .put("extensions", new JSONObject().put("persistedQuery", new JSONObject().put("version", 1).put("sha256Hash", PLAYBACK_HASH)));
        // Streamline allows anonymous playback. Do not send a Helix token issued to another client here.
        JSONObject result = send(new Request.Builder().url("https://gql.twitch.tv/gql")
                .header("Client-ID", PLAYBACK_CLIENT_ID).header("Origin", "https://www.twitch.tv")
                .header("Referer", "https://www.twitch.tv/")
                .post(RequestBody.create(body.toString(), MediaType.get("application/json"))).build());
        JSONObject data = result.optJSONObject("data");
        JSONObject token = data == null ? null : data.optJSONObject("streamPlaybackAccessToken");
        if (token == null) throw new IOException("Twitch n’a pas fourni de jeton de lecture. Son interface non publique a peut-être changé.");
        return playlistUrl(login, token.getString("signature"), token.getString("value"));
    }

    static String playlistUrl(String channel, String signature, String token) {
        return HttpUrl.get("https://usher.ttvnw.net/api/v2/channel/hls/" + channel(channel) + ".m3u8").newBuilder()
                .addQueryParameter("sig", signature).addQueryParameter("token", token)
                .addQueryParameter("fast_bread", "true").addQueryParameter("platform", "web")
                .addQueryParameter("p", String.valueOf(java.util.concurrent.ThreadLocalRandom.current().nextInt(999999)))
                .addQueryParameter("allow_source", "true").addQueryParameter("allow_audio_only", "true")
                .addQueryParameter("playlist_include_framerate", "true")
                .addQueryParameter("supported_codecs", "h264,h265,av1").build().toString();
    }

    private JSONObject send(Request request) throws Exception { return send(request, false); }

    private JSONObject send(Request request, boolean acceptError) throws Exception {
        try (Response response = http.newCall(request).execute()) {
            if (response.body() == null) throw new IOException("Réponse Twitch vide.");
            JSONObject body;
            try { body = new JSONObject(response.body().string()); }
            catch (Exception invalid) { throw new IOException("Réponse Twitch invalide (HTTP " + response.code() + ")."); }
            if (!response.isSuccessful()) {
                body.put("status", response.code());
                if (!acceptError) throw new IOException(apiError(body));
            }
            return body;
        }
    }

    private static String apiError(JSONObject body) {
        int status = body.optInt("status");
        if (status == 401) return "Session expirée ou révoquée. Reconnecte ton compte.";
        if (status == 429) return "Trop de requêtes Twitch. Réessaie dans un instant.";
        return "Erreur Twitch (" + status + ") : " + body.optString("message", "requête refusée");
    }

    static List<JSONObject> items(JSONObject response) {
        List<JSONObject> result = new ArrayList<>();
        JSONArray data = response.optJSONArray("data");
        if (data != null) for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item != null) result.add(item);
        }
        return result;
    }
}
