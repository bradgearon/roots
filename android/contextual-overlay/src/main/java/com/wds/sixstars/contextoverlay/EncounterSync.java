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
 * One client pull, then one publisher pull; full Root graph requires explicit publication scope. */
final class EncounterSync {
    static final int PAGE=32,MAX_ITEMS=8,MAX_IDS=64;
    private EncounterChannel channel;
    private final java.security.KeyPair identity;
    private final boolean directLan;
    private EncounterLanUpgrade.Upgrade upgrade;
    private final java.util.function.Consumer<String> progress;
    private final java.util.function.Consumer<java.io.Closeable> trackTransport;
    private String transport="Bluetooth";
    private boolean peerBulk,peerChunks;
    private KnowledgeManifest offeredManifest,heldManifest;
    private int skippedParts;
    private int maxPages=3;
    String transport(){return transport;}
    void close(){if(upgrade!=null)try{upgrade.close();}catch(java.io.IOException ignored){}}
    boolean completedReceive(){return complete;}
    int remoteTotal(){return remoteTotal;}
    private void report(){progress.accept("Receiving knowledge · "+transport+" · "+received+" received · "+sent+" sent · "+examined+" checked"+(peerChunks?" · "+skippedParts+" unchanged partitions skipped":"")+(remoteTotal<0?"":" · "+remoteTotal+" offered"));}
    private final EncounterBindings bindings;
    private final TreeRuntime tree;
    private final ExternalKnowledgeStore external;
    private final BooleanSupplier active;
    private final long epoch;
    private final Context context;
    private final boolean automaticScope,fullScope;
    private final NavigableMap<String,MeshItem> catalog;
    private java.util.Set<String> pendingReceipts=java.util.Collections.emptySet();
    private String root;
    private boolean peerGraph;
    private int maxItems=8,maxIds=64;
    private EncounterBindings.Binding binding;
    private int received,sent,remoteTotal=-1,examined,known;
    private boolean complete,remotePageSeen,peerMore;
    private String stage="Preparing public catalog";
    String stage(){return stage;}
    boolean pending(){return !complete||peerMore;}
    String summary(){
        String remote=remoteTotal<0?(remotePageSeen?"peer inventory total unknown":"no peer inventory"):"peer offers "+remoteTotal;
        if(complete&&received==0&&remoteTotal==0)return "Peer offers 0 Root items · local offers "+catalog.size()+(peerGraph?" · check peer Root sharing scope":" · peer needs update for graph exchange");
        if(complete&&received==0&&remoteTotal!=0)return (peerChunks?"Inventory current · "+skippedParts+" unchanged partitions skipped":"No new items in completed scan · "+known+" already known")+" · "+remote+" · local offers "+catalog.size();
        return (complete?"Inventory checked":"More inventory pending")+" · "+remote+" · "+examined+" examined · local offers "+catalog.size();
    }
    int received(){return received;}
    int sent(){return sent;}
    boolean firstEncounter(){return binding.encounters()==1;}

    EncounterSync(Context context,EncounterChannel channel,BooleanSupplier active,java.security.KeyPair identity,
                  java.util.function.Consumer<String> progress,java.util.function.Consumer<java.io.Closeable> trackTransport)throws Exception{
        this(context,channel,active,identity,progress,trackTransport,false);
    }
    EncounterSync(Context context,EncounterChannel channel,BooleanSupplier active,java.security.KeyPair identity,
                  java.util.function.Consumer<String> progress,java.util.function.Consumer<java.io.Closeable> trackTransport,boolean directLan)throws Exception{
        this.context=context.getApplicationContext();this.channel=channel;this.active=active;bindings=EncounterBindings.get(context);
        this.identity=identity;this.progress=progress;this.trackTransport=trackTransport;this.directLan=directLan;
        if(directLan)transport="Wi-Fi";
        automaticScope=EncounterSettings.automaticPublication(context);fullScope=EncounterSettings.fullRootGraph(context);
        tree=TreeRuntime.get(context);epoch=tree.captureEpoch();check();
        external=ExternalKnowledgeStore.get(context);
        catalog=new TreeMap<>();
        check();
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
        if(!active.getAsBoolean()||bindings.blocked(channel.peerId)||tree.captureEpoch()!=epoch||automaticScope!=EncounterSettings.automaticPublication(context)||fullScope!=EncounterSettings.fullRootGraph(context))throw new IOException("Encounter stopped or publication scope/Tree changed");
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
        if(client){
            send(capabilities("bind"));JSONObject reply=read();expect(reply,"bound");
            binding=bindings.bind(channel.peerId);negotiate(reply,true);pull();serve();
        }else{
            JSONObject request=read();expect(request,"bind");binding=bindings.bind(channel.peerId);
            send(capabilities("bound"));negotiate(request,false);serve();pull();
        }
        return received;
    }
    private JSONObject capabilities(String op)throws JSONException{
        return message(op).put("root_graph",true).put("bulk_receive_v1",true).put("knowledge_chunks_v1",true).put("lan_upgrade_v1",!directLan);
    }
    private void negotiate(JSONObject hello,boolean client)throws Exception{
        peerGraph=hello.optBoolean("root_graph",false);
        peerBulk=peerGraph&&hello.optBoolean("bulk_receive_v1",false);
        peerChunks=peerBulk&&hello.optBoolean("knowledge_chunks_v1",false);
        if(peerGraph){maxItems=32;maxIds=256;maxPages=9;}
        if(peerBulk){maxItems=128;maxIds=2048;maxPages=65;}
        if(peerBulk&&directLan){maxItems=4096;maxIds=65536;maxPages=2049;}
        if(peerBulk&&!directLan&&hello.optBoolean("lan_upgrade_v1",false)){
            stage="Negotiating local bulk connection";
            progress.accept("Axon bound · negotiating Wi-Fi transfer");
            upgrade=EncounterLanUpgrade.negotiate(context,channel,identity,client,active);
            if(upgrade!=null){
                channel=upgrade.channel();trackTransport.accept(upgrade);transport="Wi-Fi";
                maxItems=4096;maxIds=65536;maxPages=2049;
            }
        }
        stage="Preparing Root inventory";
        catalog.putAll(external.catalog());pendingReceipts=external.pendingSet();
        catalog.putAll(MeshPeer.get(context).encounterCatalog());
        if(!peerGraph)catalog.values().removeIf(item->item.graph()!=null);
        root=inventoryRoot(catalog.navigableKeySet());
        if(peerChunks){
            offeredManifest=new KnowledgeManifest(catalog.navigableKeySet());
            Set<String> held=new HashSet<>(catalog.navigableKeySet());held.removeAll(pendingReceipts);
            heldManifest=new KnowledgeManifest(held);
            maxPages+=KnowledgeManifest.BUCKETS;
        }
        check();
    }
    /** Full-graph peers retain a sorted scan cursor when inventory changes.
     * New items before the cursor are visited on the next sweep. Otherwise
     * reciprocal ingestion changes the root on every encounter and starves
     * large graphs. Older peers retain the original reset behavior. */
    static String inventoryCursor(boolean graph,String knownRoot,String actualRoot,String after){
        return graph||knownRoot.equals(actualRoot)?after:"";
    }
    private void checkpoint(String remoteRoot,String after)throws Exception{
        check();
        binding=new EncounterBindings.Binding(binding.peer(),remoteRoot,after,binding.encounters());bindings.save(binding);
    }
    private void pull()throws Exception{
        if(peerChunks){pullChunks();return;}
        stage="Receiving inventory";
        int inspected=0,transferred=0;
        String after=binding.after(),remoteRoot=binding.root();
        while(inspected<maxIds&&transferred<maxItems){
            send(message("inventory").put("root",remoteRoot).put("after",after));
            JSONObject answer=read();expect(answer,"inventory");remotePageSeen=true;
            String actualRoot=answer.getString("root");
            if(!EncounterBindings.digest(actualRoot))throw new IOException("Invalid inventory root");
            String start=inventoryCursor(peerGraph,remoteRoot,actualRoot,after);
            JSONArray ids=answer.getJSONArray("ids");
            if(ids.length()>PAGE)throw new IOException("Invalid inventory size");
            int total=answer.optInt("total",-1);
            if(total!=-1&&total<ids.length())throw new IOException("Invalid inventory total");
            remoteTotal=total;report();
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
                if(inspected>=maxIds||transferred>=maxItems)break;
                if(!catalog.containsKey(id)){
                    stage="Receiving publication";send(message("want").put("id",id));
                    JSONObject data=read();expect(data,"data");
                    MeshItem item=MeshItem.parse(data.getJSONObject("item"));
                    if((item.graph()!=null&&!peerGraph)||!item.id().equals(id))throw new IOException("Unexpected publication");
                    check();
                    if(!com.wds.sixstars.contextoverlay.tree.NativeTree.available())throw new IOException("Native Tree unavailable");
                    boolean added=external.receive(item,channel.peerId);
                    stage="Retaining external reference in main Tree";
                    check();external.promote(item.id(),tree,epoch);
                    if(added)received++;
                    if((received&7)==0||received==1)report();
                    // Acknowledge only after both durable external receipt and main Tree commit.
                    checkpoint(remoteRoot,id);send(message("ack").put("id",id));transferred++;
                }else{
                    if(pendingReceipts.remove(id))external.promote(id,tree,epoch);
                    known++;
                }
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
    private List<MeshItem> receiveChunk(List<String> requested)throws Exception{
        send(message("want_chunk").put("ids",new JSONArray(requested)));
        JSONObject header=read();expect(header,"chunk");
        check();byte[] wire=channel.receive();check();
        List<MeshItem> items=KnowledgeChunk.unpack(wire,header.getString("codec"),header.getString("sha256"),
            header.getInt("raw_size"),requested,header.getInt("count"));
        stage="Retaining verified chunk";
        check();received+=external.retainBatch(items,channel.peerId,tree,epoch);
        for(MeshItem item:items){heldManifest.add(item.id());pendingReceipts.remove(item.id());}
        check();send(message("chunk_ack").put("sha256",header.getString("sha256")).put("count",items.size()));
        return items;
    }
    private void pullChunks()throws Exception{
        stage="Comparing knowledge manifests";
        send(message("manifest"));
        JSONObject reply=read();expect(reply,"manifest");
        List<KnowledgeManifest.Part> remote=KnowledgeManifest.parse(reply.getJSONArray("parts"));
        remoteTotal=remote.stream().mapToInt(KnowledgeManifest.Part::count).sum();remotePageSeen=true;
        Map<Integer,KnowledgeManifest.Checkpoint> saved=external.inventoryProgress(channel.peerId);
        int inspected=0,transferred=0;boolean allComplete=true;
        for(int bucket=0;bucket<KnowledgeManifest.BUCKETS;bucket++){
            check();
            KnowledgeManifest.Part wantedPart=remote.get(bucket),localPart=heldManifest.part(bucket);
            KnowledgeManifest.Checkpoint prior=saved.get(bucket);
            // Local additions may invalidate a checkpoint for one partition,
            // but never force other completed partitions to be scanned again.
            if(wantedPart.count()==0||wantedPart.equals(localPart)||(prior!=null&&prior.complete()&&prior.matches(wantedPart,localPart))){
                skippedParts++;continue;
            }
            if(inspected>=maxIds||transferred>=maxItems){allComplete=false;break;}
            String after=prior!=null&&!prior.complete()&&prior.matches(wantedPart,localPart)?prior.after():"";
            boolean done=false;
            while(inspected<maxIds&&transferred<maxItems){
                stage="Reading changed knowledge partition";
                send(message("inventory").put("bucket",bucket).put("root",wantedPart.root()).put("after",after));
                JSONObject answer=read();expect(answer,"inventory");
                if(answer.getInt("bucket")!=bucket||!wantedPart.root().equals(answer.getString("root"))||answer.getInt("total")!=wantedPart.count())
                    throw new IOException("Manifest partition changed during session");
                JSONArray ids=answer.getJSONArray("ids");boolean more=answer.getBoolean("more");
                if(ids.length()>PAGE||(more&&ids.length()==0))throw new IOException("Invalid inventory page");
                List<String> page=new ArrayList<>();String previous=after;
                for(int i=0;i<ids.length();i++){
                    String id=ids.getString(i);
                    if(KnowledgeManifest.bucket(id)!=bucket||id.compareTo(previous)<=0)throw new IOException("Invalid partition order");
                    page.add(id);previous=id;
                }
                int position=0;
                while(position<page.size()&&inspected<maxIds&&transferred<maxItems){
                    List<String> wanted=new ArrayList<>();
                    int end=position;
                    while(end<page.size()&&inspected+end-position<maxIds&&wanted.size()<Math.min(KnowledgeChunk.MAX_ITEMS,maxItems-transferred)){
                        String id=page.get(end++);
                        if(!heldManifest.has(id))wanted.add(id);
                    }
                    if(end==position)break;
                    if(!wanted.isEmpty()){
                        stage="Receiving compressed chunk";
                        List<MeshItem> items=receiveChunk(wanted);transferred+=items.size();
                    }
                    int next=position;
                    while(next<end&&heldManifest.has(page.get(next))){
                        after=page.get(next++);inspected++;examined++;
                    }
                    if(next==position)throw new IOException("Chunk made no progress");
                    position=next;
                    external.saveInventoryProgress(channel.peerId,bucket,wantedPart,heldManifest.part(bucket),after,false);
                    report();
                }
                if(position==page.size()&&!more){
                    external.saveInventoryProgress(channel.peerId,bucket,wantedPart,heldManifest.part(bucket),"",true);
                    done=true;break;
                }
                if(position<page.size())break;
            }
            if(!done){allComplete=false;break;}
        }
        complete=allComplete;
        send(message("release").put("more_pending",!complete));
        report();
    }
    private void serve()throws Exception{
        stage="Serving public inventory";
        int pages=0,items=0;Set<String> offered=new HashSet<>();
        while(true){
            JSONObject request=read();String op=request.getString("op");
            if("release".equals(op)){peerMore=request.optBoolean("more_pending",false);return;}
            if("manifest".equals(op)&&peerChunks){
                if(++pages>maxPages)throw new IOException("Manifest budget exceeded");
                send(message("manifest").put("parts",offeredManifest.json()));
            }else if("inventory".equals(op)){
                if(++pages>maxPages)throw new IOException("Inventory budget exceeded");
                String after=request.getString("after"),knownRoot=request.getString("root");
                if((!after.isEmpty()&&!EncounterBindings.digest(after))||(!knownRoot.isEmpty()&&!EncounterBindings.digest(knownRoot)))
                    throw new IOException("Invalid inventory cursor");
                int bucket=request.optInt("bucket",-1);
                Collection<String> available;
                String inventoryRoot=root;int total=catalog.size();
                if(peerChunks&&request.has("bucket")){
                    KnowledgeManifest.checkBucket(bucket);KnowledgeManifest.Part part=offeredManifest.part(bucket);
                    if(!part.root().equals(knownRoot))throw new IOException("Unexpected manifest fingerprint");
                    available=offeredManifest.after(bucket,after);inventoryRoot=part.root();total=part.count();
                }else{
                    after=inventoryCursor(peerGraph,knownRoot,root,after);available=catalog.tailMap(after,false).keySet();
                }
                JSONArray ids=new JSONArray();boolean more=false;offered.clear();
                for(String id:available){
                    if(ids.length()==PAGE){more=true;break;}
                    ids.put(id);offered.add(id);
                }
                JSONObject inventory=message("inventory").put("root",inventoryRoot).put("ids",ids).put("more",more).put("total",total);
                if(peerChunks&&request.has("bucket"))inventory.put("bucket",bucket);
                send(inventory);
            }else if("want_chunk".equals(op)&&peerChunks){
                JSONArray ids=request.getJSONArray("ids");
                if(ids.length()<1||ids.length()>KnowledgeChunk.MAX_ITEMS||items+ids.length()>maxItems)throw new IOException("Invalid chunk request");
                List<MeshItem> wanted=new ArrayList<>();Set<String> unique=new HashSet<>();
                for(int i=0;i<ids.length();i++){
                    String id=ids.getString(i);
                    if(!unique.add(id)||!offered.contains(id)||!catalog.containsKey(id))throw new IOException("Invalid chunk member request");
                    wanted.add(catalog.get(id));
                }
                KnowledgeChunk.Packed chunk=KnowledgeChunk.pack(wanted);
                send(message("chunk").put("codec",chunk.codec()).put("sha256",chunk.sha()).put("raw_size",chunk.rawSize()).put("count",chunk.count()));
                check();channel.send(chunk.bytes());
                JSONObject ack=read();expect(ack,"chunk_ack");
                if(!chunk.sha().equals(ack.getString("sha256"))||chunk.count()!=ack.getInt("count"))throw new IOException("Invalid chunk receipt");
                for(int i=0;i<chunk.count();i++)offered.remove(wanted.get(i).id());
                items+=chunk.count();sent+=chunk.count();report();
            }else if("want".equals(op)){
                String id=request.getString("id");
                if(++items>maxItems||!offered.remove(id)||!catalog.containsKey(id))throw new IOException("Invalid or excessive want");
                send(message("data").put("item",catalog.get(id).json()));
                JSONObject ack=read();expect(ack,"ack");if(!id.equals(ack.getString("id")))throw new IOException("Invalid receipt");sent++;if((sent&7)==0||sent==1)report();
            }else throw new IOException("Unsupported encounter operation");
        }
    }
}
