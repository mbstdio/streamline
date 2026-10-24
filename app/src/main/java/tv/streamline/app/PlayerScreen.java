package tv.streamline.app;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerView;
import java.util.List;
import org.json.JSONObject;

@androidx.media3.common.util.UnstableApi
final class PlayerScreen extends Screen {
    final String channel, title;
    private final JSONObject initialStream;
    final PlaybackController playback = new PlaybackController();
    private PlayerView playerView;
    private LinearLayout playerControls, playerHeading;
    private TextView playbackStatus, playerName, playerStreamTitle, playerCategory;
    private ImageView channelAvatar;
    private Button viewerButton;
    private ImageButton playButton, muteButton, chatButton;
    private ChatPanel chatPanel;
    private boolean chatVisible, consumeCenterUp;
    private long controlsHiddenAt;

    PlayerScreen(MainActivity host, String channel, String title, boolean chatVisible, JSONObject initialStream) {
        super(host);
        this.channel = channel; this.title = title; this.chatVisible = chatVisible;
        this.initialStream = initialStream;
    }

    boolean chatVisible() { return chatVisible; }

    @Override void show() {
        base(false);
        host.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout body = row();
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        FrameLayout video = new FrameLayout(this);
        playerView = new PlayerView(this);
        playerView.setKeepContentOnPlayerReset(false);
        playerView.setUseController(false);
        playerView.setShowNextButton(false);
        playerView.setShowPreviousButton(false);
        playerView.setShowFastForwardButton(false);
        playerView.setShowRewindButton(false);
        playerView.setFocusable(true);
        video.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        playbackStatus = text("Récupération du flux Twitch…", 18, TEXT);
        playbackStatus.setGravity(Gravity.CENTER);
        playbackStatus.setBackgroundColor(0xb0101018);
        video.addView(playbackStatus, new FrameLayout.LayoutParams(-1, -1));
        playerHeading = row();
        playerHeading.setPadding(dp(20), dp(12), dp(20), dp(12));
        playerHeading.setBackgroundColor(0xcc101018);
        FrameLayout avatarFrame = new FrameLayout(this);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        avatarParams.rightMargin = dp(12);
        playerHeading.addView(avatarFrame, avatarParams);
        channelAvatar = new ImageView(this);
        channelAvatar.setImageResource(R.drawable.ri_user_line);
        channelAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xff594083);
        channelAvatar.setBackground(circle);
        channelAvatar.setClipToOutline(true);
        avatarFrame.addView(channelAvatar, new FrameLayout.LayoutParams(-1, -1));
        ImageButton back = iconButton(R.drawable.ri_arrow_left_line, "Retour au catalogue", host::showHome);
        StateListDrawable backImage = new StateListDrawable();
        backImage.addState(new int[]{android.R.attr.state_focused}, getDrawable(R.drawable.ri_arrow_left_line));
        backImage.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        back.setImageDrawable(backImage);
        StateListDrawable backSurface = new StateListDrawable();
        backSurface.addState(new int[]{android.R.attr.state_focused}, surface(0xcc101018, 0xffd4bfff));
        backSurface.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        back.setBackground(backSurface);
        avatarFrame.addView(back, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout metadata = column();
        playerName = text(channel, 18, TEXT);
        playerName.setTypeface(null, Typeface.BOLD);
        playerStreamTitle = text(title.contains(" · ") ? title.substring(title.indexOf(" · ") + 3) : title, 15, TEXT);
        playerCategory = text("", 13, MUTED);
        for (TextView line : new TextView[]{playerName, playerStreamTitle, playerCategory}) {
            line.setSingleLine(true);
            line.setEllipsize(android.text.TextUtils.TruncateAt.END);
            line.setPadding(0, dp(2), dp(8), dp(2));
            line.setIncludeFontPadding(false);
            metadata.addView(line);
        }
        playerCategory.setVisibility(View.GONE);
        playerHeading.addView(metadata, new LinearLayout.LayoutParams(0, -2, 1));
        viewerButton = button("—", this::loadMetadata);
        viewerButton.setTextColor(0xffff8280);
        viewerButton.setTextSize(14);
        viewerButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ri_group_line, 0, 0, 0);
        viewerButton.setCompoundDrawablePadding(dp(6));
        viewerButton.setContentDescription("Actualiser le nombre de spectateurs");
        playerHeading.addView(viewerButton);
        if (initialStream != null) {
            playerName.setText(initialStream.optString("user_name", initialStream.optString("display_name", channel)));
            applyStream(initialStream);
        }
        video.addView(playerHeading, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        playerControls = row();
        playerControls.setPadding(dp(14), dp(10), dp(14), dp(10));
        playerControls.setBackgroundColor(0xe6101018);
        playButton = iconButton(R.drawable.ri_pause_fill, "Pause", playback::togglePlay);
        playerControls.addView(playButton);
        playerControls.addView(iconButton(R.drawable.ri_refresh_line, "Recharger le flux", () -> host.showPlayer(channel, title)));
        muteButton = iconButton(R.drawable.ri_volume_up_line, "Couper le son", () -> {
            playback.toggleMute();
            controlIcon(muteButton, playback.muted() ? R.drawable.ri_volume_mute_line : R.drawable.ri_volume_up_line,
                    playback.muted() ? "Activer le son" : "Couper le son");
        });
        playerControls.addView(muteButton);
        playerControls.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        playerControls.addView(iconButton(R.drawable.ri_hd_line, "Qualité du direct", this::showQuality));
        chatButton = iconButton(chatVisible ? R.drawable.ri_chat_off_line : R.drawable.ri_chat_line,
                chatVisible ? "Masquer le chat" : "Afficher le chat", () -> {
                    chatVisible = !chatVisible;
                    chatPanel.setVisibility(chatVisible ? View.VISIBLE : View.GONE);
                    controlIcon(chatButton, chatVisible ? R.drawable.ri_chat_off_line : R.drawable.ri_chat_line,
                            chatVisible ? "Masquer le chat" : "Afficher le chat");
                });
        playerControls.addView(chatButton);
        video.addView(playerControls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        playerControls.setVisibility(View.GONE);
        playerHeading.setVisibility(View.GONE);
        controlsHiddenAt = SystemClock.elapsedRealtime();
        consumeCenterUp = false;
        body.addView(video, new LinearLayout.LayoutParams(0, -1, 1));
        chatPanel = new ChatPanel(this, channel);
        body.addView(chatPanel, new LinearLayout.LayoutParams(dp(275), -1));
        chatPanel.setVisibility(chatVisible ? View.VISIBLE : View.GONE);
        playerView.requestFocus();
        int request = generation;
        worker.execute(() -> {
            try {
                String playlist = api.playlist(channel);
                ui(request, () -> startPlayback(playlist, request));
            } catch (Exception failure) { ui(request, () -> playbackStatus.setText(friendly(failure) + "\nAppuie sur OK pour afficher Recharger, ou Retour pour quitter.")); }
        });
        if (api.signedIn()) loadMetadata();
    }

    private void applyStream(JSONObject stream) {
        playerStreamTitle.setText(stream.optString("title", title));
        String category = stream.optString("game_name");
        playerCategory.setText(category);
        playerCategory.setVisibility(category.isEmpty() ? View.GONE : View.VISIBLE);
        if (stream.has("viewer_count")) viewerButton.setText(java.text.NumberFormat.getIntegerInstance(java.util.Locale.FRANCE).format(stream.optInt("viewer_count")));
    }

    private void loadMetadata() {
        int request = generation;
        worker.execute(() -> {
            try {
                List<JSONObject> users = TwitchApi.items(api.helix("users", "login", channel));
                if (!users.isEmpty()) ui(request, () -> {
                    JSONObject user = users.get(0);
                    playerName.setText(user.optString("display_name", channel));
                    channelAvatar.setContentDescription("Photo de profil de " + user.optString("display_name", channel));
                    loadThumbnail(channelAvatar, user.optString("profile_image_url"));
                });
                List<JSONObject> streams = TwitchApi.items(api.helix("streams", "user_login", channel));
                ui(request, () -> { if (streams.isEmpty()) viewerButton.setText("Hors ligne"); else applyStream(streams.get(0)); });
            } catch (Exception ignored) { /* Keep the metadata supplied by the catalog. */ }
        });
    }

    private void startPlayback(String playlist, int request) {
        if (!host.isForeground()) return;
        String preferred = SettingsScreen.QUALITY_VALUES[SettingsScreen.preferredQualityIndex(host.settings)];
        playback.start(this, playerView, playlist, preferred, new Player.Listener() {
            @Override public void onPlayWhenReadyChanged(boolean ready, int reason) {
                if (request == generation) controlIcon(playButton, ready ? R.drawable.ri_pause_fill : R.drawable.ri_play_fill, ready ? "Pause" : "Lire");
            }
            @Override public void onPlaybackStateChanged(int state) {
                if (request != generation) return;
                if (state == Player.STATE_READY) playbackStatus.setVisibility(View.GONE);
                else if (state == Player.STATE_BUFFERING || state == Player.STATE_ENDED) {
                    playbackStatus.setText(state == Player.STATE_BUFFERING ? "Chargement du direct…" : "Le direct est terminé.");
                    playbackStatus.setVisibility(View.VISIBLE);
                }
            }
            @Override public void onPlayerError(PlaybackException error) {
                if (request != generation) return;
                playbackStatus.setText("Lecture impossible : " + error.getErrorCodeName()
                        + "\nLa chaîne est peut-être hors ligne, restreinte ou le flux a expiré.\nAppuie sur OK pour recharger ou choisir une autre qualité.");
                playbackStatus.setVisibility(View.VISIBLE);
            }
        });
    }

    void showQuality() {
        if (playback.player() == null) { toast("Le lecteur n’est pas encore prêt."); return; }
        List<PlaybackController.Quality> choices = playback.qualities();
        String[] labels = new String[choices.size()];
        for (int i = 0; i < choices.size(); i++) labels[i] = choices.get(i).label;
        new AlertDialog.Builder(host).setTitle("Qualité du direct")
                .setItems(labels, (dialog, which) -> playback.selectQuality(choices.get(which)))
                .setNegativeButton("Fermer", null).show();
    }

    void sessionExpired() { if (chatPanel != null) chatPanel.sessionExpired(); }

    @Override void leave() {
        super.leave();
        if (chatPanel != null) { chatPanel.close(); chatPanel = null; }
        playback.close();
        playerView = null;
        playerControls = null;
        playerHeading = null;
        host.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override boolean keyEvent(KeyEvent event) {
        if (playerControls == null) return false;
        int key = event.getKeyCode();
        boolean center = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (center && consumeCenterUp) {
            if (event.getAction() == KeyEvent.ACTION_UP) consumeCenterUp = false;
            return true;
        }
        if (playerControls.getVisibility() != View.VISIBLE) {
            if (center) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    playerControls.setVisibility(View.VISIBLE);
                    playerHeading.setVisibility(View.VISIBLE);
                    playButton.requestFocus();
                    consumeCenterUp = true;
                }
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) return true;
        }
        if (playback.player() != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                if (playback.player().isPlaying()) playback.pause(); else playback.play();
                return true;
            }
            if (key == KeyEvent.KEYCODE_MEDIA_PLAY) { playback.play(); return true; }
            if (key == KeyEvent.KEYCODE_MEDIA_PAUSE) { playback.pause(); return true; }
        }
        return false;
    }

    @Override boolean back() {
        if (playerControls == null) return false;
        if (playerControls.getVisibility() == View.VISIBLE) {
            playerControls.setVisibility(View.GONE);
            playerHeading.setVisibility(View.GONE);
            controlsHiddenAt = SystemClock.elapsedRealtime();
            playerView.requestFocus();
            return true;
        }
        return SystemClock.elapsedRealtime() - controlsHiddenAt < 1000;
    }
}
