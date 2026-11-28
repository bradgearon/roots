package com.wds.sixstars.contextoverlay;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative evidence extraction, never a URL resolver. The full-node
 * parcel can contain unrelated numbers; numeric candidates remain unverified. */
final class XPostIdEvidence {
    private static final long EPOCH=1288834974657L;
    private static final long DAY=86400000L;
    private static final Pattern URL=Pattern.compile("(?i)https?://(?:www\\.)?(?:x\\.com|twitter\\.com)/(?:[a-z0-9_]{1,25}/status|i/(?:web/)?status)/([1-9][0-9]{0,18})(?![0-9])");
    private static final Pattern DECIMAL=Pattern.compile("(?<![0-9])[1-9][0-9]{14,18}(?![0-9])");
    record Hit(String id,String kind,String field,String location){}
    private final LinkedHashMap<String,Hit> hits=new LinkedHashMap<>();
    private final LinkedHashMap<String,Hit> guesses=new LinkedHashMap<>();
    private final long now;
    private static final int MAX_HITS=64;
    XPostIdEvidence(long now){this.now=now;}
    List<Hit> hits(){java.util.ArrayList<Hit> all=new java.util.ArrayList<>(hits.values());all.addAll(guesses.values());return java.util.Collections.unmodifiableList(all);}
    private void add(String id,String kind,String field,String location){
        String key=kind+"|"+id+"|"+field+"|"+location;
        LinkedHashMap<String,Hit> lane=kind.startsWith("raw")?guesses:hits;
        if(lane.size()<(lane==guesses?24:MAX_HITS))lane.putIfAbsent(key,new Hit(id,kind,field,location));
    }
    static boolean plausibleSnowflake(long id,long now,long oldest){
        if(id<=0)return false;
        long at=EPOCH+(id>>>22);
        return at>=Math.max(EPOCH,oldest)&&at<=now+DAY;
    }
    void text(String field,String location,CharSequence raw,boolean namedId){
        if(raw==null||raw.length()==0||raw.length()>32768)return;
        String value=raw.toString();
        Matcher urls=URL.matcher(value);
        while(urls.find())add(urls.group(1),"status URL (association unverified)",field,location);
        if(namedId&&value.matches("[1-9][0-9]{0,18}")){
            add(value,"explicitly named post identifier",field,location);return;
        }
        Matcher decimals=DECIMAL.matcher(value);
        while(decimals.find()){
            String id=decimals.group();
            try{if(plausibleSnowflake(Long.parseLong(id),now,EPOCH))
                add(id,"decimal candidate only",field,location);}catch(NumberFormatException ignored){}
        }
    }
    void parcel(byte[] bytes,String field,String location){
        if(bytes==null||bytes.length>32768)return;
        // Android parcel strings can be UTF-8 or UTF-16LE. Scan both
        // alignments so unrelated field layouts do not hide decimal strings.
        text(field+" UTF-8",location,new String(bytes,StandardCharsets.UTF_8),false);
        text(field+" UTF-16LE",location,new String(bytes,StandardCharsets.UTF_16LE),false);
        if(bytes.length>1)text(field+" UTF-16LE+1",location,
            new String(bytes,1,bytes.length-1,StandardCharsets.UTF_16LE),false);
        // Longs are generally aligned to 4 bytes. Limit raw binary matches
        // to plausible recent dates, explicitly label them as guesses.
        int found=0;
        for(int offset=0;offset+8<=bytes.length&&found<4;offset+=4){
            long value=0;
            for(int i=0;i<8;i++)value|=(bytes[offset+i]&255L)<<(8*i);
            if(plausibleSnowflake(value,now,now-30*DAY)){
                add(Long.toString(value),"raw 64-bit candidate only",field+" +"+offset,location);found++;
            }
        }
    }
}
