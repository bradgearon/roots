package com.wds.sixstars.contextoverlay;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.json.*;

/** Bounded, content-addressed wire chunks. Assignments and weights are
 * deliberately excluded from content identity. REP authenticates the frame;
 * SHA-256 verifies the exact uncompressed bytes, and MeshItem verifies each
 * member against the requested immutable item identity. */
final class KnowledgeChunk {
    static final int MAX_ITEMS=32, MAX_RAW=256*1024;
    record Packed(byte[] bytes,String codec,String sha,int rawSize,int count){}
    private KnowledgeChunk(){}
    static String hash(byte[] bytes)throws Exception{return MeshItem.hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static Packed pack(List<MeshItem> items)throws Exception{
        if(items.isEmpty()||items.size()>MAX_ITEMS)throw new IOException("Invalid chunk size");
        // Fit a prefix. A single valid REP item fits as a JSON array because
        // the legacy data envelope is larger. Bound both raw and wire sizes.
        for(int n=items.size();n>=1;n=n==1?0:Math.max(1,n/2)){
            JSONArray rows=new JSONArray();for(int i=0;i<n;i++)rows.put(items.get(i).json());
            byte[] raw=rows.toString().getBytes(StandardCharsets.UTF_8);
            if(raw.length>MAX_RAW)continue;
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(GZIPOutputStream gzip=new GZIPOutputStream(out)){gzip.write(raw);}
            byte[] zipped=out.toByteArray();
            boolean compressed=zipped.length<raw.length;
            byte[] wire=compressed?zipped:raw;
            if(wire.length>EncounterChannel.MAX_FRAME)continue;
            return new Packed(wire,compressed?"gzip":"identity",hash(raw),raw.length,n);
        }
        throw new IOException("Publication does not fit a chunk");
    }
    static List<MeshItem> unpack(byte[] wire,String codec,String sha,int rawSize,List<String> requested,int count)throws Exception{
        if(wire.length<1||wire.length>EncounterChannel.MAX_FRAME||rawSize<2||rawSize>MAX_RAW||
           !EncounterBindings.digest(sha)||count<1||count>MAX_ITEMS||count>requested.size())throw new IOException("Invalid chunk header");
        byte[] raw;
        if("identity".equals(codec))raw=wire;
        else if("gzip".equals(codec)){
            ByteArrayOutputStream out=new ByteArrayOutputStream(Math.min(rawSize,8192));
            try(GZIPInputStream in=new GZIPInputStream(new ByteArrayInputStream(wire))){
                byte[] buffer=new byte[8192];int n;
                while((n=in.read(buffer))!=-1){
                    if(out.size()+n>rawSize)throw new IOException("Chunk exceeds declared size");
                    out.write(buffer,0,n);
                }
            }
            raw=out.toByteArray();
        }else throw new IOException("Unknown chunk codec");
        if(raw.length!=rawSize||!sha.equals(hash(raw)))throw new IOException("Chunk digest mismatch");
        JSONArray rows=new JSONArray(new String(raw,StandardCharsets.UTF_8));
        if(rows.length()!=count)throw new IOException("Chunk member count mismatch");
        List<MeshItem> items=new ArrayList<>(count);
        for(int i=0;i<count;i++){
            MeshItem item=MeshItem.parse(rows.getJSONObject(i));
            if(!item.id().equals(requested.get(i)))throw new IOException("Unexpected chunk member");
            items.add(item);
        }
        return items;
    }
}
