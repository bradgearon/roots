package com.wds.sixstars.contextoverlay;

import android.app.*;
import android.os.*;
import android.text.InputFilter;
import android.widget.*;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;

public final class EncounterActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private TextView status,summary,permission,last;
    private Switch toggle,automaticPublication,fullRootGraph;
    private LinearLayout peerList,eventList;
    private boolean updating,visible,loading;
    private String active="";
    private EncounterBindings.Snapshot snapshot;
    private final Runnable refresh=new Runnable(){@Override public void run(){if(!visible)return;update();handler.postDelayed(this,2000);}};
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);
        LinearLayout shell=RootsUi.shell(this);shell.addView(RootsUi.header(this,"Roots Encounters","Automatic Bluetooth knowledge exchange"));
        ScrollView scroll=new ScrollView(this);LinearLayout content=RootsUi.column(this);
        content.setPadding(RootsUi.dp(this,16),0,RootsUi.dp(this,16),0);scroll.addView(content);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout controls=RootsUi.card(this,content);
        toggle=new Switch(this);toggle.setText(getString(R.string.roots_ui_nearby));toggle.setTextColor(RootsUi.TEXT);toggle.setTextSize(18);
        controls.addView(toggle);
        status=RootsUi.text(this,"",14,RootsUi.ACCENT,true);controls.addView(status);
        summary=RootsUi.text(this,"",13,RootsUi.MUTED,false);controls.addView(summary);
        last=RootsUi.text(this,"",13,RootsUi.MUTED,false);controls.addView(last);
        permission=RootsUi.action(this,"Allow nearby exchange",()->EncounterSettings.authorize(this));controls.addView(permission);
        controls.addView(RootsUi.text(this,"Roots exchanges all eligible published topics automatically. Received records enter your main Tree as external references, without granting private membership.",13,RootsUi.MUTED,false));
        automaticPublication=new Switch(this);automaticPublication.setText("Automatically publish linked original posts");automaticPublication.setTextColor(RootsUi.TEXT);controls.addView(automaticPublication);
        controls.addView(RootsUi.text(this,"Restricted mode: linked originals only. Full Root mode below includes unlinked knowledge, all Tree observations and branches.",13,RootsUi.MUTED,false));
        fullRootGraph=new Switch(this);fullRootGraph.setText("Share full Root graph");fullRootGraph.setTextColor(RootsUi.TEXT);controls.addView(fullRootGraph);
        controls.addView(RootsUi.text(this,"No topic or source-link filter. Knowledge travels through the secure connection; only enable if all retained content may be publicly shared. This does not authorize private GPU access.",13,RootsUi.MUTED,false));
        controls.addView(RootsUi.action(this,getString(R.string.roots_ui_receive),()->startActivity(new android.content.Intent(this,MeshActivity.class))));
        controls.addView(RootsUi.action(this,"Publish existing public Tree items",()->PublicTreePublishing.choose(this,this::update)));
        controls.addView(RootsUi.action(this,getString(R.string.roots_ui_external),()->startActivity(new android.content.Intent(this,ExternalKnowledgeActivity.class))));
        controls.addView(RootsUi.action(this,getString(R.string.roots_ui_network),()->startActivity(new android.content.Intent(this,NetworkExplorerActivity.class))));
        LinearLayout peers=RootsUi.card(this,content);peers.addView(RootsUi.text(this,getString(R.string.roots_ui_axons),18,RootsUi.TEXT,true));
        peers.addView(RootsUi.text(this,"Nearby subscriptions persist offline and appear on the Roots home screen. Block a peer to stop future exchange; previously transferred knowledge remains.",13,RootsUi.MUTED,false));
        peerList=RootsUi.column(this);peers.addView(peerList);
        LinearLayout history=RootsUi.card(this,content);history.addView(RootsUi.text(this,"Recent interactions",18,RootsUi.TEXT,true));
        history.addView(RootsUi.action(this,"Clear interaction history",()->new AlertDialog.Builder(this).setTitle("Clear interaction history?")
            .setMessage("Saved bindings and transfer totals will remain.").setNegativeButton(R.string.roots_ui_cancel,null)
            .setPositiveButton(R.string.roots_ui_clear,(d,w)->mutate(()->EncounterBindings.get(this).clearHistory())).show()));
        eventList=RootsUi.column(this);history.addView(eventList);
        shell.addView(RootsUi.action(this,"‹ Roots Mesh",this::finish));setContentView(shell);
        fullRootGraph.setOnCheckedChangeListener((button,on)->{
            if(updating)return;EncounterSettings.configureRootGraph(this,on,this::update);update();
        });
        automaticPublication.setOnCheckedChangeListener((button,on)->{
            if(updating)return;EncounterSettings.configureAutomaticPublication(this,on,this::update);update();
        });
        toggle.setOnCheckedChangeListener((button,on)->{
            if(updating)return;EncounterSettings.setEnabled(this,on);
            if(on){if(EncounterSettings.eligible(this))EncounterSettings.startIfAllowed(this);else EncounterSettings.authorize(this);}
            update();
        });
    }
    @Override protected void onResume(){super.onResume();visible=true;EncounterSettings.foreground(this);handler.removeCallbacks(refresh);refresh.run();}
    @Override protected void onPause(){visible=false;handler.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){work.shutdownNow();super.onDestroy();}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);EncounterSettings.permissionResult(this,request);update();
    }
    private void update(){
        boolean supported=EncounterService.available(this),enabled=EncounterSettings.enabled(this),eligible=EncounterSettings.eligible(this);
        updating=true;toggle.setChecked(enabled);toggle.setEnabled(supported);
        automaticPublication.setChecked(EncounterSettings.automaticPublication(this));automaticPublication.setEnabled(supported&&!EncounterSettings.fullRootGraph(this));
        fullRootGraph.setChecked(EncounterSettings.fullRootGraph(this));fullRootGraph.setEnabled(supported);updating=false;
        status.setText(!supported?"Requires Android 12+ and BLE":!enabled?"Off":!eligible?"On · awaiting one-time permission":EncounterService.status());
        last.setText(EncounterService.lastResult());permission.setVisibility(supported&&enabled&&!eligible?android.view.View.VISIBLE:android.view.View.GONE);
        if(loading||work.isShutdown())return;loading=true;
        work.execute(()->{
            EncounterBindings.Snapshot next=EncounterBindings.get(this).snapshot();
            String counts;
            try{
                MeshPeer.EncounterCatalogStats stats=MeshPeer.get(this).encounterPublicationStats();
                counts=stats.total()+" local offers · "+stats.automatic()+" automatic · "+ExternalKnowledgeStore.get(this).count()+" external receipts";
                if(!EncounterSettings.fullRootGraph(this))counts+=" · "+stats.unlinked()+" originals without exact links";
                if(EncounterSettings.fullRootGraph(this))counts+=" · full Root graph";else if(!EncounterSettings.automaticPublication(this))counts+=" · restricted publication";
            }
            catch(Exception|LinkageError failure){counts="Public catalog unavailable";}
            final String publicationCounts=counts;
            runOnUiThread(()->{
                loading=false;if(!visible||isDestroyed())return;
                String now=EncounterService.activePeer();
                if(!next.equals(snapshot)||!active.equals(now)){snapshot=next;active=now;render();}
                summary.setText(summary.getText().toString().split("\\n",2)[0]+"\n"+publicationCounts);
            });
        });
    }
    private static String date(long at){return at<=0?"Unknown":DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(at));}
    private static String name(EncounterBindings.Peer peer){return peer.label().isEmpty()?"Axon "+peer.binding().peer().substring(0,12):peer.label();}
    private void render(){
        peerList.removeAllViews();eventList.removeAllViews();
        long sent=0,received=0;int allowed=0;Map<String,String> names=new HashMap<>();
        for(EncounterBindings.Peer peer:snapshot.peers()){
            sent+=peer.sent();received+=peer.received();if(!peer.blocked())allowed++;
            names.put(peer.binding().peer(),name(peer));
        }
        summary.setText(allowed+" bound · "+(snapshot.peers().size()-allowed)+" blocked · "+sent+" sent · "+received+" received");
        if(snapshot.peers().isEmpty())peerList.addView(RootsUi.text(this,"No encounters yet. Roots will bind automatically when a peer is nearby.",13,RootsUi.MUTED,false));
        for(EncounterBindings.Peer peer:snapshot.peers()){
            String id=peer.binding().peer();
            LinearLayout item=RootsUi.card(this,peerList);
            item.addView(RootsUi.text(this,name(peer),16,RootsUi.TEXT,true));
            item.addView(RootsUi.text(this,peer.blocked()?"Blocked":id.equals(active)?"Connected · exchanging":"Bound · offline",13,RootsUi.ACCENT,true));
            item.addView(RootsUi.text(this,"Last seen "+date(peer.lastSeen())+" · "+peer.binding().encounters()+" encounters\n"+peer.sent()+" sent · "+peer.received()+" received · "+peer.result(),13,RootsUi.MUTED,false));
            item.addView(RootsUi.action(this,"Manage connection",()->manage(peer)));
        }
        if(snapshot.events().isEmpty())eventList.addView(RootsUi.text(this,"No recorded interactions.",13,RootsUi.MUTED,false));
        for(EncounterBindings.Event event:snapshot.events()){
            String who=names.getOrDefault(event.peer(),event.peer().isEmpty()?"Nearby peer":"Axon "+event.peer().substring(0,12));
            eventList.addView(RootsUi.text(this,who+" · "+event.result()+"\n"+date(event.at())+" · "+event.sent()+" sent · "+event.received()+" received",13,RootsUi.TEXT,false));
        }
    }
    private void manage(EncounterBindings.Peer peer){
        String id=peer.binding().peer();
        new AlertDialog.Builder(this).setTitle(name(peer)).setMessage("Identity: "+id+"\nFirst seen: "+date(peer.firstSeen())+"\nScope: all offered Root knowledge\nBlocking prevents automatic reconnection.")
            .setNegativeButton(R.string.roots_ui_close,null)
            .setNeutralButton("Rename",(d,w)->rename(peer))
            .setPositiveButton(peer.blocked()?"Unblock":"Block",(d,w)->mutate(()->{
                try{EncounterBindings.get(this).setBlocked(id,!peer.blocked());}
                finally{if(EncounterBindings.get(this).blocked(id))EncounterService.disconnectPeer(id);else EncounterService.refreshDiscovery();}
            })).show();
    }
    private void rename(EncounterBindings.Peer peer){
        EditText input=new EditText(this);input.setSingleLine(true);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});input.setText(peer.label());input.setHint("Local label");
        new AlertDialog.Builder(this).setTitle("Rename Axon").setView(input).setNegativeButton(R.string.roots_ui_cancel,null)
            .setPositiveButton(R.string.roots_ui_save,(d,w)->{String label=input.getText().toString();mutate(()->EncounterBindings.get(this).rename(peer.binding().peer(),label));}).show();
    }
    private interface Mutation{void run()throws Exception;}
    private void mutate(Mutation mutation){
        if(work.isShutdown())return;
        work.execute(()->{try{mutation.run();}catch(Exception failure){runOnUiThread(()->Toast.makeText(this,"Could not save connection change",Toast.LENGTH_LONG).show());}
            runOnUiThread(()->{if(visible)update();});});
    }
}
