package com.meowsic.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

/** Binds an activity to {@link PlaybackService} (same process, direct access to the player). */
public final class PlaybackClient implements ServiceConnection {
    public interface Callback {
        void onConnected(PlaybackService service);

        void onDisconnected();
    }

    private final Context appContext;
    private final Callback callback;
    private PlaybackService service;
    private boolean bound;

    public PlaybackClient(Context context, Callback callback) {
        this.appContext = context.getApplicationContext();
        this.callback = callback;
    }

    public void connect() {
        if (bound) {
            return;
        }
        Intent intent = new Intent(appContext, PlaybackService.class);
        intent.setAction(PlaybackService.ACTION_LOCAL);
        bound = appContext.bindService(intent, this, Context.BIND_AUTO_CREATE);
    }

    public void disconnect() {
        if (bound) {
            appContext.unbindService(this);
            bound = false;
        }
        service = null;
    }

    public PlaybackService getService() {
        return service;
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((PlaybackService.LocalBinder) binder).getService();
        callback.onConnected(service);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
        callback.onDisconnected();
    }
}
