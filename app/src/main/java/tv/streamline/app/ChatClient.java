package tv.streamline.app;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Read-only IRC client: deliberately has no method for sending a chat message. */
final class ChatClient {
    interface Credentials { String[] get() throws Exception; }
    interface Listener {
        void status(String value);
        void message(IrcMessage message);
    }

    private final OkHttpClient http;
    private final String channel;
    private final Credentials credentials;
    private final Listener listener;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean closed;
    private WebSocket socket;
    private int retries;

    ChatClient(OkHttpClient http, String channel, Credentials credentials, Listener listener) {
        this.http = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).pingInterval(30, TimeUnit.SECONDS).build();
        this.channel = TwitchApi.channel(channel);
        this.credentials = credentials;
        this.listener = listener;
    }

    void start() { worker.execute(this::connect); }

    private void connect() {
        if (closed) return;
        listener.status("Connexion au chat…");
        try {
            String[] auth = credentials.get();
            synchronized (this) {
                if (closed) return;
                socket = http.newWebSocket(new Request.Builder().url("wss://irc-ws.chat.twitch.tv:443").build(), new WebSocketListener() {
                    private String pending = "";
                    private boolean disconnected;

                    @Override public void onOpen(WebSocket ws, Response response) {
                        if (closed) { ws.close(1000, null); return; }
                        ws.send("PASS oauth:" + auth[1] + "\r\n");
                        ws.send("NICK " + auth[0] + "\r\n");
                        ws.send("CAP REQ :twitch.tv/tags twitch.tv/commands\r\n");
                        ws.send("JOIN #" + channel + "\r\n");
                    }

                    @Override public void onMessage(WebSocket ws, String text) {
                        if (closed) return;
                        pending += text;
                        if (pending.length() > 1024 * 1024) { ws.cancel(); retry(); return; }
                        int end;
                        while ((end = pending.indexOf('\n')) >= 0) {
                            String line = pending.substring(0, end).replace("\r", "");
                            pending = pending.substring(end + 1);
                            IrcMessage message = IrcMessage.parse(line);
                            switch (message.command) {
                                case "PING": ws.send("PONG :" + message.text + "\r\n"); break;
                                case "ROOMSTATE":
                                    retries = 0;
                                    listener.status("Chat · lecture seule");
                                    break;
                                case "PRIVMSG": case "CLEARCHAT": case "CLEARMSG": listener.message(message); break;
                                case "NOTICE":
                                    listener.status(message.text);
                                    if (message.text.toLowerCase(java.util.Locale.ROOT).contains("authentication failed")) {
                                        listener.status("Session du chat expirée. Reconnecte ton compte.");
                                        close();
                                    }
                                    break;
                                case "RECONNECT": ws.cancel(); retry(); break;
                                default: break;
                            }
                        }
                    }

                    private void retry() {
                        if (closed || disconnected) return;
                        disconnected = true;
                        scheduleRetry();
                    }

                    @Override public void onFailure(WebSocket ws, Throwable error, Response response) { retry(); }
                    @Override public void onClosing(WebSocket ws, int code, String reason) { ws.close(code, null); retry(); }
                    @Override public void onClosed(WebSocket ws, int code, String reason) { retry(); }
                });
            }
        } catch (Exception error) {
            if (!closed) scheduleRetry();
        }
    }

    private void scheduleRetry() {
        if (closed) return;
        int seconds = Math.min(30, 1 << Math.min(5, retries++));
        listener.status("Chat déconnecté · nouvel essai dans " + seconds + " s");
        try { worker.schedule(this::connect, seconds, TimeUnit.SECONDS); }
        catch (java.util.concurrent.RejectedExecutionException ignored) { /* Closed during retry. */ }
    }

    synchronized void close() {
        closed = true;
        worker.shutdownNow();
        if (socket != null) { socket.close(1000, null); socket.cancel(); socket = null; }
    }
}
