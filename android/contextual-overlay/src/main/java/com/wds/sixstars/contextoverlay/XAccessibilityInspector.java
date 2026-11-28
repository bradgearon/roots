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

/** Rolling read-only diagnostic, sampled during ordinary foreground X capture.
 * Only recent snapshots are kept in memory; nothing is added to the Tree.
 * Never retains live accessibility nodes or reads editable content. */
final class XAccessibilityInspector {
    private static final long WINDOW_MS=90000, SAMPLE_MS=1500;
    private static final int MAX_REPORT=90000,MAX_VALUE=4000,MAX_FRAMES=6,MAX_COPY=11000;
    private static long request,deadline,nextSample,accepted;
    private static boolean previousSettled;
    private static long nextEventSample;
    private static XNodeIdAudit eventAudit=new XNodeIdAudit(true);
    private static final java.util.ArrayDeque<Frame> frames=new java.util.ArrayDeque<>();
    private record Frame(String header,String body,String attributes,int spanNodes,boolean settled){}
    record Snapshot(String status,String latest,String report,String attributes){}
    private XAccessibilityInspector(){}

    static synchronized void arm(){
        request++;deadline=SystemClock.elapsedRealtime()+WINDOW_MS;nextSample=0;previousSettled=false;
        nextEventSample=0;eventAudit=new XNodeIdAudit(true);
    }
    static synchronized void cancel(){request++;deadline=0;nextSample=0;accepted=0;frames.clear();nextEventSample=0;eventAudit=new XNodeIdAudit(true);}
    static synchronized boolean armed(){return deadline>SystemClock.elapsedRealtime();}
    static synchronized boolean eventDue(boolean captureEnabled,int kind){
        if(!captureEnabled&&!armed())return false;
        if(kind!=android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED&&
            kind!=android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED&&
            kind!=android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED&&
            kind!=android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)return false;
        long now=SystemClock.elapsedRealtime();
        if(now<nextEventSample)return false;
        nextEventSample=now+600;
        return true;
    }
    static synchronized void noteEvent(android.view.accessibility.AccessibilityEvent event){
        if(eventAudit.eventCount()>=128)eventAudit=new XNodeIdAudit(true);
        try{eventAudit.inspectEvent(event);}catch(RuntimeException ignored){}
    }
    static synchronized Snapshot snapshot(){
        if(frames.isEmpty())return new Snapshot(armed()?"Recording the next 90 seconds of X browsing.":"No timeline samples yet.","","","");
        String status=frames.size()+" recent timeline sample(s) · "+accepted+" distinct samples recorded";
        StringBuilder report=new StringBuilder("X accessibility scrolling trace · newest sample first\n"+
            "Actual text values first. Node paths and Compose IDs are UI identifiers, not post IDs.\n"+
            "Read-only metadata probes; no clicks or external lookup.\n");
        report.append(eventAudit.eventSummary());
        for(Frame frame:frames){
            String block="\n"+frame.header()+frame.body()+"\n";
            if(report.length()+block.length()>MAX_COPY){report.append("\n[Older samples omitted from compact copy.]\n");break;}
            report.append(block);
        }
        Frame latest=frames.getFirst();
        // A fast scroll can expose empty recycled cards as the latest frame.
        // Prefer a settled frame with actual span data for detailed export.
        Frame detailed=latest;
        for(Frame frame:frames)if(frame.spanNodes()>0){
            if(detailed.spanNodes()==0||frame.settled()&&!detailed.settled())detailed=frame;
            if(detailed.settled())break;
        }
        return new Snapshot(status,eventAudit.eventSummary()+"\n"+latest.header()+latest.body(),report.toString(),withCaptureHeader(detailed));
    }
    private static String withCaptureHeader(Frame frame){
        try{return new org.json.JSONObject(frame.attributes()).put("snapshot",frame.header()).put("eventIdAudit",eventAudit.eventJson()).toString();}
        catch(org.json.JSONException ignored){return frame.attributes();}
    }
    static synchronized Scan begin(boolean captureEnabled,boolean settled){
        long now=SystemClock.elapsedRealtime();
        if(!captureEnabled&&!armed())return null;
        if(now<nextSample&&!(settled&&!previousSettled))return null;
        nextSample=now+SAMPLE_MS;previousSettled=settled;
        return new Scan(request,captureEnabled,settled);
    }
    static synchronized void finish(Scan scan,boolean stillForeground,boolean bounded){
        if(scan==null||scan.request!=request||(!scan.automatic&&!armed())||!stillForeground||scan.count==0)return;
        String body=scan.format();
        Frame previous=frames.peekFirst();
        String serialized=scan.serialized();
        if(previous!=null&&previous.body().equals(body)&&previous.attributes().equals(serialized))return;
        String header="Snapshot "+(++accepted)+" · "+java.time.Instant.now()+(scan.settled?" · settled":" · moving")+"\n"+
            "Nodes inspected: "+scan.count+" · nodes with text or description: "+scan.values.size()+(bounded||scan.truncated?" · report bounded":"")+"\n";
        frames.addFirst(new Frame(header,body,serialized,scan.spanDetails.size(),scan.settled));
        while(frames.size()>MAX_FRAMES)frames.removeLast();
    }

    private record Value(String path,String text,String description){}
    static final class Scan {
        final long request;
        final boolean automatic,settled;
        final List<Value> values=new ArrayList<>();
        final List<String> cards=new ArrayList<>();
        final List<String> metadata=new ArrayList<>();
        final List<String> spanDetails=new ArrayList<>();
        final org.json.JSONArray attributes=new org.json.JSONArray();
        int attributeChars;
        final XPostMetadata.Probe metadataProbe;
        final XNodeIdAudit audit;
        int count;
        boolean truncated;
        Scan(long request,boolean automatic,boolean settled){
            this.request=request;this.automatic=automatic;this.settled=settled;
            metadataProbe=new XPostMetadata.Probe(settled?20:0);
            audit=new XNodeIdAudit(settled);
        }

        Bundle node(AccessibilityNodeInfo node,String path,Rect bounds){
            count++;
            Bundle requested=null;
            boolean visible=node.isVisibleToUser();
            try{audit.inspect(node,path);}catch(RuntimeException ignored){/* Keep normal capture independent of diagnostics. */}
            boolean card=visible&&XPostMetadata.conversationCard(node);
            if(card)cards.add(path);
            CharSequence value=node.getText(),description=node.getContentDescription();
            if(visible&&(nonempty(value)||nonempty(description)))values.add(new Value(path,clip(value),clip(description)));
            boolean structural=card||XPostMetadata.timelineHeader(node.getViewIdResourceName())||XKnowledgeRecords.hasPostMetadataRole(node.getViewIdResourceName());
            List<String> advertised=node.getAvailableExtraData();
            boolean advertisedId=advertised!=null&&advertised.stream().anyMatch(XPostMetadata::remoteKey);
            // Skip empty layout wrappers in the compact diagnostic, but capture
            // the actual attributes of visible text, post and tagged nodes.
            if(!visible||(!structural&&!advertisedId&&!nonempty(value)&&!nonempty(description)&&node.getViewIdResourceName()==null))return null;
            StringBuilder line=new StringBuilder();
            line.append("\n# ").append(path).append(" ").append(bounds.toShortString());
            field(line,"resource",node.getViewIdResourceName());
            field(line,"class",node.getClassName());
            line.append("\n  visible=").append(node.isVisibleToUser())
                .append(" clickable=").append(node.isClickable())
                .append(" children=").append(node.getChildCount())
                .append(" window=").append(node.getWindowId());
            if(Build.VERSION.SDK_INT>=33)field(line,"uniqueId",node.getUniqueId());
            field(line,"text",value);field(line,"description",description);
            org.json.JSONArray textSpans=XSpanAttributes.read(value),descriptionSpans=XSpanAttributes.read(description);
            String textSpanSummary=XSpanAttributes.summary(textSpans),descriptionSpanSummary=XSpanAttributes.summary(descriptionSpans);
            if(!textSpanSummary.isEmpty()||!descriptionSpanSummary.isEmpty()){
                StringBuilder detail=new StringBuilder(path);
                if(!textSpanSummary.isEmpty())detail.append("\n  text: ").append(clip(value)).append("\n  ").append(textSpanSummary.replace("\n","\n  "));
                if(!descriptionSpanSummary.isEmpty())detail.append("\n  description: ").append(clip(description)).append("\n  ").append(descriptionSpanSummary.replace("\n","\n  "));
                spanDetails.add(detail.toString());
            }
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
            if(structural||advertisedId){
                Bundle outcomes=new Bundle();
                requested=metadataProbe.read(node,true,outcomes);
                audit.requested(requested,path);
                StringBuilder probe=new StringBuilder(path+" · "+(node.getViewIdResourceName()==null?"conversation card":node.getViewIdResourceName()));
                extras(probe,"requested",requested,0);extras(probe,"probe",outcomes,0);
                if(!settled)field(probe,"state","metadata request deferred until scrolling settles");
                metadata.add(probe.toString());
                extras(line,"requested",requested,0);
                extras(line,"probe",outcomes,0);
            }
            try{
                org.json.JSONObject actual=new org.json.JSONObject();
                actual.put("path",path).put("resourceId",node.getViewIdResourceName())
                    .put("className",clip(node.getClassName())).put("bounds",bounds.toShortString())
                    .put("windowId",node.getWindowId()).put("visible",visible).put("clickable",node.isClickable())
                    .put("childCount",node.getChildCount()).put("text",value==null?org.json.JSONObject.NULL:clip(value))
                    .put("contentDescription",description==null?org.json.JSONObject.NULL:clip(description))
                    .put("textSpans",textSpans).put("descriptionSpans",descriptionSpans)
                    .put("attributes",line.toString()).put("frameworkNode",clip(node.toString()));
                if(Build.VERSION.SDK_INT>=33)actual.put("uniqueId",node.getUniqueId());
                int size=actual.toString().length();
                if(attributeChars+size<=MAX_REPORT){attributes.put(actual);attributeChars+=size;}else truncated=true;
            }catch(org.json.JSONException ignored){}
            return requested;
        }

        String format(){
            StringBuilder out=new StringBuilder(audit.summary()).append("\nVisible post cards: ").append(cards.size()).append('\n');
            int index=0;
            for(String card:cards){
                out.append("\nPost card ").append(++index).append(" · ").append(card).append('\n');
                appendValues(out,values,cards,card,6800);
                if(out.length()>=6800)break;
            }
            out.append("\nOther visible text:\n");
            appendValues(out,values,cards,"",7400);
            out.append("\nClickable span payloads (local IDs, not remote post IDs):\n");
            if(spanDetails.isEmpty())out.append("  No clickable spans returned in this scan.\n");
            for(String detail:spanDetails){
                if(out.length()+detail.length()>8800){out.append("[More spans in JSON export.]\n");break;}
                out.append(detail).append('\n');
            }
            out.append("\nPost metadata (read-only):\n");
            for(String value:metadata){
                if(out.length()+value.length()>9800){out.append("[Further metadata in node attributes.]\n");break;}
                out.append(value).append('\n');
            }
            return out.toString();
        }
        String serialized(){
            org.json.JSONObject result=new org.json.JSONObject();
            try{result.put("format","roots.accessibility.node-attributes.v3").put("postIdAudit",audit.json())
                .put("settled",settled).put("spanNodes",spanDetails.size()).put("inspected",count)
                .put("bounded",truncated).put("nodes",attributes);}catch(org.json.JSONException ignored){}
            return result.toString();
        }
    }

    private static boolean nonempty(CharSequence value){return value!=null&&value.length()>0;}
    private static String clip(CharSequence value){
        if(value==null)return "";
        return value.subSequence(0,Math.min(MAX_VALUE,value.length())).toString()+(value.length()>MAX_VALUE?" …":"");
    }
    private static boolean under(String path,String parent){return path.equals(parent)||path.startsWith(parent+"/");}
    private static String owner(String path,List<String> cards){
        String best="";
        for(String card:cards)if(under(path,card)&&card.length()>best.length())best=card;
        return best;
    }
    private static void appendValues(StringBuilder out,List<Value> values,List<String> cards,String card,int limit){
        for(Value value:values){
            if(!owner(value.path(),cards).equals(card))continue;
            String block=(value.text().isEmpty()?"":"  text: "+value.text()+"\n")+
                (value.description().isEmpty()||value.description().equals(value.text())?"":"  description: "+value.description()+"\n");
            if(out.length()+block.length()>limit){out.append("  [Further values omitted; use Copy node attributes.]\n");return;}
            out.append(block);
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
                else if(value.getClass().isArray()||value instanceof java.util.List){
                    int size=value instanceof java.util.List?((java.util.List<?>)value).size():java.lang.reflect.Array.getLength(value);
                    StringBuilder values=new StringBuilder("[");
                    for(int j=0;j<Math.min(24,size);j++){
                        if(j>0)values.append(", ");
                        Object item=value instanceof java.util.List?((java.util.List<?>)value).get(j):java.lang.reflect.Array.get(value,j);
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
