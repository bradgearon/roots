package com.wds.sixstars.contextoverlay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Fixed hash-prefix partitions keep unrelated portions stable as a Root
 * grows. Assignment weights are scheduling metadata, never part of a digest. */
final class KnowledgeManifest {
    static final int BUCKETS=64;
    record Part(String root,int count){}
    private final List<NavigableSet<String>> buckets=new ArrayList<>(BUCKETS);
    private final Part[] cached=new Part[BUCKETS];
    KnowledgeManifest(Collection<String> ids)throws Exception{
        for(int i=0;i<BUCKETS;i++)buckets.add(new TreeSet<>());
        for(String id:ids)add(id);
    }
    static int bucket(String id)throws IOException{
        if(!EncounterBindings.digest(id))throw new IOException("Invalid knowledge identity");
        return Integer.parseInt(id.substring(0,2),16)>>>2;
    }
    void add(String id)throws Exception{int i=bucket(id);if(buckets.get(i).add(id))cached[i]=null;}
    boolean has(String id)throws Exception{return buckets.get(bucket(id)).contains(id);}
    Part part(int i)throws Exception{
        checkBucket(i);if(cached[i]!=null)return cached[i];var ids=buckets.get(i);
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        for(String id:ids)hash.update(id.getBytes(StandardCharsets.US_ASCII));
        return cached[i]=new Part(MeshItem.hex(hash.digest()),ids.size());
    }
    NavigableSet<String> after(int i,String cursor)throws Exception{
        checkBucket(i);
        if(!cursor.isEmpty()&&bucket(cursor)!=i)throw new IOException("Cursor outside knowledge partition");
        return buckets.get(i).tailSet(cursor,false);
    }
    int total(){int n=0;for(var ids:buckets)n+=ids.size();return n;}
    JSONArray json()throws Exception{
        JSONArray array=new JSONArray();
        for(int i=0;i<BUCKETS;i++){Part p=part(i);array.put(new JSONObject().put("root",p.root()).put("count",p.count()));}
        return array;
    }
    static List<Part> parse(JSONArray array)throws Exception{
        if(array.length()!=BUCKETS)throw new IOException("Invalid manifest size");
        List<Part> parts=new ArrayList<>(BUCKETS);long total=0;
        String empty=KnowledgeChunk.hash(new byte[0]);
        for(int i=0;i<BUCKETS;i++){
            JSONObject j=array.getJSONObject(i);String root=j.getString("root");int count=j.getInt("count");
            if(!EncounterBindings.digest(root)||count<0||(count==0&&!empty.equals(root)))throw new IOException("Invalid knowledge partition");
            total+=count;if(total>4000000)throw new IOException("Manifest exceeds supported inventory size");
            parts.add(new Part(root,count));
        }
        return parts;
    }
    static void checkBucket(int bucket)throws IOException{if(bucket<0||bucket>=BUCKETS)throw new IOException("Invalid knowledge partition");}
    record Checkpoint(String remote,String local,String after,boolean complete){
        boolean matches(Part remotePart,Part localPart){return remote.equals(remotePart.root())&&local.equals(localPart.root());}
    }
}
