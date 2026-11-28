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
    private final java.util.Map<String,KnowledgeRank.Ranked> ranks;
    public KnowledgeRecordAdapter(Activity activity,List<NativeTree.Master> rows){this(activity,rows,null);}
    KnowledgeRecordAdapter(Activity activity,List<NativeTree.Master> rows,java.util.Map<String,KnowledgeRank.Ranked> ranks){
        this.activity=activity;this.rows=rows;this.ranks=ranks;
    }
    @Override public int getCount(){return rows.size();}
    @Override public Object getItem(int p){return rows.get(p);}
    @Override public long getItemId(int p){return rows.get(p).sequence();}
    @Override public boolean hasStableIds(){return true;}
    @Override public View getView(int p,View convert,ViewGroup parent){
        NativeTree.Master record=rows.get(p);
        String localLanguage=new OverlayPreferences(activity).targetLanguage();
        NativeTree.Fragment originalBody=record.body(),body=record.localized(localLanguage);
        boolean translated=body.source().endsWith(".translation");
        boolean home=ranks!=null;
        boolean mesh=record.key().startsWith("local:mesh:");
        LinearLayout outer=RootsUi.column(activity);
        LinearLayout card=RootsUi.card(activity,outer);
        boolean grouped=!record.key().isEmpty();
        String sourceLink=XPostLink.forRecord(record);
        String author=grouped?record.first("author"):"";
        String handle=grouped?record.first("handle"):"";
        String externalPeer=mesh?record.latest("external-peer"):"";
        card.addView(RootsUi.text(activity,!externalPeer.isEmpty()?"ROOTS MESH · EXTERNAL REFERENCE":mesh?"ROOTS MESH · REMEMBRANCE":grouped?"X · KNOWLEDGE":"RAW OBSERVATION",12,RootsUi.ACCENT,true));
        if(!externalPeer.isEmpty())card.addView(RootsUi.text(activity,"Received via Axon "+externalPeer.substring(0,Math.min(12,externalPeer.length()))+" · claims unverified",12,RootsUi.MUTED,false));
        String topic=record.latest("topic");if(!topic.isEmpty())card.addView(RootsUi.text(activity,topic,18,RootsUi.TEXT,true));
        if(!author.isEmpty())card.addView(RootsUi.text(activity,author,17,RootsUi.TEXT,true));
        if(!handle.isEmpty())card.addView(RootsUi.text(activity,handle,13,RootsUi.MUTED,false));
        String language=body.language().isEmpty()?"Language unknown":body.language().toUpperCase(Locale.ROOT);
        card.addView(RootsUi.text(activity,language+(translated?" · translated":localLanguage.equals(body.language())?" · local language":" · original, not localized")+" · "+DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(originalBody.at())),12,RootsUi.MUTED,false));
        TextView original=RootsUi.text(activity,body.body(),16,RootsUi.TEXT,false);
        // Selectable child views consume ListView taps and long presses.
        // Grouped records use card navigation; raw observations remain selectable.
        original.setTextIsSelectable(!grouped&&sourceLink.isEmpty()&&!home);
        if(home)original.setMaxLines(9);
        original.setPadding(0,RootsUi.dp(activity,8),0,RootsUi.dp(activity,8));
        original.setLineSpacing(RootsUi.dp(activity,3),1f);card.addView(original);
        if(grouped&&!home){
            if(body.sequence()!=originalBody.sequence())card.addView(RootsUi.text(activity,"Original · "+originalBody.body(),13,RootsUi.MUTED,false));
            for(NativeTree.Fragment f:record.members()){
                if(f.sequence()==body.sequence()||f.sequence()==originalBody.sequence()||f.source().endsWith(".author")||f.source().endsWith(".handle")||f.source().endsWith(".offer"))continue;
                String role=f.source().substring(f.source().lastIndexOf('.')+1);
                String label=role.replace('-',' ');
                TextView member=RootsUi.text(activity,label+" · "+f.body(),13,RootsUi.MUTED,false);
                card.addView(member);
            }
        }
        if(home){
            KnowledgeRank.Ranked rank=ranks.get(record.key());
            if(rank!=null){
                if(!rank.branches().isEmpty())card.addView(RootsUi.text(activity,"Branches · "+String.join("  /  ",rank.branches()),13,RootsUi.ACCENT,false));
                String metrics=rank.relations()+" relation types"+(rank.replicas()>0?" · "+rank.replicas()+" observed peers":"")+(rank.demand()>0?" · "+rank.demand()+" observed wants":"");
                card.addView(RootsUi.text(activity,metrics,12,RootsUi.MUTED,false));
                if(rank.wave()!=null){
                    var wave=rank.wave();
                    card.addView(RootsUi.text(activity,String.format(Locale.ROOT,"Wave %.0f%% · convergence %.0f%% · novelty %.0f%% · %d links",
                        wave.activation()*100,wave.convergence()*100,wave.novelty()*100,wave.neighbors()),12,RootsUi.ACCENT,false));
                }
            }
            String metrics="";for(String role:new String[]{"reply-count","repost-count","like-count","view-count"}){
                String value=record.latest(role);if(!value.isEmpty())metrics+=(metrics.isEmpty()?"":"  ·  ")+role.replace("-count","")+" "+value;
            }
            if(!metrics.isEmpty())card.addView(RootsUi.text(activity,metrics,12,RootsUi.MUTED,false));
            String understanding=record.latest("understanding");
            if(!understanding.isEmpty()){TextView note=RootsUi.text(activity,"Understanding · "+understanding,14,RootsUi.MUTED,false);note.setMaxLines(3);card.addView(note);}
            if(!record.latest("want").isEmpty())card.addView(RootsUi.text(activity,"Wanted",12,RootsUi.ACCENT,true));
        }
        String footer;
        if(!sourceLink.isEmpty())footer="Open original on X  ↗  ·  hold for options";
        else if(record.key().startsWith("local:x:"))footer="Source link not captured · tap to attach";
        else if(grouped)footer="Tap for details · hold for options";
        else footer=body.source()+" · fragment #"+body.sequence();
        if(grouped&&!home)footer+="\n"+record.members().size()+" associated fragments · local record #"+record.sequence();
        TextView foot=RootsUi.text(activity,footer,12,sourceLink.isEmpty()?RootsUi.MUTED:RootsUi.ACCENT,false);
        foot.setPadding(0,RootsUi.dp(activity,8),0,0);card.addView(foot);
        return outer;
    }
}
