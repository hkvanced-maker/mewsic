package com.meowsic.app;

import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.C;
import androidx.media3.common.Player;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full player: waveform scrubber, seek bar, transport, speed / boost / loop / sleep. */
public class PlayerActivity extends AppCompatActivity
        implements PlaybackClient.Callback, WaveformView.Host {

    private PlaybackClient client;
    private PlaybackService service;
    private Player player;
    private SpeedSheet sheet;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ImageView art;
    private TextView titleView;
    private TextView subtitleView;
    private TextView elapsedView;
    private TextView totalView;
    private TextView rewindLabel;
    private TextView forwardLabel;
    private WaveformView waveform;
    private SeekBar seekBar;
    private ImageButton playButton;
    private ImageButton nextButton;
    private ImageButton repeatButton;
    private ImageButton shuffleButton;
    private ImageButton loopClearButton;
    private MaterialButton speedButton;
    private MaterialButton boostButton;
    private MaterialButton sleepButton;
    private MaterialButton loopAButton;
    private MaterialButton loopBButton;

    private boolean userSeeking;
    private boolean showRemaining;
    private boolean handledIntent;
    private boolean waitingForQueue;
    private long lastLiveSeekAt;
    private String boundKey;

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onEvents(Player p, Player.Events events) {
            refreshState();
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED)) {
                bindTrack();
            }
            refreshProgress();
        }
    };

    private final PlaybackService.Listener serviceListener = new PlaybackService.Listener() {
        @Override
        public void onSettingsChanged() {
            refreshSettings();
        }

        @Override
        public void onLoopChanged() {
            updateLoopButtons();
        }

        @Override
        public void onSleepChanged() {
            refreshProgress();
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshProgress();
            ui.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        handledIntent = savedInstanceState != null && savedInstanceState.getBoolean("handled");

        art = findViewById(R.id.art);
        titleView = findViewById(R.id.player_title);
        subtitleView = findViewById(R.id.player_subtitle);
        elapsedView = findViewById(R.id.time_elapsed);
        totalView = findViewById(R.id.time_total);
        rewindLabel = findViewById(R.id.label_rewind);
        forwardLabel = findViewById(R.id.label_forward);
        waveform = findViewById(R.id.waveform);
        seekBar = findViewById(R.id.seek_bar);
        playButton = findViewById(R.id.btn_play);
        nextButton = findViewById(R.id.btn_next);
        repeatButton = findViewById(R.id.btn_repeat);
        shuffleButton = findViewById(R.id.btn_shuffle);
        loopClearButton = findViewById(R.id.loop_clear);
        speedButton = findViewById(R.id.speed_button);
        boostButton = findViewById(R.id.boost_button);
        sleepButton = findViewById(R.id.sleep_button);
        loopAButton = findViewById(R.id.loop_a);
        loopBButton = findViewById(R.id.loop_b);

        waveform.setHost(this);
        art.setImageResource(R.drawable.app_logo);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.settings_button).setOnClickListener(v -> openSheet());
        speedButton.setOnClickListener(v -> openSheet());
        boostButton.setOnClickListener(v -> openSheet());
        sleepButton.setOnClickListener(v -> showSleepDialog());

        playButton.setOnClickListener(v -> togglePlay());
        findViewById(R.id.btn_prev).setOnClickListener(v -> {
            if (player != null) {
                player.seekToPrevious();
            }
        });
        nextButton.setOnClickListener(v -> {
            if (player != null) {
                player.seekToNext();
            }
        });
        View rewind = findViewById(R.id.btn_rewind);
        rewind.setOnClickListener(v -> {
            if (service != null) {
                service.skipBack();
            }
        });
        rewind.setOnLongClickListener(v -> {
            SkipDialog.show(this, true, this::onSkipChanged);
            return true;
        });
        View forward = findViewById(R.id.btn_forward);
        forward.setOnClickListener(v -> {
            if (service != null) {
                service.skipForward();
            }
        });
        forward.setOnLongClickListener(v -> {
            SkipDialog.show(this, false, this::onSkipChanged);
            return true;
        });
        repeatButton.setOnClickListener(v -> cycleRepeat());
        shuffleButton.setOnClickListener(v -> {
            if (player != null) {
                player.setShuffleModeEnabled(!player.getShuffleModeEnabled());
            }
        });

        loopAButton.setOnClickListener(v -> {
            if (service != null && player != null) {
                service.setLoopA(player.getCurrentPosition());
            }
        });
        loopBButton.setOnClickListener(v -> {
            if (service != null && player != null
                    && !service.setLoopB(player.getCurrentPosition())) {
                Toast.makeText(this, R.string.loop_set_a_first, Toast.LENGTH_SHORT).show();
            }
        });
        loopClearButton.setOnClickListener(v -> {
            if (service != null) {
                service.clearLoop();
            }
        });

        totalView.setOnClickListener(v -> {
            showRemaining = !showRemaining;
            refreshProgress();
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser || player == null) {
                    return;
                }
                elapsedView.setText(Fmt.time(progress));
                long now = SystemClock.uptimeMillis();
                if (now - lastLiveSeekAt > 200) {
                    lastLiveSeekAt = now;
                    player.seekTo(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                if (player != null) {
                    player.seekTo(sb.getProgress());
                }
            }
        });

        client = new PlaybackClient(this, this);
        refreshLabels();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("handled", handledIntent);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handledIntent = false;
        handleViewIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        client.connect();
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacks(tick);
        if (sheet != null) {
            sheet.dismiss();
            sheet = null;
        }
        if (service != null) {
            service.removeListener(serviceListener);
        }
        if (player != null) {
            player.removeListener(playerListener);
        }
        client.disconnect();
        service = null;
        player = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    // ------------------------------------------------------------------ service connection

    @Override
    public void onConnected(PlaybackService s) {
        service = s;
        player = s.getPlayer();
        player.addListener(playerListener);
        s.addListener(serviceListener);
        handleViewIntent(getIntent());
        if (player.getMediaItemCount() == 0 && !waitingForQueue) {
            finish(); // nothing to show (e.g. opened from an old notification)
            return;
        }
        bindTrack();
        refreshState();
        refreshSettings();
        refreshProgress();
        updateLoopButtons();
        ui.removeCallbacks(tick);
        ui.post(tick);
        maybePromptNotifications();
    }

    @Override
    public void onDisconnected() {
        service = null;
        player = null;
    }

    /** A file opened from another app (Open with / share): queue just that file. */
    private void handleViewIntent(Intent intent) {
        if (service == null || intent == null || handledIntent) {
            return;
        }
        if (!Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) {
            return;
        }
        handledIntent = true;
        waitingForQueue = true;
        final Uri uri = intent.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
            // most "open with" grants are temporary; that is fine while the app runs
        }
        io.execute(() -> {
            final Track t = Library.fromUri(getApplicationContext(), uri);
            ui.post(() -> {
                waitingForQueue = false;
                if (service != null && !isFinishing()) {
                    service.setQueue(Collections.singletonList(t), 0);
                }
            });
        });
    }

    // ------------------------------------------------------------------ WaveformView.Host

    @Override
    public long getPositionMs() {
        return player != null ? player.getCurrentPosition() : 0;
    }

    @Override
    public long getDurationMs() {
        return player != null ? durationOrTrack() : 0;
    }

    @Override
    public void onScrub(long positionMs, boolean finished) {
        if (player == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (finished || now - lastLiveSeekAt > 120) {
            lastLiveSeekAt = now;
            player.seekTo(positionMs);
        }
        elapsedView.setText(Fmt.time(positionMs));
        if (!userSeeking) {
            seekBar.setProgress((int) Math.min(Integer.MAX_VALUE, positionMs));
        }
    }

    // ------------------------------------------------------------------ UI refresh

    private long durationOrTrack() {
        long d = player.getDuration();
        if (d == C.TIME_UNSET || d <= 0) {
            Track t = service != null ? service.getCurrentTrack() : null;
            d = t != null ? t.durationMs : 0;
        }
        return d;
    }

    private void bindTrack() {
        if (service == null) {
            return;
        }
        Track t = service.getCurrentTrack();
        if (t == null || t.key.equals(boundKey)) {
            return;
        }
        boundKey = t.key;
        titleView.setText(t.title);
        String sub;
        if (t.artist.isEmpty()) {
            sub = t.album.isEmpty() ? t.folder : t.album;
        } else {
            sub = t.album.isEmpty() ? t.artist : t.artist + " \u2022 " + t.album;
        }
        subtitleView.setText(sub);
        subtitleView.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
        art.setImageResource(R.drawable.app_logo);
        waveform.setData(WaveformStore.get(this, t));
        ArtLoader.load(this, t, 700, (key, bitmap) -> {
            if (bitmap != null && key.equals(boundKey) && !isFinishing() && !isDestroyed()) {
                art.setImageBitmap(bitmap);
            }
        });
        updateLoopButtons();
        refreshProgress();
    }

    private void refreshState() {
        if (player == null) {
            return;
        }
        int state = player.getPlaybackState();
        boolean showPause = player.getPlayWhenReady()
                && state != Player.STATE_ENDED && state != Player.STATE_IDLE;
        playButton.setImageResource(showPause ? R.drawable.ic_pause : R.drawable.ic_play);
        playButton.setContentDescription(getString(showPause ? R.string.pause : R.string.play));

        int repeat = player.getRepeatMode();
        repeatButton.setImageResource(repeat == Player.REPEAT_MODE_ONE
                ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        tint(repeatButton, repeat != Player.REPEAT_MODE_OFF);
        tint(shuffleButton, player.getShuffleModeEnabled());

        boolean hasNext = player.hasNextMediaItem();
        nextButton.setEnabled(hasNext);
        nextButton.setAlpha(hasNext ? 1f : 0.4f);
    }

    private void tint(ImageButton button, boolean on) {
        if (on) {
            button.setColorFilter(ContextCompat.getColor(this, R.color.sovio_primary));
            button.setAlpha(1f);
        } else {
            button.clearColorFilter();
            button.setAlpha(0.65f);
        }
    }

    private void refreshSettings() {
        if (service == null) {
            return;
        }
        speedButton.setText(Fmt.speed(service.getSpeed()));
        float db = service.getBoostDb();
        boostButton.setText(db > 0f ? Fmt.db(db) : getString(R.string.boost));
        refreshLabels();
        refreshProgress();
    }

    private void refreshLabels() {
        rewindLabel.setText(String.valueOf(Prefs.getRewindSec(this)));
        forwardLabel.setText(String.valueOf(Prefs.getForwardSec(this)));
    }

    private void refreshProgress() {
        if (player == null || service == null) {
            return;
        }
        long dur = durationOrTrack();
        long pos = player.getCurrentPosition();
        if (!userSeeking) {
            int max = (int) Math.min(Integer.MAX_VALUE, Math.max(1, dur));
            if (seekBar.getMax() != max) {
                seekBar.setMax(max);
            }
            seekBar.setProgress((int) Math.min(Integer.MAX_VALUE, Math.max(0, pos)));
            elapsedView.setText(Fmt.time(pos));
        }
        if (showRemaining && dur > 0) {
            long remaining = (long) (Math.max(0, dur - pos) / service.getSpeed());
            totalView.setText("-" + Fmt.time(remaining));
        } else {
            totalView.setText(dur > 0 ? Fmt.time(dur) : "--:--");
        }
        if (service.isSleepActive()) {
            sleepButton.setText(service.isSleepEndOfTrack()
                    ? getString(R.string.sleep_eot_short) : Fmt.time(service.getSleepRemainingMs()));
        } else {
            sleepButton.setText(R.string.sleep);
        }
    }

    private void updateLoopButtons() {
        if (service == null) {
            return;
        }
        long a = service.getLoopA();
        long b = service.getLoopB();
        loopAButton.setText(a >= 0 ? "A " + Fmt.time(a) : "A");
        loopBButton.setText(b >= 0 ? "B " + Fmt.time(b) : "B");
        loopClearButton.setVisibility(a >= 0 || b >= 0 ? View.VISIBLE : View.INVISIBLE);
        waveform.setLoop(a, b);
    }

    // ------------------------------------------------------------------ actions

    private void togglePlay() {
        if (player == null) {
            return;
        }
        int state = player.getPlaybackState();
        if (state == Player.STATE_ENDED) {
            player.seekTo(0);
            player.play();
        } else if (player.getPlayWhenReady() && state != Player.STATE_IDLE) {
            player.pause();
        } else {
            if (state == Player.STATE_IDLE) {
                player.prepare();
            }
            player.play();
        }
    }

    private void cycleRepeat() {
        if (player == null) {
            return;
        }
        int mode = player.getRepeatMode();
        if (mode == Player.REPEAT_MODE_OFF) {
            player.setRepeatMode(Player.REPEAT_MODE_ALL);
        } else if (mode == Player.REPEAT_MODE_ALL) {
            player.setRepeatMode(Player.REPEAT_MODE_ONE);
        } else {
            player.setRepeatMode(Player.REPEAT_MODE_OFF);
        }
    }

    private void openSheet() {
        if (service == null) {
            return;
        }
        if (sheet != null) {
            sheet.dismiss();
        }
        sheet = SpeedSheet.show(this, service);
    }

    private void showSleepDialog() {
        if (service == null) {
            return;
        }
        final int[] minutes = {5, 10, 15, 30, 45, 60, 90};
        List<CharSequence> labels = new ArrayList<>();
        for (int m : minutes) {
            labels.add(getString(R.string.sleep_minutes, m));
        }
        labels.add(getString(R.string.sleep_end_of_track));
        if (service.isSleepActive()) {
            labels.add(getString(R.string.sleep_off));
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sleep_timer)
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                    if (which < minutes.length) {
                        service.startSleepTimer(minutes[which] * 60_000L);
                    } else if (which == minutes.length) {
                        service.startSleepEndOfTrack();
                    } else {
                        service.cancelSleepTimer();
                    }
                })
                .show();
    }

    private void onSkipChanged() {
        if (service != null) {
            service.refreshSkipButtons(); // lock screen / notification buttons follow the new seconds
        }
        refreshLabels();
    }

    /** Without notifications Android cannot show the lock-screen / notification-shade controls. */
    private void maybePromptNotifications() {
        if (Prefs.isNotifPrompted(this)
                || NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            return;
        }
        Prefs.setNotifPrompted(this, true);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.notif_title)
                .setMessage(R.string.notif_message)
                .setPositiveButton(R.string.notif_open_settings, (dialog, which) -> {
                    Intent settings;
                    if (Build.VERSION.SDK_INT >= 26) {
                        settings = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                    } else {
                        settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", getPackageName(), null));
                    }
                    startActivity(settings);
                })
                .setNegativeButton(R.string.notif_later, null)
                .show();
    }
}
