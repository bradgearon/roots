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

/** Explicit peer sharing and receiving. Opening a roots:// link never connects
 * until the user taps Test connection or Receive. No capture permission needed. */
public final class MeshActivity extends Activity {
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"RootsMeshUi");t.setDaemon(true);return t;});
    private MeshPeer mesh;
    private TextView status,serve,copy,receive,stop,axonStatus;
    private TextView connectionSummary,connectionStatus,connectionDetails,analysisOutput;
    private TextView testConnection,analyzeConnection,copyConnectionReport;
    private android.widget.CheckBox useAxon;
    private EditText invitations,interests;
    private boolean busy;
    private boolean reportStale;
    private int diagnosticRevision;
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
        publish.addView(RootsUi.text(this,"Publish knowledge",18,RootsUi.TEXT,true));
        publish.addView(RootsUi.text(this,"Only explicitly published remembrances are offered. Captured posts, private messages, originals, SQLite and GGUF never leave this device through Mesh. Published knowledge can be retained and reshared by recipients.",13,RootsUi.MUTED,false));
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
        inbound.addView(RootsUi.text(this,"Paste up to eight invitations, one per line. Only missing content is requested. Your private branch terms and interests rank offers locally; they are not sent to peers.",13,RootsUi.MUTED,false));
        invitations=new EditText(this);invitations.setHint("roots://mesh/v1?…");invitations.setMinLines(2);
        invitations.setSaveEnabled(false); // Invitation grants remain in memory, never saved into view state.
        if(android.os.Build.VERSION.SDK_INT>=26)invitations.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        invitations.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        invitations.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8192)});inbound.addView(invitations);
        interests=new EditText(this);interests.setHint("Wanted topics, separated by commas (optional)");interests.setSingleLine(true);
        interests.setFilters(new InputFilter[]{new InputFilter.LengthFilter(512)});inbound.addView(interests);
        useAxon=new android.widget.CheckBox(this);
        useAxon.setText("Use saved .axon subscription");useAxon.setTextColor(RootsUi.TEXT);
        useAxon.setChecked(new java.io.File(getNoBackupFilesDir(),"subscription.axon").isFile());
        inbound.addView(useAxon);
        LinearLayout connection=RootsUi.section(this,inbound,"Connection details","Test access to published inventory, then ask local intelligence to explain the result.",true);
        connectionSummary=RootsUi.text(this,"Checking local network…",13,RootsUi.MUTED,false);
        connectionSummary.setTextIsSelectable(true);connection.addView(connectionSummary);
        testConnection=RootsUi.action(this,"Test connection",this::testConnection);connection.addView(testConnection);
        connectionStatus=RootsUi.text(this,"No connection test yet.",14,RootsUi.ACCENT,true);connection.addView(connectionStatus);
        connectionDetails=RootsUi.text(this,"",13,RootsUi.TEXT,false);
        connectionDetails.setTextIsSelectable(true);
        copyConnectionReport=RootsUi.action(this,"Copy connection report",this::copyConnectionReport);connection.addView(copyConnectionReport);
        analyzeConnection=RootsUi.action(this,"Analyze connection with intelligence",this::analyzeConnection);connection.addView(analyzeConnection);
        connection.addView(RootsUi.action(this,"Intelligence model settings",()->{
            if(!busy)startActivity(new Intent(this,OverlayTranslationSettingsActivity.class));
        }));
        connection.addView(connectionDetails);
        analysisOutput=RootsUi.text(this,"Run a test to enable analysis with your selected local model.",13,RootsUi.MUTED,false);
        analysisOutput.setTextIsSelectable(true);connection.addView(analysisOutput);
        inbound.addView(RootsUi.action(this,"Bind / edit .axon",this::editAxon));
        inbound.addView(RootsUi.action(this,"View current flow allocation",this::showAxon));
        inbound.addView(RootsUi.action(this,"Arm runtime Axon (30 minutes)",this::armRuntime));
        inbound.addView(RootsUi.action(this,"Revoke runtime access",()->{if(busy)return;AxonRuntime.revoke();axonStatus.setText(AxonRuntime.status());invalidateDiagnostics("Runtime access changed. Test again for current details.");refresh();}));
        inbound.addView(RootsUi.action(this,"Remove .axon binding",()->{
            if(busy)return;
            new AlertDialog.Builder(this).setTitle("Remove saved Axon?")
                .setMessage("Removes the local subscription, not retained knowledge.")
                .setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,w)->{AxonRuntime.revoke();AxonSubscription.clear(this);useAxon.setChecked(false);axonStatus.setText("No saved Axon");invalidateDiagnostics("Axon binding removed. Test again for current details.");refresh();}).show();
        }));
        axonStatus=RootsUi.text(this,"Bind one or more peer identities and select topics.",13,RootsUi.MUTED,false);
        inbound.addView(axonStatus);
        receive=RootsUi.action(this,"Receive missing knowledge",this::receive);inbound.addView(receive);
        status=RootsUi.text(this,"",14,RootsUi.ACCENT,true);content.addView(status);
        shell.addView(RootsUi.action(this,"‹ Home",()->{startActivity(new Intent(this,RootsStreamActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));finish();}));
        invitations.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){invalidateDiagnostics("Invitation changed. Test the current connection.");}
            @Override public void afterTextChanged(Editable s){}
        });
        useAxon.setOnCheckedChangeListener((button,checked)->invalidateDiagnostics("Subscription selection changed. Test the current connection."));
        RootsUi.styleInputs(this,shell);setContentView(shell);readInvitation(getIntent());refresh();
        if(mesh.lastConnectionReport()!=null){
            showConnectionReport(mesh.lastConnectionReport());
            invalidateDiagnostics("Previous connection snapshot. Test the current invitations to refresh it.");
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
    @Override protected void onResume(){super.onResume();refresh();}
    private void refresh(){
        if(isDestroyed()||mesh==null)return;
        boolean listening=!mesh.invitation().isEmpty();
        serve.setEnabled(!listening&&!busy);copy.setEnabled(listening);stop.setEnabled(listening);
        receive.setEnabled(!busy&&NativeTree.available());
        invitations.setEnabled(!busy);interests.setEnabled(!busy);useAxon.setEnabled(!busy);
        String summary=mesh.connectionSummary();
        String state=mesh.connectionStateKey();
        if(!lastConnectionState.isEmpty()&&!lastConnectionState.equals(state))
            invalidateDiagnostics("Local connection state changed. Test again for current details.");
        lastConnectionState=state;
        connectionSummary.setText(summary+"\n"+AxonRuntime.status()+"\nAnalysis model: "+IntelligenceModel.selected(this).label);
        updateDiagnosticActions();
        // Keep completed receive/start errors visible when returning to this screen.
        if(!busy&&status.getText().length()==0)status.setText(NativeTree.available()?mesh.status():"Native Tree unavailable. Connection testing is still available.");
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
            analysisOutput.setText("Ready to analyze with "+IntelligenceModel.selected(this).label+". The model receives connection checks; invitation credentials and published content are excluded.");
        }catch(Exception e){
            renderedReport="";connectionDetails.setText("");
            connectionStatus.setText("Could not render the connection report ("+e.getClass().getSimpleName()+").");
        }
        updateDiagnosticActions();
    }
    private void testConnection(){
        if(busy)return;
        final String raw=invitations.getText().toString();
        final boolean savedAxon=useAxon.isChecked();
        busy=true;refresh();
        final int revision=++diagnosticRevision;
        reportStale=true;connectionStatus.setText("Testing the invited peers…");
        analysisOutput.setText("Waiting for connection checks…");
        WORK.execute(()->{
            MeshConnectionDiagnostics.Report report=null;String error=null;
            try{report=mesh.testConnections(raw,savedAxon,progress->runOnUiThread(()->{
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
                if(failure==null)showConnectionReport(completed);else connectionStatus.setText(failure);
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
                    "Intelligence unavailable: "+error+"\nThe measured connection report remains above. Download or select a model in Intelligence model settings.");
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
            try{mesh.start();message="Listening on Wi-Fi · "+mesh.offeredCount()+" published items";}
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
        new AlertDialog.Builder(this).setTitle("Share access to published knowledge?")
            .setMessage("Anyone holding this invitation on your local subnet can fetch the published collection until you stop listening.")
            .setNegativeButton("Cancel",null).setPositiveButton("Share",(d,w)->startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,invitation),"Roots Mesh invitation"))).show();
    }
    private List<MeshPeer.Invite> parseInvitations()throws Exception{
        List<MeshPeer.Invite> peers=new ArrayList<>();
        for(String line:invitations.getText().toString().split("\\r?\\n"))if(!line.trim().isEmpty())peers.add(MeshPeer.Invite.parse(line));
        if(peers.isEmpty()||peers.size()>8)throw new IllegalArgumentException("Enter one to eight invitations.");
        return peers;
    }
    private void editAxon(){
        if(busy)return;
        final List<MeshPeer.Invite> peers;
        final AxonSubscription old;
        try{peers=parseInvitations();old=AxonSubscription.load(this);}
        catch(Exception e){axonStatus.setText(e.getMessage());return;}
        LinearLayout box=RootsUi.column(this);box.setPadding(dp(16),0,dp(16),0);box.setBackgroundColor(RootsUi.CARD);
        box.addView(RootsUi.text(this,"One topic per line; optional FFN group addresses from the Gemma flow analyzer. Example: music | 7:12,8:2. Use * to receive other topics too. No automatic claim about an element's meaning. Observed mapped flow gets half the allocation; local knowledge demand gets the other half.",13,RootsUi.MUTED,false));
        android.widget.Spinner model=new android.widget.Spinner(this);RootsUi.styleSpinner(this,model,IntelligenceModel.labels());
        model.setSelection((old==null?IntelligenceModel.selected(this):old.model).ordinal());box.addView(model);
        android.widget.Spinner op=new android.widget.Spinner(this);RootsUi.styleSpinner(this,op,ElementFlow.OPERATIONS);
        op.setSelection(old==null?0:java.util.Arrays.asList(ElementFlow.OPERATIONS).indexOf(old.operation));box.addView(op);
        EditText lines=new EditText(this);lines.setMinLines(4);lines.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        lines.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4096)});
        lines.setHint("music\nscience | 7:12\n*");
        lines.setText(old==null?interests.getText().toString().replace(',','\n'):old.lines());box.addView(lines);
        RootsUi.styleInputs(this,box);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Bind .axon to "+peers.size()+" invited peer(s)").setView(box)
            .setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            try{
                AxonSubscription next=AxonSubscription.create(peers,IntelligenceModel.values()[model.getSelectedItemPosition()],ElementFlow.OPERATIONS[op.getSelectedItemPosition()],lines.getText().toString());
                AxonRuntime.revoke();next.save(this);useAxon.setChecked(true);dialog.dismiss();invalidateDiagnostics("Axon binding changed. Test the current connection.");refresh();showAxon();
            }catch(Exception e){lines.setError(e.getMessage());}
        }));dialog.show();
    }
    private void armRuntime(){
        if(busy)return;
        try{
            AxonSubscription policy=AxonSubscription.load(this);
            List<MeshPeer.Invite> peers=parseInvitations();
            if(policy==null||!policy.accepts(peers))throw new IllegalArgumentException("Bind these peers to an Axon first.");
            new AlertDialog.Builder(this).setTitle("Allow runtime Axon access?")
                .setMessage("For 30 minutes, explicit intelligence requests may ask these bound peers for published knowledge using only named subscription topics you approve. Your prompt stays here. No background requests, no automatic retention. Invitations remain in memory only.")
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
            peers=parseInvitations();
            subscription=useAxon.isChecked()?AxonSubscription.load(this):null;
            if(useAxon.isChecked()&&subscription==null)throw new IllegalArgumentException("Bind an Axon first.");
            if(subscription!=null&&!subscription.accepts(peers))throw new IllegalArgumentException("This peer is not bound. Edit the Axon or disable it.");
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
            runOnUiThread(()->{if(isDestroyed())return;busy=false;refresh();status.setText(result);if(revision==diagnosticRevision)showConnectionReport(report);});
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
