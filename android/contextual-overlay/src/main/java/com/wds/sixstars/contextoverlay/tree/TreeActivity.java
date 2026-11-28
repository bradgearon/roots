package com.wds.sixstars.contextoverlay.tree;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.wds.sixstars.contextoverlay.OverlayPreferences;
import com.wds.sixstars.contextoverlay.OverlayDiagnostics;
import com.wds.sixstars.contextoverlay.RootsUi;
import com.wds.sixstars.contextoverlay.KnowledgeRecordAdapter;
import com.wds.sixstars.contextoverlay.KnowledgeRecordActions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native-backed local Tree viewer. Search and writes never run on the UI thread. */
public final class TreeActivity extends Activity {
    private final ExecutorService reader=Executors.newSingleThreadExecutor();
    private TreeRuntime runtime;
    private TextView status,branchLabel;
    private EditText search;
    private KnowledgeRecordAdapter adapter;
    private final ArrayList<NativeTree.Master> rows=new ArrayList<>();
    private TextView summary;
    private boolean rawMode;
    private long generation, before, branch;
    private String term="";
    private boolean loading,ended,closed,refreshOnResume;
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);
        LinearLayout shell=RootsUi.shell(this);
        shell.addView(RootsUi.header(this,"Tree of Life","Local observations and branches"));
        LinearLayout layout=RootsUi.column(this);
        int inset=RootsUi.dp(this,16);
        layout.setPadding(inset,0,inset,inset);
        shell.addView(layout,new LinearLayout.LayoutParams(-1,0,1));
        shell.addView(RootsUi.action(this,"‹  Back",this::finish));
        if(!NativeTree.available()){
            LinearLayout card=RootsUi.card(this,layout);
            card.addView(RootsUi.text(this,"Tree unavailable",19,RootsUi.TEXT,true));
            TextView missing=RootsUi.text(this,
                NativeTree.unavailableReason()+"\n\nBuild Roots with the native Tree and a SQLite amalgamation containing sqlite3.c and sqlite3.h.",15,RootsUi.MUTED,false);
            missing.setPadding(0,inset,0,inset);card.addView(missing);
            TextView command=RootsUi.text(this,
                "build-android.cmd :app:installDebug -ProotsEnableTree=true -ProotsSqliteAmalgamationDir=G:/sdk/sqlite",
                13,RootsUi.TEXT,false);
            command.setTextIsSelectable(true);card.addView(command);
            card.addView(RootsUi.text(this,"Replace G:/sdk/sqlite with your actual SQLite source directory. Translation still works without Tree; local capture does not.",13,RootsUi.MUTED,false));
            android.widget.ScrollView scroll=new android.widget.ScrollView(this);
            shell.removeView(layout);scroll.addView(layout);
            shell.addView(scroll,1,new LinearLayout.LayoutParams(-1,0,1));
            setContentView(shell);return;
        }
        runtime=TreeRuntime.get(this);
        summary=RootsUi.text(this,"Local knowledge",13,RootsUi.ACCENT,true);layout.addView(summary);
        status=new TextView(this);
        // Keep diagnostic details out of the scrollable record area.
        LinearLayout tools=new LinearLayout(this);
        tools.addView(button("Diagnostics",()->{
            refreshStatus();
            TextView details=RootsUi.text(this,runtime.status()+"\n\nAccessibility:\n"+OverlayDiagnostics.summary(this),13,RootsUi.TEXT,false);
            int p=RootsUi.dp(this,16);details.setPadding(p,p,p,p);
            android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.addView(details);
            new AlertDialog.Builder(this).setTitle("Tree diagnostics").setView(scroll)
                .setNeutralButton("Compile GGUF",(dialog,w)->{runtime.requestSnapshot();refreshStatus();})
                .setPositiveButton("Close",null).show();
        }),new LinearLayout.LayoutParams(0,-2,1));
        tools.addView(button("Capture",()->{
            if(runtime.enabled())runtime.setEnabled(false);else consent();
            refreshStatus();
        }),new LinearLayout.LayoutParams(0,-2,1));
        layout.addView(tools);
        search=new EditText(this);search.setSingleLine(true);search.setHint("Search retained text");layout.addView(search);
        layout.addView(button("Search / refresh feed",this::reset));
        branchLabel=new TextView(this);branchLabel.setText("Branch: all records");layout.addView(branchLabel);
        Button mode=button("View: grouped records",()->{rawMode=!rawMode;reset();});
        mode.setOnClickListener(v->{rawMode=!rawMode;mode.setText(rawMode?"View: all raw fragments":"View: grouped records");reset();});
        layout.addView(mode);
        LinearLayout controls=new LinearLayout(this);
        controls.addView(button("Branches",this::chooseBranch),new LinearLayout.LayoutParams(0,-2,1));
        controls.addView(button("New agent",this::createAgent),new LinearLayout.LayoutParams(0,-2,1));
        controls.addView(button("Remove",this::removeAgent),new LinearLayout.LayoutParams(0,-2,1));
        layout.addView(controls);
        ListView list=new ListView(this);
        adapter=new KnowledgeRecordAdapter(this,rows);
        list.setDivider(null);list.setSelector(android.R.color.transparent);
        list.setAdapter(adapter);layout.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        list.setOnItemClickListener((parent,view,position,id)->{
            if(position<rows.size())KnowledgeRecordActions.open(this,rows.get(position),()->{reset();refreshStatus();});
        });
        list.setOnItemLongClickListener((parent,view,position,id)->{
            if(position>=rows.size())return false;
            KnowledgeRecordActions.options(this,rows.get(position),()->{reset();refreshStatus();});return true;
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener(){
            @Override public void onScrollStateChanged(AbsListView v,int state){}
            @Override public void onScroll(AbsListView v,int first,int visible,int total){
                if(total>0&&visible>0&&first+visible>=total-4)load();
            }
        });
        layout.addView(button("Clear local Tree and snapshots",this::clear));
        setContentView(shell);refreshStatus();reset();
    }
    private Button button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextColor(RootsUi.TEXT);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF164B50));b.setOnClickListener(v->action.run());return b;}
    private void refreshStatus(){
        if(runtime==null||closed)return;
        status.setText(runtime.status());
        summary.setText("Capture "+(runtime.enabled()?"on":"off")+" · "+rows.size()+" items loaded");
        reader.execute(()->{
            try{long n=runtime.count();runOnUiThread(()->{if(!closed){status.setText(runtime.status()+"\nTotal stored fragments: "+n);summary.setText(n+" stored fragments · capture "+(runtime.enabled()?"on":"off")+" · "+rows.size()+" loaded");}});}
            catch(Exception e){runOnUiThread(()->{if(!closed)status.setText(runtime.status()+"\nTree unavailable: "+e.getMessage());});}
        });
    }
    private void consent(){
        if(!new OverlayPreferences(this).enabled()){
            new AlertDialog.Builder(this).setMessage("First enable the Roots X accessibility permission in translation settings. Tree storage is a separate opt-in.").setPositiveButton("OK",null).show();return;
        }
        new AlertDialog.Builder(this).setTitle("Retain all visible X text locally?")
            .setMessage("Roots will retain accessible text displayed in foreground X across Home, profiles, replies, search and other screens, including potentially private messages and sensitive content. Editable fields and passwords are excluded. Text is stored in a private local database and derived GGUF; capture does not upload it. No screenshots or other apps are captured. Pause stops new capture; an in-flight write may finish. Clear removes retained records and generated snapshots.")
            .setNegativeButton("Not now",null).setPositiveButton("Enable local capture",(d,w)->{runtime.setEnabled(true);refreshStatus();}).show();
    }
    private void reset(){
        if(closed||adapter==null)return;
        generation++;before=0;ended=false;loading=false;term=search.getText().toString();
        rows.clear();adapter.notifyDataSetChanged();load();
    }
    private void load(){
        if(closed||loading||ended||adapter==null)return;
        loading=true;long g=generation,b=before,a=branch;String q=term;boolean raw=rawMode;
        reader.execute(()->{
            try{
                List<NativeTree.Master> page;
                if(raw){
                    page=new ArrayList<>();
                    for(NativeTree.Fragment f:runtime.query(q,a,b,30))
                        page.add(new NativeTree.Master(f.sequence(),"",java.util.Collections.singletonList(f)));
                }else page=runtime.records(q,a,b,30);
                runOnUiThread(()->{
                    if(closed||g!=generation)return;
                    rows.addAll(page);
                    if(!page.isEmpty())before=page.get(page.size()-1).sequence();
                    ended=page.size()<30;loading=false;adapter.notifyDataSetChanged();
                    if(rows.isEmpty())summary.setText(raw?"No matching fragments.":"No grouped records yet. Browse X or switch to all raw fragments.");
                    else summary.setText(rows.size()+(raw?" fragments":" records")+" loaded · capture "+(runtime.enabled()?"on":"off"));
                });
            }catch(Exception e){runOnUiThread(()->{if(!closed&&g==generation){loading=false;ended=true;status.setText("Query failed: "+e.getMessage());summary.setText("Query failed: "+e.getMessage());}});}
        });
    }
    private void chooseBranch(){
        reader.execute(()->{
            try{List<NativeTree.Agent> list=runtime.agents();runOnUiThread(()->{
                if(closed)return;
                String[] names=new String[list.size()+1];names[0]="All knowledge";
                for(int i=0;i<list.size();i++)names[i+1]=list.get(i).name()+" ("+list.get(i).term()+")";
                new AlertDialog.Builder(this).setTitle("Choose branch").setItems(names,(d,w)->{
                    branch=w==0?0:list.get(w-1).id();branchLabel.setText("Branch: "+names[w]);reset();
                }).show();
            });}catch(Exception e){runOnUiThread(()->{if(!closed)status.setText("Branches unavailable: "+e.getMessage());});}
        });
    }
    private void createAgent(){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);
        EditText name=new EditText(this);name.setHint("Branch name");form.addView(name);
        EditText match=new EditText(this);match.setHint("Literal interest phrase");form.addView(match);
        new AlertDialog.Builder(this).setTitle("Create local branch agent").setView(form)
            .setMessage("Initial agents backfill and collect matching local fragments. Lily semantic sensors and external research are later capabilities.")
            .setNegativeButton("Cancel",null).setPositiveButton("Create",(d,w)->runtime.createAgent(name.getText().toString().trim(),match.getText().toString().trim(),error->runOnUiThread(()->{if(!closed){if(error==null){refreshStatus();chooseBranch();}else status.setText(error);}}))).show();
    }
    private void removeAgent(){
        if(branch==0)return;long id=branch;
        new AlertDialog.Builder(this).setMessage("Remove this agent and its branch membership? Observations remain.")
            .setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,w)->runtime.removeAgent(id,error->runOnUiThread(()->{if(!closed){if(error==null){branch=0;branchLabel.setText("Branch: all records");reset();refreshStatus();}else status.setText(error);}}))).show();
    }
    private void clear(){
        new AlertDialog.Builder(this).setTitle("Clear local Tree?").setMessage("Stops capture and logically deletes all observations, agents, and managed GGUF snapshots. This is not forensic erasure.")
            .setNegativeButton("Cancel",null).setPositiveButton("Clear",(d,w)->{
                generation++;ended=true;loading=false;rows.clear();adapter.notifyDataSetChanged();
                runtime.clear(error->runOnUiThread(()->{if(!closed){branch=0;branchLabel.setText("Branch: all records");reset();refreshStatus();if(error!=null)status.setText(error);}}));
            }).show();
    }
    @Override protected void onPause(){refreshOnResume=true;super.onPause();}
    @Override protected void onResume(){
        super.onResume();
        if(runtime!=null){
            refreshStatus();
            if(refreshOnResume)reset();
        }
        refreshOnResume=false;
    }
    @Override protected void onDestroy(){closed=true;generation++;reader.shutdownNow();super.onDestroy();}
}
