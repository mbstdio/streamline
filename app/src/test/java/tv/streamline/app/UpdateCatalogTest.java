package tv.streamline.app;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public class UpdateCatalogTest {
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
                "https://user:password@cdn.infiniteloop.ch/app.apk"}) {
            assertThrows(Exception.class, () -> UpdateCatalog.trustedUrl(url));
        }
    }
}
