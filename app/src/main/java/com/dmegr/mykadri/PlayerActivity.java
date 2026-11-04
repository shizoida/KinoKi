package com.dmegr.mykadri;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import java.util.Locale;

/** Full-screen player for a single stream URL. Back returns to the list. */
public class PlayerActivity extends Activity {

    static final String EXTRA_URL = "url";
    static final String EXTRA_TITLE = "title";

    private static final long SAVE_INTERVAL_MS = 15_000;
    /** Resume slightly before where playback stopped, so the user catches the sentence they left in the middle of. */
    private static final long REWIND_MS = 5_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable saveProgress = new Runnable() {
        @Override
        public void run() {
            saveCurrentPosition();
            handler.postDelayed(this, SAVE_INTERVAL_MS);
        }
    };

    private PlayerView playerView;
    private ExoPlayer player;
    private String url;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);
        playerView = findViewById(R.id.player);
        url = getIntent().getStringExtra(EXTRA_URL);
        setTitle(getIntent().getStringExtra(EXTRA_TITLE));
    }

    @Override
    protected void onStart() {
        super.onStart();

        OkHttpDataSource.Factory dataSource = new OkHttpDataSource.Factory(Site.client())
                .setUserAgent(Site.USER_AGENT);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSource))
                .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Toast.makeText(PlayerActivity.this, R.string.playback_failed, Toast.LENGTH_LONG).show();
            }
        });
        playerView.setPlayer(player);
        playerView.requestFocus();

        MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(url));
        if (url.contains(".m3u8")) {
            item.setMimeType(MimeTypes.APPLICATION_M3U8);
        }
        long saved = Progress.get(this, url);
        if (saved > 0) {
            player.setMediaItem(item.build(), Math.max(0, saved - REWIND_MS));
            Toast.makeText(this, getString(R.string.resuming, formatTime(saved)), Toast.LENGTH_SHORT).show();
        } else {
            player.setMediaItem(item.build());
        }
        player.setPlayWhenReady(true);
        player.prepare();
        handler.postDelayed(saveProgress, SAVE_INTERVAL_MS);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(saveProgress);
        saveCurrentPosition();
        playerView.setPlayer(null);
        player.release();
        player = null;
    }

    private void saveCurrentPosition() {
        // After a failed load the player is idle and reports position 0; don't let that erase the saved spot.
        if (player != null && player.getPlaybackState() != Player.STATE_IDLE) {
            long duration = player.getDuration();
            Progress.save(this, url, player.getCurrentPosition(), duration == C.TIME_UNSET ? 0 : duration);
        }
    }

    private static String formatTime(long ms) {
        long seconds = ms / 1000;
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        return hours > 0
                ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds % 60)
                : String.format(Locale.US, "%d:%02d", minutes, seconds % 60);
    }
}
