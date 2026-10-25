package tv.streamline.app;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.ui.PlayerView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Owns the Media3 instance and track-selection rules, but no screen navigation. */
@androidx.media3.common.util.UnstableApi
final class PlaybackController implements AutoCloseable {
    static final class Quality {
        final String label;
        private final TrackSelectionOverride override;
        private final boolean audioOnly;
        Quality(String label, TrackSelectionOverride override, boolean audioOnly) {
            this.label = label; this.override = override; this.audioOnly = audioOnly;
        }
    }

    private ExoPlayer player;
    private PlayerView view;

    ExoPlayer player() { return player; }

    void start(Context context, PlayerView view, String playlist, String preferredQuality, Player.Listener listener) {
        close();
        this.view = view;
        Map<String, String> headers = new HashMap<>();
        headers.put("Origin", "https://www.twitch.tv");
        headers.put("Referer", "https://www.twitch.tv/");
        DefaultHttpDataSource.Factory source = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 Streamline/0.1")
                .setConnectTimeoutMs(15000).setReadTimeoutMs(20000).setDefaultRequestProperties(headers);
        player = new ExoPlayer.Builder(context).build();
        view.setPlayer(player);
        player.addListener(listener);
        player.addListener(new Player.Listener() {
            private boolean preferredApplied;
            @Override public void onTracksChanged(Tracks tracks) {
                if (preferredApplied || !tracks.containsType(C.TRACK_TYPE_VIDEO)) return;
                preferredApplied = true;
                applyPreferredQuality(tracks, preferredQuality);
            }
        });
        player.setMediaSource(new HlsMediaSource.Factory(source).createMediaSource(MediaItem.fromUri(playlist)));
        player.prepare();
        player.play();
    }

    void togglePlay() {
        if (player == null) return;
        if (player.getPlayWhenReady()) player.pause(); else player.play();
    }

    void play() { if (player != null) player.play(); }
    void pause() { if (player != null) player.pause(); }
    boolean muted() { return player != null && player.getVolume() == 0; }
    void toggleMute() { if (player != null) player.setVolume(muted() ? 1 : 0); }

    List<Quality> qualities(Context context) {
        List<Quality> choices = new ArrayList<>();
        choices.add(new Quality(context.getString(R.string.quality_auto_recommended), null, false));
        if (player == null) return choices;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int index = 0; index < group.length; index++) {
                if (!group.isTrackSupported(index)) continue;
                Format format = group.getTrackFormat(index);
                choices.add(new Quality((format.height > 0 ? format.height + "p" : context.getString(R.string.quality_video))
                        + (format.frameRate > 0 ? " · " + Math.round(format.frameRate) + " fps" : "")
                        + (format.codecs != null ? " · " + format.codecs : ""),
                        new TrackSelectionOverride(group.getMediaTrackGroup(), index), false));
            }
        }
        choices.add(new Quality(context.getString(R.string.quality_audio_only), null, true));
        return choices;
    }

    void selectQuality(Quality quality) {
        if (player == null) return;
        androidx.media3.common.TrackSelectionParameters.Builder params = player.getTrackSelectionParameters()
                .buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, quality.audioOnly);
        if (quality.override != null) params.setOverrideForType(quality.override);
        player.setTrackSelectionParameters(params.build());
    }

    private void applyPreferredQuality(Tracks tracks, String preference) {
        if (preference.equals("auto") || player == null) return;
        int target = preference.equals("best") ? -1 : Integer.parseInt(preference);
        Format best = null;
        TrackSelectionOverride choice = null;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int index = 0; index < group.length; index++) {
                if (!group.isTrackSupported(index)) continue;
                Format format = group.getTrackFormat(index);
                if (target > 0 && format.height != target) continue;
                if (best == null || format.height > best.height || (format.height == best.height && format.frameRate > best.frameRate)) {
                    best = format;
                    choice = new TrackSelectionOverride(group.getMediaTrackGroup(), index);
                }
            }
        }
        if (choice != null) player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO).setOverrideForType(choice).build());
    }

    @Override public void close() {
        if (view != null) { view.setPlayer(null); view = null; }
        if (player != null) { player.release(); player = null; }
    }
}
