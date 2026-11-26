package com.wds.sixstars.contextoverlay;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.wds.sixstars.contextoverlay.tree.NativeTree;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Shared record cards. Never create unavailable authors, metrics or text. */
public final class KnowledgeRecordAdapter extends BaseAdapter {
    private final Activity activity;
    private final List<NativeTree.Master> rows;
    public KnowledgeRecordAdapter(Activity activity,List<NativeTree.Master> rows){
        this.activity=activity;this.rows=rows;
    }
    @Override public int getCount(){return rows.size();}
    @Override public Object getItem(int p){return rows.get(p);}
    @Override public long getItemId(int p){return rows.get(p).sequence();}
    @Override public boolean hasStableIds(){return true;}
    @Override public View getView(int p,View convert,ViewGroup parent){
        NativeTree.Master record=rows.get(p);
        NativeTree.Fragment body=record.body();
        LinearLayout outer=RootsUi.column(activity);
        LinearLayout card=RootsUi.card(activity,outer);
        boolean grouped=!record.key().isEmpty();
        String author=grouped?record.first("author"):"";
        String handle=grouped?record.first("handle"):"";
        card.addView(RootsUi.text(activity,grouped?"X · Post record":"Raw observation",12,RootsUi.ACCENT,true));
        if(!author.isEmpty())card.addView(RootsUi.text(activity,author,17,RootsUi.TEXT,true));
        if(!handle.isEmpty())card.addView(RootsUi.text(activity,handle,13,RootsUi.MUTED,false));
        String language=body.language().isEmpty()?"Language unknown":body.language().toUpperCase(Locale.ROOT);
        card.addView(RootsUi.text(activity,language+" · "+DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(body.at())),12,RootsUi.MUTED,false));
        TextView original=RootsUi.text(activity,body.body(),16,RootsUi.TEXT,false);
        original.setTextIsSelectable(true);
        original.setPadding(0,RootsUi.dp(activity,8),0,RootsUi.dp(activity,8));
        original.setLineSpacing(RootsUi.dp(activity,3),1f);card.addView(original);
        if(grouped){
            for(NativeTree.Fragment f:record.members()){
                if(f.sequence()==body.sequence()||f.source().endsWith(".author")||f.source().endsWith(".handle"))continue;
                String role=f.source().substring(f.source().lastIndexOf('.')+1);
                String label=role.replace('-',' ');
                TextView member=RootsUi.text(activity,label+" · "+f.body(),13,RootsUi.MUTED,false);
                member.setTextIsSelectable(true);card.addView(member);
            }
        }
        String footer=grouped?record.members().size()+" associated fragments · local record #"+record.sequence():
            body.source()+" · fragment #"+body.sequence();
        TextView foot=RootsUi.text(activity,footer,12,RootsUi.MUTED,false);
        foot.setPadding(0,RootsUi.dp(activity,8),0,0);card.addView(foot);
        return outer;
    }
}
