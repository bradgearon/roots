package com.wds.sixstars.contextoverlay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.View;
import android.view.WindowManager;
import com.wds.sixstars.contextoverlay.tree.NativeTree;
import com.wds.sixstars.contextoverlay.tree.TreeRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit peer sharing and receiving. A pasted link never connects without
 * user action. Previously approved, saved Axons get foreground health checks. */
public final class MeshActivity extends Activity {
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"RootsMeshUi");t.setDaemon(true);return t;});
    private MeshPeer mesh;
    private TextView status,serve,copy,receive,stop,axonStatus,servingStatus;
    private TextView connectionSummary,connectionStatus,connectionDetails,analysisOutput;
    private TextView testConnection,analyzeConnection,copyConnectionReport;
    private TextView savedAxonStatus,boundConnectionStatus,nearbyStatus,subscriptionStatus;
    private LinearLayout subscriptionRows;
    private String shownSubscriptions="";
    private static final long REFRESH_MS=30000;
    private static final ExecutorService CONNECTION_REFRESH=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"RootsAxonRefresh");t.setDaemon(true);return t;});
    private final android.os.Handler refreshHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile boolean foreground;
    private final Runnable subscriptionObserver=()->runOnUiThread(()->{
        if(foreground&&!isDestroyed()){refreshNearbyStatus();refreshSavedAxon();}
    });
    private final Runnable servingObserver=()->runOnUiThread(()->{if(foreground&&!isDestroyed())refresh();});
    private boolean refreshing,clearingSavedInvite;
    private volatile int foregroundGeneration;
    private android.net.ConnectivityManager refreshNetworkManager;
    private android.net.ConnectivityManager.NetworkCallback refreshNetworkCallback;
    private String lastBoundHealth="";
    private final Runnable refreshNearbyTick=new Runnable(){
        @Override public void run(){
            if(!foreground||isDestroyed())return;
            refreshNearbyStatus();
            refreshHandler.postDelayed(this,2000);
        }
    };
    private final Runnable refreshBoundTick=new Runnable(){
        @Override public void run(){
            if(!foreground||isDestroyed())return;
            refresh();refreshBoundConnections();
            if(foreground)refreshHandler.postDelayed(this,REFRESH_MS);
        }
    };
    private android.widget.CheckBox useAxon;
    private EditText invitations,interests;
    private boolean busy;
    private boolean reportStale;
    private volatile int diagnosticRevision;
    private MeshConnectionDiagnostics.Report connectionReport;
    private String renderedReport="",lastConnectionState="";
    private int dp(int n){return RootsUi.dp(this,n);}
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);
        mesh=MeshPeer.get(this);
        LinearLayout shell=RootsUi.shell(this);
        shell.addView(RootsUi.header(this,"Roots Mesh","Share knowledge · receive from nearby peers"));
        ScrollView scroll=new ScrollView(this);
        LinearLayout content=RootsUi.column(this);content.setPadding(dp(16),0,dp(16),dp(16));scroll.addView(content);
        shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout subscriptions=RootsUi.card(this,content);
        subscriptions.addView(RootsUi.text(this,getString(R.string.roots_ui_axons),18,RootsUi.TEXT,true));
        subscriptionStatus=RootsUi.text(this,"Loading subscriptions…",14,RootsUi.ACCENT,true);subscriptions.addView(subscriptionStatus);
        subscriptionRows=RootsUi.column(this);subscriptions.addView(subscriptionRows);
        subscriptions.addView(RootsUi.action(this,"Manage Axon subscriptions",()->startActivity(new Intent(this,EncounterActivity.class))));
        LinearLayout publish=RootsUi.card(this,content);
        publish.addView(RootsUi.text(this,"Tree graph",18,RootsUi.TEXT,true));
        publish.addView(RootsUi.text(this,"Listening shares your Tree with connected peers.",13,RootsUi.MUTED,false));
        publish.addView(RootsUi.action(this,"Write a remembrance",()->publish(this,null,this::refresh)));
        publish.addView(RootsUi.action(this,"Publish existing public Tree items",()->PublicTreePublishing.choose(this,this::refresh)));
        publish.addView(RootsUi.action(this,"Automatic publication settings",()->startActivity(new Intent(this,EncounterActivity.class))));
        LinearLayout nearby=RootsUi.card(this,content);
        nearby.addView(RootsUi.text(this,"Nearby encounters",18,RootsUi.TEXT,true));
        nearby.addView(RootsUi.text(this,"Bluetooth discovers and binds Axons. Receive uses the best available transport automatically.",13,RootsUi.MUTED,false));
        nearbyStatus=RootsUi.text(this,"",13,RootsUi.MUTED,false);nearby.addView(nearbyStatus);
        nearby.addView(RootsUi.action(this,"External receipt history",()->startActivity(new Intent(this,ExternalKnowledgeActivity.class))));
        nearby.addView(RootsUi.action(this,getString(R.string.roots_ui_nearby),()->startActivity(new Intent(this,EncounterActivity.class))));
        nearby.addView(RootsUi.action(this,getString(R.string.roots_ui_network),()->startActivity(new Intent(this,NetworkExplorerActivity.class))));
        LinearLayout listener=RootsUi.card(this,content);
        listener.addView(RootsUi.text(this,"Serve on this Wi-Fi",18,RootsUi.TEXT,true));
        listener.addView(RootsUi.text(this,"Start listening once to serve automatically in the background and after restarts. Stop listening turns sharing off; saved invitations remain valid.",13,RootsUi.MUTED,false));
        servingStatus=RootsUi.text(this,"Sharing off",14,RootsUi.ACCENT,true);listener.addView(servingStatus);
        serve=RootsUi.action(this,getString(R.string.roots_ui_listen_start),this::start);listener.addView(serve);
        copy=RootsUi.action(this,"Copy private invitation",this::copy);listener.addView(copy);
        listener.addView(RootsUi.action(this,"Share invitation…",this::share));
        stop=RootsUi.action(this,getString(R.string.roots_ui_listen_stop),()->{try{MeshServingService.disable(this);invalidateDiagnostics("Listener state changed. Test again for current details.");refresh();}catch(Exception e){status.setText(e.getMessage());}});listener.addView(stop);
        // One shared connection control, outside the optional Axon section.
        LinearLayout connection=RootsUi.section(this,content,getString(R.string.roots_ui_connection),"Check this device and any selected peers.",true);
        connectionSummary=RootsUi.text(this,"Checking local network…",13,RootsUi.MUTED,false);
        connectionSummary.setTextIsSelectable(true);connection.addView(connectionSummary);
        testConnection=RootsUi.action(this,"Refresh / test private LAN connection",this::testConnection);connection.addView(testConnection);
        connectionStatus=RootsUi.text(this,"No connection test yet.",14,RootsUi.ACCENT,true);connection.addView(connectionStatus);
        connectionDetails=RootsUi.text(this,"",13,RootsUi.TEXT,false);
        connectionDetails.setTextIsSelectable(true);
        copyConnectionReport=RootsUi.action(this,"Copy connection report",this::copyConnectionReport);connection.addView(copyConnectionReport);
        analyzeConnection=RootsUi.action(this,"Analyze connection with intelligence",this::analyzeConnection);connection.addView(analyzeConnection);
        connection.addView(RootsUi.action(this,"Intelligence model settings",()->{
            if(!busy)startActivity(new Intent(this,OverlayTranslationSettingsActivity.class));
        }));
        connection.addView(connectionDetails);
        analysisOutput=RootsUi.text(this,"Run a test to enable analysis with your selected model.",13,RootsUi.MUTED,false);
        analysisOutput.setTextIsSelectable(true);connection.addView(analysisOutput);
        LinearLayout inbound=RootsUi.card(this,content);
        inbound.addView(RootsUi.text(this,getString(R.string.roots_ui_receive),18,RootsUi.TEXT,true));
        inbound.addView(RootsUi.text(this,"Receive synchronizes saved Axons, including automatically bound nearby peers. Wi-Fi is preferred for bulk transfer; Bluetooth is the offline fallback. Enter an invitation only for an additional private LAN peer.",13,RootsUi.MUTED,false));
        invitations=new EditText(this);invitations.setHint("Optional: new or refreshed peer invitations");invitations.setMinLines(2);
        invitations.setSaveEnabled(false); // Credentials use encrypted private storage, never Android view state.
        if(android.os.Build.VERSION.SDK_INT>=26)invitations.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        invitations.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        invitations.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8192)});inbound.addView(invitations);
        interests=new EditText(this);interests.setHint("Wanted topics, separated by commas (optional)");interests.setSingleLine(true);
        interests.setFilters(new InputFilter[]{new InputFilter.LengthFilter(512)});inbound.addView(interests);
        useAxon=new android.widget.CheckBox(this);
        useAxon.setText("Use saved private LAN .axon subscription");useAxon.setTextColor(RootsUi.TEXT);
        useAxon.setChecked(new java.io.File(getNoBackupFilesDir(),"subscription.axon").isFile());
        inbound.addView(useAxon);
        savedAxonStatus=RootsUi.text(this,"Loading saved Axon…",13,RootsUi.MUTED,false);
        inbound.addView(savedAxonStatus);
        boundConnectionStatus=RootsUi.text(this,"Bound connection: not checked",13,RootsUi.MUTED,false);
        inbound.addView(boundConnectionStatus);
        inbound.addView(RootsUi.action(this,"Bind / edit .axon",this::editAxon));
        inbound.addView(RootsUi.action(this,"View current flow allocation",this::showAxon));
        inbound.addView(RootsUi.action(this,"Arm runtime Axon (30 minutes)",this::armRuntime));
        inbound.addView(RootsUi.action(this,"Revoke runtime access",()->{if(busy)return;AxonRuntime.revoke();axonStatus.setText(AxonRuntime.status());invalidateDiagnostics("Runtime access changed. Test again for current details.");refresh();}));
        inbound.addView(RootsUi.action(this,"Remove .axon binding",()->{
            if(busy)return;
            new AlertDialog.Builder(this).setTitle("Remove saved Axon?")
                .setMessage("Removes the local subscription and saved peer access. Retained knowledge stays here.")
                .setNegativeButton(R.string.roots_ui_cancel,null).setPositiveButton(R.string.roots_ui_remove,(d,w)->{
                    AxonRuntime.revoke();
                    try{AxonSubscription.clear(this);useAxon.setChecked(false);axonStatus.setText("No saved Axon");invalidateDiagnostics("Axon binding removed. Test again for current details.");}
                    catch(Exception e){axonStatus.setText("Could not finish removing saved peer access. Try Remove again.");}
                    refresh();
                }).show();
        }));
        axonStatus=RootsUi.text(this,"View current flow allocation to inspect your subscribed topics.",13,RootsUi.MUTED,false);
        inbound.addView(axonStatus);
        receive=RootsUi.action(this,"Receive missing knowledge",this::receive);inbound.addView(receive);
        status=RootsUi.text(this,"",14,RootsUi.ACCENT,true);content.addView(status);
        shell.addView(RootsUi.action(this,getString(R.string.roots_ui_home),()->{startActivity(new Intent(this,RootsStreamActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));finish();}));
        invitations.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){
                if(clearingSavedInvite)return;
                lastBoundHealth="";boundConnectionStatus.setText(s.toString().trim().isEmpty()?"Bound connection: awaiting refresh":"Invitation draft: tap Refresh / test connection to apply it.");
                invalidateDiagnostics("Invitation changed. Test the current connection.");
                if(s.toString().trim().isEmpty())scheduleBoundRefresh(500);
            }
            @Override public void afterTextChanged(Editable s){}
        });
        useAxon.setOnCheckedChangeListener((button,checked)->{
            lastBoundHealth="";boundConnectionStatus.setText(checked?"Bound connection: awaiting refresh":"Bound connection: not selected");
            invalidateDiagnostics("Subscription selection changed. Test the current connection.");
            if(checked)scheduleBoundRefresh(500);
        });
        RootsUi.styleInputs(this,shell);setContentView(shell);readInvitation(getIntent());refresh();
        if(mesh.lastConnectionReport()!=null){
            showConnectionReport(mesh.lastConnectionReport());
            invalidateDiagnostics("Previous connection snapshot. Test the current connection to refresh it.");
        }
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);readInvitation(intent);}
    private void readInvitation(Intent intent){
        if(intent==null||!Intent.ACTION_VIEW.equals(intent.getAction())||intent.getDataString()==null)return;
        String raw=intent.getDataString();
        try{
            if(raw.length()>1024)throw new IllegalArgumentException("Invalid invitation");
            MeshPeer.Invite.parse(raw);invitations.setText(raw);
            status.setText("Invitation ready. Tap Test connection or Receive.");
        }catch(Exception e){status.setText("Invalid Roots Mesh invitation.");}
    }
    @Override protected void onResume(){
        super.onResume();foreground=true;foregroundGeneration++;MeshServingService.addObserver(servingObserver);EncounterBindings.get(this).addListener(subscriptionObserver);MeshServingService.restore(this);refresh();scheduleBoundRefresh(0);refreshHandler.removeCallbacks(refreshNearbyTick);refreshHandler.post(refreshNearbyTick);
        refreshNetworkManager=(android.net.ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        if(refreshNetworkManager!=null){
            android.net.ConnectivityManager.NetworkCallback callback=new android.net.ConnectivityManager.NetworkCallback(){
                private void changed(){runOnUiThread(()->{
                    if(foreground&&refreshNetworkCallback==this){refresh();scheduleBoundRefresh(500);}
                });}
                @Override public void onAvailable(android.net.Network network){changed();}
                @Override public void onLost(android.net.Network network){changed();}
                @Override public void onLinkPropertiesChanged(android.net.Network network,android.net.LinkProperties properties){changed();}
            };
            refreshNetworkCallback=callback;
            try{refreshNetworkManager.registerDefaultNetworkCallback(callback);}
            catch(RuntimeException ignored){refreshNetworkCallback=null;}
        }
    }
    @Override protected void onPause(){
        foreground=false;foregroundGeneration++;MeshServingService.removeObserver(servingObserver);EncounterBindings.get(this).removeListener(subscriptionObserver);refreshHandler.removeCallbacks(refreshBoundTick);refreshHandler.removeCallbacks(refreshNearbyTick);
        if(refreshNetworkManager!=null&&refreshNetworkCallback!=null)
            try{refreshNetworkManager.unregisterNetworkCallback(refreshNetworkCallback);}catch(RuntimeException ignored){}
        refreshNetworkCallback=null;refreshNetworkManager=null;super.onPause();
    }
    private void scheduleBoundRefresh(long delay){
        refreshHandler.removeCallbacks(refreshBoundTick);
        if(foreground)refreshHandler.postDelayed(refreshBoundTick,delay);
    }
    private void clearSavedInvitationDraft(){
        clearingSavedInvite=true;
        try{invitations.setText("");}finally{clearingSavedInvite=false;}
    }
    private boolean savedAccessWasStored(MeshConnectionDiagnostics.Report report){
        if(report==null)return false;
        for(MeshConnectionDiagnostics.Check check:report.localChecks())if("Peer access".equals(check.stage())&&
            MeshConnectionDiagnostics.PASS.equals(check.state())&&check.detail().contains("saved encrypted"))return true;
        return false;
    }
    private void refreshBoundConnections(){
        if(!foreground||busy||refreshing||!useAxon.isChecked()||!invitations.getText().toString().trim().isEmpty())return;
        if(!new java.io.File(getNoBackupFilesDir(),"subscription.axon").isFile())return;
        final int revision=diagnosticRevision,generation=foregroundGeneration;
        final String network=mesh.connectionStateKey();
        refreshing=true;boundConnectionStatus.setText("Bound connection: refreshing…");
        CONNECTION_REFRESH.execute(()->{
            MeshConnectionDiagnostics.Report report=null;
            try{report=mesh.refreshBoundConnections(()->foreground&&generation==foregroundGeneration&&revision==diagnosticRevision);}
            catch(Exception|LinkageError ignored){}
            final MeshConnectionDiagnostics.Report result=report;
            runOnUiThread(()->{
                refreshing=false;
                if(isDestroyed()||!foreground||busy||!useAxon.isChecked()||!invitations.getText().toString().trim().isEmpty())return;
                if(generation!=foregroundGeneration||revision!=diagnosticRevision){scheduleBoundRefresh(500);return;}
                if(!network.equals(mesh.connectionStateKey())){scheduleBoundRefresh(500);return;}
                if(result==null){boundConnectionStatus.setText("Bound connection: refresh failed; tap Refresh / test connection.");return;}
                String health=updateBoundHealth(result);
                if(connectionReport==null||reportStale||!health.equals(lastBoundHealth))showConnectionReport(result);
                lastBoundHealth=health;
            });
        });
    }
    private String updateBoundHealth(MeshConnectionDiagnostics.Report report){
        int connected=MeshConnectionDiagnostics.connectedPeers(report);
        int bound=report.peers().size();
        try{AxonSubscription policy=AxonSubscription.load(this);if(policy!=null)bound=policy.pins.size();}catch(Exception ignored){}
        String health=connected+"/"+bound+" bound peers connected";
        if(connected<bound||bound==0)health+=" · "+(connected==0?MeshConnectionDiagnostics.failureSummary(report):
            "Some bound peers are unavailable or not checked; see connection details.");
        boundConnectionStatus.setText(health+"\nChecked "+java.text.DateFormat.getTimeInstance().format(new java.util.Date(report.checkedAtMillis())));
        return health;
    }
    private void refresh(){
        if(isDestroyed()||mesh==null)return;
        boolean listening=!mesh.invitation().isEmpty();
        boolean serving=MeshServingService.enabled(this);
        servingStatus.setText(MeshServingService.status(this)+(listening?" · "+mesh.tlsIdentityStatus():""));
        enable(serve,!serving&&!busy);enable(copy,serving&&listening);enable(stop,serving);
        receive.setEnabled(!busy&&(NativeTree.available()||EncounterService.available(this)));
        refreshNearbyStatus();
        invitations.setEnabled(!busy);interests.setEnabled(!busy);useAxon.setEnabled(!busy);
        String summary=mesh.connectionSummary();
        String state=mesh.connectionStateKey();
        if(!lastConnectionState.isEmpty()&&!lastConnectionState.equals(state)){
            lastBoundHealth="";boundConnectionStatus.setText("Bound connection: network changed; awaiting refresh");
            invalidateDiagnostics("Local connection state changed. Test again for current details.");
        }
        lastConnectionState=state;
        connectionSummary.setText(summary+"\n"+AxonRuntime.status()+"\nAnalysis model: "+IntelligenceModel.selected(this).label+"\nAnalysis runs on: "+intelligenceLocation());
        refreshSavedAxon();
        updateDiagnosticActions();
        // Keep completed receive/start errors visible when returning to this screen.
        if(!busy&&status.getText().length()==0)status.setText(NativeTree.available()?mesh.status():"Native Tree unavailable. Connection testing is still available.");
    }
    private void refreshNearbyStatus(){
        if(nearbyStatus==null)return;
        AxonRegistry.Snapshot registry=AxonRegistry.load(this);
        nearbyStatus.setText(registry.nearbyCount()+" nearby Axon subscription(s) · "+EncounterService.status()+"\n"+EncounterService.lastResult());
        subscriptionStatus.setText(registry.summary());
        List<String> labels=new ArrayList<>();
        for(var entry:registry.entries())labels.add(AxonRegistry.display(entry));
        String fingerprint=String.join("\n",labels);
        if(!fingerprint.equals(shownSubscriptions)||subscriptionRows.getChildCount()==0){
            shownSubscriptions=fingerprint;subscriptionRows.removeAllViews();
            if(labels.isEmpty())subscriptionRows.addView(RootsUi.text(this,"No subscriptions yet. Nearby peers bind automatically.",13,RootsUi.MUTED,false));
            for(int i=0;i<Math.min(12,labels.size());i++)subscriptionRows.addView(RootsUi.text(this,labels.get(i),13,RootsUi.TEXT,false));
            if(labels.size()>12)subscriptionRows.addView(RootsUi.text(this,"+"+(labels.size()-12)+" more · manage subscriptions",13,RootsUi.MUTED,false));
        }
    }
    private void refreshSavedAxon(){
        try{
            AxonSubscription policy=AxonSubscription.load(this);
            if(policy==null){
                int nearby=EncounterBindings.get(this).availableCount();
                savedAxonStatus.setText(nearby>0?nearby+" automatic nearby subscription(s) · all Root knowledge. Private LAN: not configured.":"No Axon subscriptions yet. Nearby Axons bind automatically; private LAN uses invitations.");
                boundConnectionStatus.setText(nearby>0?"Nearby Axons: "+EncounterService.status():"Private LAN: no saved binding");return;
            }
            String binding=AxonRegistry.load(this).summary()+"\nPrivate LAN: "+policy.pins.size()+" bound peer(s), "+policy.lanes.size()+" topic(s).";
            try{
                int count=AxonPeerStore.load(this,policy).size();
                savedAxonStatus.setText(binding+"\n"+(count==0?"This older binding has no saved connection details. Paste a current invitation once and tap Test connection to save access.":
                    count+" encrypted peer connection(s) saved. "+(useAxon.isChecked()?"Leave the invitation box empty to use them.":"Select Use saved .axon subscription to use them.")));
            }catch(Exception e){savedAxonStatus.setText(binding+"\nSaved access could not be opened. Paste a current invitation and Test connection to replace it.");}
        }catch(Exception e){savedAxonStatus.setText("Saved Axon could not be loaded. Use Bind / edit .axon with a current invitation to repair it.");}
    }
    private String intelligenceLocation(){
        OverlayPreferences preferences=new OverlayPreferences(this);
        return preferences.desktopIntelligenceEnabled()?"paired desktop · "+preferences.desktopIntelligenceUrl():"this device";
    }
    private void updateDiagnosticActions(){
        if(testConnection==null)return;
        enable(testConnection,!busy);
        enable(analyzeConnection,!busy&&connectionReport!=null&&!reportStale&&!renderedReport.isEmpty());
        enable(copyConnectionReport,!busy&&!renderedReport.isEmpty());
    }
    private static void enable(TextView view,boolean enabled){view.setEnabled(enabled);view.setAlpha(enabled?1f:0.45f);}
    private void invalidateDiagnostics(String reason){
        diagnosticRevision++;
        if(connectionReport!=null){
            reportStale=true;connectionStatus.setText(reason+" Previous results are shown below.");
            analysisOutput.setText("Run a fresh connection test before analyzing the changed setup.");
        }
        updateDiagnosticActions();
    }
    private void showConnectionReport(MeshConnectionDiagnostics.Report report){
        if(report==null)return;
        connectionReport=report;reportStale=false;
        try{
            renderedReport=MeshDiagnosticTemplates.report(this,report);
            connectionDetails.setText(renderedReport);
            connectionStatus.setText("Connection report ready.");
            analysisOutput.setText("Ready to analyze with "+IntelligenceModel.selected(this).label+" on "+intelligenceLocation()+". The model receives connection checks; invitation credentials and graph content are excluded.");
        }catch(Exception e){
            renderedReport="";connectionDetails.setText("");
            connectionStatus.setText("Could not render the connection report ("+e.getClass().getSimpleName()+").");
        }
        updateDiagnosticActions();
    }
    private void testConnection(){
        if(busy)return;
        final String raw=invitations.getText().toString();
        final boolean savedAxon=useAxon.isChecked()&&(!raw.trim().isEmpty()||new java.io.File(getNoBackupFilesDir(),"subscription.axon").isFile());
        final boolean localCheck=!savedAxon&&raw.trim().isEmpty();
        busy=true;refresh();
        final int revision=++diagnosticRevision;
        reportStale=true;connectionStatus.setText(localCheck?"Checking this device…":"Testing the selected peer connections…");
        analysisOutput.setText("Waiting for connection checks…");
        WORK.execute(()->{
            MeshConnectionDiagnostics.Report report=null;String error=null;
            try{report=localCheck?mesh.testLocal():mesh.testConnections(raw,savedAxon,progress->runOnUiThread(()->{
                if(!isDestroyed()&&revision==diagnosticRevision)connectionStatus.setText(progress);
            }));}
            catch(Exception|LinkageError e){error="Connection test could not finish ("+e.getClass().getSimpleName()+"). Try again.";}
            final MeshConnectionDiagnostics.Report completed=report;final String failure=error;
            runOnUiThread(()->{
                if(isDestroyed())return;
                busy=false;refresh();
                if(revision!=diagnosticRevision){
                    if(completed!=null)showConnectionReport(completed);
                    invalidateDiagnostics("Setup changed during testing. Run a fresh test.");return;
                }
                if(failure==null){
                    if(!localCheck&&savedAxon&&completed!=null){
                        if(!raw.trim().isEmpty()&&savedAccessWasStored(completed))clearSavedInvitationDraft();
                        lastBoundHealth=updateBoundHealth(completed);refreshSavedAxon();
                        if(!raw.trim().isEmpty()&&savedAccessWasStored(completed))scheduleBoundRefresh(500);
                    }
                    showConnectionReport(completed);
                }else connectionStatus.setText(failure);
            });
        });
    }
    private void analyzeConnection(){
        if(busy||connectionReport==null||reportStale)return;
        // Re-check local state before using a snapshot. Do not retrieve through a
        // potentially broken Axon to diagnose that same connection.
        refresh();if(reportStale)return;
        final String request;
        try{request=MeshDiagnosticTemplates.analysis(this,connectionReport);}
        catch(Exception e){analysisOutput.setText("Could not prepare the diagnosis request ("+e.getClass().getSimpleName()+").");return;}
        final int revision=diagnosticRevision;
        IntelligenceModel model=IntelligenceModel.selected(this);
        busy=true;refresh();analysisOutput.setText("Queued for "+model.label+"…");
        LocalIntelligenceProvider.generate(this,model,request,new LocalIntelligenceProvider.Result(){
            @Override public void partial(String prefix){runOnUiThread(()->{
                if(!isDestroyed()&&revision==diagnosticRevision)StreamText.reveal(analysisOutput,prefix);
            });}
            @Override public void completed(String message,String error){runOnUiThread(()->{
                if(isDestroyed())return;busy=false;refresh();
                if(revision!=diagnosticRevision){analysisOutput.setText("Setup changed during analysis. Run a fresh connection test.");return;}
                analysisOutput.setText(error==null?"Intelligence interpretation\n"+message:
                    "Intelligence unavailable: "+error+"\nThe measured connection report remains above. Check the selected model and connection in Intelligence model settings.");
            });}
        });
    }
    private void copyConnectionReport(){
        if(busy||renderedReport.isEmpty())return;
        ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(clipboard==null){connectionStatus.setText("Clipboard unavailable.");return;}
        String report=(reportStale?"Previous snapshot; setup has changed.\n":"")+renderedReport;
        clipboard.setPrimaryClip(ClipData.newPlainText("Roots Mesh connection report",report));
        connectionStatus.setText("Connection report copied. Invitation credentials are excluded.");
    }
    private void start(){
        if(busy)return;
        try{MeshServingService.enable(this);refresh();}
        catch(Exception error){status.setText("Could not enable sharing: "+error.getMessage());}
    }
    private void copy(){
        String invitation=mesh.invitation();if(invitation.isEmpty())return;
        ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(clipboard!=null){
            ClipData clip=ClipData.newPlainText("Roots Mesh invitation",invitation);
            if(android.os.Build.VERSION.SDK_INT>=33){android.os.PersistableBundle extras=new android.os.PersistableBundle();extras.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(extras);}
            clipboard.setPrimaryClip(clip);status.setText("Invitation copied. Share it only with your intended peer.");
        }
    }
    private void share(){
        String invitation=mesh.invitation();if(invitation.isEmpty()){status.setText("Start listening first.");return;}
        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,invitation),"Roots Mesh invitation"));
    }
    private MeshPeerAccess.Resolution selectedPeers(boolean saved){
        return MeshPeerAccess.resolve(this,invitations.getText().toString(),saved).requireReady();
    }
    private void editAxon(){
        if(busy)return;
        final List<MeshPeer.Invite> peers;
        final AxonSubscription old;
        try{
            // Existing policy settings remain editable even when no live grant
            // is available. Explicit input is a deliberate peer rebind.
            AxonSubscription previous;
            try{previous=AxonSubscription.load(this);}
            catch(Exception e){if(invitations.getText().toString().trim().isEmpty())throw e;previous=null;}
            old=previous;
            if(invitations.getText().toString().trim().isEmpty()){
                if(old==null)throw new IllegalArgumentException("Paste a peer invitation before creating an Axon binding.");
                peers=null;
            }else peers=selectedPeers(false).peers();
        }
        catch(Exception e){axonStatus.setText(e.getMessage());return;}
        LinearLayout box=RootsUi.column(this);box.setPadding(dp(16),0,dp(16),0);box.setBackgroundColor(RootsUi.CARD);
        box.addView(RootsUi.text(this,"Saved peer access survives listener restarts. Paste a new invitation only if the source address or access changes.",13,RootsUi.MUTED,false));
        box.addView(RootsUi.text(this,"All knowledge is wanted by default. Optional topics and FFN groups prioritize its flow. Example: music | 7:12,8:2.",13,RootsUi.MUTED,false));
        android.widget.Spinner model=new android.widget.Spinner(this);RootsUi.styleSpinner(this,model,IntelligenceModel.labels());
        model.setSelection((old==null?IntelligenceModel.selected(this):old.model).ordinal());box.addView(model);
        android.widget.Spinner op=new android.widget.Spinner(this);RootsUi.styleSpinner(this,op,ElementFlow.OPERATIONS);
        op.setSelection(old==null?0:java.util.Arrays.asList(ElementFlow.OPERATIONS).indexOf(old.operation));box.addView(op);
        EditText lines=new EditText(this);lines.setMinLines(4);lines.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        lines.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4096)});
        lines.setHint("music\nscience | 7:12\n*");
        lines.setText(old==null?interests.getText().toString().replace(',','\n'):old.lines());box.addView(lines);
        RootsUi.styleInputs(this,box);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(peers==null?"Edit saved .axon · "+old.pins.size()+" peer(s)":"Bind .axon to "+peers.size()+" invited peer(s)").setView(box)
            .setNegativeButton(R.string.roots_ui_cancel,null).setPositiveButton(R.string.roots_ui_save,null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            try{
                IntelligenceModel selectedModel=IntelligenceModel.values()[model.getSelectedItemPosition()];
                String operation=ElementFlow.OPERATIONS[op.getSelectedItemPosition()],topics=lines.getText().toString();
                AxonSubscription next=peers==null?old.withSettings(selectedModel,operation,topics):AxonSubscription.create(peers,selectedModel,operation,topics);
                // Access is authenticated to this exact pin set. A failed policy
                // write cannot authorize peers outside the previous binding.
                if(peers!=null)AxonPeerStore.save(this,next,peers);
                AxonRuntime.revoke();next.save(this);useAxon.setChecked(true);clearSavedInvitationDraft();dialog.dismiss();
                lastBoundHealth="";invalidateDiagnostics("Axon binding changed. Refreshing the saved connection.");refresh();scheduleBoundRefresh(0);showAxon();
            }catch(Exception e){lines.setError(e.getMessage());}
        }));dialog.show();
    }
    private void armRuntime(){
        if(busy)return;
        try{
            MeshPeerAccess.Resolution access=selectedPeers(true);
            AxonSubscription policy=access.policy();List<MeshPeer.Invite> peers=access.peers();
            new AlertDialog.Builder(this).setTitle("Allow runtime Axon access?")
                .setMessage("Enable runtime knowledge requests for 30 minutes.")
                .setNegativeButton(R.string.roots_ui_cancel,null).setPositiveButton("Arm",(d,w)->{AxonRuntime.arm(policy,peers);axonStatus.setText(AxonRuntime.status());invalidateDiagnostics("Runtime access changed. Test again for current details.");refresh();}).show();
        }catch(Exception e){axonStatus.setText(e.getMessage());}
    }
    private void showAxon(){
        if(busy||!NativeTree.available())return;
        final AxonSubscription subscription;
        try{subscription=AxonSubscription.load(this);if(subscription==null){axonStatus.setText(AxonRegistry.load(this).summary()+" · nearby Axons subscribe to all Root knowledge; private LAN allocation is separate.");return;}}
        catch(Exception e){axonStatus.setText(e.getMessage());return;}
        final String lang=new OverlayPreferences(this).targetLanguage();
        axonStatus.setText("Calculating local flow…");
        WORK.execute(()->{
            String message;
            try{
                TreeRuntime tree=TreeRuntime.get(this);
                message=subscription.describe(subscription.allocation(this,tree,tree.allRecords("",10000),lang));
            }catch(Exception|LinkageError e){message="Axon allocation unavailable: "+e.getClass().getSimpleName();}
            final String result=message;runOnUiThread(()->{if(!isDestroyed())axonStatus.setText(result);});
        });
    }
    private void receive(){
        if(busy)return;
        final boolean noDraft=invitations.getText().toString().trim().isEmpty();
        final boolean lanSelected=useAxon.isChecked()&&new java.io.File(getNoBackupFilesDir(),"subscription.axon").isFile();
        final boolean nearby=EncounterReceivePolicy.nearby(noDraft,lanSelected,EncounterBindings.get(this).availableCount(),EncounterService.available(this)&&EncounterSettings.enabled(this));
        if(!NativeTree.available()){status.setText("Native Tree unavailable for knowledge receive.");return;}
        List<MeshPeer.Invite> selected=java.util.Collections.emptyList();
        AxonSubscription policy=null;
        String selectionFailure="";
        if(!noDraft||lanSelected||!nearby){
            try{
                MeshPeerAccess.Resolution access=selectedPeers(useAxon.isChecked());
                selected=access.peers();policy=access.policy();
                if(policy!=null&&!access.fromSaved())clearSavedInvitationDraft();
            }catch(Exception e){
                selectionFailure=e.getMessage()==null?"Private LAN access unavailable":e.getMessage();
                if(!nearby){status.setText(selectionFailure);return;}
            }
        }
        final List<MeshPeer.Invite> peers=selected;
        final AxonSubscription subscription=policy;
        final String accessFailure=selectionFailure;
        final String wanted=interests.getText().toString(),lang=new OverlayPreferences(this).targetLanguage();
        busy=true;refresh();status.setText("Reading Axon inventories…");
        final int revision=++diagnosticRevision;
        reportStale=true;analysisOutput.setText("Receiving knowledge from selected Axons…");
        // Discovery/bulk synchronization starts from the UI, then completes
        // asynchronously on the service. Private LAN work can run concurrently.
        final AxonReceiveCoordinator.Job publicJob=nearby?AxonReceiveCoordinator.start(this,
            message->runOnUiThread(()->{if(!isDestroyed()&&revision==diagnosticRevision)status.setText(message);})):null;
        WORK.execute(()->{
            String lanMessage=accessFailure;
            MeshConnectionDiagnostics.Report lanReport=null;
            if(!peers.isEmpty()){
                try{
                    MeshPeer.SyncResult result=mesh.sync(peers,wanted,lang,subscription,progress->runOnUiThread(()->{
                        if(!isDestroyed()&&revision==diagnosticRevision)status.setText("Private LAN · "+progress);
                    }));
                    lanMessage="Private LAN: received "+result.received()+" of "+result.wanted()+" missing items · "+result.peers()+" peers · "+result.failed()+" failures.";
                }catch(Exception|LinkageError e){lanMessage="Private LAN receive failed: "+e.getClass().getSimpleName()+" · "+e.getMessage();}
                lanReport=mesh.lastConnectionReport();
            }
            final String privateResult=lanMessage;
            final MeshConnectionDiagnostics.Report report=lanReport;
            // Do not occupy the shared UI worker while a radio session is
            // waiting for another encounter. Completion is event-driven.
            java.util.concurrent.CompletableFuture<AxonReceiveCoordinator.Result> completion=
                publicJob==null?java.util.concurrent.CompletableFuture.completedFuture(null):publicJob.finished;
            completion.whenComplete((publicResult,error)->{
                String publicMessage=error!=null?"Public Axon receive unavailable.":publicResult==null?"":publicResult.summary();
                String result=publicMessage+(publicMessage.isEmpty()||privateResult.isEmpty()?"":"\n")+privateResult;
                runOnUiThread(()->{
                    if(isDestroyed())return;
                    busy=false;refresh();status.setText(result);
                    if(revision==diagnosticRevision&&report!=null){
                        if(subscription!=null)lastBoundHealth=updateBoundHealth(report);
                        showConnectionReport(report);
                    }else if(revision==diagnosticRevision)analysisOutput.setText("Receive result shown below · public Axon progress is independent of private LAN diagnostics.");
                });
            });
        });
    }
    /** Explicit publication confirmation; never prefill private original text. */
    static void publish(Activity activity,NativeTree.Master record,Runnable changed){
        if(!NativeTree.available())return;
        TreeRuntime tree=TreeRuntime.get(activity);long epoch=tree.captureEpoch();
        LinearLayout box=RootsUi.column(activity);box.setPadding(RootsUi.dp(activity,16),0,RootsUi.dp(activity,16),0);
        TextView warning=RootsUi.text(activity,"Write your own shareable knowledge or remembrance. Do not publish private messages, personal identifiers, or copied text without permission. Recipients may keep and reshare it.",13,RootsUi.MUTED,false);box.addView(warning);
        EditText topic=new EditText(activity);topic.setHint("Topic / wanted label");topic.setSingleLine(true);topic.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});box.addView(topic);
        EditText text=new EditText(activity);text.setHint("Shareable remembrance");text.setMinLines(4);text.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3000)});box.addView(text);
        EditText link=new EditText(activity);link.setHint("Optional public X post URL");link.setSingleLine(true);
        if(record!=null)link.setText(XPostLink.forRecord(record));
        box.addView(link);
        EditText language=new EditText(activity);language.setHint("Language code");language.setSingleLine(true);language.setText(new OverlayPreferences(activity).targetLanguage());language.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});box.addView(language);
        // Use explicit dark input colors even when invoked from the settings theme.
        box.setBackgroundColor(RootsUi.CARD);RootsUi.styleInputs(activity,box);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Publish to Roots Mesh").setView(box)
            .setNegativeButton(R.string.roots_ui_cancel,null).setPositiveButton("Publish",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            final MeshItem item;
            try{item=MeshItem.create(language.getText().toString(),topic.getText().toString(),text.getText().toString(),link.getText().toString());}
            catch(Exception e){text.setError(e.getMessage());return;}
            button.setEnabled(false);
            WORK.execute(()->{
                String error=null;
                try{item.store(tree,epoch);}catch(Exception|LinkageError e){error=e.getMessage();}
                final String failure=error;
                activity.runOnUiThread(()->{if(activity.isDestroyed())return;if(failure==null){dialog.dismiss();MeshPeer.get(activity).invalidateEncounterPublicationCount();EncounterService.refreshDiscovery();changed.run();android.widget.Toast.makeText(activity,"Published locally · eligible for nearby exchange",android.widget.Toast.LENGTH_LONG).show();}else{button.setEnabled(true);text.setError(failure);}});
            });
        }));
        dialog.show();
    }
}
