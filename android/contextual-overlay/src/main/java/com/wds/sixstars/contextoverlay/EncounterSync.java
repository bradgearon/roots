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
    private final BooleanSupplier active;
    private final long epoch;
    private final NavigableMap<String,MeshItem> catalog;
    private final String root;
    private EncounterBindings.Binding binding;
    private int received,sent;
    int received(){return received;}
    int sent(){return sent;}
    boolean firstEncounter(){return binding.encounters()==1;}

    EncounterSync(Context context,EncounterChannel channel,BooleanSupplier active)throws Exception{
        this.channel=channel;this.active=active;bindings=EncounterBindings.get(context);
        tree=TreeRuntime.get(context);epoch=tree.captureEpoch();check();
        catalog=new TreeMap<>(MeshPeer.get(context).encounterCatalog());
        root=inventoryRoot(catalog.navigableKeySet());check();
        binding=bindings.bind(channel.peerId);
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
        if(client){send(message("bind"));expect(read(),"bound");pull();serve();}
        else{expect(read(),"bind");send(message("bound"));serve();pull();}
        return received;
    }
    private void checkpoint(String remoteRoot,String after)throws Exception{
        check();
        binding=new EncounterBindings.Binding(binding.peer(),remoteRoot,after,binding.encounters());bindings.save(binding);
    }
    private void pull()throws Exception{
        int inspected=0,transferred=0;
        String after=binding.after(),remoteRoot=binding.root();
        while(inspected<MAX_IDS&&transferred<MAX_ITEMS){
            send(message("inventory").put("root",remoteRoot).put("after",after));
            JSONObject answer=read();expect(answer,"inventory");
            String actualRoot=answer.getString("root");
            if(!EncounterBindings.digest(actualRoot))throw new IOException("Invalid inventory root");
            String start=actualRoot.equals(remoteRoot)?after:"";
            JSONArray ids=answer.getJSONArray("ids");
            if(ids.length()>PAGE)throw new IOException("Invalid inventory size");
            // Validate the entire page before committing any cursor.
            List<String> page=new ArrayList<>();String previous=start;
            for(int i=0;i<ids.length();i++){
                String id=ids.getString(i);
                if(!EncounterBindings.digest(id)||id.compareTo(previous)<=0)throw new IOException("Invalid inventory order");
                page.add(id);previous=id;
            }
            boolean more=answer.getBoolean("more");
            if(more&&page.isEmpty())throw new IOException("Invalid inventory continuation");
            remoteRoot=actualRoot;after=start;checkpoint(remoteRoot,after);
            int processed=0;
            for(String id:page){
                if(inspected>=MAX_IDS||transferred>=MAX_ITEMS)break;
                if(!catalog.containsKey(id)){
                    send(message("want").put("id",id));
                    JSONObject data=read();expect(data,"data");
                    MeshItem item=MeshItem.parse(data.getJSONObject("item"));
                    if(item.graph()!=null||!item.id().equals(id))throw new IOException("Unexpected publication");
                    check();item.store(tree,epoch);received++;
                    // Do not advertise arbitrary graph records from a stranger.
                    // Verified explicit publications enter the same public Tree catalog.
                    checkpoint(remoteRoot,id);send(message("ack").put("id",id));transferred++;
                }else checkpoint(remoteRoot,id);
                after=id;processed++;inspected++;
            }
            if(processed==page.size()&&!more){checkpoint(remoteRoot,"");break;}
            if(processed<page.size())break;
        }
        send(message("release"));
    }
    private void serve()throws Exception{
        int pages=0,items=0;Set<String> offered=new HashSet<>();
        while(true){
            JSONObject request=read();String op=request.getString("op");
            if("release".equals(op))return;
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
                send(message("inventory").put("root",root).put("ids",ids).put("more",more));
            }else if("want".equals(op)){
                String id=request.getString("id");
                if(++items>MAX_ITEMS||!offered.remove(id)||!catalog.containsKey(id))throw new IOException("Invalid or excessive want");
                send(message("data").put("item",catalog.get(id).json()));
                JSONObject ack=read();expect(ack,"ack");if(!id.equals(ack.getString("id")))throw new IOException("Invalid receipt");sent++;
            }else throw new IOException("Unsupported encounter operation");
        }
    }
}
