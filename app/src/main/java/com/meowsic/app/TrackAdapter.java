package com.meowsic.app;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** The list of tracks on the library screen. */
public class TrackAdapter extends RecyclerView.Adapter<TrackAdapter.Holder> {
    public interface Listener {
        void onTrackClick(Track t);
    }

    private final Listener listener;
    private List<Track> items = new ArrayList<>();
    private String currentKey;

    public TrackAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<Track> list) {
        items = list;
        notifyDataSetChanged();
    }

    public List<Track> getItems() {
        return items;
    }

    public void setCurrentKey(String key) {
        boolean same = key == null ? currentKey == null : key.equals(currentKey);
        if (!same) {
            currentKey = key;
            notifyDataSetChanged();
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_track, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        final Track t = items.get(position);
        h.title.setText(t.title);
        String sub;
        if (t.artist.isEmpty()) {
            sub = t.folder;
        } else if (t.folder.isEmpty()) {
            sub = t.artist;
        } else {
            sub = t.artist + " \u2022 " + t.folder;
        }
        h.subtitle.setText(sub);
        h.subtitle.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
        h.duration.setText(t.durationMs > 0 ? Fmt.time(t.durationMs) : "");
        boolean current = t.key.equals(currentKey);
        if (current) {
            h.title.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.sovio_primary));
        } else {
            h.title.setTextColor(h.defaultTitleColors);
        }
        h.itemView.setOnClickListener(v -> listener.onTrackClick(t));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;
        final TextView duration;
        final ColorStateList defaultTitleColors;

        Holder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.track_title);
            subtitle = itemView.findViewById(R.id.track_subtitle);
            duration = itemView.findViewById(R.id.track_duration);
            defaultTitleColors = title.getTextColors();
        }
    }
}
