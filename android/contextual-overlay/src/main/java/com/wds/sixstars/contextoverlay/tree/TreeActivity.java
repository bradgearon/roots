package com.wds.sixstars.contextoverlay.tree;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.wds.sixstars.contextoverlay.OverlayPreferences;
import com.wds.sixstars.contextoverlay.RootsUi;
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
    private ArrayAdapter<String> adapter;
    private final ArrayList<String> rows=new ArrayList<>();
    private long generation, before, branch;
    private String term="";
    private boolean loading,ended,closed;
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
        status=new TextView(this);layout.addView(status);
        Button refresh=button("Refresh status",()->refreshStatus());layout.addView(refresh);
        layout.addView(button("Enable timeline capture",this::consent));
        layout.addView(button("Pause capture",()->{runtime.setEnabled(false);refreshStatus();}));
        layout.addView(button("Compile GGUF",()->{runtime.requestSnapshot();refreshStatus();}));
        search=new EditText(this);search.setSingleLine(true);search.setHint("Search retained text");layout.addView(search);
        layout.addView(button("Search / refresh feed",this::reset));
        branchLabel=new TextView(this);branchLabel.setText("Branch: all fragments");layout.addView(branchLabel);
        LinearLayout controls=new LinearLayout(this);
        controls.addView(button("Branches",this::chooseBranch),new LinearLayout.LayoutParams(0,-2,1));
        controls.addView(button("New agent",this::createAgent),new LinearLayout.LayoutParams(0,-2,1));
        controls.addView(button("Remove",this::removeAgent),new LinearLayout.LayoutParams(0,-2,1));
        layout.addView(controls);
        ListView list=new ListView(this);
        adapter=new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,rows);
        list.setAdapter(adapter);layout.addView(list,new LinearLayout.LayoutParams(-1,0,1));
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
        reader.execute(()->{
            try{long n=runtime.count();runOnUiThread(()->{if(!closed)status.setText(runtime.status()+"\nTotal stored fragments: "+n);});}
            catch(Exception e){runOnUiThread(()->{if(!closed)status.setText(runtime.status()+"\nTree unavailable: "+e.getMessage());});}
        });
    }
    private void consent(){
        if(!new OverlayPreferences(this).enabled()){
            new AlertDialog.Builder(this).setMessage("First enable the Roots X accessibility permission in translation settings. Tree storage is a separate opt-in.").setPositiveButton("OK",null).show();return;
        }
        new AlertDialog.Builder(this).setTitle("Retain home timeline text locally?")
            .setMessage("Roots will try to retain eligible text from X's foreground Home timeline, including posts you scroll past quickly and accessible lookahead. It cannot guarantee that every fragment is public or complete. It rejects unknown or private-looking surfaces. Text is retained in a private local database and derived GGUF, not uploaded. Pause stops new capture; an in-flight write may finish. Clear removes retained records and generated snapshots.")
            .setNegativeButton("Not now",null).setPositiveButton("Enable local capture",(d,w)->{runtime.setEnabled(true);refreshStatus();}).show();
    }
    private void reset(){
        if(closed||adapter==null)return;
        generation++;before=0;ended=false;loading=false;term=search.getText().toString();
        rows.clear();adapter.notifyDataSetChanged();load();
    }
    private void load(){
        if(closed||loading||ended||adapter==null)return;
        loading=true;long g=generation,b=before,a=branch;String q=term;
        reader.execute(()->{
            try{
                List<NativeTree.Fragment> page=runtime.query(q,a,b,30);
                runOnUiThread(()->{
                    if(closed||g!=generation)return;
                    for(NativeTree.Fragment f:page)rows.add(f.body()+"\n"+f.source()+" · "+(f.language().isEmpty()?"language unknown":f.language())+" · observed fragment #"+f.sequence());
                    if(!page.isEmpty())before=page.get(page.size()-1).sequence();
                    ended=page.size()<30;loading=false;adapter.notifyDataSetChanged();
                    if(rows.isEmpty())status.setText(runtime.status()+"\nNo matching fragments.");
                });
            }catch(Exception e){runOnUiThread(()->{if(!closed&&g==generation){loading=false;ended=true;status.setText("Query failed: "+e.getMessage());}});}
        });
    }
    private void chooseBranch(){
        reader.execute(()->{
            try{List<NativeTree.Agent> list=runtime.agents();runOnUiThread(()->{
                if(closed)return;
                String[] names=new String[list.size()+1];names[0]="All fragments";
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
            .setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,w)->runtime.removeAgent(id,error->runOnUiThread(()->{if(!closed){if(error==null){branch=0;branchLabel.setText("Branch: all fragments");reset();refreshStatus();}else status.setText(error);}}))).show();
    }
    private void clear(){
        new AlertDialog.Builder(this).setTitle("Clear local Tree?").setMessage("Stops capture and logically deletes all observations, agents, and managed GGUF snapshots. This is not forensic erasure.")
            .setNegativeButton("Cancel",null).setPositiveButton("Clear",(d,w)->{
                generation++;ended=true;loading=false;rows.clear();adapter.notifyDataSetChanged();
                runtime.clear(error->runOnUiThread(()->{if(!closed){branch=0;branchLabel.setText("Branch: all fragments");reset();refreshStatus();if(error!=null)status.setText(error);}}));
            }).show();
    }
    @Override protected void onResume(){super.onResume();if(runtime!=null)refreshStatus();}
    @Override protected void onDestroy(){closed=true;generation++;reader.shutdownNow();super.onDestroy();}
}
