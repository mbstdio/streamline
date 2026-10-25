package tv.streamline.app;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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
        TextView title = heading(getString(R.string.settings_title));
        title.setTextSize(24);
        header.addView(title);
        root.addView(header);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout options = column();
        scroll.addView(options);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        addCategory(options, R.string.settings_general);
        Button autoLoad = button(getString(settings.getBoolean("auto_load_lists", false) ? R.string.enabled : R.string.disabled), () -> {});
        autoLoad.setOnClickListener(v -> {
            boolean enabled = !settings.getBoolean("auto_load_lists", false);
            settings.edit().putBoolean("auto_load_lists", enabled).apply();
            autoLoad.setText(enabled ? R.string.enabled : R.string.disabled);
        });
        addOption(options, R.string.auto_load_lists, autoLoad);
        Button backdrop = button(getString(settings.getBoolean("stream_backdrop", true) ? R.string.enabled : R.string.disabled), () -> {});
        backdrop.setOnClickListener(v -> {
            boolean enabled = !settings.getBoolean("stream_backdrop", true);
            settings.edit().putBoolean("stream_backdrop", enabled).apply();
            backdrop.setText(enabled ? R.string.enabled : R.string.disabled);
        });
        addOption(options, R.string.stream_backdrop, backdrop);

        addCategory(options, R.string.quality_video);
        Button quality = button(qualityLabels[preferredQualityIndex(settings)], () -> {});
        quality.setOnClickListener(v -> new AlertDialog.Builder(host).setTitle(R.string.preferred_quality)
                .setSingleChoiceItems(qualityLabels, preferredQualityIndex(settings), (dialog, which) -> {
                    settings.edit().putString("preferred_quality", QUALITY_VALUES[which]).apply();
                    quality.setText(qualityLabels[which]);
                    dialog.dismiss();
                }).setNegativeButton(R.string.cancel, null).show());
        addOption(options, R.string.preferred_quality, quality);

        addCategory(options, R.string.settings_chat);
        Button chat = button(getString(settings.getBoolean("chat_visible", true) ? R.string.enabled : R.string.disabled), () -> {});
        chat.setOnClickListener(v -> {
            boolean visible = !settings.getBoolean("chat_visible", true);
            settings.edit().putBoolean("chat_visible", visible).apply();
            chat.setText(visible ? R.string.enabled : R.string.disabled);
        });
        addOption(options, R.string.chat_default_visible, chat);
        addCategory(options, R.string.settings_updates);
        Button updates = button(getString(R.string.check_updates), () -> host.showUpdates(true));
        updates.setTextSize(14);
        options.addView(updates);
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            root.addView(text(getString(R.string.version, version), 12, MUTED));
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { /* Own package is installed. */ }
        autoLoad.requestFocus();
    }

    private void addCategory(LinearLayout options, int label) {
        TextView title = text(getString(label), 20, TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(4), options.getChildCount() == 0 ? dp(6) : dp(14), dp(4), dp(6));
        options.addView(title);
    }

    private void addOption(LinearLayout options, int label, Button control) {
        LinearLayout option = row();
        option.addView(text(getString(label), 16, MUTED), new LinearLayout.LayoutParams(0, -2, 1));
        control.setTextSize(14);
        option.addView(control);
        options.addView(option);
    }
}
