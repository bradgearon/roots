package com.wds.sixstars.contextoverlay;

import com.wds.sixstars.contextoverlay.tree.NativeTree;
import com.wds.sixstars.contextoverlay.tree.TreeRuntime;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** An explicitly published, shareable remembrance, not a captured X fragment.
 * Content IDs cover every immutable field. Original records remain private. */
record MeshItem(String id,String language,String topic,String text,String link) {
    static MeshItem create(String language,String topic,String text,String link)throws Exception{
        String lang=language.trim().toLowerCase(Locale.ROOT);
        String title=topic.trim(),body=text.trim(),source=link.trim();
        if(!lang.matches("[a-z]{2,3}")||title.isEmpty()||title.length()>120||body.isEmpty()||body.length()>3000
            ||title.indexOf('\0')>=0||body.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid language, topic or remembrance.");
        if(!source.isEmpty()){
            source=XPostLink.canonical(source);
            if(source.isEmpty())throw new IllegalArgumentException("Use an exact HTTPS X status link.");
        }
        String canonical="roots.mesh.item.v1\n"+field(lang)+field(title)+field(body)+field(source);
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        return new MeshItem(hex(hash),lang,title,body,source);
    }
    private static String field(String value){return value.getBytes(StandardCharsets.UTF_8).length+":"+value;}
    static String hex(byte[] data){
        StringBuilder out=new StringBuilder(data.length*2);
        for(byte b:data)out.append(String.format(Locale.ROOT,"%02x",b&255));
        return out.toString();
    }
    JSONObject json()throws Exception{
        return new JSONObject().put("id",id).put("language",language).put("topic",topic).put("text",text).put("link",link);
    }
    static MeshItem parse(JSONObject json)throws Exception{
        MeshItem result=create(json.getString("language"),json.getString("topic"),json.getString("text"),json.getString("link"));
        if(!result.id.equals(json.getString("id")))throw new IllegalArgumentException("Content hash mismatch.");
        return result;
    }
    String key(){return "local:mesh:"+id;}
    long store(TreeRuntime tree)throws Exception{
        long at=System.currentTimeMillis();
        List<NativeTree.Observation> rows=new ArrayList<>();
        rows.add(new NativeTree.Observation("record.mesh.body",key(),text,language,at));
        rows.add(new NativeTree.Observation("record.mesh.topic",key(),topic,language,at));
        if(!link.isEmpty())rows.add(new NativeTree.Observation("record.mesh.link",key(),link,"",at));
        // The offer is the only lane a peer can access. Nothing else is sent.
        rows.add(new NativeTree.Observation("record.mesh.offer",key(),json().toString(),"",at));
        return tree.storeExplicit(rows);
    }
    static MeshItem from(NativeTree.Master record){
        if(!record.key().startsWith("local:mesh:"))return null;
        try{
            MeshItem item=parse(new JSONObject(record.latest("offer")));
            return item.key().equals(record.key())?item:null;
        }catch(Exception e){return null;}
    }
}
