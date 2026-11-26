package com.wds.sixstars.contextoverlay;

import android.content.Context;
import android.os.SystemClock;
import android.util.AtomicFile;
import com.wds.sixstars.contextoverlay.tree.NativeTree;
import com.wds.sixstars.contextoverlay.tree.TreeRuntime;
import com.wds.sixstars.contextoverlay.tree.KnowledgeWave;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Persistent, local subscription policy, bound to peer certificate identities.
 * Invitations are still short-lived access grants; never persist their token.
 * Topics are explicitly chosen, element bindings optional and model-specific. */
final class AxonSubscription {
    record Lane(String topic,List<String> elements){}
    final Set<String> pins;
    final IntelligenceModel model;
    final String operation;
    final List<Lane> lanes;
    private AxonSubscription(Set<String> pins,IntelligenceModel model,String operation,List<Lane> lanes){
        this.pins=Collections.unmodifiableSet(new HashSet<>(pins));this.model=model;this.operation=operation;this.lanes=Collections.unmodifiableList(new ArrayList<>(lanes));
    }
    static AxonSubscription create(List<MeshPeer.Invite> peers,IntelligenceModel model,String operation,String lines){
        Set<String> pins=new HashSet<>();for(MeshPeer.Invite peer:peers)pins.add(peer.pin());
        if(pins.isEmpty()||pins.size()>8)throw new IllegalArgumentException("Provide one to eight peer invitations first.");
        return parse(pins,model,operation,lines);
    }
    private static AxonSubscription parse(Set<String> pins,IntelligenceModel model,String operation,String lines){
        if(pins.isEmpty()||pins.size()>8||!java.util.Arrays.asList(ElementFlow.OPERATIONS).contains(operation))throw new IllegalArgumentException("Invalid Axon identity or operation.");
        for(String pin:pins)if(!pin.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid peer identity.");
        if(lines.length()>4096)throw new IllegalArgumentException("Axon policy too large.");
        List<Lane> lanes=new ArrayList<>();Set<String> topics=new HashSet<>(),mapped=new HashSet<>();
        for(String line:lines.split("\\r?\\n")){
            if(line.trim().isEmpty())continue;
            String[] fields=line.split("\\|",-1);String topic=fields[0].trim().toLowerCase(Locale.ROOT);
            if(fields.length>2||topic.isEmpty()||topic.length()>80||!topics.add(topic)||lanes.size()>=16)throw new IllegalArgumentException("Use up to sixteen unique topics.");
            List<String> elements=new ArrayList<>();
            if(fields.length==2&&!fields[1].trim().isEmpty())for(String raw:fields[1].split(",")){
                String group=raw.trim();
                if(!group.matches("[0-9]{1,3}:[0-9]{1,4}"))throw new IllegalArgumentException("Element format: layer:group");
                String[] parts=group.split(":");
                int layer=Integer.parseInt(parts[0]),index=Integer.parseInt(parts[1]);
                if(layer>=128||index>=1024)throw new IllegalArgumentException("Element address outside bounds.");
                group=layer+":"+index;
                if(!mapped.add(group)||elements.size()>=32)throw new IllegalArgumentException("Map an element to only one topic (32 per topic maximum).");
                elements.add(group);
            }
            lanes.add(new Lane(topic,Collections.unmodifiableList(elements)));
        }
        if(lanes.isEmpty())throw new IllegalArgumentException("Enter at least one topic, or * for all published knowledge.");
        return new AxonSubscription(pins,model,operation,lanes);
    }
    String lines(){
        StringBuilder out=new StringBuilder();
        for(Lane lane:lanes){if(out.length()>0)out.append('\n');out.append(lane.topic());if(!lane.elements().isEmpty())out.append(" | ").append(String.join(",",lane.elements()));}
        return out.toString();
    }
    boolean accepts(List<MeshPeer.Invite> peers){
        for(MeshPeer.Invite peer:peers)if(!pins.contains(peer.pin()))return false;
        return !peers.isEmpty();
    }
    private static AtomicFile file(Context c){return new AtomicFile(new File(c.getNoBackupFilesDir(),"subscription.axon"));}
    static AxonSubscription load(Context c)throws Exception{
        AtomicFile f=file(c);if(!f.getBaseFile().exists())return null;
        if(f.getBaseFile().length()>16384)throw new IllegalArgumentException("Invalid saved Axon.");
        JSONObject json=new JSONObject(new String(f.readFully(),StandardCharsets.UTF_8));
        if(json.getInt("version")!=1||!"roots.axon".equals(json.getString("type")))throw new IllegalArgumentException("Unsupported Axon.");
        IntelligenceModel model=IntelligenceModel.valueOf(json.getString("model"));
        if(!model.sha.equals(json.getString("model_sha")))throw new IllegalArgumentException("Axon model version changed; rebind.");
        Set<String> pins=new HashSet<>();JSONArray peers=json.getJSONArray("peers");
        if(peers.length()>8)throw new IllegalArgumentException("Too many Axon peers.");
        for(int i=0;i<peers.length();i++)pins.add(peers.getString(i));
        return parse(pins,model,json.getString("operation"),json.getString("lanes"));
    }
    void save(Context c)throws Exception{
        JSONArray peers=new JSONArray();List<String> sorted=new ArrayList<>(pins);Collections.sort(sorted);
        for(String pin:sorted)peers.put(pin);
        JSONObject json=new JSONObject().put("type","roots.axon").put("version",1).put("peers",peers)
            .put("model",model.name()).put("model_sha",model.sha).put("operation",operation).put("lanes",lines());
        AtomicFile f=file(c);FileOutputStream stream=f.startWrite();
        try{stream.write(json.toString().getBytes(StandardCharsets.UTF_8));f.finishWrite(stream);}
        catch(Exception e){f.failWrite(stream);throw e;}
    }
    static void clear(Context c){file(c).delete();}
    record Allocation(List<AxonPlanner.Share> shares,int observedRequests,long waveRevision,boolean mappedFlow){}
    Allocation allocation(Context context,TreeRuntime tree,List<NativeTree.Master> records,String language){
        KnowledgeWave.Field field=tree.coordination();
        long now=SystemClock.elapsedRealtime();
        ElementFlow.Profile profile=ElementFlow.read(context,model,operation);
        Map<String,Double> element=ElementFlow.ffnShares(profile);
        List<AxonPlanner.Need> masses=new ArrayList<>();boolean mappedFlow=false;
        for(Lane lane:lanes){
            double knowledge=0;int matches=0;
            for(NativeTree.Master record:records){
                if(lane.topic().equals("*"))continue; // catch-all is a baseline lane, not duplicate mass
                String local=(record.localized(language).body()+" "+record.latest("topic")+" "+record.latest("understanding")).toLowerCase(Locale.ROOT);
                if(!local.contains(lane.topic()))continue;
                matches++;
                KnowledgeWave.Weight weight=language.equals(field.language())?field.weight(record.key(),now):null;
                if(weight!=null)knowledge+=weight.priority()/45.0;
                else if(!record.latest("want").isEmpty())knowledge+=0.4;
            }
            // A prior keeps a requested topic with no local records from starvation.
            // Element mass contributes only for explicitly mapped, observed groups.
            double observed=0;for(String group:lane.elements())observed+=element.getOrDefault(group,0.0);
            double need=1+knowledge/Math.sqrt(Math.max(1,matches));
            if(observed>0)mappedFlow=true;
            masses.add(new AxonPlanner.Need(lane.topic(),need,observed));
        }
        return new Allocation(AxonPlanner.allocate(masses),profile.requests(),field.revision(),mappedFlow);
    }
    String describe(Allocation allocation){
        StringBuilder out=new StringBuilder("Axon · "+pins.size()+" peer identities\n"+model.label+" · "+operation+"\n");
        out.append(allocation.observedRequests()).append(" observed model requests · wave revision ").append(allocation.waveRevision()).append('\n');
        out.append(allocation.mappedFlow()?"50% knowledge demand + 50% mapped element flow\n":"Knowledge demand only · no mapped observations yet\n");
        for(AxonPlanner.Share share:allocation.shares())out.append(String.format(Locale.ROOT,"%.1f%%  %s\n",100*share.fraction(),share.topic()));
        return out.toString();
    }
}
