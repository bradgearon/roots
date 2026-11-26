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
    private final ArrayList<NativeTree.Fragment> rows=new ArrayList<>();
    private TreeRuntime runtime;
    private TextView status,empty,more,capture;
    private EditText search;
    private ListView feed;
    private FeedAdapter adapter;
    private long generation,cursor;
    private String term="";
    private boolean loading,ended,closed;
    private static final int PAGE=30;

    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);
        LinearLayout shell=RootsUi.shell(this);
        shell.addView(RootsUi.header(this,"Roots","Stream · your acquired knowledge"));
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
        actions.addView(RootsUi.action(this,"Search / refresh",this::reset),new LinearLayout.LayoutParams(0,-2,1));
        TextView tree=RootsUi.action(this,"Tree of Life",()->startActivity(new Intent(this,TreeActivity.class)));
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,-2,1);tp.leftMargin=dp(8);actions.addView(tree,tp);
        tools.addView(actions);
        capture=RootsUi.action(this,"Enable local capture",this::toggleCapture);tools.addView(capture);
        feed=new ListView(this);feed.setDivider(null);feed.setClipToPadding(false);
        feed.setPadding(dp(16),dp(4),dp(16),dp(8));feed.setSelector(android.R.color.transparent);
        adapter=new FeedAdapter();
        // Footer before setAdapter, to keep the explicit pagination control.
        more=RootsUi.action(this,"Load older observations",this::load);feed.addFooterView(more,null,false);
        feed.setAdapter(adapter);
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

    @Override protected void onResume(){super.onResume();if(runtime!=null)reset();}
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
        new AlertDialog.Builder(this).setTitle("Retain X Home timeline text locally?")
            .setMessage("Roots will try to retain eligible text from the foreground X Home timeline, including accessible lookahead. It rejects unknown and private-looking surfaces, but cannot guarantee every fragment is public or complete. Text stays in a private local database and derived GGUF; this capture does not upload it. Pause stops new capture; an in-flight write may finish. Clear is available in Tree of Life.")
            .setNegativeButton("Not now",null).setPositiveButton("Enable local capture",(d,w)->{runtime.setEnabled(true);reset();}).show();
    }
    private void reset(){
        if(runtime==null||closed)return;
        generation++;cursor=0;loading=false;ended=false;rows.clear();
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
    private void load(){
        if(runtime==null||closed||loading||ended)return;
        loading=true;more.setText("Loading…");long g=generation,b=cursor;String q=term;
        reader.execute(()->{
            try {
                long count=runtime.count();
                List<NativeTree.Fragment> page=runtime.query(q,0,b,PAGE);
                runOnUiThread(()->{
                    if(closed||generation!=g)return;
                    rows.addAll(page);
                    if(!page.isEmpty())cursor=page.get(page.size()-1).sequence();
                    ended=page.size()<PAGE;loading=false;adapter.notifyDataSetChanged();
                    showEmpty(rows.isEmpty());
                    status.setText(count+" stored fragments · capture "+(runtime.enabled()?"on":"off"));
                    empty.setText(count==0?"Your stream is empty. Enable local capture, then browse X Home.":"No fragments match your search.");
                    more.setText(ended?"All matching observations loaded":"Load older observations");
                    more.setEnabled(!ended);
                });
            }catch(Exception error){runOnUiThread(()->{
                if(closed||generation!=g)return;
                loading=false;more.setText("Retry");more.setEnabled(true);
                status.setText("Could not read the local Tree");
                empty.setText("Local knowledge unavailable. Check Tree of Life and build configuration.");
            });}
        });
    }

    private final class FeedAdapter extends BaseAdapter {
        @Override public int getCount(){return rows.size();}
        @Override public Object getItem(int p){return rows.get(p);}
        @Override public long getItemId(int p){return rows.get(p).sequence();}
        @Override public boolean hasStableIds(){return true;}
        @Override public View getView(int position,View convert,ViewGroup parent){
            NativeTree.Fragment f=rows.get(position);
            LinearLayout outer=RootsUi.column(RootsStreamActivity.this);
            LinearLayout card=RootsUi.card(RootsStreamActivity.this,outer);
            LinearLayout top=new LinearLayout(RootsStreamActivity.this);top.setGravity(Gravity.CENTER_VERTICAL);
            TextView source=RootsUi.text(RootsStreamActivity.this,"X  ·  Home observation",13,RootsUi.ACCENT,true);
            top.addView(source,new LinearLayout.LayoutParams(0,-2,1));
            String code=f.language().isEmpty()?"Unknown":f.language().toUpperCase(Locale.ROOT);
            TextView lang=RootsUi.text(RootsStreamActivity.this,code,12,RootsUi.MUTED,true);
            lang.setBackground(RootsUi.rounded(RootsStreamActivity.this,0xFF1C393E,8,RootsUi.STROKE));
            lang.setPadding(dp(8),dp(4),dp(8),dp(4));top.addView(lang);
            card.addView(top);
            TextView when=RootsUi.text(RootsStreamActivity.this,DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(f.at())),12,RootsUi.MUTED,false);
            when.setPadding(0,dp(5),0,dp(9));card.addView(when);
            TextView text=RootsUi.text(RootsStreamActivity.this,f.body(),16,RootsUi.TEXT,false);
            text.setTextIsSelectable(true);text.setLineSpacing(dp(3),1f);card.addView(text);
            TextView footer=RootsUi.text(RootsStreamActivity.this,"Original · fragment #"+f.sequence(),12,RootsUi.MUTED,false);
            footer.setPadding(0,dp(10),0,0);card.addView(footer);
            return outer;
        }
    }
}
