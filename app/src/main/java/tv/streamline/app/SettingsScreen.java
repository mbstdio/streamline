package tv.streamline.app;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

final class SettingsScreen extends Screen {
    static final String[] QUALITY_VALUES = {"best", "auto", "1080", "720", "480", "360", "160"};
    private static final String[] QUALITY_LABELS = {"Toujours la meilleure", "Automatique", "1080p", "720p", "480p", "360p", "160p"};
    private final SharedPreferences settings;

    SettingsScreen(MainActivity host) { super(host); settings = host.settings; }

    static int preferredQualityIndex(SharedPreferences settings) {
        String value = settings.getString("preferred_quality", "best");
        for (int i = 0; i < QUALITY_VALUES.length; i++) if (QUALITY_VALUES[i].equals(value)) return i;
        return 0;
    }

    @Override void show() {
        base(true);
        LinearLayout header = row();
        header.addView(iconButton(R.drawable.ri_arrow_left_line, "Retour", host::showHome));
        header.addView(heading("Streamline · Réglages"));
        root.addView(header);
        root.addView(text("Qualité préférée", 20, TEXT));
        Button quality = button(QUALITY_LABELS[preferredQualityIndex(settings)], () -> {});
        quality.setOnClickListener(v -> new AlertDialog.Builder(host).setTitle("Qualité préférée")
                .setSingleChoiceItems(QUALITY_LABELS, preferredQualityIndex(settings), (dialog, which) -> {
                    settings.edit().putString("preferred_quality", QUALITY_VALUES[which]).apply();
                    quality.setText(QUALITY_LABELS[which]);
                    dialog.dismiss();
                }).setNegativeButton("Annuler", null).show());
        root.addView(quality);
        root.addView(text("Chargement automatique des listes", 20, TEXT));
        Button autoLoad = button(settings.getBoolean("auto_load_lists", false) ? "Activé" : "Désactivé", () -> {});
        autoLoad.setOnClickListener(v -> {
            boolean enabled = !settings.getBoolean("auto_load_lists", false);
            settings.edit().putBoolean("auto_load_lists", enabled).apply();
            autoLoad.setText(enabled ? "Activé" : "Désactivé");
        });
        root.addView(autoLoad);
        root.addView(button("Vérifier les mises à jour", () -> host.showUpdates(true)));
        root.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            root.addView(text("Version " + version, 14, MUTED));
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { /* Own package is installed. */ }
        quality.requestFocus();
    }
}
