package com.meowsic.app;

import android.net.Uri;

/** One playable audio file. Immutable. */
public final class Track {
    /** Stable id used for resume position / boost / waveform cache. */
    public final String key;
    public final Uri uri;
    public final String title;
    public final String artist;
    public final String album;
    public final String folder;
    public final long durationMs;
    /** Seconds since epoch (MediaStore DATE_ADDED), 0 if unknown. */
    public final long dateAdded;
    public final long size;
    /** Cover art location, may be null. */
    public final Uri artUri;

    public Track(String key, Uri uri, String title, String artist, String album, String folder,
                 long durationMs, long dateAdded, long size, Uri artUri) {
        this.key = key;
        this.uri = uri;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.folder = folder;
        this.durationMs = durationMs;
        this.dateAdded = dateAdded;
        this.size = size;
        this.artUri = artUri;
    }
}
