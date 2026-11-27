package com.wds.sixstars.contextoverlay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.*;
import android.widget.*;

public final class EncounterActivity extends Activity {
    private static final int REQUEST=8234;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private String requested;
    private TextView status,bindings,last,publish,passerby,stop;
    private final Runnable refresh=new Runnable(){@Override public void run(){update();handler.postDelayed(this,2000);}};
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);
        LinearLayout shell=RootsUi.shell(this);
        shell.addView(RootsUi.header(this,"Roots Encounters","Bluetooth · no Wi-Fi or Internet"));
        ScrollView scroll=new ScrollView(this);
        LinearLayout content=RootsUi.column(this);content.setPadding(RootsUi.dp(this,16),0,RootsUi.dp(this,16),RootsUi.dp(this,16));
        scroll.addView(content);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout open=RootsUi.card(this,content);
        open.addView(RootsUi.text(this,"Open exchange",18,RootsUi.TEXT,true));
        open.addView(RootsUi.text(this,"Automatically bind to nearby Roots Posts and exchange all explicitly published topics in both directions. Your raw captured Tree, prompts and LAN invitation credentials are not published. Copies can travel onward and cannot reliably be recalled.",14,RootsUi.MUTED,false));
        publish=RootsUi.action(this,"Start Publisher · advertise a Roots Post",()->confirm(EncounterService.PUBLISHER));open.addView(publish);
        passerby=RootsUi.action(this,"Start Passerby · listen for Roots Posts",()->confirm(EncounterService.PASSERBY));open.addView(passerby);
        stop=RootsUi.action(this,"Stop nearby exchange",()->{
            startService(new Intent(this,EncounterService.class).setAction(EncounterService.ACTION_STOP));
        });open.addView(stop);
        status=RootsUi.text(this,"",14,RootsUi.ACCENT,true);open.addView(status);
        bindings=RootsUi.text(this,"",14,RootsUi.TEXT,false);open.addView(bindings);
        last=RootsUi.text(this,"",13,RootsUi.MUTED,false);open.addView(last);
        open.addView(RootsUi.text(this,"A persistent notification keeps the radio service visible while Roots is in the background. Android 12+ and compatible BLE hardware are required; discovery is best effort. Stop before switching roles. Saved bindings survive disconnection.",13,RootsUi.MUTED,false));
        shell.addView(RootsUi.action(this,"‹ Roots Mesh",this::finish));
        setContentView(shell);update();
    }
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(refresh);refresh.run();}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    private void update(){
        boolean available=EncounterService.available(this),running=EncounterService.isRunning();
        status.setText(available?EncounterService.status():"Nearby exchange requires Android 12+ and Bluetooth LE");
        bindings.setText(new EncounterBindings(this).count()+" persistent encounter Axon(s) · all published topics");
        last.setText(EncounterService.lastResult());
        enable(publish,available&&!running);enable(passerby,available&&!running);enable(stop,running);
    }
    private static void enable(TextView view,boolean enabled){view.setEnabled(enabled);view.setAlpha(enabled?1f:0.45f);}
    private void confirm(String role){
        if(!EncounterService.available(this)||EncounterService.isRunning())return;
        // Global consent is required once per installation; never per encounter.
        if(getPreferences(MODE_PRIVATE).getBoolean("open_exchange_v1",false)){authorize(role);return;}
        new AlertDialog.Builder(this).setTitle("Enable open Roots exchange?")
            .setMessage("Every nearby compatible Roots peer can automatically bind and receive your explicitly published knowledge, across all topics. Received publications can be forwarded to others. Only publish information you have permission to redistribute. You can stop exchange at any time; already delivered copies cannot be recalled.")
            .setNegativeButton("Cancel",null).setPositiveButton("Enable",(dialog,which)->{
                getPreferences(MODE_PRIVATE).edit().putBoolean("open_exchange_v1",true).apply();authorize(role);
            }).show();
    }
    private void authorize(String role){
        requested=role;
        String[] permissions=EncounterService.permissions(role);
        for(String permission:permissions)if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(permissions,REQUEST);return;
        }
        launch(role);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request!=REQUEST||requested==null)return;
        for(String permission:EncounterService.permissions(requested))if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED){
            Toast.makeText(this,"Nearby devices permission required",Toast.LENGTH_LONG).show();return;
        }
        launch(requested);
    }
    private void launch(String role){
        try{
            startForegroundService(new Intent(this,EncounterService.class).putExtra(EncounterService.EXTRA_MODE,role));
            Toast.makeText(this,"Starting Roots nearby exchange",Toast.LENGTH_SHORT).show();
        }catch(Exception failure){Toast.makeText(this,"Could not start nearby exchange",Toast.LENGTH_LONG).show();}
        update();
    }
}
