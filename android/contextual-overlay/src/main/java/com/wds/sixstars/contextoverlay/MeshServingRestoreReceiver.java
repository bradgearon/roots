package com.wds.sixstars.contextoverlay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restore only an explicitly enabled listener, after normal boot/unlock or
 * an in-place APK update. No exported command or remote wake broadcast. */
public final class MeshServingRestoreReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(intent==null)return;
        String action=intent.getAction();
        if(Intent.ACTION_BOOT_COMPLETED.equals(action)||Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))
            MeshServingService.restore(context);
    }
}
