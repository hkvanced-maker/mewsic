package com.meowsic.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Binder;
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
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

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
    public static final String ACTION_LOCAL = "com.meowsic.app.action.LOCAL_BIND";
    public static final float MIN_SPEED = 0.25f;
    public static final float MAX_SPEED_DEFAULT = 3.0f;
    public static final float MAX_SPEED_EXTENDED = 5.0f;
    public static final float MAX_BOOST_DB = 24f;
    private static final long PROGRESS_SAVE_INTERVAL_MS = 4000;

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
    private final class MeowRenderersFactory extends DefaultRenderersFactory {
        MeowRenderersFactory(Context context) {
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

        player = new ExoPlayer.Builder(this, new MeowRenderersFactory(this))
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
        session = new MediaSession.Builder(this, player).setSessionActivity(contentIntent).build();
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
            if (!playWhenReady && sleepEndOfTrack
                    && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                cancelSleepTimer();
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
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
        saveProgress();
        queue.clear();
        queue.addAll(tracks);
        List<MediaItem> items = new ArrayList<>(tracks.size());
        for (Track t : tracks) {
            items.add(toItem(t));
        }
        int idx = Math.max(0, Math.min(startIndex, tracks.size() - 1));
        Track first = tracks.get(idx);
        long startPos = Prefs.getResume(this, first.key);
        if (startPos < 5000 || (first.durationMs > 0 && startPos > first.durationMs - 8000)) {
            startPos = 0;
        }
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
