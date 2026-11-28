package com.wds.sixstars.contextoverlay;

import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Annotation;
import android.text.Spanned;
import android.text.style.URLSpan;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Explicit one-shot diagnostic of the foreground X accessibility tree.
 * Independent of graph capture. No persistence, inference, or network export.
 * Never retains live AccessibilityNodeInfo objects or reads editable content. */
final class XAccessibilityInspector {
    private static final long WINDOW_MS=90000;
    private static final int MAX_REPORT=180000,MAX_VALUE=1200;
    private static long request,deadline;
    private static String report="";
    private XAccessibilityInspector(){}

    static synchronized void arm(){
        request++;deadline=SystemClock.elapsedRealtime()+WINDOW_MS;report="";
    }
    static synchronized void cancel(){request++;deadline=0;report="";}
    static synchronized boolean armed(){return deadline>SystemClock.elapsedRealtime();}
    static synchronized String report(){return report;}
    static synchronized String status(){
        if(armed())return "Inspector armed. Return to the X post you want to inspect.";
        return report.isEmpty()?"No inspection captured.":"X node report captured. View or copy it.";
    }
    static synchronized Scan begin(){return armed()?new Scan(request):null;}
    static synchronized void finish(Scan scan,boolean stillForeground,boolean bounded){
        if(scan==null||scan.request!=request||!armed()||!stillForeground||scan.count==0)return;
        report="X accessibility inspector · "+java.time.Instant.now()+"\n"+
            "Nodes inspected: "+scan.count+(bounded||scan.truncated?" · report bounded":"")+
            "\nPaths are accessibility-tree child indexes, not remote post IDs.\n"+
            "Extra-data keys are advertised by X; unrequested data may be absent.\n"+
            scan.text;
        deadline=0;
    }

    static final class Scan {
        final long request;
        final StringBuilder text=new StringBuilder();
        final XPostMetadata.Probe metadataProbe=new XPostMetadata.Probe(24);
        int count;
        boolean truncated;
        Scan(long request){this.request=request;}

        void node(AccessibilityNodeInfo node,String path,Rect bounds){
            if(truncated)return;
            StringBuilder line=new StringBuilder();
            line.append("\n# ").append(path).append(" ").append(bounds.toShortString());
            field(line,"resource",node.getViewIdResourceName());
            field(line,"class",node.getClassName());
            line.append("\n  visible=").append(node.isVisibleToUser())
                .append(" clickable=").append(node.isClickable())
                .append(" children=").append(node.getChildCount())
                .append(" window=").append(node.getWindowId());
            if(Build.VERSION.SDK_INT>=33)field(line,"uniqueId",node.getUniqueId());
            CharSequence value=node.getText(),description=node.getContentDescription();
            field(line,"text",value);field(line,"description",description);
            spans(line,"text",value);spans(line,"description",description);
            field(line,"hint",node.getHintText());
            if(Build.VERSION.SDK_INT>=28){
                field(line,"tooltip",node.getTooltipText());
                field(line,"paneTitle",node.getPaneTitle());
            }
            if(Build.VERSION.SDK_INT>=30)field(line,"state",node.getStateDescription());
            List<AccessibilityNodeInfo.AccessibilityAction> actions=node.getActionList();
            for(AccessibilityNodeInfo.AccessibilityAction action:actions){
                field(line,"action "+action.getId(),action.getLabel()==null?"(unlabeled)":action.getLabel());
            }
            List<String> available=node.getAvailableExtraData();
            if(available!=null&&!available.isEmpty())field(line,"availableExtraData",String.join(", ",available));
            extras(line,"extras",node.getExtras(),0);
            if(XPostMetadata.conversationCard(node)||XPostMetadata.timelineHeader(node.getViewIdResourceName())||XKnowledgeRecords.hasPostMetadataRole(node.getViewIdResourceName()))
                extras(line,"requested",metadataProbe.read(node,true),0);
            if(text.length()+line.length()>MAX_REPORT){truncated=true;return;}
            text.append(line).append('\n');count++;
        }
    }

    private static void field(StringBuilder line,String name,CharSequence value){
        if(value==null||value.length()==0)return;
        line.append("\n  ").append(name).append(": ");
        String s=value.subSequence(0,Math.min(MAX_VALUE,value.length())).toString();
        line.append(s.replace("\r","\\r").replace("\n","\\n"));
        if(value.length()>MAX_VALUE)line.append(" …");
    }
    private static void spans(StringBuilder line,String name,CharSequence value){
        if(!(value instanceof Spanned))return;
        Spanned spanned=(Spanned)value;
        Object[] spans=spanned.getSpans(0,spanned.length(),Object.class);
        for(int i=0;i<Math.min(48,spans.length);i++){
            Object span=spans[i];
            String label=name+" span "+spanned.getSpanStart(span)+".."+spanned.getSpanEnd(span);
            field(line,label,span.getClass().getName());
            if(span instanceof URLSpan)field(line,label+" URL",((URLSpan)span).getURL());
            if(span instanceof Annotation){
                Annotation a=(Annotation)span;
                field(line,label+" annotation "+a.getKey(),a.getValue());
            }
        }
    }
    private static void extras(StringBuilder line,String prefix,Bundle bundle,int depth){
        if(bundle==null||depth>2)return;
        List<String> keys;
        try{keys=new ArrayList<>(bundle.keySet());Collections.sort(keys);}
        catch(RuntimeException e){field(line,prefix,"(unavailable)");return;}
        for(int i=0;i<Math.min(64,keys.size());i++){
            String key=keys.get(i),name=prefix+"."+key;
            try{
                Object value=bundle.get(key);
                if(value==null)field(line,name,"null");
                else if(value instanceof Bundle)extras(line,name,(Bundle)value,depth+1);
                else if(value instanceof CharSequence)field(line,name,(CharSequence)value);
                else if(value instanceof Number||value instanceof Boolean||value instanceof android.net.Uri)
                    field(line,name,value.toString());
                else if(value.getClass().isArray()){
                    int size=java.lang.reflect.Array.getLength(value);
                    StringBuilder values=new StringBuilder("[");
                    for(int j=0;j<Math.min(24,size);j++){
                        if(j>0)values.append(", ");
                        Object item=java.lang.reflect.Array.get(value,j);
                        if(item==null)values.append("null");
                        else if(item instanceof Number||item instanceof Boolean||item instanceof CharSequence||item instanceof android.net.Uri){
                            String s=item.toString();values.append(s,0,Math.min(160,s.length()));
                        }else values.append(item.getClass().getSimpleName());
                    }
                    if(size>24)values.append(", …");
                    field(line,name,values.append(']'));
                }else field(line,name,"("+value.getClass().getName()+")");
            }catch(RuntimeException e){field(line,name,"(unavailable)");}
        }
    }
}
