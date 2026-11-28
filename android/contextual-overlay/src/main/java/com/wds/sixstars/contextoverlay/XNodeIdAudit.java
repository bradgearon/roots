package com.wds.sixstars.contextoverlay;

import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Bounded, passive examination of the complete exposed node representation.
 * A node parcel is not the original X view or its click-handler closure. */
final class XNodeIdAudit {
    private static final int MAX_NODES=256, MAX_PARCEL=32768, MAX_TOTAL=512*1024;
    private final XPostIdEvidence evidence=new XPostIdEvidence(System.currentTimeMillis());
    private int nodes,parcels,bytes,skipped;
    private final boolean settled;

    XNodeIdAudit(boolean settled){this.settled=settled;}

    void inspect(AccessibilityNodeInfo node,String path){
        if(!node.isVisibleToUser())return;
        nodes++;
        evidence.text("resourceId",path,node.getViewIdResourceName(),false);
        if(Build.VERSION.SDK_INT>=33)evidence.text("uniqueId",path,node.getUniqueId(),false);
        evidence.text("text",path,node.getText(),false);
        evidence.text("description",path,node.getContentDescription(),false);
        evidence.text("hint",path,node.getHintText(),false);
        if(Build.VERSION.SDK_INT>=28){
            evidence.text("tooltip",path,node.getTooltipText(),false);
            evidence.text("paneTitle",path,node.getPaneTitle(),false);
        }
        if(Build.VERSION.SDK_INT>=30)evidence.text("stateDescription",path,node.getStateDescription(),false);
        for(AccessibilityNodeInfo.AccessibilityAction action:node.getActionList())
            evidence.text("action."+action.getId(),path,action.getLabel(),false);
        bundle(node.getExtras(),"extras",path,0);
        spans(node.getText(),"textSpan",path);
        spans(node.getContentDescription(),"descriptionSpan",path);
        if(!settled)return;
        if(parcels>=MAX_NODES||bytes>=MAX_TOTAL){skipped++;return;}
        Parcel data=Parcel.obtain();
        try{
            // writeToParcel may recycle its input on older Android versions.
            // Serialize a disposable copy, never the reader's live snapshot.
            AccessibilityNodeInfo.obtain(node).writeToParcel(data,0);
            int length=data.dataSize();
            if(length>MAX_PARCEL||bytes+length>MAX_TOTAL||data.hasFileDescriptors()){skipped++;return;}
            byte[] raw=data.marshall();
            parcels++;bytes+=raw.length;
            evidence.parcel(raw,"nodeParcel",path);
        }catch(RuntimeException error){skipped++;}
        finally{data.recycle();}
    }

    void requested(Bundle values,String path){bundle(values,"requested",path,0);}

    // Events are a separate public Android channel. Keep candidate evidence
    // only, never raw event data or references to live nodes.
    private int eventCount,eventRecords,eventParcels,eventBytes;
    private final java.util.Map<String,Integer> eventTypes=new java.util.TreeMap<>();
    void inspectEvent(android.view.accessibility.AccessibilityEvent event){
        if(event==null||event.isPassword())return;
        String type=android.view.accessibility.AccessibilityEvent.eventTypeToString(event.getEventType());
        String path="event/"+type+"/"+event.getWindowId()+"/"+event.getEventTime();
        eventCount++;eventTypes.merge(type,1,Integer::sum);
        eventRecord(event,path);
        for(int i=0;i<Math.min(16,event.getRecordCount());i++)eventRecord(event.getRecord(i),path+"/record/"+i);
        Parcel data=Parcel.obtain();
        try{
            event.writeToParcel(data,0);
            if(data.dataSize()<=MAX_PARCEL&&!data.hasFileDescriptors()){
                byte[] raw=data.marshall();eventParcels++;eventBytes+=raw.length;
                evidence.parcel(raw,"eventParcel",path);
            }
        }catch(RuntimeException ignored){}
        finally{data.recycle();}
    }
    private void eventRecord(android.view.accessibility.AccessibilityRecord record,String path){
        if(record==null||record.isPassword())return;
        eventRecords++;
        evidence.text("event.description",path,record.getContentDescription(),false);
        for(CharSequence text:record.getText()){
            evidence.text("event.text",path,text,false);spans(text,"event.span",path);
        }
        android.os.Parcelable payload=record.getParcelableData();
        if(payload instanceof Bundle)bundle((Bundle)payload,"event.payload",path,0);
        else if(payload instanceof android.net.Uri)evidence.text("event.payload.uri",path,payload.toString(),false);
    }
    String eventSummary(){
        int named=0,urls=0,other=0;
        for(XPostIdEvidence.Hit hit:evidence.hits()){
            if(hit.kind().startsWith("explicit"))named++;
            else if(hit.kind().startsWith("status URL"))urls++;
            else other++;
        }
        StringBuilder out=new StringBuilder("Passive event audit · ").append(eventCount).append(" events · ")
            .append(eventRecords).append(" records · ").append(eventParcels).append(" parcels / ").append(eventBytes)
            .append(" bytes\nNamed IDs: ").append(named).append(" · status URLs: ").append(urls)
            .append(" · unverified numeric candidates: ").append(other).append('\n');
        int shown=0;
        for(XPostIdEvidence.Hit hit:evidence.hits()){
            if(hit.kind().startsWith("raw")||shown>=6)continue;
            out.append("  ").append(hit.kind()).append(": ").append(hit.id()).append(" · ").append(hit.field()).append('\n');shown++;
        }
        return out.toString();
    }
    JSONObject eventJson()throws JSONException{
        JSONArray candidates=new JSONArray();
        for(XPostIdEvidence.Hit hit:evidence.hits())candidates.put(new JSONObject().put("candidate",hit.id())
            .put("classification",hit.kind()).put("field",hit.field()).put("location",hit.location()));
        return new JSONObject().put("events",eventCount).put("records",eventRecords).put("parcels",eventParcels)
            .put("bytes",eventBytes).put("types",new JSONObject(eventTypes)).put("candidates",candidates);
    }
    int eventCount(){return eventCount;}
    private void spans(CharSequence text,String field,String path){
        if(!(text instanceof android.text.Spanned))return;
        android.text.Spanned s=(android.text.Spanned)text;
        Object[] spans=s.getSpans(0,s.length(),Object.class);
        for(int i=0;i<Math.min(48,spans.length);i++){
            Object span=spans[i];
            if(span instanceof android.text.style.URLSpan)
                evidence.text(field+".url",path,((android.text.style.URLSpan)span).getURL(),false);
            if(span instanceof android.text.Annotation){
                android.text.Annotation a=(android.text.Annotation)span;
                evidence.text(field+"."+a.getKey(),path,a.getValue(),idKey(a.getKey()));
            }
        }
    }
    private static boolean idKey(String key){
        return XPostMetadata.keyRole(key).matches("(tweet|post|status)(id|idstr)");
    }
    private void bundle(Bundle values,String field,String path,int depth){
        if(values==null||depth>3)return;
        try{
            int count=0;
            for(String key:values.keySet()){
                if(count++>=96)break;
                Object value=values.get(key);
                String next=field+"."+key;
                if(value instanceof Bundle)bundle((Bundle)value,next,path,depth+1);
                else if(value instanceof CharSequence||value instanceof Long||value instanceof Integer||value instanceof android.net.Uri)
                    evidence.text(next,path,value.toString(),idKey(key));
                else if(value instanceof List<?>){
                    List<?> list=(List<?>)value;
                    for(int i=0;i<Math.min(32,list.size());i++){
                        Object item=list.get(i);
                        if(item instanceof Bundle)bundle((Bundle)item,next+"["+i+"]",path,depth+1);
                        else if(item instanceof CharSequence||item instanceof Long||item instanceof Integer||item instanceof android.net.Uri)
                            evidence.text(next+"["+i+"]",path,item.toString(),idKey(key));
                    }
                }else if(value instanceof String[]||value instanceof long[]||value instanceof int[]){
                    int size=java.lang.reflect.Array.getLength(value);
                    for(int i=0;i<Math.min(32,size);i++){
                        Object item=java.lang.reflect.Array.get(value,i);
                        if(item!=null)evidence.text(next+"["+i+"]",path,item.toString(),idKey(key));
                    }
                }
            }
        }catch(RuntimeException ignored){}
    }

    String summary(){
        int named=0,urls=0,decimal=0,binary=0;
        for(XPostIdEvidence.Hit hit:evidence.hits()){
            if(hit.kind().startsWith("explicit"))named++;
            else if(hit.kind().startsWith("status URL"))urls++;
            else if(hit.kind().startsWith("decimal"))decimal++;
            else binary++;
        }
        StringBuilder out=new StringBuilder("Remote post ID audit · ").append(nodes).append(" visible nodes");
        if(settled)out.append(" · ").append(parcels).append(" full parcels · ").append(bytes).append(" bytes · ").append(skipped).append(" skipped");
        else out.append(" · binary inspection deferred until scrolling settles");
        out.append("\nNamed post IDs: ").append(named).append(" · status URL hits: ").append(urls)
            .append(" · other decimal candidates: ").append(decimal).append(" · raw binary guesses: ").append(binary).append('\n');
        int displayed=0;
        for(XPostIdEvidence.Hit hit:evidence.hits()){
            if(hit.kind().startsWith("raw"))continue;
            if(displayed++>=10){out.append("Further candidates in JSON export.\n");break;}
            out.append("  ").append(hit.kind()).append(": ").append(hit.id()).append(" · ").append(hit.field())
                .append(" · ").append(hit.location()).append('\n');
        }
        return out.toString();
    }

    JSONObject json()throws JSONException{
        JSONArray hits=new JSONArray();
        for(XPostIdEvidence.Hit hit:evidence.hits())
            hits.put(new JSONObject().put("candidate",hit.id()).put("classification",hit.kind()).put("field",hit.field()).put("path",hit.location()));
        return new JSONObject().put("settled",settled).put("visibleNodes",nodes).put("serializedNodes",parcels)
            .put("serializedBytes",bytes).put("skippedParcels",skipped).put("candidates",hits)
            .put("note","Candidate evidence only; no inferred identifier is assigned to a post.");
    }
}
