package com.wds.sixstars.contextoverlay;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.wds.sixstars.contextoverlay.tree.NativeTree;

/** Exact X status links only; never construct a remote ID from a local hash. */
public final class XPostLink {
    private XPostLink(){}
    private static final Pattern STATUS_URL=Pattern.compile(
        "(?i)https://(?:www\\.)?(?:x\\.com|twitter\\.com)/(?:[a-z0-9_]{1,25}/status|i/(?:web/)?status)/[0-9]{1,25}/?(?=$|[\\s?#,.;:!\"'<>\\)\\]\\}])");
    static String postId(String raw){
        String link=canonical(raw);
        return link.isEmpty()?"":link.substring(link.lastIndexOf('/')+1);
    }
    // An explicitly named remote ID supplied by X, not a view ID or inferred ID.
    static String fromRemoteId(Object value){
        String id=value instanceof CharSequence||value instanceof Long||value instanceof Integer?value.toString().trim():"";
        return id.matches("[1-9][0-9]{0,24}")?"https://x.com/i/web/status/"+id:"";
    }
    // For source metadata only, not arbitrary tweet body text.
    static String fromMetadata(CharSequence raw){
        if(raw==null||raw.length()>16000)return "";
        String whole=canonical(raw.toString());
        if(!whole.isEmpty())return whole;
        Matcher matcher=STATUS_URL.matcher(raw);
        String found="";
        while(matcher.find()){
            String next=canonical(matcher.group());
            if(next.isEmpty())continue;
            if(!found.isEmpty()&&!postId(found).equals(postId(next)))return "";
            if(found.isEmpty())found=next;
        }
        return found;
    }
    static String forRecord(NativeTree.Master record){
        String confirmed=canonical(record.latest("link-confirmed"));
        if(!confirmed.isEmpty())return confirmed;
        String found="";
        for(NativeTree.Fragment member:record.members()){
            if(!member.source().endsWith(".link"))continue;
            String next=canonical(member.body());
            if(next.isEmpty())continue;
            // A provisional text/author identity may represent two different
            // posts. Do not silently navigate to the most recently seen one.
            if(!found.isEmpty()&&!postId(found).equals(postId(next)))return "";
            if(found.isEmpty()||found.contains("/i/web/status/"))found=next;
        }
        return found;
    }
    public static String canonical(String raw){
        try{
            URI uri=new URI(raw.trim());String host=uri.getHost();
            if(host==null||!"https".equalsIgnoreCase(uri.getScheme())||uri.getUserInfo()!=null||uri.getPort()!=-1)return "";
            host=host.toLowerCase(Locale.ROOT);
            if(!host.equals("x.com")&&!host.equals("www.x.com")&&!host.equals("twitter.com")&&!host.equals("www.twitter.com"))return "";
            String path=uri.getRawPath();
            if(path==null||!path.matches("/(?:[A-Za-z0-9_]{1,25}/status|i/(?:web/)?status)/[1-9][0-9]{0,24}/?"))return "";
            return "https://x.com"+path.replaceFirst("/$","");
        }catch(Exception e){return "";}
    }
}
