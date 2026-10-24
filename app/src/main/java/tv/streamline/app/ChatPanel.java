package tv.streamline.app;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
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
        status = screen.text("Connexion au chat…", 14, Screen.MUTED);
        scroll = new ScrollView(screen);
        scroll.setFocusable(false);
        scroll.setContentDescription("Messages du chat, lecture seule");
        text = screen.text("", 16, Screen.TEXT);
        scroll.addView(text);
        addView(scroll, new LayoutParams(-1, 0, 1));
        int request = screen.generation;
        client = new ChatClient(screen.api.http, channel, () -> new String[]{screen.api.login(), screen.api.accessToken()}, new ChatClient.Listener() {
            @Override public void status(String value) { screen.ui(request, () -> { if (!closed) status.setText(value); }); }
            @Override public void message(IrcMessage value) { screen.ui(request, () -> { if (!closed) append(value); }); }
        });
        client.start();
    }

    void sessionExpired() { client.close(); status.setText("Session expirée · reconnecte ton compte"); }

    private void append(IrcMessage message) {
        if (message.command.equals("CLEARCHAT")) {
            if (message.text.isEmpty()) messages.clear();
            else messages.removeIf(item -> item.prefix.split("!", 2)[0].equalsIgnoreCase(message.text));
        } else if (message.command.equals("CLEARMSG")) {
            String id = message.tags.get("target-msg-id");
            if (id != null) messages.removeIf(item -> id.equals(item.tags.get("id")));
        } else {
            messages.add(message);
            if (messages.size() > 150) messages.remove(0);
        }
        if (renderQueued) return;
        renderQueued = true;
        screen.main.postDelayed(render, 200);
    }

    private void renderMessages() {
        boolean atBottom = text.getHeight() - scroll.getHeight() - scroll.getScrollY() < screen.dp(70);
        SpannableStringBuilder content = new SpannableStringBuilder();
        for (IrcMessage item : messages) {
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
            content.append(body).append("\n\n");
        }
        text.setText(content);
        if (atBottom) scroll.post(() -> { if (!closed) scroll.scrollTo(0, text.getHeight()); });
    }

    @Override public void close() {
        closed = true;
        client.close();
        screen.main.removeCallbacks(render);
        messages.clear();
    }
}
