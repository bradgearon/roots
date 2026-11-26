package com.wds.sixstars.contextoverlay;

import android.graphics.Rect;
import com.wds.sixstars.contextoverlay.tree.NativeTree;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** X network structural adapter. Local identities are provisional; never
 * manufacture a remote post ID. Uncertain nodes remain in the raw lane. */
final class XKnowledgeRecords {
    static final class Sample {
        final String text,id;
        final Rect bounds;
        final boolean description;
        Sample(String text,String id,Rect bounds,boolean description){
            this.text=text;this.id=id==null?"":id.toLowerCase(Locale.ROOT);
            this.bounds=new Rect(bounds);this.description=description;
        }
    }
    static void sample(List<Sample> out,CharSequence value,String id,Rect bounds,boolean description){
        if(out.size()>=1800 || value==null)return;
        if(value instanceof android.text.Spanned && id!=null && (id.toLowerCase(Locale.ROOT).contains("timestamp")||id.toLowerCase(Locale.ROOT).contains("tweet_time")||id.toLowerCase(Locale.ROOT).contains("permalink"))){
            android.text.Spanned spans=(android.text.Spanned)value;
            for(android.text.style.URLSpan span:spans.getSpans(0,spans.length(),android.text.style.URLSpan.class)){
                String link=XPostLink.canonical(span.getURL());if(!link.isEmpty()&&out.size()<1800)out.add(new Sample(link,id,bounds,description));
            }
        }
        for(String text:KnowledgeText.fragments(value)){
            if(out.size()>=1800)break;
            out.add(new Sample(text,id,bounds,description));
        }
    }
    static List<NativeTree.Observation> assemble(List<TextCandidate> bodies,List<Sample> samples,long at){
        Map<String,NativeTree.Observation> out=new LinkedHashMap<>();
        for(TextCandidate body:bodies){
            if(body.authorName || body.text.trim().isEmpty())continue;
            if(!body.knownPostTextId && body.author==null && !body.hasPostContainer)continue;
            String author=body.author==null?"":body.author.displayName;
            String handle=handle(body,samples);
            // An author or handle plus body is a local identity hint only.
            // Do not silently merge uncertain authors across cards.
            String identity=handle.isEmpty()?author:handle;
            String key="local:x:"+TextFingerprint.of(identity.length()+":"+identity+":"+body.text);
            body.knowledgeKey=key;
            add(out,key,"body",body.text,at);
            if(!author.isEmpty())add(out,key,"author",author,at);
            if(!handle.isEmpty())add(out,key,"handle",handle,at);
            String permalink="";boolean ambiguous=false;
            for(Sample sample:samples){
                if(!belongs(body,sample,bodies))continue;
                if(TextFingerprint.normalized(sample.text).equals(body.text)
                        || TextFingerprint.normalized(sample.text).equals(author)
                        || sample.text.equals(handle))continue;
                if(sample.id.contains("permalink")||sample.id.contains("tweet_time")||sample.id.contains("timestamp")){
                    String link=XPostLink.canonical(sample.text);
                    if(!link.isEmpty()){
                        if(!permalink.isEmpty()&&!permalink.equals(link))ambiguous=true;
                        permalink=link;
                    }
                }
                String role=role(sample);
                if(role!=null)add(out,key,role,sample.text,at);
            }
            if(!ambiguous&&!permalink.isEmpty())add(out,key,"link",permalink,at);
        }
        return new ArrayList<>(out.values());
    }
    private static String handle(TextCandidate body,List<Sample> samples){
        if(body.author==null)return "";
        Rect name=body.author.nameBounds;
        String best="";
        for(Sample s:samples){
            if(Math.abs(s.bounds.top-name.top)>Math.max(4,name.height()/2)
                    || s.bounds.left<name.left || s.bounds.left>body.bounds.right)continue;
            String value=s.text.trim();
            if(XAuthorNameHeuristics.looksCompositeHeader(value)){
                int at=value.indexOf('@'),end=at+1;
                while(end<value.length()&&(Character.isLetterOrDigit(value.charAt(end))||value.charAt(end)=='_'))end++;
                value=value.substring(at,end);
            }
            if(!value.matches("@[A-Za-z0-9_]{1,25}"))continue;
            if(!best.isEmpty()&&!best.equals(value))return "";
            best=value;
        }
        return best;
    }
    private static boolean belongs(TextCandidate body,Sample sample,List<TextCandidate> all){
        // Only trust a card that encloses the member and is unambiguous among
        // the observed bodies. Do not attach adjacent posts by distance.
        if(!body.hasPostContainer || body.postBounds.equals(body.bounds)
                || !body.postBounds.contains(sample.bounds))return false;
        for(TextCandidate other:all)
            if(other!=body && other.hasPostContainer && other.postBounds.contains(sample.bounds))return false;
        return true;
    }
    private static String role(Sample s){
        String id=s.id;
        if(PostTextHeuristics.isPostTextId(id))return null;
        if(id.contains("timestamp")||id.contains("created_at")||id.contains("tweet_time"))return "time";
        if(id.contains("reply_count"))return "reply-count";
        if(id.contains("repost_count")||id.contains("retweet_count"))return "repost-count";
        if(id.contains("like_count")||id.contains("favorite_count"))return "like-count";
        if(id.contains("view_count")||id.contains("impression_count"))return "view-count";
        if(id.contains("media") && s.description)return "media-description";
        if(id.contains("quote")||id.contains("repost"))return "context";
        // Unclassified card content is still associated, but not given a
        // fabricated semantic role. Generic UI labels remain raw only.
        if(!s.description && !PostTextHeuristics.isUiLabel(s.text,id,""))return "text";
        return null;
    }
    private static void add(Map<String,NativeTree.Observation> out,String key,String role,String value,long at){
        for(String text:KnowledgeText.fragments(value)){
            String source="record.x."+role;
            String id=source+"\u001f"+key+"\u001f"+text;
            out.putIfAbsent(id,new NativeTree.Observation(source,key,text,XVisibleTextReader.languageHint(text),at));
        }
    }
}
