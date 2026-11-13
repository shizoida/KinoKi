package com.kinoki.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the TV ready to be cast to. Started when the app is first opened; it keeps the process and the small
 * {@link CastHost} server alive after the user goes back to the launcher, so the phone can start playback later.
 */
public class CastService extends Service {

    private static final String CHANNEL_ID = "cast";
    private static final int NOTIFICATION_ID = 1;

    static void start(Context context) {
        Intent intent = new Intent(context, CastService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Minimal importance: the TV has nothing to show, the notification only satisfies the foreground rules.
            manager.createNotificationChannel(
                    new NotificationChannel(CHANNEL_ID, getString(R.string.cast_channel),
                            NotificationManager.IMPORTANCE_MIN));
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        startForeground(NOTIFICATION_ID, builder
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.cast_ready))
                .setOngoing(true)
                .build());
        CastHost.start(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        CastHost.stop();
        super.onDestroy();
    }
}
