package com.wds.sixstars.contextoverlay;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** One compressed public archive is sent directly over the byte stream.
 * Content identity is independent of assignment weights and transport. */
final class KnowledgeChunk {
    static final int INITIAL_ITEMS=4096,MAX_ITEMS=16384,MAX_RAW=16*1024*1024,MAX_WIRE=8*1024*1024;
    static final int MIN_BUDGET=65536; // Minimum advertised memory budget, not a frame size.
    record Packed(byte[] bytes,String codec,String sha,int rawSize,int count){}
    private KnowledgeChunk(){}
    static String hash(byte[] bytes)throws Exception{return MeshItem.hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static void limits(int raw,int wire)throws IOException{
        if(raw<MIN_BUDGET||raw>MAX_RAW||wire<MIN_BUDGET||wire>MAX_WIRE)throw new IOException("Invalid archive byte budget");
    }
    static Packed pack(List<MeshItem> items,int rawLimit,int wireLimit)throws Exception{
        limits(rawLimit,wireLimit);
        if(items.isEmpty()||items.size()>MAX_ITEMS)throw new IOException("Invalid archive count");
        // Serialize once. Fit a prefix to the receiver's decompressed budget.
        ByteArrayOutputStream buffer=new ByteArrayOutputStream(Math.min(rawLimit,1024*1024));
        buffer.write('[');
        int[] ends=new int[items.size()];int count=0;
        for(MeshItem item:items){
            byte[] row=item.json().toString().getBytes(StandardCharsets.UTF_8);
            if(buffer.size()+row.length+2>rawLimit)break;
            if(count>0)buffer.write(',');
            buffer.write(row);ends[count++]=buffer.size();
        }
        if(count==0)throw new IOException("Record exceeds archive budget");
        byte[] prefix=buffer.toByteArray();
        // ZIP-like gzip stream, fast compression, adaptive prefix if the
        // compressed bytes exceed the budget. Avoid base64 and per-frame ACKs.
        for(int n=count;n>=1;){
            byte[] raw=Arrays.copyOf(prefix,ends[n-1]+1);raw[raw.length-1]=']';
            ByteArrayOutputStream zipped=new ByteArrayOutputStream(Math.min(raw.length,1024*1024));
            try(GZIPOutputStream gzip=new GZIPOutputStream(zipped){{def.setLevel(Deflater.BEST_SPEED);}}){gzip.write(raw);}
            byte[] compressed=zipped.toByteArray();
            boolean useGzip=compressed.length<raw.length;
            byte[] wire=useGzip?compressed:raw;
            if(wire.length<=wireLimit)return new Packed(wire,useGzip?"gzip":"identity",hash(raw),raw.length,n);
            if(n==1)break;
            n=Math.max(1,Math.min(n-1,(int)((long)n*wireLimit/wire.length*0.9)));
        }
        throw new IOException("Record exceeds compressed archive budget");
    }
    static List<MeshItem> unpack(byte[] wire,String codec,String sha,int rawSize,List<String> requested,int count,int rawLimit,int wireLimit)throws Exception{
        limits(rawLimit,wireLimit);
        if(wire.length<1||wire.length>wireLimit||rawSize<2||rawSize>rawLimit||
           !EncounterBindings.digest(sha)||count<1||count>MAX_ITEMS||count>requested.size())throw new IOException("Invalid archive header");
        byte[] raw;
        if("identity".equals(codec))raw=wire;
        else if("gzip".equals(codec)){
            raw=new byte[rawSize];
            try(GZIPInputStream in=new GZIPInputStream(new ByteArrayInputStream(wire))){
                int offset=0;while(offset<raw.length){int n=in.read(raw,offset,raw.length-offset);if(n<0)throw new IOException("Truncated archive");offset+=n;}
                if(in.read()!=-1)throw new IOException("Archive exceeds declared raw size");
            }
        }else throw new IOException("Unknown archive codec");
        if(raw.length!=rawSize||!sha.equals(hash(raw)))throw new IOException("Archive digest mismatch");
        JSONArray rows=new JSONArray(new String(raw,StandardCharsets.UTF_8));
        if(rows.length()!=count)throw new IOException("Archive member count mismatch");
        List<MeshItem> items=new ArrayList<>(count);
        for(int i=0;i<count;i++){
            MeshItem item=MeshItem.parse(rows.getJSONObject(i));
            if(!item.id().equals(requested.get(i)))throw new IOException("Unexpected archive member");
            items.add(item);
        }
        return items;
    }
    static byte[] encodeIds(List<String> ids)throws Exception{
        if(ids.isEmpty()||ids.size()>MAX_ITEMS)throw new IOException("Invalid archive request count");
        byte[] bytes=new byte[ids.size()*32];int p=0;Set<String> unique=new HashSet<>();
        for(String id:ids){
            if(!EncounterBindings.digest(id)||!unique.add(id))throw new IOException("Invalid archive request identity");
            for(int i=0;i<64;i+=2)bytes[p++]=(byte)Integer.parseInt(id.substring(i,i+2),16);
        }
        return bytes;
    }
    static List<String> decodeIds(byte[] bytes,int count)throws Exception{
        if(count<1||count>MAX_ITEMS||bytes.length!=count*32)throw new IOException("Invalid archive request size");
        List<String> ids=new ArrayList<>(count);Set<String> unique=new HashSet<>();
        for(int offset=0;offset<bytes.length;offset+=32){
            String id=MeshItem.hex(Arrays.copyOfRange(bytes,offset,offset+32));
            if(!unique.add(id))throw new IOException("Duplicate archive request");ids.add(id);
        }
        return ids;
    }
}
