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
    private static final int SAMPLE_LIMIT=1800;
    static final class Sample {
        final String text,id;
        final Rect bounds;
        final boolean description,sourceLink;
        Sample(String text,String id,Rect bounds,boolean description){
            this(text,id,bounds,description,false);
        }
        Sample(String text,String id,Rect bounds,boolean description,boolean sourceLink){
            this.text=text;this.id=id==null?"":id.toLowerCase(Locale.ROOT);
            this.bounds=new Rect(bounds);this.description=description;this.sourceLink=sourceLink;
        }
    }
    private static String roleId(String id){
        String value=id==null?"":id.toLowerCase(Locale.ROOT);
        return value.substring(value.lastIndexOf('/')+1);
    }
    private static boolean secondaryRole(String id){
        return id.contains("quote")||id.contains("repost")||id.contains("retweet")||id.contains("embedded");
    }
    static boolean isPermalinkRole(String id){
        String role=roleId(id);
        if(secondaryRole(role)||PostTextHeuristics.isPostTextId(role))return false;
        return role.contains("permalink")||role.contains("timestamp")||role.contains("tweet_time")
            ||role.contains("post_time")||role.contains("status_time")
            ||role.matches("(tweet|post|status)_(url|uri|link|id)");
    }
    static boolean hasPostMetadataRole(String id){
        String role=roleId(id);
        return !secondaryRole(role)&&(isPermalinkRole(role)||PostTextHeuristics.isPostContainerId(role)
            ||PostTextHeuristics.isPostTextId(role)||XPostMetadata.timelineHeader(id));
    }
    private static void sourceLink(List<Sample> out,String link,String id,Rect bounds){
        if(link.isEmpty()||bounds.isEmpty())return;
        // Source metadata is important even on a crowded screen; do not let
        // arbitrary labels consume every slot before an explicit permalink.
        if(out.size()>=SAMPLE_LIMIT){
            int ordinary=-1;
            for(int i=out.size()-1;i>=0;i--)if(!out.get(i).sourceLink){ordinary=i;break;}
            if(ordinary<0)return;
            out.remove(ordinary);
        }
        out.add(new Sample(link,id,bounds,false,true));
    }
    static boolean secondarySourceRole(String id){
        return secondaryRole(roleId(id));
    }
    static void sample(List<Sample> out,CharSequence value,String id,Rect bounds,boolean description){
        sample(out,value,id,bounds,description,true);
    }
    static void sample(List<Sample> out,CharSequence value,String id,Rect bounds,boolean description,boolean allowSource){
        if(value==null)return;
        if(allowSource&&isPermalinkRole(id)){
            // Keep the actual URL in a URLSpan even if only a formatted date
            // or the word "link" is visible in the accessibility label.
            if(value instanceof android.text.Spanned){
                android.text.Spanned spans=(android.text.Spanned)value;
                for(android.text.style.URLSpan span:spans.getSpans(0,spans.length(),android.text.style.URLSpan.class))
                    sourceLink(out,XPostLink.canonical(span.getURL()),id,bounds);
            }
            // Only an explicitly designated ID view can supply a bare ID.
            String role=roleId(id);
            String link=role.matches("(tweet|post|status)_id")
                ?XPostLink.fromRemoteId(value):XPostLink.fromMetadata(value);
            sourceLink(out,link,id,bounds);
        }
        for(String text:KnowledgeText.fragments(value)){
            if(out.size()>=SAMPLE_LIMIT)break;
            out.add(new Sample(text,id,bounds,description));
        }
    }
    /** Optional app-provided accessibility extras, never Android view IDs,
     * timestamps, local hashes or arbitrary links in a post body. */
    static void sampleMetadata(List<Sample> out,android.os.Bundle extras,String id,Rect bounds){
        if(extras==null||!hasPostMetadataRole(id))return;
        for(String key:extras.keySet()){
            String name=XPostMetadata.keyRole(key);
            try{
                Object value=extras.get(key);
                if(XPostMetadata.TEST_TAG.equals(key)){
                    sourceLink(out,XPostMetadata.linkFromTag(value),id,bounds);
                }else if(name.matches("(tweet|post|status)(id|idstr)")){
                    sourceLink(out,XPostLink.fromRemoteId(value),id,bounds);
                }else if(name.matches("(tweet|post|status)(url|uri|link|permalink)|permalink")&&value!=null){
                    sourceLink(out,XPostLink.canonical(value.toString()),id,bounds);
                }
            }catch(RuntimeException ignored){/* Optional metadata; raw capture still proceeds. */}
        }
    }
    /** A node's metadata is gathered together before adding it to the legacy
     * sample lane, so list truncation cannot change its structural owner. */
    static void appendSamples(List<Sample> out,List<Sample> samples){
        for(Sample sample:samples){
            if(sample.sourceLink)sourceLink(out,sample.text,sample.id,sample.bounds);
            else if(out.size()<SAMPLE_LIMIT)out.add(sample);
        }
    }
    static List<NativeTree.Observation> assembleCards(List<XTimelineCardRecords.Post> posts,long at){
        Map<String,NativeTree.Observation> out=new LinkedHashMap<>();
        for(XTimelineCardRecords.Post post:posts)
            for(XTimelineCardRecords.Field field:post.fields())
                add(out,post.key(),field.role(),field.value(),at);
        return new ArrayList<>(out.values());
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
                if(sample.sourceLink){
                    if(!sourceBelongs(body,sample,bodies))continue;
                    String link=XPostLink.canonical(sample.text);
                    if(!link.isEmpty()){
                        if(!permalink.isEmpty()&&!XPostLink.postId(permalink).equals(XPostLink.postId(link)))ambiguous=true;
                        if(permalink.isEmpty()||permalink.contains("/i/web/status/"))permalink=link;
                    }
                    continue;
                }
                if(!belongs(body,sample,bodies))continue;
                if(TextFingerprint.normalized(sample.text).equals(body.text)
                        || TextFingerprint.normalized(sample.text).equals(author)
                        || sample.text.equals(handle))continue;
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
    private static boolean sourceBelongs(TextCandidate body,Sample sample,List<TextCandidate> all){
        if(belongs(body,sample,all))return true;
        // Detail screens sometimes expose the actual source on the body node
        // or a named card without providing a separate postBounds. Accept only
        // a directly enclosing structural node with one unambiguous body.
        String role=roleId(sample.id);
        boolean direct=PostTextHeuristics.isPostTextId(role)&&sample.bounds.equals(body.bounds);
        boolean container=PostTextHeuristics.isPostContainerId(role)&&sample.bounds.contains(body.bounds);
        if(!direct&&!container)return false;
        for(TextCandidate other:all){
            if(other!=body&&!other.authorName&&(sample.bounds.contains(other.bounds)
                ||other.hasPostContainer&&other.postBounds.contains(sample.bounds)))return false;
        }
        return true;
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
            String language=role.equals("link")?"":XVisibleTextReader.languageHint(text);
            if(role.equals("body")&&language.isEmpty())language=KnowledgeLanguage.englishHint(text);
            out.putIfAbsent(id,new NativeTree.Observation(source,key,text,language,at));
        }
    }
}
