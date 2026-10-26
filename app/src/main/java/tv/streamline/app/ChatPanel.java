package tv.streamline.app;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.LineHeightSpan;
import android.text.style.StyleSpan;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Owns the chat connection, moderation buffer and throttled rendering together. */
final class ChatPanel extends LinearLayout implements AutoCloseable {
    private final Screen screen;
    private final TextView status, text;
    private final ScrollView scroll;
    private final List<IrcMessage> messages = new ArrayList<>();
    private final ChatClient client;
    private boolean renderQueued, closed;
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
        scroll.setFocusable(false);
        scroll.setContentDescription(screen.getString(R.string.chat_accessibility));
        text = screen.text("", 14, Screen.TEXT);
        text.setIncludeFontPadding(false);
        scroll.addView(text);
        addView(scroll, new LayoutParams(-1, 0, 1));
        int request = screen.generation;
        client = new ChatClient(screen, screen.api.http, channel, () -> new String[]{screen.api.login(), screen.api.accessToken()}, new ChatClient.Listener() {
            @Override public void status(String value) { screen.ui(request, () -> { if (!closed) status.setText(value); }); }
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
        }
        if (renderQueued) return;
        renderQueued = true;
        screen.main.postDelayed(render, 200);
    }

    private void renderMessages() {
        SpannableStringBuilder content = new SpannableStringBuilder();
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
            content.append(body);
            int messageEnd = content.length();
            content.setSpan((LineHeightSpan) (value, lineStart, lineEnd, spanTop, lineTop, metrics) -> {
                if (lineEnd >= messageEnd) {
                    metrics.descent += screen.dp(3);
                    metrics.bottom += screen.dp(3);
                }
            }, start, messageEnd, 0);
        }
        text.setText(content);
        scroll.post(() -> { if (!closed) scroll.fullScroll(ScrollView.FOCUS_DOWN); });
    }

    @Override public void close() {
        closed = true;
        client.close();
        screen.main.removeCallbacks(render);
        messages.clear();
    }
}
