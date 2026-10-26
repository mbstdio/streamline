package tv.streamline.app;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.text.style.LineHeightSpan;
import android.text.style.StyleSpan;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Owns the chat connection, moderation buffer and throttled rendering together. */
final class ChatPanel extends LinearLayout implements AutoCloseable {
    private final Screen screen;
    private final TextView status, text;
    private final ScrollView scroll;
    private final List<IrcMessage> messages = new ArrayList<>();
    private final ChatClient client;
    private final ChatAnimations animations;
    private boolean renderQueued, closed, welcomeShown;
    private final Runnable render = () -> {
        renderQueued = false;
        if (!closed) renderMessages();
    };

    ChatPanel(Screen screen, String channel) {
        super(screen);
        this.screen = screen;
        setOrientation(VERTICAL);
        setPadding(screen.dp(10), screen.dp(4), screen.dp(12), screen.dp(8));
        setBackgroundColor(0xff1c1926);
        status = screen.text(screen.getString(R.string.chat_connecting), 14, Screen.MUTED);
        scroll = new ScrollView(screen);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        scroll.setContentDescription(screen.getString(R.string.chat_accessibility));
        text = screen.text("", 14, Screen.TEXT);
        text.setIncludeFontPadding(false);
        animations = screen.host.settings.getBoolean("animated_emotes", false)
                ? new ChatAnimations(screen, text, this::queueRender) : null;
        scroll.addView(text);
        addView(scroll, new LayoutParams(-1, 0, 1));
        int request = screen.generation;
        client = new ChatClient(screen, screen.api.http, channel, () -> new String[]{screen.api.login(), screen.api.accessToken()}, new ChatClient.Listener() {
            @Override public void status(String value) { screen.ui(request, () -> { if (!closed) status.setText(value); }); }
            @Override public void connected() {
                screen.ui(request, () -> {
                    if (closed || welcomeShown) return;
                    welcomeShown = true;
                    IrcMessage welcome = new IrcMessage();
                    welcome.prefix = "Streamline";
                    welcome.text = screen.getString(R.string.chat_welcome);
                    append(welcome);
                });
            }
            @Override public void message(IrcMessage value) { screen.ui(request, () -> { if (!closed) append(value); }); }
        });
        client.start();
    }

    void sessionExpired() { client.close(); status.setText(R.string.session_expired_short); }

    private void append(IrcMessage message) {
        if (message.command.equals("CLEARCHAT")) {
            if (message.text.isEmpty()) messages.clear();
            else messages.removeIf(item -> item.prefix.split("!", 2)[0].equalsIgnoreCase(message.text));
        } else if (message.command.equals("CLEARMSG")) {
            String id = message.tags.get("target-msg-id");
            if (id != null) messages.removeIf(item -> id.equals(item.tags.get("id")));
        } else {
            messages.add(message);
            if (messages.size() > 30) messages.remove(0);
            String emotes = message.tags.get("emotes");
            if (emotes != null) {
                for (String entry : emotes.split("/")) {
                    String id = entry.split(":", 2)[0];
                    if (!id.matches("[A-Za-z0-9_-]+")) continue;
                    screen.host.images.load(this, emoteUrl(id), 1, bitmap -> screen.main.post(() -> {
                        if (!closed) queueRender();
                    }));
                }
            }
        }
        queueRender();
    }

    private void queueRender() {
        if (renderQueued) return;
        renderQueued = true;
        screen.main.postDelayed(render, 200);
    }

    private void renderMessages() {
        SpannableStringBuilder content = new SpannableStringBuilder();
        Set<String> activeEmotes = new HashSet<>();
        for (IrcMessage item : messages) {
            if (content.length() > 0) content.append('\n');
            int start = content.length();
            content.append(item.name()).append(" : ");
            int color = 0xffc6afff;
            try {
                String raw = item.tags.get("color");
                if (raw != null && raw.matches("#[0-9A-Fa-f]{6}")) {
                    int candidate = Color.parseColor(raw);
                    if (Color.luminance(candidate) >= .25f) color = candidate;
                }
            } catch (IllegalArgumentException ignored) { }
            content.setSpan(new ForegroundColorSpan(color), start, content.length(), 0);
            content.setSpan(new StyleSpan(Typeface.BOLD), start, content.length(), 0);
            String body = item.text;
            if (body.startsWith("\u0001ACTION ") && body.endsWith("\u0001")) body = body.substring(8, body.length() - 1);
            int bodyStart = content.length();
            content.append(body);
            applyEmotes(content, bodyStart, body, item.tags.get("emotes"), activeEmotes);
            int messageEnd = content.length();
            content.setSpan((LineHeightSpan) (value, lineStart, lineEnd, spanTop, lineTop, metrics) -> {
                if (lineEnd >= messageEnd) {
                    metrics.descent += screen.dp(4);
                    metrics.bottom += screen.dp(4);
                }
            }, start, messageEnd, 0);
        }
        text.setText(content);
        if (animations != null) animations.retain(activeEmotes);
        scroll.post(() -> { if (!closed) scroll.fullScroll(ScrollView.FOCUS_DOWN); });
    }

    private static String emoteUrl(String id) {
        return "https://static-cdn.jtvnw.net/emoticons/v2/" + id + "/static/dark/2.0";
    }

    private void applyEmotes(SpannableStringBuilder content, int bodyStart, String body, String emotes, Set<String> activeEmotes) {
        if (emotes == null || emotes.isEmpty()) return;
        int length = body.codePointCount(0, body.length());
        for (String entry : emotes.split("/")) {
            String[] parts = entry.split(":", 2);
            if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9_-]+")) continue;
            activeEmotes.add(parts[0]);
            Drawable image = animations == null ? null : animations.get(parts[0]);
            if (image == null) {
                Bitmap bitmap = screen.host.images.cached(emoteUrl(parts[0]), 1);
                if (bitmap == null) continue;
                image = new BitmapDrawable(getResources(), bitmap);
            }
            for (String range : parts[1].split(",")) {
                String[] bounds = range.split("-", -1);
                if (bounds.length != 2) continue;
                try {
                    int first = Integer.parseInt(bounds[0]), last = Integer.parseInt(bounds[1]);
                    if (first < 0 || last < first || last >= length) continue;
                    int start = bodyStart + body.offsetByCodePoints(0, first);
                    int end = bodyStart + body.offsetByCodePoints(0, last + 1);
                    int height = Math.round(text.getTextSize() * 1.4f);
                    int width = Math.max(1, Math.round(height * (float) image.getIntrinsicWidth() / Math.max(1, image.getIntrinsicHeight())));
                    image.setBounds(0, 0, width, height);
                    content.setSpan(new ImageSpan(image, ImageSpan.ALIGN_BASELINE), start, end, 0);
                } catch (NumberFormatException ignored) { /* Keep text for malformed emote ranges. */ }
            }
        }
    }

    @Override protected void onVisibilityChanged(android.view.View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (animations != null) animations.setVisible(isShown() && getWindowVisibility() == VISIBLE);
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (animations != null) animations.setVisible(isShown() && visibility == VISIBLE);
    }

    @Override public void close() {
        closed = true;
        client.close();
        if (animations != null) animations.close();
        screen.host.images.cancel(this);
        screen.main.removeCallbacks(render);
        messages.clear();
    }
}
