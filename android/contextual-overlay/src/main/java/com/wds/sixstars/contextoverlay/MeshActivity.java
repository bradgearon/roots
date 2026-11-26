package com.wds.sixstars.contextoverlay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
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

/** Explicit peer sharing and receiving. Opening a roots:// link never sends
 * anything until the user taps Receive. No capture permission needed. */
public final class MeshActivity extends Activity {
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"RootsMeshUi");t.setDaemon(true);return t;});
    private MeshPeer mesh;
    private TextView status,serve,copy,receive,stop,axonStatus;
    private android.widget.CheckBox useAxon;
    private EditText invitations,interests;
    private boolean busy;
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
        stop=RootsUi.action(this,"Stop listening",()->{mesh.stop();getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);refresh();});listener.addView(stop);
        LinearLayout inbound=RootsUi.card(this,content);
        inbound.addView(RootsUi.text(this,"Receive knowledge",18,RootsUi.TEXT,true));
        inbound.addView(RootsUi.text(this,"Paste up to eight invitations, one per line. Only missing content is requested. Your private branch terms and interests rank offers locally; they are not sent to peers.",13,RootsUi.MUTED,false));
        invitations=new EditText(this);invitations.setHint("roots://mesh/v1?…");invitations.setMinLines(2);
        invitations.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        invitations.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8192)});inbound.addView(invitations);
        interests=new EditText(this);interests.setHint("Wanted topics, separated by commas (optional)");interests.setSingleLine(true);
        interests.setFilters(new InputFilter[]{new InputFilter.LengthFilter(512)});inbound.addView(interests);
        useAxon=new android.widget.CheckBox(this);
        useAxon.setText("Use saved .axon subscription");useAxon.setTextColor(RootsUi.TEXT);
        useAxon.setChecked(new java.io.File(getNoBackupFilesDir(),"subscription.axon").isFile());
        inbound.addView(useAxon);
        inbound.addView(RootsUi.action(this,"Bind / edit .axon",this::editAxon));
        inbound.addView(RootsUi.action(this,"View current flow allocation",this::showAxon));
        inbound.addView(RootsUi.action(this,"Remove .axon binding",()->{
            new AlertDialog.Builder(this).setTitle("Remove saved Axon?")
                .setMessage("Removes the local subscription, not retained knowledge.")
                .setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,w)->{AxonSubscription.clear(this);useAxon.setChecked(false);axonStatus.setText("No saved Axon");}).show();
        }));
        axonStatus=RootsUi.text(this,"Bind one or more peer identities and select topics.",13,RootsUi.MUTED,false);
        inbound.addView(axonStatus);
        receive=RootsUi.action(this,"Receive missing knowledge",this::receive);inbound.addView(receive);
        status=RootsUi.text(this,"",14,RootsUi.ACCENT,true);content.addView(status);
        shell.addView(RootsUi.action(this,"‹ Home",()->{startActivity(new Intent(this,RootsStreamActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));finish();}));
        RootsUi.styleInputs(this,shell);setContentView(shell);readInvitation(getIntent());refresh();
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);readInvitation(intent);}
    private void readInvitation(Intent intent){
        if(intent==null||!Intent.ACTION_VIEW.equals(intent.getAction())||intent.getDataString()==null)return;
        String raw=intent.getDataString();
        try{
            if(raw.length()>1024)throw new IllegalArgumentException("Invalid invitation");
            MeshPeer.Invite.parse(raw);invitations.setText(raw);
            status.setText("Invitation ready. Tap Receive to connect.");
        }catch(Exception e){status.setText("Invalid Roots Mesh invitation.");}
    }
    @Override protected void onResume(){super.onResume();refresh();}
    private void refresh(){
        if(isDestroyed()||mesh==null)return;
        boolean listening=!mesh.invitation().isEmpty();
        serve.setEnabled(!listening&&!busy);copy.setEnabled(listening);stop.setEnabled(listening);
        receive.setEnabled(!busy&&NativeTree.available());
        if(!busy)status.setText(NativeTree.available()?mesh.status():"Native Tree unavailable.");
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
        box.addView(RootsUi.text(this,"One topic per line; optional FFN group addresses from the Gemma flow analyzer. Example: music | 7:12,8:2. Use * to receive other topics too. No automatic claim about an element's meaning.",13,RootsUi.MUTED,false));
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
                next.save(this);useAxon.setChecked(true);dialog.dismiss();showAxon();
            }catch(Exception e){lines.setError(e.getMessage());}
        }));dialog.show();
    }
    private void showAxon(){
        if(!NativeTree.available())return;
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
        WORK.execute(()->{
            String message;
            try{
                MeshPeer.SyncResult result=mesh.sync(peers,wanted,lang,subscription,progress->runOnUiThread(()->{if(!isDestroyed())status.setText(progress);}));
                message="Received "+result.received()+" of "+result.wanted()+" missing items · "+result.peers()+" peers · "+result.failed()+" failures. Return Home to read.";
            }catch(Exception|LinkageError e){message="Receive failed: "+e.getClass().getSimpleName()+" · "+e.getMessage();}
            String result=message;
            runOnUiThread(()->{if(isDestroyed())return;busy=false;refresh();status.setText(result);});
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
