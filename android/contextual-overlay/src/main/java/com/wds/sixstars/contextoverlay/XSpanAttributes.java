package com.wds.sixstars.contextoverlay;

import android.os.Parcel;
import android.text.Annotation;
import android.text.ParcelableSpan;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Inspect the framework's actual text-span payload through public APIs only.
 * No reflection, hidden API, click, or attempt to infer a remote post ID. */
final class XSpanAttributes {
    private static final int MAX_SPANS=48,MAX_PAYLOAD=512,MAX_TEXT=4000;
    private XSpanAttributes(){}
    static JSONArray read(CharSequence text){
        JSONArray result=new JSONArray();
        if(!(text instanceof Spanned))return result;
        Spanned value=(Spanned)text;
        Object[] spans=value.getSpans(0,value.length(),Object.class);
        for(int i=0;i<Math.min(MAX_SPANS,spans.length);i++){
            Object span=spans[i];
            try{
                int start=value.getSpanStart(span),end=value.getSpanEnd(span);
                String type=span.getClass().getName();
                JSONObject entry=new JSONObject().put("class",type)
                    .put("start",start).put("end",end).put("flags",value.getSpanFlags(span))
                    .put("clickable",span instanceof ClickableSpan);
                if(start>=0&&end>=start&&end<=value.length())
                    entry.put("coveredText",bounded(value.subSequence(start,end)));
                if(span instanceof URLSpan)entry.put("url",bounded(((URLSpan)span).getURL()));
                if(span instanceof Annotation){
                    Annotation annotation=(Annotation)span;
                    entry.put("annotationKey",bounded(annotation.getKey()))
                        .put("annotationValue",bounded(annotation.getValue()));
                }
                if(span instanceof ParcelableSpan)parcel(entry,(ParcelableSpan)span,type);
                result.put(entry);
            }catch(JSONException|RuntimeException ignored){/* Other spans remain inspectable. */}
        }
        return result;
    }
    private static String bounded(CharSequence value){
        if(value==null)return "";
        return value.subSequence(0,Math.min(value.length(),MAX_TEXT)).toString()+(value.length()>MAX_TEXT?" …":"");
    }
    private static void parcel(JSONObject entry,ParcelableSpan span,String type)throws JSONException{
        Parcel data=Parcel.obtain();
        try{
            entry.put("parcelTypeId",span.getSpanTypeId());
            span.writeToParcel(data,0);
            int size=data.dataSize();
            entry.put("parcelSize",size);
            // These are serialized values in the exposed accessibility span,
            // not X's private Compose data or a remote status identifier.
            if(size<=MAX_PAYLOAD&&!data.hasFileDescriptors()){
                byte[] bytes=data.marshall();
                StringBuilder hex=new StringBuilder(bytes.length*2);
                for(byte b:bytes){int n=b&255;hex.append(Character.forDigit(n>>>4,16)).append(Character.forDigit(n&15,16));}
                entry.put("parcelHex",hex.toString());
                data.setDataPosition(0);
                if(type.equals("android.text.style.AccessibilityClickableSpan")&&size==4){
                    entry.put("originalClickableSpanId",data.readInt());
                }else if(type.equals("android.text.style.AccessibilityURLSpan")){
                    // AOSP URLSpan serializes the URL string followed by the
                    // AccessibilityClickableSpan's original local integer ID.
                    String url=data.readString();
                    if(data.dataAvail()==4){
                        entry.put("serializedUrl",bounded(url))
                            .put("originalClickableSpanId",data.readInt());
                    }
                }
            }else entry.put("parcelNote","Payload omitted (size or descriptor limit)");
        }catch(RuntimeException error){entry.put("parcelNote",error.getClass().getSimpleName());}
        finally{data.recycle();}
    }
    static String summary(JSONArray spans){
        StringBuilder out=new StringBuilder();
        for(int i=0;i<spans.length();i++){
            JSONObject span=spans.optJSONObject(i);
            if(span==null||(!span.optBoolean("clickable")&&!span.has("annotationKey")))continue;
            if(out.length()>0)out.append('\n');
            String type=span.optString("class");
            out.append(type.substring(type.lastIndexOf('.')+1)).append(' ')
                .append(span.optInt("start")).append("..").append(span.optInt("end"));
            if(span.has("originalClickableSpanId"))out.append(" · local span ID ").append(span.optInt("originalClickableSpanId"));
            if(span.has("url"))out.append(" · URL ").append(span.optString("url"));
            if(span.has("annotationKey"))out.append(" · annotation ").append(span.optString("annotationKey")).append('=').append(span.optString("annotationValue"));
            if(span.has("parcelHex"))out.append(" · parcel ").append(span.optString("parcelHex"));
            else if(span.has("parcelNote"))out.append(" · ").append(span.optString("parcelNote"));
        }
        return out.toString();
    }
}
