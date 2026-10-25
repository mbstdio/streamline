package tv.streamline.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import okhttp3.HttpUrl;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/** Public release manifest; deliberately independent of Twitch authentication. */
final class UpdateCatalog {
    static final String URL = "https://cdn.infiniteloop.ch/streamline/update.json";
    private static final String VERSION = "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?";
    final List<Release> releases = new ArrayList<>();
    final Release stable;

    static final class Release {
        final String version, apk, checksum;
        final List<String> changelog = new ArrayList<>();

        Release(String key, JSONObject data) throws Exception {
            version = data.getString("version");
            if (!version.equals(key) || !version.matches(VERSION)) throw new IOException("Version de mise à jour invalide.");
            apk = data.getString("apk");
            trustedUrl(apk);
            checksum = data.optString("sha256");
            if (!checksum.isEmpty() && !checksum.matches("(?i)[0-9a-f]{64}")) throw new IOException("Empreinte APK invalide.");
            JSONArray entries = data.getJSONArray("changelog");
            for (int i = 0; i < entries.length(); i++) changelog.add(entries.getString(i));
        }
    }

    UpdateCatalog(JSONObject manifest) throws Exception {
        JSONObject versions = manifest.getJSONObject("versions");
        Iterator<String> keys = versions.keys();
        Release latest = null;
        String selected = manifest.getString("stable");
        while (keys.hasNext()) {
            String key = keys.next();
            Release release = new Release(key, versions.getJSONObject(key));
            releases.add(release);
            if (key.equals(selected)) latest = release;
        }
        if (latest == null || latest.version.contains("-")) throw new IOException("Dernière version stable introuvable.");
        releases.sort((a, b) -> compare(b.version, a.version));
        stable = latest;
    }

    static UpdateCatalog read(Response response) throws Exception {
        if (!response.isSuccessful() || response.body() == null) {
            throw new IOException("Serveur de mises à jour indisponible (HTTP " + response.code() + ").");
        }
        try (InputStream input = response.body().byteStream()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (bytes.size() + read > 512 * 1024) throw new IOException("Catalogue de mises à jour trop volumineux.");
                bytes.write(buffer, 0, read);
            }
            return new UpdateCatalog(new JSONObject(bytes.toString("UTF-8")));
        }
    }

    static HttpUrl trustedUrl(String value) throws IOException {
        HttpUrl url = HttpUrl.parse(value);
        if (url == null || !url.isHttps() || !url.host().equals("cdn.infiniteloop.ch")
                || url.port() != 443 || !url.username().isEmpty() || !url.password().isEmpty()) {
            throw new IOException("Adresse de téléchargement non autorisée.");
        }
        return url;
    }

    static int compare(String left, String right) {
        if (!left.matches(VERSION) || !right.matches(VERSION)) throw new IllegalArgumentException("Numéro de version non pris en charge.");
        String[] a = left.split("-", 2), b = right.split("-", 2);
        String[] ac = a[0].split("\\."), bc = b[0].split("\\.");
        for (int i = 0; i < 3; i++) {
            int order = new BigInteger(ac[i]).compareTo(new BigInteger(bc[i]));
            if (order != 0) return order;
        }
        if (a.length == 1 || b.length == 1) return Integer.compare(b.length, a.length);
        String[] ap = a[1].split("\\."), bp = b[1].split("\\.");
        for (int i = 0; i < Math.min(ap.length, bp.length); i++) {
            boolean an = ap[i].matches("[0-9]+"), bn = bp[i].matches("[0-9]+");
            int order = an && bn ? new BigInteger(ap[i]).compareTo(new BigInteger(bp[i]))
                    : an != bn ? (an ? -1 : 1) : ap[i].compareTo(bp[i]);
            if (order != 0) return order;
        }
        return Integer.compare(ap.length, bp.length);
    }
}
