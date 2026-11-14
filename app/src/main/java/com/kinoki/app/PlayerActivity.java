package com.kinoki.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Rational;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * Full-screen player for a single stream URL. Back returns to the list.
 *
 * <p>On a phone the same screen can hand playback over to a TV on the local network and then acts as its remote
 * ({@link CastClient}); on a TV it reports what it plays to {@link CastHost} so the phone can find it.
 */
public class PlayerActivity extends Activity implements CastHost.Remote {

    static final String EXTRA_URL = "url";
    static final String EXTRA_TITLE = "title";
    /** Identifies what to mark as watched: an episode's stream URL, or a movie's page URL. */
    static final String EXTRA_WATCH_KEY = "watchKey";
    /** Where to start, when the phone is casting; -1 means "carry on where the user left off". */
    static final String EXTRA_START_MS = "startMs";
    /** Opened by a cast from the phone, so no "resuming" message is needed. */
    static final String EXTRA_CAST = "cast";

    private static final long SAVE_INTERVAL_MS = 15_000;
    /** Resume slightly before where playback stopped, so the user catches the sentence they left in the middle of. */
    private static final long REWIND_MS = 5_000;
    private static final long CAST_POLL_MS = 2_000;
    private static final long CAST_STEP_MS = 30_000;
    /** How long a fresh cast may take to show up on the TV before the phone says the TV app is not open. */
    private static final long CAST_START_TIMEOUT_MS = 25_000;
    /** The TV is already playing a cast we know about, so its status should appear almost at once. */
    private static final long CAST_RESTORE_TIMEOUT_MS = 8_000;
    /** How often the phone looks for a TV running the app while the player is open. */
    private static final long CAST_CHECK_INTERVAL_MS = 6_000;
    /** Discovery window for that check; a TV on the same Wi-Fi answers in milliseconds. */
    private static final int CAST_CHECK_WINDOW_MS = 1_200;
    /** The cast button is shown this long after a touch, then gets out of the picture again. */
    private static final long CAST_BUTTON_HIDE_MS = 4_000;
    /** On the phone the player is full screen: the status and navigation bars come back on a touch, then go away. */
    private static final long SYSTEM_BARS_HIDE_MS = 4_000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable saveProgress = new Runnable() {
        @Override
        public void run() {
            saveCurrentPosition();
            handler.postDelayed(this, SAVE_INTERVAL_MS);
        }
    };

    private final Runnable publishProgress = new Runnable() {
        @Override
        public void run() {
            if (player != null && player.getPlaybackState() != Player.STATE_IDLE) {
                long duration = durationOf(player);
                CastHost.publish(url, title, watchKey, player.getCurrentPosition(), duration, player.isPlaying());
            }
            handler.postDelayed(this, 500);
        }
    };

    private final Runnable castPoll = new Runnable() {
        @Override
        public void run() {
            final CastClient.Device device = castDevice;
            if (!castPolling || device == null) {
                return;
            }
            new Thread(() -> {
                JSONObject status = CastClient.status(device);
                handler.post(() -> onCastStatus(device, status));
            }, "cast-status").start();
        }
    };

    /** Keeps the cast button honest: it exists only while a TV running the app answers on the network. */
    private final Runnable checkCastHosts = new Runnable() {
        @Override
        public void run() {
            if (castDevice != null) {
                return;
            }
            if (castCheckRunning) {
                handler.postDelayed(this, CAST_CHECK_INTERVAL_MS);
                return;
            }
            castCheckRunning = true;
            new Thread(() -> {
                List<CastClient.Device> devices = CastClient.find(CAST_CHECK_WINDOW_MS);
                handler.post(() -> {
                    castCheckRunning = false;
                    onCastHostsChecked(devices);
                });
            }, "cast-check").start();
            handler.postDelayed(this, CAST_CHECK_INTERVAL_MS);
        }
    };

    private final Runnable hideCastButton = this::hideCastButtonNow;

    private void hideCastButtonNow() {
        castButton.setVisibility(View.GONE);
    }

    private final Runnable hidePipButton = this::hidePipButtonNow;

    private void hidePipButtonNow() {
        pipButton.setVisibility(View.GONE);
    }

    private final Runnable hideSystemBars = this::hideSystemBarsNow;

    /** Hides the clock and the navigation bar so the video gets the whole screen. */
    private void hideSystemBarsNow() {
        if (bars != null) {
            bars.hide(WindowInsetsCompat.Type.systemBars());
        }
    }

    /** Brings the status and navigation bars back and leaves them up (the remote is not a movie). */
    private void showSystemBars() {
        handler.removeCallbacks(hideSystemBars);
        if (bars != null) {
            bars.show(WindowInsetsCompat.Type.systemBars());
        }
    }

    /** Shows the bars after a touch, then hides them again unless the remote is on screen. */
    private void showSystemBarsBriefly() {
        showSystemBars();
        if (bars != null && castPanel.getVisibility() != View.VISIBLE) {
            handler.postDelayed(hideSystemBars, SYSTEM_BARS_HIDE_MS);
        }
    }

    private PlayerView playerView;
    private ExoPlayer player;
    private View castButton;
    private View pipButton;
    private View castPanel;
    private TextView castState;
    private TextView castTitle;
    private TextView castTime;
    private TextView castVolume;
    private Button castPlayPause;
    private Button castMute;
    private String url;
    private String watchKey;
    private String title;

    private CastClient.Device castDevice;
    private boolean castPolling;
    private boolean castConnected;
    private boolean castPlaying;
    private long castPositionMs;
    private long castDurationMs;
    private long castStartedAt;
    private long castTimeoutMs = CAST_START_TIMEOUT_MS;
    private boolean discovering;
    private boolean activityStarted;
    private boolean castCheckRunning;
    private boolean castAvailable;
    private List<CastClient.Device> castDevices = java.util.Collections.emptyList();
    private GestureDetector gestures;
    private WindowInsetsControllerCompat bars;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);

        playerView = findViewById(R.id.player);
        castButton = findViewById(R.id.cast_button);
        pipButton = findViewById(R.id.pip_button);
        castPanel = findViewById(R.id.cast_panel);
        castState = findViewById(R.id.cast_state);
        castTitle = findViewById(R.id.cast_title);
        castTime = findViewById(R.id.cast_time);
        castVolume = findViewById(R.id.cast_volume);
        castPlayPause = findViewById(R.id.cast_play_pause);
        castMute = findViewById(R.id.cast_mute);

        readIntent();

        if (CastHost.isTv(this)) {
            castButton.setVisibility(View.GONE);
            pipButton.setVisibility(View.GONE);
        } else {
            castButton.setVisibility(View.GONE);
            castButton.setOnClickListener(v -> startCasting());
            pipButton.setVisibility(View.GONE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                pipButton.setOnClickListener(v -> enterPipMode());
                updatePipParams();
            }
            gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public void onLongPress(MotionEvent event) {
                    // Discovery can be blocked (guest Wi-Fi); a long press takes the TV's address by hand instead.
                    if (!castAvailable && castDevice == null) {
                        showAddressDialog();
                    }
                }
            });
        }
        castPlayPause.setOnClickListener(v -> toggleCastPlayback());
        findViewById(R.id.cast_rewind).setOnClickListener(v -> seekCast(-CAST_STEP_MS));
        findViewById(R.id.cast_forward).setOnClickListener(v -> seekCast(CAST_STEP_MS));
        findViewById(R.id.cast_volume_down).setOnClickListener(v -> changeCastVolume(CastClient.VOLUME_DOWN));
        findViewById(R.id.cast_volume_up).setOnClickListener(v -> changeCastVolume(CastClient.VOLUME_UP));
        castMute.setOnClickListener(v -> changeCastVolume(CastClient.VOLUME_MUTE));
        findViewById(R.id.cast_close).setOnClickListener(v -> stopCasting(true));

        if (!CastHost.isTv(this)) {
            // Full screen like a video app: the clock and buttons only come back when the screen is touched.
            bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            getWindow().getDecorView().post(this::hideSystemBarsNow);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;

        // Back on a screen that is casting (after a call, or after leaving the app): show the remote, not the video.
        CastClient.Session session = CastClient.session;
        if (session != null && session.url.equals(url)) {
            enterCastMode(session.device, CAST_RESTORE_TIMEOUT_MS);
        } else {
            startPlayback(getIntent().getLongExtra(EXTRA_START_MS, -1));
        }

        // The video plays full screen; the remote needs the bars for its buttons.
        if (castPanel.getVisibility() == View.VISIBLE) {
            showSystemBars();
        } else {
            hideSystemBarsNow();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        activityStarted = false;
        handler.removeCallbacks(saveProgress);
        handler.removeCallbacks(publishProgress);
        handler.removeCallbacks(castPoll);
        handler.removeCallbacks(checkCastHosts);
        handler.removeCallbacks(hideCastButton);
        handler.removeCallbacks(hidePipButton);
        handler.removeCallbacks(hideSystemBars);
        castPolling = false;
        saveCurrentPosition();
        CastHost.detach(this);
        if (player != null) {
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (gestures != null) {
            gestures.onTouchEvent(event);
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !isInPictureInPictureMode()) {
            // A tap brings the cast and picture-in-picture buttons and the status bar back for a few seconds.
            showCastButton();
            showPipButton();
            showSystemBarsBriefly();
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // The TV is already open and the phone cast something else; replace what is playing.
        setIntent(intent);
        readIntent();
        CastClient.session = null;
        if (player != null) {
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
        startPlayback(getIntent().getLongExtra(EXTRA_START_MS, -1));
    }

    private void readIntent() {
        url = getIntent().getStringExtra(EXTRA_URL);
        watchKey = getIntent().getStringExtra(EXTRA_WATCH_KEY);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        setTitle(title);
    }

    // ---- picture-in-picture (phone) -----------------------------------------------------------------------------

    /** Picture-in-picture exists from Android 8.0 and only makes sense on a phone, not on a TV. */
    private boolean pipSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !CastHost.isTv(this);
    }

    /** True while the phone itself shows the video (not while it is only the TV's remote). */
    private boolean canShowPip() {
        return pipSupported() && player != null && castPanel.getVisibility() != View.VISIBLE;
    }

    /** Android rejects PiP aspect ratios outside this range, and many films are wider than 2.39:1. */
    private static final double MIN_PIP_ASPECT = 0.42;
    private static final double MAX_PIP_ASPECT = 2.39;

    /** The floating window keeps the movie's shape, so the video is neither stretched nor letterboxed. */
    private Rational pipAspectRatio() {
        int width = 16;
        int height = 9;
        if (player != null) {
            VideoSize size = player.getVideoSize();
            if (size.width > 0 && size.height > 0) {
                width = size.width;
                height = size.height;
            }
        }
        double ratio = (double) width / height;
        if (ratio < MIN_PIP_ASPECT) {
            width = Math.max(1, (int) Math.round(height * MIN_PIP_ASPECT));
        } else if (ratio > MAX_PIP_ASPECT) {
            height = Math.max(1, (int) Math.round(width / MAX_PIP_ASPECT));
        }
        return new Rational(width, height);
    }

    private PictureInPictureParams pipParams() {
        PictureInPictureParams.Builder params = new PictureInPictureParams.Builder()
                .setAspectRatio(pipAspectRatio());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: swiping home shrinks the playing movie straight away, without a tap.
            params.setAutoEnterEnabled(canShowPip() && player.isPlaying());
            params.setSeamlessResizeEnabled(true);
        }
        return params.build();
    }

    /** Keeps the system's "auto enter" decision in step with what is on screen. */
    private void updatePipParams() {
        if (!pipSupported() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        try {
            setPictureInPictureParams(pipParams());
        } catch (IllegalStateException | IllegalArgumentException ignored) {
            // The activity is going away, or this device refuses these params; there is nothing to configure.
        }
    }

    private void enterPipMode() {
        if (!canShowPip() || isInPictureInPictureMode()
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        try {
            enterPictureInPictureMode(pipParams());
        } catch (IllegalStateException | IllegalArgumentException ignored) {
            // Not allowed in this state (for example while another dialog is up).
        }
    }

    /** Shows the button for a few seconds after a touch; in the tiny window it is not needed. */
    private void showPipButton() {
        if (!canShowPip() || isInPictureInPictureMode()) {
            return;
        }
        pipButton.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hidePipButton);
        handler.postDelayed(hidePipButton, CAST_BUTTON_HIDE_MS);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Going home (or opening another app) shrinks the movie instead of stopping it.
        if (canShowPip() && player.isPlaying()) {
            enterPipMode();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration configuration) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        super.onPictureInPictureModeChanged(inPip, configuration);
        if (inPip) {
            // The window is tiny: no cast or picture-in-picture button and no system bars.
            handler.removeCallbacks(hideCastButton);
            handler.removeCallbacks(hidePipButton);
            handler.removeCallbacks(hideSystemBars);
            castButton.setVisibility(View.GONE);
            pipButton.setVisibility(View.GONE);
        } else if (pipSupported()) {
            hideSystemBarsNow();
            updatePipParams();
        }
    }

    // ---- playback -----------------------------------------------------------------------------------------------

    /** Opens the stream; {@code startMs} below zero means "use the saved position". */
    private void startPlayback(long startMs) {
        if (player != null) {
            return;
        }
        // A cast left over from another title has been replaced by this one.
        CastClient.Session session = CastClient.session;
        if (session != null && !session.url.equals(url)) {
            CastClient.session = null;
            new Thread(() -> CastClient.stop(session.device), "cast-stop").start();
        }

        long saved = Progress.get(this, url);
        long start = startMs >= 0 ? startMs : saved > 0 ? Math.max(0, saved - REWIND_MS) : 0;
        boolean fromCast = getIntent().getBooleanExtra(EXTRA_CAST, false);

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

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                updatePipParams();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                // Android 12+ may shrink the movie automatically only while it is actually playing.
                updatePipParams();
            }
        });
        playerView.setPlayer(player);
        playerView.requestFocus();
        playerView.setVisibility(View.VISIBLE);
        castPanel.setVisibility(View.GONE);
        if (!CastHost.isTv(this)) {
            // The button appears once a TV with the app is actually seen on the network.
            handler.removeCallbacks(hideCastButton);
            castButton.setVisibility(View.GONE);
            handler.post(checkCastHosts);
        }

        MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(url));
        if (url.contains(".m3u8")) {
            item.setMimeType(MimeTypes.APPLICATION_M3U8);
        }
        if (start > 0) {
            player.setMediaItem(item.build(), start);
            if (!fromCast) {
                Toast.makeText(this, getString(R.string.resuming, formatTime(start)), Toast.LENGTH_SHORT).show();
            }
        } else {
            player.setMediaItem(item.build());
        }
        player.setPlayWhenReady(true);
        player.prepare();
        updatePipParams();
        // Point the feature out once, then it gets out of the picture like the cast button.
        showPipButton();

        if (CastHost.isTv(this)) {
            CastHost.attach(this);
            handler.post(publishProgress);
        }
        handler.postDelayed(saveProgress, SAVE_INTERVAL_MS);
    }

    private void saveCurrentPosition() {
        if (castDevice != null) {
            // The TV is playing: follow it, so watching later on the phone continues where the TV stopped.
            Progress.save(this, url, castPositionMs, castDurationMs);
            Progress.markWatchedIfDone(this, watchKey != null ? watchKey : url, castPositionMs, castDurationMs);
            return;
        }
        // After a failed load the player is idle and reports position 0; don't let that erase the saved spot.
        if (player != null && player.getPlaybackState() != Player.STATE_IDLE) {
            long duration = durationOf(player);
            long position = player.getCurrentPosition();
            Progress.save(this, url, position, duration);
            Progress.markWatchedIfDone(this, watchKey != null ? watchKey : url, position, duration);
        }
    }

    // ---- what the phone can do to this player -------------------------------------------------------------------

    @Override
    public void onRemotePause() {
        if (player != null) {
            player.pause();
        }
    }

    @Override
    public void onRemoteResume() {
        if (player != null) {
            player.play();
        }
    }

    @Override
    public void onRemoteSeek(long positionMs) {
        if (player != null) {
            player.seekTo(Math.max(0, positionMs));
        }
    }

    @Override
    public void onRemoteStop() {
        finish();
    }

    // ---- casting from the phone ---------------------------------------------------------------------------------

    /** Finds a TV and starts playback there, at the position this player is at. */
    private void startCasting() {
        if (discovering || castDevice != null) {
            return;
        }
        if (!castDevices.isEmpty()) {
            // The availability check has just seen them, so there is no need to wait through another round.
            chooseCastTarget(castDevices);
            return;
        }
        discovering = true;
        Toast.makeText(this, R.string.cast_searching, Toast.LENGTH_SHORT).show();
        CastClient.discover(devices -> {
            discovering = false;
            chooseCastTarget(devices);
        });
    }

    private void chooseCastTarget(List<CastClient.Device> devices) {
        if (devices.isEmpty()) {
            showAddressDialog();
        } else if (devices.size() == 1) {
            castTo(devices.get(0));
        } else {
            String[] names = new String[devices.size()];
            for (int i = 0; i < devices.size(); i++) {
                names[i] = devices.get(i).name;
            }
            new AlertDialog.Builder(this)
                    .setTitle(R.string.cast_choose)
                    .setItems(names, (dialog, which) -> castTo(devices.get(which)))
                    .show();
        }
    }

    /** Keeps the button in step with the discovery result: no TV, no button. */
    private void onCastHostsChecked(List<CastClient.Device> devices) {
        if (!activityStarted || castDevice != null) {
            return;
        }
        castDevices = devices;
        boolean found = !devices.isEmpty();
        boolean appeared = found && !castAvailable;
        castAvailable = found;
        if (!found) {
            handler.removeCallbacks(hideCastButton);
            castButton.setVisibility(View.GONE);
        } else if (appeared) {
            showCastButton();
        }
    }

    /** Shows the cast button for a few seconds, unless there is nothing to cast to or the remote is on screen. */
    private void showCastButton() {
        if (!castAvailable || castDevice != null || castPanel.getVisibility() == View.VISIBLE
                || isInPictureInPictureMode()) {
            return;
        }
        castButton.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideCastButton);
        handler.postDelayed(hideCastButton, CAST_BUTTON_HIDE_MS);
    }

    /** Manual fallback for networks where broadcast discovery is blocked. */
    private void showAddressDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.cast_address_hint);
        LinearLayout layout = new LinearLayout(this);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, 0);
        layout.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(R.string.cast_not_found)
                .setMessage(R.string.cast_not_found_help)
                .setView(layout)
                .setPositiveButton(R.string.cast_connect, (dialog, which) -> {
                    String host = input.getText().toString().trim();
                    if (!host.isEmpty()) {
                        castTo(new CastClient.Device(host, host));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void castTo(CastClient.Device device) {
        long position = player == null ? 0 : player.getCurrentPosition();
        if (player != null) {
            player.pause();
        }
        Toast.makeText(this, R.string.cast_connecting, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean ok = CastClient.play(device, url, title, watchKey, position);
            handler.post(() -> {
                if (!ok) {
                    Toast.makeText(this, R.string.cast_failed, Toast.LENGTH_LONG).show();
                    if (player != null) {
                        player.play();
                    }
                    return;
                }
                CastClient.session = new CastClient.Session(device, url, title, watchKey);
                castPositionMs = position;
                enterCastMode(device, CAST_START_TIMEOUT_MS);
            });
        }, "cast-play").start();
    }

    /** Hides the video and shows the remote for the TV that is now playing. */
    private void enterCastMode(CastClient.Device device, long startTimeoutMs) {
        castDevice = device;
        castConnected = false;
        castStartedAt = System.currentTimeMillis();
        castTimeoutMs = startTimeoutMs;
        if (player != null) {
            // The TV does the decoding now; the phone only keeps the remote.
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
        castButton.setVisibility(View.GONE);
        castPanel.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hidePipButton);
        pipButton.setVisibility(View.GONE);
        updatePipParams();
        showSystemBars();
        handler.removeCallbacks(checkCastHosts);
        handler.removeCallbacks(hideCastButton);
        castTitle.setText(title == null ? "" : title);
        castState.setText(R.string.cast_connecting);
        castTime.setText("");
        castPlayPause.setText(R.string.cast_pause);
        castPlayPause.setEnabled(false);
        castVolume.setText("");
        castMute.setText(R.string.cast_mute);
        handler.removeCallbacks(saveProgress);
        handler.postDelayed(saveProgress, SAVE_INTERVAL_MS);
        castPolling = true;
        handler.post(castPoll);
    }

    private void onCastStatus(CastClient.Device device, JSONObject status) {
        if (!castPolling || castDevice != device) {
            return;
        }
        boolean playingThere = status != null && !status.optBoolean("idle", true)
                && url.equals(status.optString("url", ""));
        if (playingThere) {
            castConnected = true;
            castPositionMs = status.optLong("positionMs", 0);
            castDurationMs = status.optLong("durationMs", 0);
            castPlaying = status.optBoolean("playing", false);
            castState.setText(castPlaying ? R.string.cast_playing : R.string.cast_paused);
            castTime.setText(castDurationMs > 0
                    ? getString(R.string.cast_time, formatTime(castPositionMs), formatTime(castDurationMs))
                    : formatTime(castPositionMs));
            castPlayPause.setText(castPlaying ? R.string.cast_pause : R.string.cast_resume);
            castPlayPause.setEnabled(true);
            updateCastVolume(status);
            handler.postDelayed(castPoll, CAST_POLL_MS);
            return;
        }

        // If the TV never reported a position, start where the phone itself left off.
        long resumeAt = castPositionMs > 0 ? castPositionMs : -1;
        if (castConnected) {
            // The TV stopped (back button) or went away mid-cast; carry on here without losing the spot.
            Toast.makeText(this, status == null ? R.string.cast_lost : R.string.cast_finished,
                    Toast.LENGTH_SHORT).show();
            endCastMode();
            startPlayback(resumeAt);
            return;
        }
        if (System.currentTimeMillis() - castStartedAt > castTimeoutMs) {
            // The TV answered but never opened the player: its app is closed, or Android blocked the launch.
            Toast.makeText(this, R.string.cast_tv_not_ready, Toast.LENGTH_LONG).show();
            endCastMode();
            startPlayback(resumeAt);
            return;
        }
        handler.postDelayed(castPoll, CAST_POLL_MS);
    }

    private void toggleCastPlayback() {
        final CastClient.Device device = castDevice;
        if (device == null || !castConnected) {
            return;
        }
        final boolean play = !castPlaying;
        castPlayPause.setEnabled(false);
        castPlaying = play;
        castState.setText(play ? R.string.cast_playing : R.string.cast_paused);
        castPlayPause.setText(play ? R.string.cast_pause : R.string.cast_resume);
        new Thread(() -> {
            if (play) {
                CastClient.resume(device);
            } else {
                CastClient.pause(device);
            }
        }, "cast-command").start();
    }

    /** Mirrors the TV's media volume: a percentage, or "muted", plus the mute button's label. */
    private void updateCastVolume(JSONObject volume) {
        if (volume == null || castDevice == null) {
            return;
        }
        boolean muted = volume.optBoolean("muted", false);
        int max = volume.optInt("max", 0);
        castMute.setText(muted ? R.string.cast_unmute : R.string.cast_mute);
        if (muted) {
            castVolume.setText(R.string.cast_volume_muted);
        } else if (max > 0) {
            int percent = Math.round(100f * volume.optInt("level", 0) / max);
            castVolume.setText(getString(R.string.cast_volume, percent));
        } else {
            castVolume.setText("");
        }
    }

    private void changeCastVolume(String action) {
        final CastClient.Device device = castDevice;
        if (device == null || !castConnected) {
            return;
        }
        new Thread(() -> {
            JSONObject volume = CastClient.volume(device, action);
            handler.post(() -> updateCastVolume(volume));
        }, "cast-volume").start();
    }

    private void seekCast(long deltaMs) {
        final CastClient.Device device = castDevice;
        if (device == null || !castConnected) {
            return;
        }
        castPositionMs = Math.max(0, castPositionMs + deltaMs);
        if (castDurationMs > 0) {
            castPositionMs = Math.min(castPositionMs, castDurationMs);
        }
        castTime.setText(castDurationMs > 0
                ? getString(R.string.cast_time, formatTime(castPositionMs), formatTime(castDurationMs))
                : formatTime(castPositionMs));
        final long target = castPositionMs;
        new Thread(() -> CastClient.seek(device, target), "cast-seek").start();
    }

    /** Ends the cast; with {@code resumeHere} the phone picks playback up where the TV left it. */
    private void stopCasting(boolean resumeHere) {
        final CastClient.Device device = castDevice;
        if (device == null) {
            return;
        }
        final long fallback = castPositionMs > 0 ? castPositionMs : -1;
        endCastMode();
        new Thread(() -> {
            JSONObject status = CastClient.status(device);
            boolean idle = status == null || status.optBoolean("idle", true);
            long resumeAt = idle ? fallback : status.optLong("positionMs", fallback);
            CastClient.stop(device);
            if (resumeHere) {
                handler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        startPlayback(resumeAt);
                    }
                });
            }
        }, "cast-stop").start();
    }

    private void endCastMode() {
        castPolling = false;
        castConnected = false;
        castDevice = null;
        CastClient.session = null;
        handler.removeCallbacks(castPoll);
        handler.removeCallbacks(saveProgress);
        castPanel.setVisibility(View.GONE);
        handler.removeCallbacks(hideCastButton);
        castButton.setVisibility(View.GONE);
        updatePipParams();
        hideSystemBarsNow();
    }

    // ---- formatting ---------------------------------------------------------------------------------------------

    private static long durationOf(Player player) {
        return player.getDuration() == C.TIME_UNSET ? 0 : player.getDuration();
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
