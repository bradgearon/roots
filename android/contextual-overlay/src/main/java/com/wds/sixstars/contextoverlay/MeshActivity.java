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
    private TextView status,serve,copy,receive,stop,axonStatus;
    private TextView connectionSummary,connectionStatus,connectionDetails,analysisOutput;
    private TextView testConnection,checkDevice,analyzeConnection,copyConnectionReport;
    private TextView savedAxonStatus,boundConnectionStatus;
    private static final long REFRESH_MS=30000;
    private static final ExecutorService CONNECTION_REFRESH=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"RootsAxonRefresh");t.setDaemon(true);return t;});
    private final android.os.Handler refreshHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile boolean foreground;
    private boolean refreshing,clearingSavedInvite;
    private volatile int foregroundGeneration;
    private android.net.ConnectivityManager refreshNetworkManager;
    private android.net.ConnectivityManager.NetworkCallback refreshNetworkCallback;
    private String lastBoundHealth="";
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
        LinearLayout publish=RootsUi.card(this,content);
        publish.addView(RootsUi.text(this,"Tree graph",18,RootsUi.TEXT,true));
        publish.addView(RootsUi.text(this,"Listening shares your Tree with connected peers.",13,RootsUi.MUTED,false));
        publish.addView(RootsUi.action(this,"Write a remembrance",()->publish(this,null,this::refresh)));
        LinearLayout listener=RootsUi.card(this,content);
        listener.addView(RootsUi.text(this,"Serve on this Wi-Fi",18,RootsUi.TEXT,true));
        listener.addView(RootsUi.text(this,"Create a roots:// invitation for another phone. Keep Roots running while the other phone receives. Stop serving to invalidate the invitation.",13,RootsUi.MUTED,false));
        serve=RootsUi.action(this,"Start listening",this::start);listener.addView(serve);
        copy=RootsUi.action(this,"Copy private invitation",this::copy);listener.addView(copy);
        listener.addView(RootsUi.action(this,"Share invitation…",this::share));
        stop=RootsUi.action(this,"Stop listening",()->{mesh.stop();getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);invalidateDiagnostics("Listener state changed. Test again for current details.");refresh();status.setText(mesh.status());});listener.addView(stop);
        LinearLayout inbound=RootsUi.card(this,content);
        inbound.addView(RootsUi.text(this,"Receive knowledge",18,RootsUi.TEXT,true));
        inbound.addView(RootsUi.text(this,"Use your saved Axon connection, or paste up to eight invitations to add or refresh peers. Leave the invitation box empty to use saved access. Only missing content is requested; private interests stay on this device.",13,RootsUi.MUTED,false));
        invitations=new EditText(this);invitations.setHint("Optional: new or refreshed peer invitations");invitations.setMinLines(2);
        invitations.setSaveEnabled(false); // Credentials use encrypted private storage, never Android view state.
        if(android.os.Build.VERSION.SDK_INT>=26)invitations.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        invitations.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        invitations.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8192)});inbound.addView(invitations);
        interests=new EditText(this);interests.setHint("Wanted topics, separated by commas (optional)");interests.setSingleLine(true);
        interests.setFilters(new InputFilter[]{new InputFilter.LengthFilter(512)});inbound.addView(interests);
        useAxon=new android.widget.CheckBox(this);
        useAxon.setText("Use saved .axon subscription");useAxon.setTextColor(RootsUi.TEXT);
        useAxon.setChecked(new java.io.File(getNoBackupFilesDir(),"subscription.axon").isFile());
        inbound.addView(useAxon);
        savedAxonStatus=RootsUi.text(this,"Loading saved Axon…",13,RootsUi.MUTED,false);
        inbound.addView(savedAxonStatus);
        boundConnectionStatus=RootsUi.text(this,"Bound connection: not checked",13,RootsUi.MUTED,false);
        inbound.addView(boundConnectionStatus);
        LinearLayout connection=RootsUi.section(this,inbound,"Connection details","Test the peer connection and graph inventory.",true);
        connectionSummary=RootsUi.text(this,"Checking local network…",13,RootsUi.MUTED,false);
        connectionSummary.setTextIsSelectable(true);connection.addView(connectionSummary);
        testConnection=RootsUi.action(this,"Refresh / test connection",this::testConnection);connection.addView(testConnection);
        checkDevice=RootsUi.action(this,"Check this device",()->runConnectionTest(true));connection.addView(checkDevice);
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
        inbound.addView(RootsUi.action(this,"Bind / edit .axon",this::editAxon));
        inbound.addView(RootsUi.action(this,"View current flow allocation",this::showAxon));
        inbound.addView(RootsUi.action(this,"Arm runtime Axon (30 minutes)",this::armRuntime));
        inbound.addView(RootsUi.action(this,"Revoke runtime access",()->{if(busy)return;AxonRuntime.revoke();axonStatus.setText(AxonRuntime.status());invalidateDiagnostics("Runtime access changed. Test again for current details.");refresh();}));
        inbound.addView(RootsUi.action(this,"Remove .axon binding",()->{
            if(busy)return;
            new AlertDialog.Builder(this).setTitle("Remove saved Axon?")
                .setMessage("Removes the local subscription and saved peer access. Retained knowledge stays here.")
                .setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,w)->{
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
        shell.addView(RootsUi.action(this,"‹ Home",()->{startActivity(new Intent(this,RootsStreamActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));finish();}));
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
        super.onResume();foreground=true;foregroundGeneration++;refresh();scheduleBoundRefresh(0);
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
        foreground=false;foregroundGeneration++;refreshHandler.removeCallbacks(refreshBoundTick);
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
        serve.setEnabled(!listening&&!busy);copy.setEnabled(listening);stop.setEnabled(listening);
        receive.setEnabled(!busy&&NativeTree.available());
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
    private void refreshSavedAxon(){
        try{
            AxonSubscription policy=AxonSubscription.load(this);
            if(policy==null){savedAxonStatus.setText("Saved Axon: none. Paste an invitation and choose Bind / edit .axon to save a peer.");boundConnectionStatus.setText("Bound connection: no saved binding");return;}
            String binding="Saved Axon: "+policy.pins.size()+" bound peer(s), "+policy.lanes.size()+" topic(s).";
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
        if(checkDevice!=null)enable(checkDevice,!busy);
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
    private void testConnection(){runConnectionTest(false);}
    private void runConnectionTest(boolean localOnly){
        if(busy)return;
        final String raw=invitations.getText().toString();
        final boolean savedAxon=!localOnly&&useAxon.isChecked();
        final boolean localCheck=localOnly||(!savedAxon&&raw.trim().isEmpty());
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
        if(busy)return;busy=true;refresh();status.setText("Starting local TLS listener…");
        WORK.execute(()->{
            String message;
            try{mesh.start();message="Listening on Wi-Fi · "+mesh.offeredCount()+" graph items\n"+mesh.tlsIdentityStatus();}
            catch(Exception|LinkageError e){message="Could not start listener: "+e.getClass().getSimpleName()+" · "+e.getMessage();}
            final String result=message;
            runOnUiThread(()->{if(isDestroyed())return;busy=false;refresh();if(!mesh.invitation().isEmpty())getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);status.setText(result);});
        });
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
        box.addView(RootsUi.text(this,"Peer connection details are saved encrypted on this device. Test and Receive reuse them when the invitation box is empty. If a peer restarts its listener, paste its new invitation to refresh access.",13,RootsUi.MUTED,false));
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
            .setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
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
                .setNegativeButton("Cancel",null).setPositiveButton("Arm",(d,w)->{AxonRuntime.arm(policy,peers);axonStatus.setText(AxonRuntime.status());invalidateDiagnostics("Runtime access changed. Test again for current details.");refresh();}).show();
        }catch(Exception e){axonStatus.setText(e.getMessage());}
    }
    private void showAxon(){
        if(busy||!NativeTree.available())return;
        final AxonSubscription subscription;
        try{subscription=AxonSubscription.load(this);if(subscription==null){axonStatus.setText("No saved .axon subscription");return;}}
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
        if(busy||!NativeTree.available())return;
        final List<MeshPeer.Invite> peers;
        final AxonSubscription subscription;
        try{
            MeshPeerAccess.Resolution access=selectedPeers(useAxon.isChecked());
            peers=access.peers();subscription=access.policy();
            if(subscription!=null&&!access.fromSaved())clearSavedInvitationDraft();
        }catch(Exception e){status.setText(e.getMessage());return;}
        final String wanted=interests.getText().toString(),lang=new OverlayPreferences(this).targetLanguage();
        busy=true;refresh();status.setText("Reading peer inventories…");
        final int revision=++diagnosticRevision;
        reportStale=true;analysisOutput.setText("Waiting for receive connection checks…");
        WORK.execute(()->{
            String message;
            try{
                MeshPeer.SyncResult result=mesh.sync(peers,wanted,lang,subscription,progress->runOnUiThread(()->{if(!isDestroyed()&&revision==diagnosticRevision)status.setText(progress);}));
                message="Received "+result.received()+" of "+result.wanted()+" missing items · "+result.peers()+" peers · "+result.failed()+" failures. Return Home to read.";
            }catch(Exception|LinkageError e){message="Receive failed: "+e.getClass().getSimpleName()+" · "+e.getMessage();}
            String result=message;
            final MeshConnectionDiagnostics.Report report=mesh.lastConnectionReport();
            runOnUiThread(()->{if(isDestroyed())return;busy=false;refresh();status.setText(result);if(revision==diagnosticRevision){
                if(subscription!=null&&report!=null)lastBoundHealth=updateBoundHealth(report);
                showConnectionReport(report);
            }});
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
            .setNegativeButton("Cancel",null).setPositiveButton("Publish",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            final MeshItem item;
            try{item=MeshItem.create(language.getText().toString(),topic.getText().toString(),text.getText().toString(),link.getText().toString());}
            catch(Exception e){text.setError(e.getMessage());return;}
            button.setEnabled(false);
            WORK.execute(()->{
                String error=null;
                try{item.store(tree,epoch);}catch(Exception|LinkageError e){error=e.getMessage();}
                final String failure=error;
                activity.runOnUiThread(()->{if(activity.isDestroyed())return;if(failure==null){dialog.dismiss();changed.run();android.widget.Toast.makeText(activity,"Published locally · start Mesh listener to share",android.widget.Toast.LENGTH_LONG).show();}else{button.setEnabled(true);text.setError(failure);}});
            });
        }));
        dialog.show();
    }
}
