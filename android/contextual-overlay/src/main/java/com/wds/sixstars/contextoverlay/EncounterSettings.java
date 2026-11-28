package com.wds.sixstars.contextoverlay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.Manifest;

/** Desired state is separate from OS permission and actual radio state. */
@android.annotation.TargetApi(31)
final class EncounterSettings {
    static final int REQUEST=8234;
    private static boolean explaining;
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("roots_encounter_service",Context.MODE_PRIVATE);}
    static boolean enabled(Context c){return prefs(c).getBoolean("enabled",true);}
    static boolean consent(Context c){
        return prefs(c).getBoolean("consent",false)||c.getSharedPreferences("EncounterActivity",Context.MODE_PRIVATE).getBoolean("open_exchange_v1",false);
    }
    static String[] permissions(){return new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_ADVERTISE};}
    static boolean granted(Context c){
        if(!EncounterService.available(c))return false;
        for(String p:permissions())if(c.checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)return false;
        return true;
    }
    static boolean eligible(Context c){return enabled(c)&&consent(c)&&granted(c);}
    static void setEnabled(Context c,boolean enabled){
        prefs(c).edit().putBoolean("enabled",enabled).commit();
        if(!enabled)c.stopService(new Intent(c,EncounterService.class));
    }
    static void startIfAllowed(Context c){
        if(!eligible(c)||EncounterService.isRunning())return;
        try{c.startForegroundService(new Intent(c,EncounterService.class));}
        catch(RuntimeException denied){EncounterService.noteStartDeferred();}
    }
    static void foreground(Activity activity){
        if(!EncounterService.available(activity)||!enabled(activity))return;
        if(eligible(activity)){startIfAllowed(activity);return;}
        if(!prefs(activity).getBoolean("setup_shown",false))authorize(activity);
    }
    static void authorize(Activity activity){
        if(!enabled(activity)||!EncounterService.available(activity)||explaining)return;
        if(consent(activity)){request(activity);return;}
        explaining=true;
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Nearby Roots exchange is on by default")
            .setMessage("Roots automatically discovers and binds to nearby Roots devices over Bluetooth and exchanges all explicitly published knowledge, including onward sharing. Raw captured Tree data and private prompts stay outside this exchange. Copies already delivered cannot be recalled. You can turn the service off or block a peer in Roots Mesh.")
            .setNegativeButton("Not now",(d,w)->{prefs(activity).edit().putBoolean("setup_shown",true).apply();})
            .setPositiveButton("Continue",(d,w)->{
                prefs(activity).edit().putBoolean("consent",true).putBoolean("setup_shown",true).commit();request(activity);
            }).create();
        dialog.setOnDismissListener(d->{explaining=false;prefs(activity).edit().putBoolean("setup_shown",true).apply();});
        dialog.show();
    }
    private static void request(Activity activity){
        prefs(activity).edit().putBoolean("setup_shown",true).apply();
        if(granted(activity)){startIfAllowed(activity);return;}
        activity.requestPermissions(permissions(),REQUEST);
    }
    static void permissionResult(Activity activity,int request){
        if(request==REQUEST)startIfAllowed(activity);
    }
}
