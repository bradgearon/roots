package com.wds.sixstars.contextoverlay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.wds.sixstars.contextoverlay.tree.NativeTree;
import com.wds.sixstars.contextoverlay.tree.TreeActivity;
import com.wds.sixstars.contextoverlay.tree.TreeRuntime;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** First screen: actual retained local observations, never synthetic social data. */
public final class RootsStreamActivity extends Activity {
    private final ExecutorService reader=Executors.newSingleThreadExecutor();
    private final ArrayList<NativeTree.Master> rows=new ArrayList<>();
    private final ArrayList<KnowledgeRank.Ranked> ranked=new ArrayList<>();
    private final java.util.Map<String,KnowledgeRank.Ranked> rankByKey=new java.util.HashMap<>();
    private TreeRuntime runtime;
    private TextView status,empty,more,capture;
    private EditText search;
    private ListView feed;
    private KnowledgeRecordAdapter adapter;
    private long generation;
    private String term="";
    private boolean loading,ended,closed,resumed;
    private long shownWave=-1;
    private final Runnable waveListener=()->runOnUiThread(()->{
        if(closed||!resumed||runtime==null)return;
        long revision=runtime.coordination().revision();
        if(revision==shownWave)return;
        // Avoid jumping the list while the user is reading or a search is in progress.
        if(!loading&&rows.isEmpty())reset();
        else status.setText("Coordination changed · tap Rank / refresh");
    });
    private static final int PAGE=30;

    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);
        LinearLayout shell=RootsUi.shell(this);
        shell.addView(RootsUi.header(this,"Roots","Tree of Life · knowledge stream"));
        LinearLayout tools=RootsUi.column(this);
        tools.setPadding(dp(16),0,dp(16),dp(8));
        shell.addView(tools);
        status=RootsUi.text(this,"Local knowledge",14,RootsUi.ACCENT,true);
        tools.addView(status);

        search=new EditText(this);search.setSingleLine(true);search.setTextSize(15);
        search.setTextColor(RootsUi.TEXT);search.setHintTextColor(RootsUi.MUTED);
        search.setHint("Search retained text");search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setBackground(RootsUi.rounded(this,RootsUi.CARD,10,RootsUi.STROKE));
        search.setPadding(dp(12),dp(8),dp(12),dp(8));search.setMinHeight(dp(48));
        tools.addView(search);
        search.setOnEditorActionListener((v,action,event)->{
            if(action==EditorInfo.IME_ACTION_SEARCH){reset();return true;}return false;
        });
        LinearLayout actions=new LinearLayout(this);actions.setPadding(0,dp(8),0,0);
        actions.addView(RootsUi.action(this,"Rank / refresh",this::reset),new LinearLayout.LayoutParams(0,-2,1));
        TextView tree=RootsUi.action(this,"Tree of Life",()->startActivity(new Intent(this,TreeActivity.class)));
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,-2,1);tp.leftMargin=dp(8);actions.addView(tree,tp);
        tools.addView(actions);
        LinearLayout network=new LinearLayout(this);network.setPadding(0,dp(6),0,0);
        network.addView(RootsUi.action(this,"Roots Mesh",()->startActivity(new Intent(this,MeshActivity.class))),new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams historyParams=new LinearLayout.LayoutParams(0,-2,1);historyParams.leftMargin=dp(8);
        network.addView(RootsUi.action(this,"Inference",()->startActivity(new Intent(this,InferenceHistoryActivity.class))),historyParams);
        tools.addView(network);
        capture=RootsUi.action(this,"Enable local capture",this::toggleCapture);tools.addView(capture);
        feed=new ListView(this);feed.setDivider(null);feed.setClipToPadding(false);
        feed.setPadding(dp(16),dp(4),dp(16),dp(8));feed.setSelector(android.R.color.transparent);
        adapter=new KnowledgeRecordAdapter(this,rows,rankByKey);
        // Footer before setAdapter, to keep the explicit pagination control.
        more=RootsUi.action(this,"Show more knowledge",this::load);feed.addFooterView(more,null,false);
        feed.setAdapter(adapter);
        feed.setOnItemClickListener((parent,view,position,id)->{if(position<rows.size())KnowledgeRecordActions.open(this,rows.get(position),this::reset);});
        feed.setOnItemLongClickListener((parent,view,position,id)->{if(position>=rows.size())return false;KnowledgeRecordActions.options(this,rows.get(position),this::reset);return true;});
        empty=RootsUi.text(this,"No observations yet.",16,RootsUi.MUTED,false);
        empty.setPadding(dp(20),dp(16),dp(20),dp(16));empty.setGravity(Gravity.CENTER);
        android.widget.FrameLayout body=new android.widget.FrameLayout(this);
        body.addView(feed,new android.widget.FrameLayout.LayoutParams(-1,-1));
        body.addView(empty,new android.widget.FrameLayout.LayoutParams(-1,-1));
        showEmpty(true);
        shell.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        shell.addView(RootsUi.navigation(this,false));
        setContentView(shell);
        if(NativeTree.available())runtime=TreeRuntime.get(this);
        else {
            status.setText("Native Tree unavailable");
            empty.setText(NativeTree.unavailableReason()+"\n\nBuild Roots with Tree support to retain and browse timeline text. Open Tree of Life for build instructions.");
            capture.setText("Open settings");capture.setOnClickListener(v->startActivity(new Intent(this,OverlayTranslationSettingsActivity.class)));
            more.setVisibility(View.GONE);
        }
    }

    @Override protected void onResume(){super.onResume();resumed=true;if(runtime!=null){runtime.addWaveListener(waveListener);reset();}}
    @Override protected void onPause(){resumed=false;if(runtime!=null)runtime.removeWaveListener(waveListener);super.onPause();}
    @Override protected void onDestroy(){closed=true;generation++;reader.shutdownNow();super.onDestroy();}
    private int dp(int n){return RootsUi.dp(this,n);}
    private void toggleCapture(){
        if(runtime==null)return;
        if(runtime.enabled()){runtime.setEnabled(false);reset();return;}
        if(!new OverlayPreferences(this).enabled()){
            new AlertDialog.Builder(this).setTitle("Accessibility consent required")
                .setMessage("First enable X text processing in Settings. Local knowledge retention is a separate choice.")
                .setNegativeButton("Cancel",null).setPositiveButton("Settings",(d,w)->startActivity(new Intent(this,OverlayTranslationSettingsActivity.class))).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Retain all visible X text locally?")
            .setMessage("Roots will retain accessible text displayed in foreground X across Home, profiles, replies, search and other screens, including potentially private messages and sensitive content. Editable fields and passwords are excluded. Text is stored in a private local database and derived GGUF; capture does not upload it. No screenshots or other apps are captured. Pause stops new capture; an in-flight write may finish. Clear removes retained records and generated snapshots.")
            .setNegativeButton("Not now",null).setPositiveButton("Enable local capture",(d,w)->{runtime.setEnabled(true);reset();}).show();
    }
    private void reset(){
        if(runtime==null||closed)return;
        generation++;loading=false;ended=false;rows.clear();ranked.clear();rankByKey.clear();
        term=search.getText().toString().trim();
        adapter.notifyDataSetChanged();
        showEmpty(true);
        more.setEnabled(true);
        empty.setText("Reading local knowledge…");
        capture.setText(runtime.enabled()?"Pause local capture":"Enable local capture");
        load();
    }
    private void showEmpty(boolean value){
        feed.setVisibility(value?View.GONE:View.VISIBLE);
        empty.setVisibility(value?View.VISIBLE:View.GONE);
    }
    private void showNext(){
        int end=Math.min(ranked.size(),rows.size()+PAGE);
        while(rows.size()<end)rows.add(ranked.get(rows.size()).record());
        ended=rows.size()>=ranked.size();adapter.notifyDataSetChanged();showEmpty(rows.isEmpty());
        more.setText(ended?"All matching knowledge shown":"Show more knowledge");
        more.setEnabled(!ended);
    }
    private void load(){
        if(runtime==null||closed||loading||ended)return;
        if(!ranked.isEmpty()){showNext();return;}
        loading=true;more.setText("Ranking…");long g=generation;String q=term;
        String language=new OverlayPreferences(this).targetLanguage();
        reader.execute(()->{
            try{
                long count=runtime.count();
                List<NativeTree.Master> records=runtime.allRecords(q,10000);
                var field=runtime.coordination();
                MeshPeer mesh=MeshPeer.get(this);
                List<KnowledgeRank.Ranked> result=KnowledgeRank.rank(records,runtime.agents(),language,System.currentTimeMillis(),mesh::replicas,mesh::demand,field,android.os.SystemClock.elapsedRealtime());
                runOnUiThread(()->{
                    if(closed||generation!=g)return;
                    ranked.addAll(result);
                    for(KnowledgeRank.Ranked rank:result)rankByKey.put(rank.record().key(),rank);
                    loading=false;showNext();
                    shownWave=field.revision();
                    status.setText(result.size()+" knowledge records · "+count+" fragments · "+(runtime.enabled()?"capture on":"capture off")+
                        "\n"+(field.revision()<0?"Coordination awaiting localized GGUF":"Wave · "+field.weights().size()+" records · "+field.edges()+" links · rev "+field.revision())+
                        (runtime.coordination().revision()!=shownWave?" · refresh for new wave":""));
                    empty.setText(count==0?"Your Tree is empty. Receive knowledge from Roots Mesh or enable local capture.":"No grouped records match. Search another term or browse X.");
                });
            }catch(Exception|LinkageError error){runOnUiThread(()->{
                if(closed||generation!=g)return;
                loading=false;more.setText("Retry");more.setEnabled(true);
                status.setText("Could not rank the local Tree");
                empty.setText("Local knowledge unavailable. Check Tree of Life and build configuration.");
            });}
        });
    }
}
