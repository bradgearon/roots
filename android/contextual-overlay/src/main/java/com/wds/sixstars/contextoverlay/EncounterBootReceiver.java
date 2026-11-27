package com.wds.sixstars.contextoverlay;
import android.content.*;
/** Restore an already authorized, enabled service after normal boot/update. */
public final class EncounterBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        String action=intent.getAction();
        if(Intent.ACTION_BOOT_COMPLETED.equals(action)||Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))
            EncounterSettings.startIfAllowed(context);
    }
}
