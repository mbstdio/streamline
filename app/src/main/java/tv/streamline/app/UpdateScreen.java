package tv.streamline.app;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Response;

final class UpdateScreen extends Screen {
    private static final long MAX_APK_BYTES = 150L * 1024 * 1024;
    private final ExecutorService downloads = Executors.newSingleThreadExecutor();
    private final OkHttpClient http;
    private volatile Call call;
    private PackageInfo installed;
    private TextView status;
    private Button action, retry;
    private LinearLayout history;
    private UpdateCatalog.Release latest;
    private File downloaded;
    private final boolean checkNow;

    UpdateScreen(MainActivity host, boolean checkNow) {
        super(host);
        this.checkNow = checkNow;
        // Redirects are checked individually by UpdateCatalog.request.
        http = api.http.newBuilder().callTimeout(5, TimeUnit.MINUTES).build();
    }

    @Override void show() {
        base(true);
        LinearLayout header = row();
        header.addView(iconButton(R.drawable.ri_arrow_left_line, getString(R.string.back_settings), host::showSettings));
        header.addView(heading(getString(R.string.updates)));
        root.addView(header);
        try {
            installed = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        } catch (PackageManager.NameNotFoundException failure) { throw new IllegalStateException(failure); }
        root.addView(text(getString(R.string.installed_version, installed.versionName), 16, MUTED));
        status = text(getString(R.string.updates_searching), 18, TEXT);
        root.addView(status);
        action = button(getString(R.string.download_latest), this::downloadOrInstall);
        action.setVisibility(android.view.View.GONE);
        root.addView(action);
        retry = button(getString(R.string.refresh), this::load);
        root.addView(retry);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        history = column();
        scroll.addView(history);
        LinearLayout.LayoutParams historyParams = new LinearLayout.LayoutParams(-1, 0, 1);
        historyParams.topMargin = dp(6);
        root.addView(scroll, historyParams);
        retry.requestFocus();
        if (!checkNow && host.updates != null) render(host.updates);
        else load();
    }

    private Response request(String url) throws Exception {
        return UpdateCatalog.request(http, url, active -> {
            call = active;
            if (downloads.isShutdown()) active.cancel();
        });
    }

    private void load() {
        int request = generation;
        retry.setEnabled(false);
        action.setVisibility(android.view.View.GONE);
        history.removeAllViews();
        downloaded = null;
        status.setText(R.string.updates_searching);
        downloads.execute(() -> {
            try (Response response = request(UpdateCatalog.URL)) {
                UpdateCatalog catalog = UpdateCatalog.read(response);
                ui(request, () -> { host.setUpdates(catalog); render(catalog); });
            } catch (Exception failure) { ui(request, () -> failed(failure)); }
        });
    }

    private void render(UpdateCatalog catalog) {
        latest = catalog.stable;
        boolean newer = UpdateCatalog.compare(latest.version, installed.versionName) > 0;
        status.setText(newer ? getString(R.string.update_available_version, latest.version) : getString(R.string.up_to_date));
        action.setText(getString(R.string.download_install_version, latest.version));
        action.setVisibility(newer ? android.view.View.VISIBLE : android.view.View.GONE);
        retry.setEnabled(true);
        for (UpdateCatalog.Release release : catalog.releases) {
            int order = UpdateCatalog.compare(release.version, installed.versionName);
            String label = getString(order == 0 ? R.string.release_installed : order > 0 ? R.string.release_newer : R.string.release_older, release.version);
            if (release == latest) label = getString(R.string.release_stable, label);
            history.addView(text(label, 20, TEXT));
            for (String entry : release.changelog) history.addView(text("• " + entry, 16, MUTED));
        }
        // ScrollView can then scroll long changelogs using the remote, without focusing every line.
        history.setFocusable(true);
        if (newer) action.requestFocus();
    }

    private void downloadOrInstall() {
        if (downloaded != null && downloaded.isFile()) { install(); return; }
        int request = generation;
        UpdateCatalog.Release release = latest;
        action.setEnabled(false);
        retry.setEnabled(false);
        status.setText(getString(R.string.downloading_version, release.version));
        downloads.execute(() -> {
            File partial = null;
            try {
                File directory = new File(getCacheDir(), "updates");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException(getString(R.string.error_download_prepare));
                // Each screen owns its partial file, even if the user leaves and immediately returns.
                partial = File.createTempFile("download-", ".part", directory);
                File apk = new File(directory, "streamline-update.apk");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (Response response = request(release.apk); InputStream input = response.body().byteStream();
                        FileOutputStream output = new FileOutputStream(partial)) {
                    long length = response.body().contentLength();
                    if (length > MAX_APK_BYTES) throw new IOException(getString(R.string.error_apk_size));
                    byte[] buffer = new byte[32768];
                    long total = 0, lastProgress = 0;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException(getString(R.string.error_download_cancelled));
                        total += read;
                        if (total > MAX_APK_BYTES) throw new IOException(getString(R.string.error_apk_size));
                        output.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - lastProgress > 300) {
                            String progress = length > 0 ? getString(R.string.download_percent, total * 100 / length) : getString(R.string.download_kilobytes, total / 1024);
                            ui(request, () -> status.setText(getString(R.string.download_progress, release.version, progress)));
                            lastProgress = now;
                        }
                    }
                    if (total == 0 || (length >= 0 && total != length)) throw new IOException(getString(R.string.error_download_incomplete));
                }
                if (!release.checksum.isEmpty() && !release.checksum.equalsIgnoreCase(hex(digest.digest()))) {
                    throw new IOException(getString(R.string.error_apk_checksum));
                }
                verifyApk(partial, release);
                if (Thread.currentThread().isInterrupted()) throw new IOException(getString(R.string.error_download_cancelled));
                if (!partial.renameTo(apk)) throw new IOException(getString(R.string.error_apk_save));
                ui(request, () -> {
                    downloaded = apk;
                    action.setEnabled(true);
                    action.setText(getString(R.string.install_version, release.version));
                    action.requestFocus();
                    retry.setEnabled(true);
                    status.setText(R.string.download_complete);
                    install();
                });
            } catch (Exception failure) { ui(request, () -> failed(failure)); }
            finally { if (partial != null && partial.exists()) partial.delete(); }
        });
    }

    private void verifyApk(File apk, UpdateCatalog.Release release) throws Exception {
        PackageInfo archive = getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (archive == null || !getPackageName().equals(archive.packageName)
                || !release.version.equals(archive.versionName) || archive.getLongVersionCode() <= installed.getLongVersionCode()) {
            throw new IOException(getString(R.string.error_apk_mismatch));
        }
        if (archive.signingInfo == null || installed.signingInfo == null) throw new IOException(getString(R.string.error_apk_signature_missing));
        android.content.pm.Signature[] expected = installed.signingInfo.getApkContentsSigners();
        android.content.pm.Signature[] actual = archive.signingInfo.getApkContentsSigners();
        if (expected.length != actual.length || !new java.util.HashSet<>(Arrays.asList(expected))
                .equals(new java.util.HashSet<>(Arrays.asList(actual)))) {
            throw new IOException(getString(R.string.error_apk_signature));
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }

    private void install() {
        try {
            if (!getPackageManager().canRequestPackageInstalls()) {
                status.setText(R.string.install_permission);
                host.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                return;
            }
            Uri uri = FileProvider.getUriForFile(host, getPackageName() + ".updates", downloaded);
            host.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (android.content.ActivityNotFoundException failure) {
            status.setText(R.string.error_installer_unavailable);
        } catch (Exception failure) { status.setText(R.string.error_installer_open); }
    }

    private void failed(Exception failure) {
        status.setText(failure instanceof java.net.UnknownHostException ? getString(R.string.error_no_internet_retry)
                : failure instanceof java.io.InterruptedIOException ? getString(R.string.error_download_timeout)
                : failure.getMessage() == null ? getString(R.string.error_update_fetch) : friendly(failure));
        action.setEnabled(true);
        retry.setEnabled(true);
    }

    @Override boolean back() { host.showSettings(); return true; }

    @Override void leave() {
        super.leave();
        Call active = call;
        if (active != null) active.cancel();
        downloads.shutdownNow();
    }
}
