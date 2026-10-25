package tv.streamline.app;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

/** Card rendering stays independent of pagination and directional navigation. */
final class CatalogCards {
    private final CatalogScreen screen;
    CatalogCards(CatalogScreen screen) { this.screen = screen; }

    void add(LinearLayout cards, JSONObject item, String key) {
        boolean game = key.equals("games") || key.startsWith("search:categories:");
        boolean channel = key.startsWith("search:channels:");
        boolean search = key.startsWith("search:");
        String id = game ? item.optString("id") : item.optString(search ? "broadcaster_login" : "user_login");
        if (id.isEmpty()) return;
        String name = item.optString(game ? "name" : search ? "display_name" : "user_name", id);
        String identity = key + ":" + id;
        LinearLayout card = screen.column();
        card.setTag(identity);
        card.setFocusable(true);
        card.setClickable(true);
        card.setPadding(screen.dp(channel ? 8 : 6), screen.dp(channel ? 8 : 6), screen.dp(channel ? 8 : 6), screen.dp(channel ? 8 : 6));
        if (channel) card.setGravity(Gravity.CENTER_HORIZONTAL);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, screen.surface(0xff42315d, 0xffd4bfff));
        background.addState(new int[]{}, channel ? new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT) : screen.surface(0xff252330, 0xff353140));
        card.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(screen.dp(game || channel ? 160 : 280), -2);
        params.setMargins(screen.dp(4), screen.dp(4), screen.dp(10), screen.dp(4));
        cards.addView(card, params);
        ImageView preview = new ImageView(screen);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (channel) {
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(0xff252330);
            preview.setBackground(circle);
        } else preview.setBackground(screen.surface(0xff17151e, 0xff17151e, 6));
        preview.setClipToOutline(true);
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(channel ? screen.dp(128) : -1, screen.dp(channel ? 128 : game ? 197 : 151));
        previewParams.bottomMargin = screen.dp(8);
        card.addView(preview, previewParams);
        String url = item.optString(game ? "box_art_url" : "thumbnail_url")
                .replace("{width}", game ? "300" : "560").replace("{height}", game ? "400" : "315");
        screen.loadThumbnail(preview, url, game || channel ? 1 : 2);
        TextView marquee;
        boolean live = !search || item.optBoolean("is_live");
        if (game || channel) {
            marquee = screen.text(name, 16, Screen.TEXT);
            if (channel) marquee.setGravity(Gravity.CENTER);
            card.addView(marquee, new LinearLayout.LayoutParams(-1, -2));
            card.setContentDescription(name);
        } else {
            TextView label = detail(live ? "● " + name : screen.getString(R.string.offline_channel, name), 15, live ? 0xffd4bfff : Screen.MUTED);
            card.addView(label);
            marquee = detail(item.optString("title"), 17, Screen.TEXT);
            card.addView(marquee);
            LinearLayout details = screen.row();
            LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(-1, -2);
            detailsParams.topMargin = Math.max(0, screen.dp(4) - 3);
            details.setLayoutParams(detailsParams);
            details.addView(detail(item.optString("game_name"), 14, Screen.MUTED), new LinearLayout.LayoutParams(0, -2, 1));
            if (item.has("viewer_count")) {
                ImageView icon = new ImageView(screen);
                icon.setImageResource(R.drawable.ri_viewer_line);
                icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                details.addView(icon, new LinearLayout.LayoutParams(screen.dp(16), screen.dp(16)));
                int count = item.optInt("viewer_count");
                String formatted = java.text.NumberFormat.getIntegerInstance(screen.getResources().getConfiguration().getLocales().get(0)).format(count);
                TextView viewers = detail(formatted, 14, Screen.MUTED);
                viewers.setContentDescription(screen.getResources().getQuantityString(R.plurals.viewers, count, formatted));
                details.addView(viewers);
            }
            card.addView(details);
            card.setContentDescription(name + ", " + item.optString("title") + ", " + item.optString("game_name"));
        }
        marquee.setSingleLine(true);
        marquee.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        marquee.setMarqueeRepeatLimit(-1);
        marquee.setHorizontallyScrolling(true);
        card.setOnFocusChangeListener((v, focused) -> {
            marquee.setSelected(focused);
            screen.streamCardFocus(card, !game && !channel && live ? url : "", focused);
            if (focused) screen.state.selectedCard = identity;
        });
        card.setOnClickListener(v -> {
            screen.state.selectedCard = identity;
            if (game) screen.openGame(id, name, search);
            else if (live) screen.host.showPlayer(id, name + " · " + item.optString("title"));
            else screen.toast(screen.getString(R.string.channel_offline));
        });
    }

    private TextView detail(String value, int size, int color) {
        TextView view = screen.text(value, size, color);
        view.setPadding(screen.dp(4), screen.dp(2), screen.dp(4), screen.dp(2));
        view.setIncludeFontPadding(false);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }
}
