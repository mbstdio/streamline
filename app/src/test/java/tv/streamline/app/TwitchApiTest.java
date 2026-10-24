package tv.streamline.app;

import static org.junit.Assert.*;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TwitchApiTest {
    private MockWebServer server;
    private MemoryStore store;
    private TwitchApi api;

    private static class MemoryStore implements TwitchApi.Store {
        JSONObject value = new JSONObject();
        @Override public JSONObject load() { return value; }
        @Override public void save(JSONObject value) { this.value = value; }
    }

    @Before public void setup() throws Exception {
        server = new MockWebServer();
        server.start();
        store = new MemoryStore();
        api = new TwitchApi("mypublicclient123", store, new OkHttpClient(), server.url("/").toString());
    }

    @After public void cleanup() throws Exception { server.shutdown(); }

    private void reply(int code, String body) {
        server.enqueue(new MockResponse().setResponseCode(code).setBody(body).addHeader("Content-Type", "application/json"));
    }

    private String identity(String client) {
        return "{\"client_id\":\"" + client + "\",\"login\":\"viewer\",\"user_id\":\"123\",\"scopes\":[\"chat:read\",\"user:read:follows\"]}";
    }

    @Test public void channelValidationPreventsPathInjection() {
        assertEquals("some_channel", TwitchApi.channel("Some_Channel"));
        for (String invalid : new String[]{"", "../admin", "x?token=y", "a/b", "foo bar", "x\r\nJOIN #other"}) {
            assertThrows(IllegalArgumentException.class, () -> TwitchApi.channel(invalid));
        }
    }

    @Test public void playlistPreservesEncodedSignatureAndTokenWithoutOAuth() {
        String token = "{\"a\":\"+ / &= é\"}";
        HttpUrl url = HttpUrl.get(TwitchApi.playlistUrl("Test_Channel", "a+b/=&", token));
        assertEquals("/api/v2/channel/hls/test_channel.m3u8", url.encodedPath());
        assertEquals(token, url.queryParameter("token"));
        assertEquals("a+b/=&", url.queryParameter("sig"));
        assertEquals("h264,h265,av1", url.queryParameter("supported_codecs"));
        assertNull(url.queryParameter("oauth"));
    }

    @Test public void deviceFlowOnlyRequestsReadPermissionsAndNoSecret() throws Exception {
        reply(200, "{\"device_code\":\"device\",\"user_code\":\"ABC\"}");
        assertEquals("ABC", api.startDeviceLogin().getString("user_code"));
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("chat%3Aread"));
        assertTrue(body.contains("user%3Aread%3Afollows"));
        assertFalse(body.contains("chat%3Aedit"));
        assertFalse(body.contains("client_secret"));
    }

    @Test public void pendingAuthorizationDoesNotCreateSession() throws Exception {
        reply(400, "{\"message\":\"authorization_pending\"}");
        assertFalse(api.pollDeviceLogin("device"));
        assertFalse(api.signedIn());
    }

    @Test public void slowDownCanBeHandledByPollingScheduler() {
        reply(400, "{\"message\":\"slow_down\"}");
        Exception failure = assertThrows(Exception.class, () -> api.pollDeviceLogin("device"));
        assertEquals("slow_down", failure.getMessage());
    }

    @Test public void cancelledDeviceLoginDoesNotPersistTokens() throws Exception {
        reply(200, "{\"access_token\":\"access\",\"refresh_token\":\"refresh\",\"expires_in\":3600}");
        assertFalse(api.pollDeviceLogin("device", () -> false));
        assertFalse(api.signedIn());
        assertEquals(0, store.value.length());
    }

    @Test public void grantedTokensArePersistedAndValidated() throws Exception {
        reply(200, "{\"access_token\":\"access\",\"refresh_token\":\"refresh\",\"expires_in\":3600}");
        assertTrue(api.pollDeviceLogin("device"));
        assertTrue(api.signedIn());
        assertTrue(store.value.getLong("expires_at") > System.currentTimeMillis());
        reply(200, identity("mypublicclient123"));
        assertEquals("viewer", api.login());
        assertEquals("access", api.accessToken());
        // Repeated requests reuse the validation for up to one hour.
        assertEquals(2, server.getRequestCount());
    }

    @Test public void revokedAccessRefreshesAndPersistsRotatedRefreshToken() throws Exception {
        store.value.put("access_token", "old").put("refresh_token", "refresh-old")
                .put("expires_at", System.currentTimeMillis() + 3600000);
        api = new TwitchApi("mypublicclient123", store, new OkHttpClient(), server.url("/").toString());
        reply(401, "{\"status\":401}");
        reply(200, "{\"access_token\":\"new\",\"refresh_token\":\"refresh-new\",\"expires_in\":3600}");
        reply(200, identity("mypublicclient123"));
        assertEquals("new", api.accessToken());
        assertEquals("refresh-new", store.value.getString("refresh_token"));
        server.takeRequest();
        String body = server.takeRequest().getBody().readUtf8();
        assertTrue(body.contains("refresh_token=refresh-old"));
        assertFalse(body.contains("client_secret"));
    }

    @Test public void rejectsTokensIssuedToAnotherClient() throws Exception {
        store.value.put("access_token", "access").put("expires_at", System.currentTimeMillis() + 3600000);
        api = new TwitchApi("mypublicclient123", store, new OkHttpClient(), server.url("/").toString());
        reply(200, identity("someoneelse"));
        assertThrows(Exception.class, () -> api.validateSession());
        assertFalse(api.signedIn());
        assertEquals(0, store.value.length());
    }

    @Test public void missingChatPermissionRequiresReauthorization() throws Exception {
        store.value.put("access_token", "access").put("expires_at", System.currentTimeMillis() + 3600000);
        api = new TwitchApi("mypublicclient123", store, new OkHttpClient(), server.url("/").toString());
        reply(200, identity("mypublicclient123").replace("\"chat:read\",", ""));
        assertThrows(Exception.class, () -> api.validateSession());
    }

    @Test public void twitchOutageDoesNotEraseValidSession() throws Exception {
        store.value.put("access_token", "access").put("expires_at", System.currentTimeMillis() + 3600000);
        api = new TwitchApi("mypublicclient123", store, new OkHttpClient(), server.url("/").toString());
        reply(503, "{\"message\":\"Service unavailable\"}");
        assertThrows(Exception.class, () -> api.validateSession());
        assertTrue(api.signedIn());
    }

    @Test public void revokedRefreshTokenErasesLocalSession() throws Exception {
        store.value.put("access_token", "access").put("refresh_token", "revoked").put("expires_at", 0);
        api = new TwitchApi("mypublicclient123", store, new OkHttpClient(), server.url("/").toString());
        reply(400, "{\"message\":\"Invalid refresh token\"}");
        assertThrows(Exception.class, () -> api.validateSession());
        assertFalse(api.signedIn());
        assertEquals(0, store.value.length());
    }

    @Test public void malformedTwitchResponseIsReported() {
        reply(502, "<html>unavailable</html>");
        Exception error = assertThrows(Exception.class, () -> api.startDeviceLogin());
        assertTrue(error.getMessage().contains("502"));
    }
}
