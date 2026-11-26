package com.wds.sixstars.contextoverlay;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import java.util.ArrayList;
import java.util.List;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/** Live, newest-first view of actual mobile-originated inference attempts. */
public final class InferenceHistoryActivity extends Activity {
    private InferenceHistory history;
    private final ArrayList<InferenceHistory.Entry> rows=new ArrayList<>();
    private TextView summary;
    private ListView list;
    private BaseAdapter adapter;
    private final Runnable refresh=this::refresh;
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        history=InferenceHistory.get(this);
        LinearLayout shell=RootsUi.shell(this);
        shell.addView(RootsUi.header(this,"Inference history","Live · actual requests from this phone"));
        LinearLayout top=RootsUi.column(this);top.setPadding(dp(16),0,dp(16),dp(8));shell.addView(top);
        summary=RootsUi.text(this,"",14,RootsUi.TEXT,true);top.addView(summary);
        TextView hint=RootsUi.text(this,"Last 100 attempts · desktop completion means an authenticated response. No source text is saved here.",12,RootsUi.MUTED,false);top.addView(hint);
        top.addView(RootsUi.action(this,"Clear history",()->new AlertDialog.Builder(this).setMessage("Clear the saved inference history?").setNegativeButton("Cancel",null).setPositiveButton("Clear",(d,w)->history.clear()).show()));
        list=new ListView(this);list.setDivider(null);list.setClipToPadding(false);list.setPadding(dp(16),dp(4),dp(16),dp(8));
        adapter=new BaseAdapter(){
            public int getCount(){return rows.size();}
            public Object getItem(int position){return rows.get(position);}
            public long getItemId(int position){return position;}
            public View getView(int position,View convert,ViewGroup parent){
                InferenceHistory.Entry e=rows.get(position);
                LinearLayout wrap=RootsUi.column(InferenceHistoryActivity.this);
                LinearLayout card=RootsUi.card(InferenceHistoryActivity.this,wrap);
                LinearLayout header=new LinearLayout(InferenceHistoryActivity.this);header.setGravity(Gravity.CENTER_VERTICAL);
                int color=e.device().startsWith("Desktop")?0xFF8ACCF8:RootsUi.ACCENT;
                TextView device=RootsUi.text(InferenceHistoryActivity.this,e.device(),15,color,true);
                header.addView(device,new LinearLayout.LayoutParams(0,-2,1));
                int stateColor="Failed".equals(e.state())?0xFFFFB7A0:"Completed".equals(e.state())?RootsUi.TEXT:RootsUi.MUTED;
                String elapsed=e.ms()<0?"":String.format(Locale.ROOT," · %.2f s",e.ms()/1000.0);
                header.addView(RootsUi.text(InferenceHistoryActivity.this,e.state()+elapsed,13,stateColor,true));card.addView(header);
                card.addView(RootsUi.text(InferenceHistoryActivity.this,e.model()+" · "+e.operation(),14,RootsUi.TEXT,false));
                String date=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.MEDIUM).format(new Date(e.time()));
                card.addView(RootsUi.text(InferenceHistoryActivity.this,date+" · "+e.detail(),12,RootsUi.MUTED,false));
                return wrap;
            }
        };
        list.setAdapter(adapter);shell.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        shell.addView(RootsUi.action(this,"‹ Back",this::finish));
        setContentView(shell);refresh();
    }
    @Override protected void onStart(){super.onStart();history.listen(refresh);refresh();}
    @Override protected void onStop(){history.unlisten(refresh);super.onStop();}
    private int dp(int n){return RootsUi.dp(this,n);}
    private void refresh(){
        if(history==null||isDestroyed())return;
        List<InferenceHistory.Entry> snapshot=history.snapshot();int pc=0,phone=0,failed=0,active=0;
        for(InferenceHistory.Entry e:snapshot){
            if("Completed".equals(e.state())){if(e.device().startsWith("Desktop"))pc++;else phone++;}
            else if("Failed".equals(e.state()))failed++;
            else if(!"Interrupted".equals(e.state()))active++;
        }
        summary.setText(snapshot.isEmpty()?"No recorded requests yet.":("Desktop "+pc+"  ·  Phone "+phone+"  ·  Failed "+failed+"  ·  Active "+active));
        int first=list.getFirstVisiblePosition();String anchor=first>0&&first<rows.size()?rows.get(first).id():null;
        View child=list.getChildAt(0);int offset=child==null?0:child.getTop();
        rows.clear();rows.addAll(snapshot);adapter.notifyDataSetChanged();
        if(anchor!=null){for(int i=0;i<rows.size();i++)if(anchor.equals(rows.get(i).id())){list.setSelectionFromTop(i,offset);break;}}
        else if(first==0&&!rows.isEmpty())list.setSelection(0);
    }
}
