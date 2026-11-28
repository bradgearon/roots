package com.wds.sixstars.contextoverlay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure snapshot assembler. Paths belong to ONE accessibility traversal;
 * they are never persisted as remote identity. The timeline card and its
 * timeline_post header, rather than screen proximity, own their descendants. */
final class XTimelineCardRecords {
    private static final Pattern HANDLE=Pattern.compile("@[A-Za-z0-9_]{1,15}");
    private static final Pattern TIME=Pattern.compile("(?i)(?:[·•]\\s*)?(?:\\d+\\s*[smhdwy]|\\d{1,2}\\s+[\\p{L}]{3,12}(?:\\s+\\d{2,4})?|[\\p{L}]{3,12}\\s+\\d{1,2}(?:,?\\s+\\d{2,4})?)");
    private static final Pattern COUNT=Pattern.compile("(?i)\\d[\\d,.\\s]*(?:[kmb万億])?");
    record Node(int order,String path,String id,String className,String text,String description,
                boolean card,boolean header,boolean bodyRole,boolean bodySpan,boolean primary,List<String> links,String language){
        Node{
            id=id==null?"":id;className=className==null?"":className;
            text=text==null?"":text;description=description==null?"":description;
            links=links==null?List.of():List.copyOf(links);
            language=PostLanguage.code(language);
        }
    }
    record Field(String role,String value){}
    record Post(String cardPath,int bodyOrder,String key,String body,List<Field> fields,String language){}
    record Result(List<Post> posts,Map<Integer,String> cardByOrder){}

    static boolean under(String path,String parent){
        return path.equals(parent)||path.startsWith(parent+"/");
    }
    private static String parent(String path){int cut=path.lastIndexOf('/');return cut<0?"":path.substring(0,cut);}
    private static String branch(String path,String root){
        if(!path.startsWith(root+"/"))return "";
        int end=path.indexOf('/',root.length()+1);return end<0?path:path.substring(0,end);
    }
    private static int pathCompare(String a,String b){
        String[] aa=a.split("/"),bb=b.split("/");
        for(int i=0;i<Math.min(aa.length,bb.length);i++){
            int cmp=Integer.compare(Integer.parseInt(aa[i]),Integer.parseInt(bb[i]));
            if(cmp!=0)return cmp;
        }
        return Integer.compare(aa.length,bb.length);
    }
    static Result assemble(List<Node> snapshot){
        List<String> cards=new ArrayList<>();
        for(Node n:snapshot)if(n.card())cards.add(n.path());
        Map<String,List<Node>> groups=new TreeMap<>(XTimelineCardRecords::pathCompare);
        Map<Integer,String> owners=new HashMap<>();
        for(Node n:snapshot){
            String owner="";
            for(String card:cards)if(under(n.path(),card)&&card.length()>owner.length())owner=card;
            if(owner.isEmpty())continue;
            owners.put(n.order(),owner);
            groups.computeIfAbsent(owner,k->new ArrayList<>()).add(n);
        }
        List<Post> posts=new ArrayList<>();
        for(Map.Entry<String,List<Node>> group:groups.entrySet()){
            List<Node> nodes=group.getValue();
            nodes.sort(Comparator.comparing(Node::path,XTimelineCardRecords::pathCompare));
            Post post=post(group.getKey(),nodes);
            if(post!=null)posts.add(post);
        }
        return new Result(List.copyOf(posts),Map.copyOf(owners));
    }
    private static Post post(String card,List<Node> nodes){
        // The actual Compose header is a direct child of the conversation
        // card. A second header inside a quoted/media subtree is not its author.
        Node header=null;
        for(Node n:nodes)if(n.header()&&n.primary()&&parent(n.path()).equals(card)){
            if(header!=null)return null;
            header=n;
        }
        if(header==null)return null;
        String author="",handle="",time="";
        for(Node n:nodes)if(n.primary()&&under(n.path(),header.path())){
            String value=n.text().trim();
            if(HANDLE.matcher(value).matches()){
                if(!handle.isEmpty()&&!handle.equalsIgnoreCase(value))return null;
                handle=value;
            }else if(TIME.matcher(value).matches()&&!value.isEmpty())time=value;
            else if(!value.isEmpty()&&author.isEmpty()&&!value.startsWith("@")&&!value.equalsIgnoreCase("Ad"))
                author=value;
        }
        // No provisional author-only key for a clipped header: otherwise the
        // same post is duplicated when its @handle comes into view later.
        if(handle.isEmpty())return null;
        // Quoted/embedded headers can be exposed without a named container.
        // Their descendants are context, not this card's source or body.
        java.util.Set<String> contextBranches=new java.util.HashSet<>();
        for(Node n:nodes)if((n.header()&&!under(n.path(),header.path()))||"Quote".equalsIgnoreCase(n.description().trim())){
            String sub=branch(n.path(),card);if(!sub.isEmpty()&&!sub.equals(header.path()))contextBranches.add(sub);
        }
        Node body=null;int best=0;boolean ambiguous=false;
        for(Node n:nodes){
            if(!n.primary()||n.text().isBlank()||under(n.path(),header.path())
                ||n.text().length()>4000)continue;
            String direct=branch(n.path(),card);
            if(direct.isEmpty()||contextBranches.contains(direct)||pathCompare(direct,header.path())<=0)continue;
            String text=n.text().trim();
            boolean directText=parent(n.path()).equals(card)&&n.className().endsWith("TextView")&&!isControl(text)
                &&!COUNT.matcher(text).matches()&&!HANDLE.matcher(text).matches()&&!TIME.matcher(text).matches();
            int strength=n.bodyRole()?4:n.bodySpan()&&parent(n.path()).equals(card)?3:directText?1:0;
            if(strength==0)continue;
            if(strength>best){body=n;best=strength;ambiguous=false;}
            else if(strength==best&&!TextFingerprint.normalized(body.text()).equals(TextFingerprint.normalized(n.text())))ambiguous=true;
        }
        if(body==null||ambiguous)return null;
        String original=TextFingerprint.normalized(body.text());
        if(original.isEmpty())return null;
        // Preserve the established handle/body key format, so revisiting a
        // previously correctly grouped post enriches it instead of forking it.
        String key="local:x:"+TextFingerprint.of(handle.length()+":"+handle+":"+original);
        List<Field> fields=new ArrayList<>();
        put(fields,"body",original);put(fields,"author",author);put(fields,"handle",handle);put(fields,"time",time);
        String link="";boolean conflict=false;
        for(Node n:nodes)if(n.primary()&&!contextBranches.contains(branch(n.path(),card))){
            // Explicit links only. Never treat a URL appearing in post prose or
            // a quoted card as the source of this conversation.
            for(String url:n.links()){
                String next=XPostLink.canonical(url);
                if(next.isEmpty())continue;
                if(!link.isEmpty()&&!XPostLink.postId(link).equals(XPostLink.postId(next)))conflict=true;
                if(link.isEmpty()||link.contains("/i/web/status/"))link=next;
            }
        }
        if(!conflict&&!link.isEmpty())put(fields,"link",link);
        // An action icon and its adjacent counter share a direct action
        // subtree. Do not infer engagement roles from row positions.
        Map<String,List<Node>> branches=new LinkedHashMap<>();
        for(Node n:nodes){
            if(n.path().equals(card)||under(n.path(),header.path()))continue;
            branches.computeIfAbsent(branch(n.path(),card),k->new ArrayList<>()).add(n);
        }
        for(Map.Entry<String,List<Node>> entry:branches.entrySet()){
            String sub=entry.getKey();
            if(under(body.path(),sub))continue;
            List<Node> members=entry.getValue();
            String metric="";boolean metricConflict=false;
            for(Node n:members)if(n.primary()){
                String role=metricRole(n.description());
                if(!role.isEmpty()){
                    if(!metric.isEmpty()&&!role.equals(metric))metricConflict=true;
                    metric=role;
                }
            }
            // Reply/Repost/Like/Impressions each live in their own sibling
            // branch in the observed X Compose tree.
            String count="";boolean countConflict=false;
            if(!metric.isEmpty()&&!metricConflict&&!contextBranches.contains(sub)){
                for(Node n:members)if(n.primary()&&COUNT.matcher(n.text().trim()).matches()){
                    String value=n.text().trim();
                    if(!count.isEmpty()&&!count.equals(value))countConflict=true;
                    count=value;
                }
                if(!countConflict)put(fields,metric,count);
                continue;
            }
            for(Node n:members){
                String value=n.text().trim(),description=n.description().trim();
                if(n.primary()&&!description.isEmpty()&&isMedia(description))put(fields,"media-description",description);
                if(!value.isEmpty()&&!value.equals(author)&&!value.equals(handle)&&!value.equals(time)
                    &&!value.equals(original)&&!isControl(value)&&!COUNT.matcher(value).matches()
                    &&!HANDLE.matcher(value).matches()&&!TIME.matcher(value).matches())
                    put(fields,n.primary()&&!contextBranches.contains(sub)?"text":"context",value);
            }
        }
        return new Post(card,body.order(),key,original,List.copyOf(fields),PostLanguage.hint(original,body.language()));
    }
    private static boolean isMedia(String description){
        String s=description.toLowerCase(java.util.Locale.ROOT);
        return s.equals("image")||s.equals("video")||s.equals("gif")||s.startsWith("image:")||s.startsWith("photo:")||s.startsWith("video:");
    }
    private static boolean isControl(String text){
        return switch(text.toLowerCase(java.util.Locale.ROOT)){
            case "reply","repost","retweet","like","liked","share","bookmark","bookmarked","impressions","post options","verified","verified business","show more","ad","follow","following","unmute","mute","install","explain this post with grok" -> true;
            default -> false;
        };
    }
    private static String metricRole(String description){
        return switch(description.trim().toLowerCase(java.util.Locale.ROOT)){
            case "reply","replies" -> "reply-count";
            case "repost","reposted","retweet","retweeted" -> "repost-count";
            case "like","liked" -> "like-count";
            case "impressions","views" -> "view-count";
            default -> "";
        };
    }
    private static void put(List<Field> fields,String role,String value){
        if(value==null||value.isBlank())return;
        Field field=new Field(role,value);
        if(!fields.contains(field))fields.add(field);
    }
}
