package tv.streamline.app;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONObject;
import org.junit.Test;

public class UpdateCatalogTest {
    @Test public void acceptsCdnAndProjectGithubReleasesInTheSameCatalog() throws Exception {
        String github = "https://github.com/mbstdio/streamline/releases/download/v0.2.3/streamline-0.2.3.apk";
        JSONObject versions = new JSONObject()
                .put("0.2.3", new JSONObject().put("version", "0.2.3").put("apk", github)
                        .put("changelog", new org.json.JSONArray()))
                .put("0.2.2", new JSONObject().put("version", "0.2.2")
                        .put("apk", "https://cdn.infiniteloop.ch/streamline/apk/streamline-tv-0.2.2.apk")
                        .put("changelog", new org.json.JSONArray()));
        UpdateCatalog catalog = new UpdateCatalog(new JSONObject().put("stable", "0.2.3").put("versions", versions));
        assertEquals(github, catalog.stable.apk);
        assertEquals(2, catalog.releases.size());
    }

    @Test public void comparesVersionsNumericallyAndPlacesStableAfterPrereleases() {
        assertTrue(UpdateCatalog.compare("0.2.10", "0.2.9") > 0);
        assertTrue(UpdateCatalog.compare("0.3.0", "0.2.99") > 0);
        assertTrue(UpdateCatalog.compare("1.0.0", "1.0.0-beta.10") > 0);
        assertTrue(UpdateCatalog.compare("1.0.0-beta.10", "1.0.0-beta.2") > 0);
        assertTrue(UpdateCatalog.compare("1.0.0-beta.2", "1.0.0-beta") > 0);
        assertEquals(0, UpdateCatalog.compare("0.2.2", "0.2.2"));
    }

    @Test public void usesStablePointerRatherThanNewestBetaAndSortsHistory() throws Exception {
        JSONObject versions = new JSONObject();
        for (String version : new String[]{"0.2.2", "0.3.0-beta.1", "0.2.10"}) {
            versions.put(version, new JSONObject().put("version", version)
                    .put("apk", "https://cdn.infiniteloop.ch/streamline/apk/" + version + ".apk")
                    .put("changelog", new org.json.JSONArray().put("Une amélioration")));
        }
        UpdateCatalog catalog = new UpdateCatalog(new JSONObject().put("stable", "0.2.10").put("versions", versions));
        assertEquals("0.2.10", catalog.stable.version);
        assertEquals("0.3.0-beta.1", catalog.releases.get(0).version);
        assertEquals("0.2.2", catalog.releases.get(2).version);
        assertEquals("Une amélioration", catalog.stable.changelog.get(0));
    }

    @Test public void refusesUnsafeDownloadLocations() {
        for (String url : new String[]{"http://cdn.infiniteloop.ch/app.apk", "https://example.com/app.apk",
                "https://cdn.infiniteloop.ch.example.com/app.apk", "https://cdn.infiniteloop.ch:8443/app.apk",
                "https://user:password@cdn.infiniteloop.ch/app.apk",
                "https://github.com/other/project/releases/download/v1/app.apk",
                "https://github.com/mbstdio/streamline/blob/main/app.apk",
                "https://github.com/mbstdio/streamline-other/releases/download/v1/app.apk",
                "https://github.com.evil.example/mbstdio/streamline/releases/download/v1/app.apk",
                "http://github.com/mbstdio/streamline/releases/download/v1/app.apk",
                "https://github.com:8443/mbstdio/streamline/releases/download/v1/app.apk",
                "https://user:password@github.com/mbstdio/streamline/releases/download/v1/app.apk",
                "https://release-assets.githubusercontent.com/file.apk"}) {
            assertThrows(Exception.class, () -> UpdateCatalog.trustedUrl(url));
        }
    }
    private static final String GITHUB_APK = "https://github.com/mbstdio/streamline/releases/download/v0.2.3/streamline-0.2.3.apk";
    private static final String ASSET = "https://release-assets.githubusercontent.com/github-production-release-asset/123/file.apk?signature=test";

    private OkHttpClient redirectClient(String location, List<String> requests) {
        return new OkHttpClient.Builder().addInterceptor(chain -> {
            String url = chain.request().url().toString();
            requests.add(url);
            assertNull(chain.request().header("Authorization"));
            Response.Builder response = new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .message("test").body(ResponseBody.create("apk", okhttp3.MediaType.get("application/octet-stream")));
            if (url.equals(ASSET)) return response.code(200).build();
            return response.code(302).header("Location", location).build();
        }).build();
    }

    @Test public void downloadsGithubAssetThroughValidatedRedirect() throws Exception {
        List<String> requests = new ArrayList<>();
        try (Response response = UpdateCatalog.request(redirectClient(ASSET, requests), GITHUB_APK, call -> {})) {
            assertEquals("apk", response.body().string());
        }
        assertEquals(java.util.Arrays.asList(GITHUB_APK, ASSET), requests);
    }

    @Test public void downloadsDirectlyFromCdn() throws Exception {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> new Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create("apk", okhttp3.MediaType.get("application/octet-stream"))).build()).build();
        try (Response response = UpdateCatalog.request(client, "https://cdn.infiniteloop.ch/file.apk", call -> {})) {
            assertEquals("apk", response.body().string());
        }
    }

    @Test public void validatesRedirectsFromGithubAssetHostToo() {
        List<String> requests = new ArrayList<>();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            String url = chain.request().url().toString();
            requests.add(url);
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302)
                    .message("Redirect").body(ResponseBody.create("", okhttp3.MediaType.get("text/plain")))
                    .header("Location", url.equals(GITHUB_APK) ? ASSET : "https://evil.example/file.apk").build();
        }).build();
        assertThrows(IOException.class, () -> UpdateCatalog.request(client, GITHUB_APK, call -> {}));
        assertEquals(java.util.Arrays.asList(GITHUB_APK, ASSET), requests);
    }

    @Test public void rejectsUnsafeRedirectsBeforeContactingTheirTargets() {
        for (String target : new String[]{"https://evil.example/file.apk", "http://release-assets.githubusercontent.com/file.apk",
                "https://release-assets.githubusercontent.com.evil.example/file.apk",
                "https://release-assets.githubusercontent.com:8443/file.apk",
                "https://user:password@release-assets.githubusercontent.com/file.apk",
                "https://github.com/other/project/releases/download/v1/file.apk", "https://cdn.infiniteloop.ch/file.apk"}) {
            List<String> requests = new ArrayList<>();
            assertThrows(IOException.class, () -> UpdateCatalog.request(redirectClient(target, requests), GITHUB_APK, call -> {}));
            assertEquals(1, requests.size());
        }
    }

    @Test public void cdnRedirectsRemainForbidden() {
        List<String> requests = new ArrayList<>();
        assertThrows(IOException.class, () -> UpdateCatalog.request(redirectClient(ASSET, requests),
                "https://cdn.infiniteloop.ch/file.apk", call -> {}));
        assertEquals(1, requests.size());
    }

    @Test public void redirectLoopsAreBounded() {
        List<String> requests = new ArrayList<>();
        assertThrows(IOException.class, () -> UpdateCatalog.request(redirectClient(GITHUB_APK, requests), GITHUB_APK, call -> {}));
        assertEquals(6, requests.size());
    }
}
