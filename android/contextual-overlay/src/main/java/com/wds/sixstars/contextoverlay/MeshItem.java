package com.wds.sixstars.contextoverlay;

import com.wds.sixstars.contextoverlay.tree.NativeTree;
import com.wds.sixstars.contextoverlay.tree.TreeRuntime;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Content-addressed knowledge. Graph items retain their original observation
 * and master identity, so the native Tree reconstructs the same relationships
 * instead of wrapping every received fragment in a new remembrance. */
record MeshItem(String id,String language,String topic,String text,String link,Graph graph) {
    record Graph(String kind,String source,String key,String language,long at) {}
    MeshItem(String id,String language,String topic,String text,String link){
        this(id,language,topic,text,link,null);
    }
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
        return new MeshItem(hash(canonical),lang,title,body,source);
    }
    private static String field(String value){return value.getBytes(StandardCharsets.UTF_8).length+":"+value;}
    private static String hash(String value)throws Exception{
        return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    static String hex(byte[] data){
        char[] out=new char[data.length*2];String digits="0123456789abcdef";
        for(int i=0;i<data.length;i++){
            int b=data[i]&255;out[2*i]=digits.charAt(b>>>4);out[2*i+1]=digits.charAt(b&15);
        }
        return new String(out);
    }
    private static boolean bounded(String value,int max,boolean nonempty){
        return value!=null&&(!nonempty||!value.isEmpty())&&value.indexOf('\0')<0
            &&value.getBytes(StandardCharsets.UTF_8).length<=max;
    }
    static MeshItem fromFragment(NativeTree.Fragment f)throws Exception{
        return graphItem(new Graph("fragment",f.source(),f.externalId(),f.language(),f.at()),f.body());
    }
    static MeshItem fromBranch(NativeTree.Agent a)throws Exception{
        return graphItem(new Graph("branch","branch",a.name(),"",0),a.term());
    }
    private static MeshItem graphItem(Graph g,String body)throws Exception{
        boolean branch=g.kind().equals("branch");
        if(branch){
            if(!g.source().equals("branch")||!g.language().isEmpty()||g.at()!=0
                ||!bounded(g.key(),160,true)||!bounded(body,512,true))
                throw new IllegalArgumentException("Invalid graph branch");
        }else if(!g.kind().equals("fragment")||!bounded(g.source(),256,true)||!bounded(g.key(),512,false)
            ||!bounded(body,16384,true)||!bounded(g.language(),64,false)||g.at()<0){
            throw new IllegalArgumentException("Invalid graph fragment");
        }
        // Match native SQLite uniqueness: source + external identity + body.
        // Language and observed time remain covered by the wire payload digest.
        String id=hash("roots.mesh.graph.v1\n"+field(g.kind())+field(g.source())+field(g.key())+field(body));
        String lang=g.language().toLowerCase(Locale.ROOT);
        if(!lang.matches("[a-z]{2,3}"))lang="und";
        String topic=RuntimeNeed.safe(branch?g.key()+" "+body:body,120);
        if(topic.isEmpty())topic=RuntimeNeed.safe(g.source(),120);
        if(topic.isEmpty())topic="Knowledge";
        String link=g.source().endsWith(".link")||g.source().endsWith(".link-confirmed")?XPostLink.canonical(body):"";
        return new MeshItem(id,lang,topic,body,link,g);
    }
    private String payloadDigest()throws Exception{
        return hash("roots.mesh.graph.payload.v1\n"+field(id)+field(graph.kind())+field(graph.source())+
            field(graph.key())+field(text)+field(graph.language())+field(Long.toString(graph.at())));
    }
    JSONObject json()throws Exception{
        JSONObject json=new JSONObject().put("id",id).put("language",language).put("topic",topic).put("text",text).put("link",link);
        if(graph!=null)json.put("kind",graph.kind()).put("source",graph.source()).put("external_id",graph.key())
            .put("observed_language",graph.language()).put("observed_ms",graph.at()).put("payload_sha256",payloadDigest());
        return json;
    }
    static MeshItem parse(JSONObject json)throws Exception{
        MeshItem result;
        if(json.has("kind")){
            result=graphItem(new Graph(json.getString("kind"),json.getString("source"),json.getString("external_id"),
                json.getString("observed_language"),json.getLong("observed_ms")),json.getString("text"));
            if(!result.payloadDigest().equals(json.getString("payload_sha256"))||
                !result.language.equals(json.getString("language"))||!result.topic.equals(json.getString("topic"))||
                !result.link.equals(json.getString("link")))throw new IllegalArgumentException("Graph payload mismatch.");
        }else result=create(json.getString("language"),json.getString("topic"),json.getString("text"),json.getString("link"));
        if(!result.id.equals(json.getString("id")))throw new IllegalArgumentException("Content hash mismatch.");
        return result;
    }
    boolean matchesTopic(String wanted){
        return RuntimeNeed.matches(topic,wanted)||(graph!=null&&RuntimeNeed.matches(text,wanted));
    }
    int retrievalRank(){
        if(graph==null)return 0;
        String source=graph.source();
        if(source.endsWith(".understanding"))return 0;
        if(source.endsWith(".body")||source.endsWith(".translation")||source.endsWith(".localized"))return 1;
        if(source.startsWith("x.android.visible."))return 2;
        return 3;
    }
    String key(){return graph==null?"local:mesh:"+id:graph.key();}
    long store(TreeRuntime tree,long epoch)throws Exception{
        if(graph!=null){
            if(graph.kind().equals("branch"))return tree.storeSharedBranch(graph.key(),text,epoch);
            return tree.storeExplicit(java.util.Collections.singletonList(
                new NativeTree.Observation(graph.source(),graph.key(),text,graph.language(),graph.at())),epoch);
        }
        return tree.storeExplicit(publicationRows(System.currentTimeMillis()),epoch);
    }
    long storeReceived(TreeRuntime tree,long epoch,String peer,long receivedAt)throws Exception{
        if(graph!=null||!EncounterBindings.digest(peer)||receivedAt<0)throw new IllegalArgumentException("Invalid external provenance");
        return tree.storeExplicit(receivedRows(peer,receivedAt),epoch);
    }
    List<NativeTree.Observation> receivedRows(String peer,long at)throws Exception{
        if(graph!=null||!EncounterBindings.digest(peer)||at<0)throw new IllegalArgumentException("Invalid external provenance");
        List<NativeTree.Observation> rows=publicationRows(at);
        rows.add(new NativeTree.Observation("record.mesh.external-peer",key(),peer,"",at));
        rows.add(new NativeTree.Observation("record.mesh.trust",key(),"External reference · unverified claims","",at));
        return rows;
    }
    private List<NativeTree.Observation> publicationRows(long at)throws Exception{
        List<NativeTree.Observation> rows=new ArrayList<>();
        rows.add(new NativeTree.Observation("record.mesh.body",key(),text,language,at));
        rows.add(new NativeTree.Observation("record.mesh.topic",key(),topic,language,at));
        if(!link.isEmpty())rows.add(new NativeTree.Observation("record.mesh.link",key(),link,"",at));
        String offer=json().toString();
        if(offer.getBytes(StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException("Remembrance is too large after encoding.");
        rows.add(new NativeTree.Observation("record.mesh.offer",key(),offer,"",at));
        return rows;
    }
    static MeshItem from(NativeTree.Master record){
        if(!record.key().startsWith("local:mesh:"))return null;
        try{
            MeshItem item=parse(new JSONObject(record.latest("offer")));
            return item.graph()==null&&item.key().equals(record.key())?item:null;
        }catch(Exception e){return null;}
    }
}