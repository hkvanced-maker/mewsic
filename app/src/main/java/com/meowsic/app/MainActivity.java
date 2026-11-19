package com.meowsic.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Library screen: every audio file on the phone, search, sort, mini player. */
public class MainActivity extends AppCompatActivity
        implements PlaybackClient.Callback, TrackAdapter.Listener {

    private PlaybackClient client;
    private PlaybackService service;
    private TrackAdapter adapter;
    private SearchView searchView;
    private View emptyView;
    private View loadingView;
    private View miniPlayer;
    private TextView emptyText;
    private TextView miniTitle;
    private TextView miniSub;
    private Button emptyButton;
    private ImageButton miniPlay;
    private ProgressBar miniProgress;

    private final List<Track> all = new ArrayList<>();
    private String query = "";
    private boolean askedPermission;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String[]> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> onPermissionResult());
    private final ActivityResultLauncher<String[]> pickerLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(), this::onFilesPicked);

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onEvents(Player player, Player.Events events) {
            refreshMini();
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshMini();
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.inflateMenu(R.menu.menu_main);
        setupMenu(toolbar.getMenu());
        toolbar.setOnMenuItemClickListener(this::onMenuItem);

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TrackAdapter(this);
        list.setAdapter(adapter);

        emptyView = findViewById(R.id.empty_view);
        emptyText = findViewById(R.id.empty_text);
        emptyButton = findViewById(R.id.empty_button);
        loadingView = findViewById(R.id.loading);
        miniPlayer = findViewById(R.id.mini_player);
        miniTitle = findViewById(R.id.mini_title);
        miniSub = findViewById(R.id.mini_subtitle);
        miniPlay = findViewById(R.id.mini_play);
        miniProgress = findViewById(R.id.mini_progress);

        miniPlayer.setOnClickListener(v -> openPlayer());
        miniPlay.setOnClickListener(v -> {
            if (service != null && service.getPlayer().getMediaItemCount() > 0) {
                Player p = service.getPlayer();
                if (p.getPlayWhenReady() && p.getPlaybackState() != Player.STATE_ENDED) {
                    p.pause();
                } else {
                    p.play();
                }
            }
        });
        emptyButton.setOnClickListener(v -> onEmptyButton());

        client = new PlaybackClient(this, this);
        if (hasAudioPermission()) {
            loadLibrary();
        } else {
            requestAccess();
        }
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
        if (service != null) {
            service.getPlayer().removeListener(playerListener);
        }
        client.disconnect();
        service = null;
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
        s.getPlayer().addListener(playerListener);
        refreshMini();
        ui.removeCallbacks(tick);
        ui.postDelayed(tick, 500);
    }

    @Override
    public void onDisconnected() {
        service = null;
    }

    private void refreshMini() {
        if (service == null) {
            return;
        }
        Player p = service.getPlayer();
        Track t = service.getCurrentTrack();
        if (t == null || p.getMediaItemCount() == 0) {
            miniPlayer.setVisibility(View.GONE);
            adapter.setCurrentKey(null);
            return;
        }
        miniPlayer.setVisibility(View.VISIBLE);
        miniTitle.setText(t.title);
        miniSub.setText(t.artist.isEmpty() ? t.folder : t.artist);
        boolean showPause = p.getPlayWhenReady() && p.getPlaybackState() != Player.STATE_ENDED
                && p.getPlaybackState() != Player.STATE_IDLE;
        miniPlay.setImageResource(showPause ? R.drawable.ic_pause : R.drawable.ic_play);
        long dur = p.getDuration();
        if (dur == C.TIME_UNSET || dur <= 0) {
            dur = t.durationMs;
        }
        miniProgress.setMax(1000);
        miniProgress.setProgress(dur > 0 ? (int) Math.min(1000L, p.getCurrentPosition() * 1000L / dur) : 0);
        adapter.setCurrentKey(t.key);
    }

    private void openPlayer() {
        startActivity(new Intent(this, PlayerActivity.class));
    }

    // ------------------------------------------------------------------ menu

    private void setupMenu(Menu menu) {
        MenuItem searchItem = menu.findItem(R.id.action_search);
        searchView = (SearchView) searchItem.getActionView();
        searchView.setQueryHint(getString(R.string.search_hint));
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String q) {
                return true;
            }

            @Override
            public boolean onQueryTextChange(String q) {
                query = q == null ? "" : q.trim();
                applyFilter();
                return true;
            }
        });
        menu.findItem(R.id.action_system_sounds).setChecked(Prefs.getShowSystemSounds(this));
        int sort = Prefs.getSort(this);
        int checkId = sort == Library.SORT_TITLE ? R.id.sort_title
                : sort == Library.SORT_LONGEST ? R.id.sort_longest : R.id.sort_newest;
        menu.findItem(checkId).setChecked(true);
    }

    private boolean onMenuItem(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_open) {
            pickerLauncher.launch(new String[]{"audio/*", "application/ogg"});
            return true;
        } else if (id == R.id.action_rescan) {
            loadLibrary();
            return true;
        } else if (id == R.id.action_system_sounds) {
            boolean v = !item.isChecked();
            item.setChecked(v);
            Prefs.setShowSystemSounds(this, v);
            loadLibrary();
            return true;
        } else if (id == R.id.sort_newest || id == R.id.sort_title || id == R.id.sort_longest) {
            item.setChecked(true);
            Prefs.setSort(this, id == R.id.sort_title ? Library.SORT_TITLE
                    : id == R.id.sort_longest ? Library.SORT_LONGEST : Library.SORT_NEWEST);
            sortAll();
            applyFilter();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ permissions + loading

    private String audioPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAccess() {
        if (Build.VERSION.SDK_INT >= 33) {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS});
        } else {
            permissionLauncher.launch(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE});
        }
    }

    private void onPermissionResult() {
        askedPermission = true;
        if (hasAudioPermission()) {
            loadLibrary();
        } else {
            updateEmptyState(true);
        }
    }

    private void onEmptyButton() {
        if (!hasAudioPermission()) {
            boolean blocked = askedPermission
                    && !ActivityCompat.shouldShowRequestPermissionRationale(this, audioPermission());
            if (blocked) {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null));
                startActivity(i);
            } else {
                requestAccess();
            }
        } else {
            pickerLauncher.launch(new String[]{"audio/*", "application/ogg"});
        }
    }

    private void loadLibrary() {
        loadingView.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);
        final boolean system = Prefs.getShowSystemSounds(this);
        io.execute(() -> {
            final List<Track> loaded = Library.load(getApplicationContext(), system);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                loadingView.setVisibility(View.GONE);
                all.clear();
                all.addAll(loaded);
                sortAll();
                applyFilter();
            });
        });
    }

    private void sortAll() {
        Library.sort(all, Prefs.getSort(this));
    }

    private void applyFilter() {
        List<Track> out = new ArrayList<>();
        String q = query.toLowerCase();
        for (Track t : all) {
            if (q.isEmpty()
                    || t.title.toLowerCase().contains(q)
                    || t.artist.toLowerCase().contains(q)
                    || t.album.toLowerCase().contains(q)
                    || t.folder.toLowerCase().contains(q)) {
                out.add(t);
            }
        }
        adapter.setItems(out);
        updateEmptyState(out.isEmpty());
    }

    private void updateEmptyState(boolean empty) {
        if (!hasAudioPermission()) {
            emptyView.setVisibility(View.VISIBLE);
            emptyText.setText(R.string.permission_needed);
            emptyButton.setText(R.string.grant_access);
            emptyButton.setVisibility(View.VISIBLE);
        } else if (empty && loadingView.getVisibility() != View.VISIBLE) {
            emptyView.setVisibility(View.VISIBLE);
            if (query.isEmpty()) {
                emptyText.setText(R.string.no_audio_found);
                emptyButton.setText(R.string.open_files);
                emptyButton.setVisibility(View.VISIBLE);
            } else {
                emptyText.setText(R.string.no_matches);
                emptyButton.setVisibility(View.GONE);
            }
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------------ actions

    @Override
    public void onTrackClick(Track t) {
        if (service == null) {
            return;
        }
        List<Track> shown = adapter.getItems();
        int idx = shown.indexOf(t);
        if (idx < 0) {
            return;
        }
        int from = Math.max(0, idx - 100);
        int to = Math.min(shown.size(), idx + 300);
        service.setQueue(new ArrayList<>(shown.subList(from, to)), idx - from);
        openPlayer();
    }

    private void onFilesPicked(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            return;
        }
        final List<Uri> picked = new ArrayList<>(uris);
        io.execute(() -> {
            final List<Track> tracks = new ArrayList<>();
            for (Uri u : picked) {
                try {
                    getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                    // not every provider offers persistable grants
                }
                tracks.add(Library.fromUri(getApplicationContext(), u));
            }
            ui.post(() -> {
                if (service != null && !isFinishing()) {
                    service.setQueue(tracks, 0);
                    openPlayer();
                }
            });
        });
    }
}
