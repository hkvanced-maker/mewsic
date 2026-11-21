package com.meowsic.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.PlayerMessage;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.mp3.Mp3Extractor;
import androidx.media3.session.CommandButton;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionResult;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Background playback. Same engine settings as AntennaPod (ExoPlayer, exact seeking, Sonic
 * time-stretching that keeps the pitch), plus: speed up to 5x, pitch, volume boost with a
 * limiter, A-B loop and sleep timer.
 */
@OptIn(markerClass = UnstableApi.class)
public class PlaybackService extends MediaSessionService {
    public static final String ACTION_LOCAL = "com.sovio.app.action.LOCAL_BIND";
    public static final float MIN_SPEED = 0.25f;
    public static final float MAX_SPEED_DEFAULT = 3.0f;
    public static final float MAX_SPEED_EXTENDED = 5.0f;
    public static final float MAX_BOOST_DB = 24f;
    private static final long PROGRESS_SAVE_INTERVAL_MS = 4000;
    private static final String CMD_SKIP_BACK = "com.sovio.app.SKIP_BACK";
    private static final String CMD_SKIP_FORWARD = "com.sovio.app.SKIP_FORWARD";

    /** UI callbacks, always called on the main thread. */
    public interface Listener {
        default void onSettingsChanged() {
        }

        default void onLoopChanged() {
        }

        default void onSleepChanged() {
        }
    }

    public class LocalBinder extends Binder {
        public PlaybackService getService() {
            return PlaybackService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<Track> queue = new ArrayList<>();

    private ExoPlayer player;
    private MediaSession session;
    private BoostProcessor boost;
    private LoudnessEnhancer fallbackEnhancer;
    private boolean sinkWired;
    private final SessionCommand cmdBack = new SessionCommand(CMD_SKIP_BACK, Bundle.EMPTY);
    private final SessionCommand cmdForward = new SessionCommand(CMD_SKIP_FORWARD, Bundle.EMPTY);
    private ImmutableList<CommandButton> customLayout = ImmutableList.of();
    /** A restored session that the user has not started yet (errors on it stay silent). */
    private boolean restoredIdle;

    private float speed = 1f;
    private float pitchSt = 0f;
    private boolean skipSilence = false;
    private float boostDb = 0f;
    private String currentKey;

    private long loopA = -1;
    private long loopB = -1;
    private PlayerMessage loopMessage;

    private long sleepEndAt = 0;
    private boolean sleepEndOfTrack = false;

    private final Runnable sleepRunnable = new Runnable() {
        @Override
        public void run() {
            sleepEndAt = 0;
            player.pause();
            notifySleep();
        }
    };

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            saveProgress();
            if (player != null && player.isPlaying()) {
                handler.postDelayed(this, PROGRESS_SAVE_INTERVAL_MS);
            }
        }
    };

    /**
     * Same as the stock factory, but puts the boost limiter in front of the speed processor.
     * The method intentionally has no @Override: if the Media3 signature ever differs, the
     * build still works and {@link #sinkWired} stays false, which switches to a fallback.
     */
    private final class SovioRenderersFactory extends DefaultRenderersFactory {
        SovioRenderersFactory(Context context) {
            super(context);
        }

        protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput,
                                           boolean enableAudioTrackPlaybackParams) {
            sinkWired = true;
            return new DefaultAudioSink.Builder(context)
                    .setAudioProcessors(new AudioProcessor[]{boost})
                    .build();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        boost = new BoostProcessor();

        DefaultExtractorsFactory extractors = new DefaultExtractorsFactory();
        extractors.setConstantBitrateSeekingEnabled(true);
        extractors.setMp3ExtractorFlags(Mp3Extractor.FLAG_DISABLE_ID3_METADATA);

        player = new ExoPlayer.Builder(this, new SovioRenderersFactory(this))
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this, extractors))
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .setSeekParameters(SeekParameters.EXACT)
                .build();

        speed = Prefs.getSpeed(this);
        pitchSt = Prefs.getPitch(this);
        skipSilence = Prefs.isSkipSilence(this);
        applyPlaybackParams();
        player.setSkipSilenceEnabled(skipSilence);
        player.setRepeatMode(Prefs.getRepeatMode(this));
        player.setShuffleModeEnabled(Prefs.isShuffle(this));
        player.addListener(playerListener);
        initFallbackEnhancer(player.getAudioSessionId());

        DefaultMediaNotificationProvider notifications =
                new DefaultMediaNotificationProvider.Builder(this).build();
        notifications.setSmallIcon(R.drawable.ic_notification);
        setMediaNotificationProvider(notifications);

        Intent open = new Intent(this, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        session = new MediaSession.Builder(this, player)
                .setCallback(new SessionCallback())
                .setSessionActivity(contentIntent)
                .build();
        // Without this Media3 never shows the notification / lock-screen controls and never keeps
        // the service in the foreground (so playback dies soon after the screen turns off).
        addSession(session);
        refreshSkipButtons();
        restoreLastSession();
    }

    /** Lets notification / lock-screen / headset controllers use the rewind and forward buttons. */
    private final class SessionCallback implements MediaSession.Callback {
        @Override
        public MediaSession.ConnectionResult onConnect(MediaSession s, MediaSession.ControllerInfo controller) {
            SessionCommands commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(cmdBack)
                    .add(cmdForward)
                    .build();
            return new MediaSession.ConnectionResult.AcceptedResultBuilder(s)
                    .setAvailableSessionCommands(commands)
                    .setCustomLayout(customLayout)
                    .build();
        }

        @Override
        public ListenableFuture<SessionResult> onCustomCommand(MediaSession s, MediaSession.ControllerInfo controller,
                                                               SessionCommand command, Bundle args) {
            if (CMD_SKIP_BACK.equals(command.customAction)) {
                skipBack();
            } else if (CMD_SKIP_FORWARD.equals(command.customAction)) {
                skipForward();
            }
            return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
        }
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Nullable
    @Override
    public IBinder onBind(@Nullable Intent intent) {
        if (intent != null && ACTION_LOCAL.equals(intent.getAction())) {
            return binder;
        }
        return super.onBind(intent);
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        // keep playing in the background; stop only when nothing is playing
        if (!player.getPlayWhenReady() || player.getMediaItemCount() == 0) {
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        saveProgress();
        session.release();
        player.release();
        releaseFallbackEnhancer();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- listener plumbing

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifySettings() {
        for (Listener l : listeners) {
            l.onSettingsChanged();
        }
    }

    private void notifyLoop() {
        for (Listener l : listeners) {
            l.onLoopChanged();
        }
    }

    private void notifySleep() {
        for (Listener l : listeners) {
            l.onSleepChanged();
        }
    }

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && currentKey != null) {
                Prefs.setResume(PlaybackService.this, currentKey, 0); // finished: start over next time
            }
            currentKey = item != null ? item.mediaId : null;
            if (item != null) {
                Prefs.setLastIndex(PlaybackService.this, player.getCurrentMediaItemIndex());
            }
            clearLoop();
            applyTrackBoost();
            notifySettings();
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            handler.removeCallbacks(progressRunnable);
            if (isPlaying) {
                handler.postDelayed(progressRunnable, PROGRESS_SAVE_INTERVAL_MS);
            } else {
                saveProgress();
            }
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_ENDED && currentKey != null) {
                Prefs.setResume(PlaybackService.this, currentKey, 0);
            }
        }

        @Override
        public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
            if (playWhenReady) {
                restoredIdle = false;
            }
            if (!playWhenReady && sleepEndOfTrack
                    && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                cancelSleepTimer();
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            if (restoredIdle) {
                discardRestored(); // the remembered file is gone: forget it quietly
                return;
            }
            Toast.makeText(PlaybackService.this,
                    getString(R.string.error_playback) + " (" + error.getErrorCodeName() + ")",
                    Toast.LENGTH_LONG).show();
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem();
                player.prepare();
            }
        }

        @Override
        public void onAudioSessionIdChanged(int audioSessionId) {
            initFallbackEnhancer(audioSessionId);
        }

        @Override
        public void onRepeatModeChanged(int repeatMode) {
            Prefs.setRepeatMode(PlaybackService.this, repeatMode);
        }

        @Override
        public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
            Prefs.setShuffle(PlaybackService.this, shuffleModeEnabled);
        }
    };

    // ---------------------------------------------------------------- queue

    public ExoPlayer getPlayer() {
        return player;
    }

    static MediaItem toItem(Track t) {
        MediaMetadata md = new MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artist.isEmpty() ? null : t.artist)
                .setAlbumTitle(t.album.isEmpty() ? null : t.album)
                .setArtworkUri(t.artUri)
                .build();
        return new MediaItem.Builder()
                .setMediaId(t.key)
                .setUri(t.uri)
                .setMediaMetadata(md)
                .build();
    }

    /** Replaces the queue and starts playing {@code startIndex} (resuming where it was left). */
    public void setQueue(List<Track> tracks, int startIndex) {
        if (tracks == null || tracks.isEmpty()) {
            return;
        }
        restoredIdle = false;
        saveProgress();
        queue.clear();
        queue.addAll(tracks);
        List<MediaItem> items = new ArrayList<>(tracks.size());
        for (Track t : tracks) {
            items.add(toItem(t));
        }
        int idx = Math.max(0, Math.min(startIndex, tracks.size() - 1));
        Track first = tracks.get(idx);
        long startPos = resumePosition(first);
        QueueStore.save(this, tracks, idx);
        currentKey = first.key;
        clearLoop();
        applyTrackBoost();
        player.setMediaItems(items, idx, startPos);
        player.prepare();
        player.play();
        try {
            startService(new Intent(this, PlaybackService.class));
        } catch (RuntimeException ignored) {
            // background start not allowed: Media3 will promote the service when playing
        }
        notifySettings();
    }

    @Nullable
    public Track getCurrentTrack() {
        int i = player.getCurrentMediaItemIndex();
        return (i >= 0 && i < queue.size()) ? queue.get(i) : null;
    }

    private void saveProgress() {
        if (player == null || currentKey == null || player.getMediaItemCount() == 0) {
            return;
        }
        long pos = player.getCurrentPosition();
        long dur = player.getDuration();
        if (dur != C.TIME_UNSET && dur > 0 && pos >= dur - 5000) {
            pos = 0;
        }
        Prefs.setResume(this, currentKey, pos);
    }

    public void skipBack() {
        long target = player.getCurrentPosition() - Prefs.getRewindSec(this) * 1000L;
        player.seekTo(Math.max(0, target));
    }

    public void skipForward() {
        long dur = player.getDuration();
        long target = player.getCurrentPosition() + Prefs.getForwardSec(this) * 1000L;
        if (dur != C.TIME_UNSET && dur > 0 && target >= dur) {
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem();
            } else {
                player.seekTo(dur);
            }
        } else {
            player.seekTo(target);
        }
    }

    /** Rebuilds the rewind / forward buttons (lock screen, notification) after the seconds changed. */
    public void refreshSkipButtons() {
        CommandButton back = new CommandButton.Builder()
                .setDisplayName(getString(R.string.skip_back_cd, Prefs.getRewindSec(this)))
                .setSessionCommand(cmdBack)
                .setIconResId(R.drawable.ic_notif_rewind)
                .build();
        CommandButton forward = new CommandButton.Builder()
                .setDisplayName(getString(R.string.skip_forward_cd, Prefs.getForwardSec(this)))
                .setSessionCommand(cmdForward)
                .setIconResId(R.drawable.ic_notif_forward)
                .build();
        customLayout = ImmutableList.of(back, forward);
        if (session != null) {
            session.setCustomLayout(customLayout);
        }
        notifySettings();
    }

    private long resumePosition(Track t) {
        long pos = Prefs.getResume(this, t.key);
        if (pos < 5000 || (t.durationMs > 0 && pos > t.durationMs - 8000)) {
            return 0;
        }
        return pos;
    }

    /** Loads the last played queue (paused) so the app opens on the last file, like AntennaPod. */
    private void restoreLastSession() {
        QueueStore.Session saved = QueueStore.load(this);
        if (saved == null || saved.tracks.isEmpty()) {
            return;
        }
        Track first = saved.tracks.get(saved.index);
        queue.clear();
        queue.addAll(saved.tracks);
        List<MediaItem> items = new ArrayList<>(saved.tracks.size());
        for (Track t : saved.tracks) {
            items.add(toItem(t));
        }
        currentKey = first.key;
        applyTrackBoost();
        restoredIdle = true;
        player.setMediaItems(items, saved.index, resumePosition(first));
        player.setPlayWhenReady(false);
        player.prepare();
    }

    private void discardRestored() {
        restoredIdle = false;
        QueueStore.clear(this);
        queue.clear();
        currentKey = null;
        player.clearMediaItems();
        notifySettings();
    }

    // ---------------------------------------------------------------- audio settings

    public float getSpeed() {
        return speed;
    }

    public void setSpeed(float s) {
        speed = Fmt.roundSpeed(Fmt.clamp(s, MIN_SPEED, MAX_SPEED_EXTENDED));
        Prefs.setSpeed(this, speed);
        applyPlaybackParams();
        notifySettings();
    }

    public float getPitchSemitones() {
        return pitchSt;
    }

    public void setPitchSemitones(float st) {
        pitchSt = Math.round(Fmt.clamp(st, -12f, 12f) * 2f) / 2f;
        Prefs.setPitch(this, pitchSt);
        applyPlaybackParams();
        notifySettings();
    }

    private void applyPlaybackParams() {
        player.setPlaybackParameters(new PlaybackParameters(speed, Fmt.pitchFactor(pitchSt)));
    }

    public boolean isSkipSilence() {
        return skipSilence;
    }

    public void setSkipSilence(boolean enabled) {
        skipSilence = enabled;
        Prefs.setSkipSilence(this, enabled);
        player.setSkipSilenceEnabled(enabled);
        notifySettings();
    }

    /** Volume boost of the current track in dB (0 = off). */
    public float getBoostDb() {
        return boostDb;
    }

    public void setBoostDb(float db) {
        boostDb = Math.round(Fmt.clamp(db, 0f, MAX_BOOST_DB) * 2f) / 2f;
        if (currentKey != null) {
            Prefs.setBoost(this, currentKey, boostDb);
        }
        applyBoost();
        notifySettings();
    }

    private void applyTrackBoost() {
        boostDb = currentKey != null ? Prefs.getBoost(this, currentKey) : 0f;
        applyBoost();
    }

    private void applyBoost() {
        boost.setGain(Fmt.dbToGain(boostDb));
        if (!sinkWired && fallbackEnhancer != null) {
            try {
                if (boostDb <= 0f) {
                    fallbackEnhancer.setEnabled(false);
                } else {
                    fallbackEnhancer.setTargetGain(Math.round(boostDb * 100f));
                    fallbackEnhancer.setEnabled(true);
                }
            } catch (RuntimeException ignored) {
                // effect not usable on this device
            }
        }
    }

    private void initFallbackEnhancer(int sessionId) {
        if (sinkWired || sessionId == C.AUDIO_SESSION_ID_UNSET) {
            return;
        }
        releaseFallbackEnhancer();
        try {
            fallbackEnhancer = new LoudnessEnhancer(sessionId);
            applyBoost();
        } catch (RuntimeException e) {
            fallbackEnhancer = null;
        }
    }

    private void releaseFallbackEnhancer() {
        if (fallbackEnhancer != null) {
            try {
                fallbackEnhancer.release();
            } catch (RuntimeException ignored) {
                // nothing to do
            }
            fallbackEnhancer = null;
        }
    }

    // ---------------------------------------------------------------- A-B loop

    public long getLoopA() {
        return loopA;
    }

    public long getLoopB() {
        return loopB;
    }

    public boolean hasLoop() {
        return loopA >= 0 && loopB > loopA;
    }

    public void setLoopA(long ms) {
        loopA = Math.max(0, ms);
        if (loopB >= 0 && loopB <= loopA) {
            loopB = -1;
        }
        rescheduleLoop();
        notifyLoop();
    }

    /** Returns false if point A is not set yet or B would not be after A. */
    public boolean setLoopB(long ms) {
        if (loopA < 0 || ms <= loopA + 200) {
            return false;
        }
        loopB = ms;
        rescheduleLoop();
        notifyLoop();
        return true;
    }

    public void clearLoop() {
        boolean had = loopA >= 0 || loopB >= 0;
        loopA = -1;
        loopB = -1;
        rescheduleLoop();
        if (had) {
            notifyLoop();
        }
    }

    private void rescheduleLoop() {
        if (loopMessage != null) {
            loopMessage.cancel();
            loopMessage = null;
        }
        if (player == null || loopA < 0 || loopB <= loopA) {
            return;
        }
        final long a = loopA;
        PlayerMessage m = player.createMessage((messageType, payload) -> player.seekTo(a));
        m.setLooper(Looper.getMainLooper());
        m.setPosition(player.getCurrentMediaItemIndex(), loopB);
        m.setDeleteAfterDelivery(false);
        m.send();
        loopMessage = m;
    }

    // ---------------------------------------------------------------- sleep timer

    public void startSleepTimer(long durationMs) {
        cancelSleep(false);
        sleepEndAt = SystemClock.elapsedRealtime() + durationMs;
        handler.postDelayed(sleepRunnable, durationMs);
        notifySleep();
    }

    public void startSleepEndOfTrack() {
        cancelSleep(false);
        sleepEndOfTrack = true;
        player.setPauseAtEndOfMediaItems(true);
        notifySleep();
    }

    public void cancelSleepTimer() {
        cancelSleep(true);
    }

    private void cancelSleep(boolean notify) {
        handler.removeCallbacks(sleepRunnable);
        sleepEndAt = 0;
        if (sleepEndOfTrack) {
            sleepEndOfTrack = false;
            player.setPauseAtEndOfMediaItems(false);
        }
        if (notify) {
            notifySleep();
        }
    }

    public boolean isSleepActive() {
        return sleepEndAt != 0 || sleepEndOfTrack;
    }

    public boolean isSleepEndOfTrack() {
        return sleepEndOfTrack;
    }

    public long getSleepRemainingMs() {
        return sleepEndAt == 0 ? 0 : Math.max(0, sleepEndAt - SystemClock.elapsedRealtime());
    }
}
