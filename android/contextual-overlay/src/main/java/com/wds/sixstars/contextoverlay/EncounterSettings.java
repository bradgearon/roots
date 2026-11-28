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
    // Fresh installs approve this default scope as part of initial consent.
    // Existing open-exchange permission covered explicit publications only.
    static boolean automaticPublication(Context c){return prefs(c).getBoolean("automatic_public_posts",false);}
    static boolean publicationScopeReviewed(Context c){return prefs(c).getBoolean("automatic_public_reviewed",false);}
    private static void setAutomaticPublication(Context c,boolean enabled){
        prefs(c).edit().putBoolean("automatic_public_posts",enabled).putBoolean("automatic_public_reviewed",true).commit();
        if(com.wds.sixstars.contextoverlay.tree.NativeTree.available())MeshPeer.get(c).invalidateEncounterPublicationCount();
        EncounterService.refreshDiscovery();
    }
    static void configureAutomaticPublication(Activity activity,boolean enabled,Runnable changed){
        if(!enabled){setAutomaticPublication(activity,false);changed.run();return;}
        if(explaining)return;
        explaining=true;
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Automatically publish linked original posts?")
            .setMessage("Roots will offer every retained original X post with an exact source link automatically, without a per-item selection or topic filter. An exact link does not prove a post is public; only enable this if your linked captured posts are public and you have permission to redistribute them. Private understanding, other screen text, prompts and credentials are excluded. Received knowledge enters your Tree as external reference material. Shared copies cannot reliably be recalled.")
            .setNegativeButton("Only existing publications",(d,w)->{setAutomaticPublication(activity,false);changed.run();})
            .setPositiveButton("Enable automatic publication",(d,w)->{setAutomaticPublication(activity,true);changed.run();}).create();
        dialog.setOnDismissListener(d->{explaining=false;prefs(activity).edit().putBoolean("automatic_public_reviewed",true).apply();changed.run();});
        dialog.show();
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
        if(eligible(activity)){
            startIfAllowed(activity);
            if(!publicationScopeReviewed(activity))configureAutomaticPublication(activity,true,()->{});
            return;
        }
        if(!prefs(activity).getBoolean("setup_shown",false))authorize(activity);
    }
    static void authorize(Activity activity){
        if(!enabled(activity)||!EncounterService.available(activity)||explaining)return;
        if(consent(activity)){request(activity);return;}
        explaining=true;
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Nearby Roots exchange is on by default")
            .setMessage("Roots automatically binds over Bluetooth, shares public knowledge and retains received records in your Tree as external references. The default automatic publication scope includes original X posts with exact links. Links do not prove public visibility; enable automatic publication only if those captured posts are safe and permitted to redistribute. Private notes, other screen text, prompts and credentials are excluded. Copies cannot reliably be recalled.")
            .setNegativeButton("Not now",(d,w)->{prefs(activity).edit().putBoolean("setup_shown",true).apply();})
            .setNeutralButton("Only existing publications",(d,w)->{
                prefs(activity).edit().putBoolean("consent",true).putBoolean("setup_shown",true).putBoolean("automatic_public_posts",false).putBoolean("automatic_public_reviewed",true).commit();request(activity);
            })
            .setPositiveButton("Enable automatic sharing",(d,w)->{
                prefs(activity).edit().putBoolean("consent",true).putBoolean("setup_shown",true).putBoolean("automatic_public_posts",true).putBoolean("automatic_public_reviewed",true).commit();request(activity);
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
