package tv.streamline.app;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

final class SettingsScreen extends Screen {
    static final String[] QUALITY_VALUES = {"best", "auto", "1080", "720", "480", "360", "160"};
    private final SharedPreferences settings;

    SettingsScreen(MainActivity host) { super(host); settings = host.settings; }

    static int preferredQualityIndex(SharedPreferences settings) {
        String value = settings.getString("preferred_quality", "best");
        for (int i = 0; i < QUALITY_VALUES.length; i++) if (QUALITY_VALUES[i].equals(value)) return i;
        return 0;
    }

    @Override void show() {
        base(true);
        String[] qualityLabels = {getString(R.string.quality_best), getString(R.string.quality_auto), "1080p", "720p", "480p", "360p", "160p"};
        LinearLayout header = row();
        header.addView(iconButton(R.drawable.ri_arrow_left_line, getString(R.string.back), host::showHome));
        header.addView(heading(getString(R.string.settings_title)));
        root.addView(header);
        root.addView(text(getString(R.string.preferred_quality), 20, TEXT));
        Button quality = button(qualityLabels[preferredQualityIndex(settings)], () -> {});
        quality.setOnClickListener(v -> new AlertDialog.Builder(host).setTitle(R.string.preferred_quality)
                .setSingleChoiceItems(qualityLabels, preferredQualityIndex(settings), (dialog, which) -> {
                    settings.edit().putString("preferred_quality", QUALITY_VALUES[which]).apply();
                    quality.setText(qualityLabels[which]);
                    dialog.dismiss();
                }).setNegativeButton(R.string.cancel, null).show());
        root.addView(quality);
        root.addView(text(getString(R.string.auto_load_lists), 20, TEXT));
        Button autoLoad = button(getString(settings.getBoolean("auto_load_lists", false) ? R.string.enabled : R.string.disabled), () -> {});
        autoLoad.setOnClickListener(v -> {
            boolean enabled = !settings.getBoolean("auto_load_lists", false);
            settings.edit().putBoolean("auto_load_lists", enabled).apply();
            autoLoad.setText(enabled ? R.string.enabled : R.string.disabled);
        });
        root.addView(autoLoad);
        root.addView(button(getString(R.string.check_updates), () -> host.showUpdates(true)));
        root.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            root.addView(text(getString(R.string.version, version), 14, MUTED));
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { /* Own package is installed. */ }
        quality.requestFocus();
    }
}
