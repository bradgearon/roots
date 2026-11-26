package com.wds.sixstars.contextoverlay;
import java.net.URI;
import java.util.Locale;
/** Exact X status links only; never construct a post ID from a local hash. */
public final class XPostLink {
    private XPostLink(){}
    public static String canonical(String raw){
        try{
            URI uri=new URI(raw.trim());String host=uri.getHost();
            if(host==null||!"https".equalsIgnoreCase(uri.getScheme())||uri.getUserInfo()!=null||uri.getPort()!=-1)return "";
            host=host.toLowerCase(Locale.ROOT);
            if(!host.equals("x.com")&&!host.equals("www.x.com")&&!host.equals("twitter.com")&&!host.equals("www.twitter.com"))return "";
            String path=uri.getPath();
            if(path==null||!path.matches("/(?:[A-Za-z0-9_]{1,25}/status|i/web/status)/[0-9]{1,25}/?"))return "";
            return "https://x.com"+path.replaceFirst("/$","");
        }catch(Exception e){return "";}
    }
}
