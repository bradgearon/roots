package com.wds.sixstars.contextoverlay;

import android.content.Context;
import com.wds.sixstars.contextoverlay.tree.TreeRuntime;
import org.json.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Symmetric, content-addressed public publication exchange over REP/1.
 * One client pull, then one publisher pull; never sends runtime prompts or raw captures. */
final class EncounterSync {
    static final int PAGE=32,MAX_ITEMS=8,MAX_IDS=64;
    private final EncounterChannel channel;
    private final EncounterBindings bindings;
    private final TreeRuntime tree;
    private final ExternalKnowledgeStore external;
    private final BooleanSupplier active;
    private final long epoch;
    private final NavigableMap<String,MeshItem> catalog;
    private final String root;
    private EncounterBindings.Binding binding;
    private int received,sent,remoteTotal=-1,examined,known;
    private boolean complete,remotePageSeen,peerMore;
    private String stage="Preparing public catalog";
    String stage(){return stage;}
    boolean pending(){return !complete||peerMore;}
    String summary(){
        String remote=remoteTotal<0?(remotePageSeen?"peer inventory total unknown":"no peer inventory"):"peer offers "+remoteTotal;
        if(complete&&received==0&&remoteTotal==0)return "Peer offers 0 published items · local offers "+catalog.size();
        if(complete&&received==0&&remoteTotal!=0)return "No new items in completed scan · "+remote+" · "+known+" already known · local offers "+catalog.size();
        return (complete?"Inventory checked":"More inventory pending")+" · "+remote+" · "+examined+" examined · local offers "+catalog.size();
    }
    int received(){return received;}
    int sent(){return sent;}
    boolean firstEncounter(){return binding.encounters()==1;}

    EncounterSync(Context context,EncounterChannel channel,BooleanSupplier active)throws Exception{
        this.channel=channel;this.active=active;bindings=EncounterBindings.get(context);
        tree=TreeRuntime.get(context);epoch=tree.captureEpoch();check();
        external=ExternalKnowledgeStore.get(context);
        catalog=new TreeMap<>(external.catalog());
        catalog.putAll(MeshPeer.get(context).encounterCatalog());
        root=inventoryRoot(catalog.navigableKeySet());check();
        // Persist after the authenticated application bind is accepted, not merely after opening the radio.
    }
    static String inventoryRoot(Collection<String> sortedIds)throws Exception{
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        for(String id:sortedIds){
            if(!EncounterBindings.digest(id))throw new IOException("Invalid publication digest");
            hash.update(id.getBytes(StandardCharsets.US_ASCII));
        }
        return MeshItem.hex(hash.digest());
    }
    private void check()throws IOException{
        if(!active.getAsBoolean()||bindings.blocked(channel.peerId)||tree.captureEpoch()!=epoch)throw new IOException("Encounter stopped or Tree changed");
    }
    private void send(JSONObject message)throws Exception{
        check();channel.send(message.toString().getBytes(StandardCharsets.UTF_8));
    }
    private JSONObject read()throws Exception{
        check();byte[] bytes=channel.receive();check();return new JSONObject(new String(bytes,StandardCharsets.UTF_8));
    }
    private static JSONObject message(String op)throws JSONException{return new JSONObject().put("op",op);}
    private static void expect(JSONObject message,String op)throws IOException{
        if(!op.equals(message.optString("op")))throw new IOException("Unexpected encounter operation");
    }
    int exchange(boolean client)throws Exception{
        // Both peers have proved their key and persisted a binding before exchange.
        stage="Binding handshake";
        if(client){send(message("bind"));expect(read(),"bound");binding=bindings.bind(channel.peerId);pull();serve();}
        else{expect(read(),"bind");binding=bindings.bind(channel.peerId);send(message("bound"));serve();pull();}
        return received;
    }
    private void checkpoint(String remoteRoot,String after)throws Exception{
        check();
        binding=new EncounterBindings.Binding(binding.peer(),remoteRoot,after,binding.encounters());bindings.save(binding);
    }
    private void pull()throws Exception{
        stage="Receiving inventory";
        int inspected=0,transferred=0;
        String after=binding.after(),remoteRoot=binding.root();
        while(inspected<MAX_IDS&&transferred<MAX_ITEMS){
            send(message("inventory").put("root",remoteRoot).put("after",after));
            JSONObject answer=read();expect(answer,"inventory");remotePageSeen=true;
            String actualRoot=answer.getString("root");
            if(!EncounterBindings.digest(actualRoot))throw new IOException("Invalid inventory root");
            String start=actualRoot.equals(remoteRoot)?after:"";
            JSONArray ids=answer.getJSONArray("ids");
            if(ids.length()>PAGE)throw new IOException("Invalid inventory size");
            int total=answer.optInt("total",-1);
            if(total!=-1&&total<ids.length())throw new IOException("Invalid inventory total");
            remoteTotal=total;
            // Validate the entire page before committing any cursor.
            List<String> page=new ArrayList<>();String previous=start;
            for(int i=0;i<ids.length();i++){
                String id=ids.getString(i);
                if(!EncounterBindings.digest(id)||id.compareTo(previous)<=0)throw new IOException("Invalid inventory order");
                page.add(id);previous=id;
            }
            boolean more=answer.getBoolean("more");
            if(more&&page.isEmpty())throw new IOException("Invalid inventory continuation");
            remoteRoot=actualRoot;after=start;
            if(!remoteRoot.equals(binding.root())||!after.equals(binding.after()))checkpoint(remoteRoot,after);
            int processed=0;
            for(String id:page){
                if(inspected>=MAX_IDS||transferred>=MAX_ITEMS)break;
                if(!catalog.containsKey(id)){
                    stage="Receiving publication";send(message("want").put("id",id));
                    JSONObject data=read();expect(data,"data");
                    MeshItem item=MeshItem.parse(data.getJSONObject("item"));
                    if(item.graph()!=null||!item.id().equals(id))throw new IOException("Unexpected publication");
                    check();if(external.receive(item,channel.peerId))received++;
                    // Public items stay outside the private Tree; forward only verified publications.
                    checkpoint(remoteRoot,id);send(message("ack").put("id",id));transferred++;
                }else known++;
                after=id;processed++;inspected++;examined++;stage="Receiving inventory";
            }
            if(processed==page.size()&&!more){checkpoint(remoteRoot,"");complete=true;break;}
            // Skip already-held IDs in memory and save only once per page,
            // not a synchronous disk write for each duplicate over Bluetooth.
            if(!after.equals(binding.after()))checkpoint(remoteRoot,after);
            if(processed<page.size())break;
        }
        send(message("release").put("more_pending",!complete));
    }
    private void serve()throws Exception{
        stage="Serving public inventory";
        int pages=0,items=0;Set<String> offered=new HashSet<>();
        while(true){
            JSONObject request=read();String op=request.getString("op");
            if("release".equals(op)){peerMore=request.optBoolean("more_pending",false);return;}
            if("inventory".equals(op)){
                if(++pages>3)throw new IOException("Inventory budget exceeded");
                String after=request.getString("after"),knownRoot=request.getString("root");
                if((!after.isEmpty()&&!EncounterBindings.digest(after))||(!knownRoot.isEmpty()&&!EncounterBindings.digest(knownRoot)))
                    throw new IOException("Invalid inventory cursor");
                if(!root.equals(knownRoot))after="";
                JSONArray ids=new JSONArray();boolean more=false;offered.clear();
                for(String id:catalog.tailMap(after,false).keySet()){
                    if(ids.length()==PAGE){more=true;break;}
                    ids.put(id);offered.add(id);
                }
                send(message("inventory").put("root",root).put("ids",ids).put("more",more).put("total",catalog.size()));
            }else if("want".equals(op)){
                String id=request.getString("id");
                if(++items>MAX_ITEMS||!offered.remove(id)||!catalog.containsKey(id))throw new IOException("Invalid or excessive want");
                send(message("data").put("item",catalog.get(id).json()));
                JSONObject ack=read();expect(ack,"ack");if(!id.equals(ack.getString("id")))throw new IOException("Invalid receipt");sent++;
            }else throw new IOException("Unsupported encounter operation");
        }
    }
}
