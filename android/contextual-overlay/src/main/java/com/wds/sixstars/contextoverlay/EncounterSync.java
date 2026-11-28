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
    private ArchiveSizing sizing;
    private static final int ARCHIVE_PAGE=512;
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
            binding=bindings.bind(channel.peerId);retainFootprints(reply);negotiate(reply,true);pull();serve();
        }else{
            JSONObject request=read();expect(request,"bind");binding=bindings.bind(channel.peerId);
            retainFootprints(request);send(capabilities("bound"));negotiate(request,false);serve();pull();
        }
        return received;
    }
    private JSONObject capabilities(String op)throws JSONException{
        JSONObject hello=message(op).put("root_graph",true).put("bulk_receive_v1",true).put("knowledge_archives_v1",true).put("archive_channel_v2",true).put("lan_tls_v1",!directLan);
        try{hello.put("footprints_v1",PublicFootprints.get(context).offer(identity,channel.peerId));}
        catch(Exception failure){android.util.Log.w("RootsMesh","Public footprint offer unavailable");}
        return hello;
    }
    private void retainFootprints(JSONObject hello){
        try{check();PublicFootprints.get(context).receive(hello.optJSONArray("footprints_v1"),channel.peerId,identity);}
        catch(Exception failure){android.util.Log.w("RootsMesh","Public footprint retention unavailable");}
    }
    private void negotiate(JSONObject hello,boolean client)throws Exception{
        peerGraph=hello.optBoolean("root_graph",false);
        peerBulk=peerGraph&&hello.optBoolean("bulk_receive_v1",false);
        peerChunks=peerBulk&&hello.optBoolean("knowledge_archives_v1",false)&&hello.optBoolean("archive_channel_v2",false);
        if(peerGraph){maxItems=32;maxIds=256;maxPages=9;}
        if(peerBulk){maxItems=128;maxIds=2048;maxPages=65;}
        if(peerBulk&&directLan){maxItems=4096;maxIds=65536;maxPages=2049;}
        if(peerBulk&&!directLan&&hello.optBoolean("lan_tls_v1",false)){
            stage="Negotiating local bulk connection";
            progress.accept("Axon bound · negotiating Wi-Fi transfer");
            upgrade=EncounterLanUpgrade.negotiate(context,channel,identity,client,active);
            if(upgrade!=null){
                channel=upgrade.channel();trackTransport.accept(upgrade);transport="Wi-Fi";
                maxItems=4096;maxIds=65536;maxPages=2049;
            }
        }
        // The selected connection already protects the archive. No payload cipher.
        stage="Preparing Root inventory";
        catalog.putAll(external.catalog());pendingReceipts=external.pendingSet();
        catalog.putAll(MeshPeer.get(context).encounterCatalog());
        if(!peerGraph)catalog.values().removeIf(item->item.graph()!=null);
        if(!peerChunks){
            Iterator<MeshItem> legacy=catalog.values().iterator();
            while(legacy.hasNext())if(!RootGraphExchange.fitsFrame(legacy.next()))legacy.remove();
        }
        root=inventoryRoot(catalog.navigableKeySet());
        if(peerChunks){
            // A persisted receipt that has not reached the main Tree is
            // recovered locally first; do not advertise it as retained.
            catalog.keySet().removeAll(pendingReceipts);
            offeredManifest=new KnowledgeManifest(catalog.navigableKeySet());
            heldManifest=new KnowledgeManifest(catalog.navigableKeySet());
            boolean wifi="Wi-Fi".equals(transport);
            sizing=new ArchiveSizing(context,channel.peerId,wifi);
            maxItems=wifi?65536:8192;
            maxIds=wifi?262144:32768;
            maxPages=maxIds+KnowledgeManifest.BUCKETS+1;
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
    private void sendBytes(byte[] bytes)throws Exception{
        check();channel.sendPublicBytes(bytes,KnowledgeChunk.MAX_WIRE);check();
    }
    private byte[] readBytes(int size,int limit)throws Exception{
        check();byte[] bytes=channel.receivePublicBytes(size,limit);check();return bytes;
    }
    private List<MeshItem> receiveArchive(List<String> requested,ArchiveSizing.Budget budget)throws Exception{
        sizing.begin(requested.size());
        long start=android.os.SystemClock.elapsedRealtime();
        stage="Receiving compressed archive";
        progress.accept("Requesting public archive · "+requested.size()+" records · "+(budget.estimatedMs()<0?"measuring device performance":budget.estimatedMs()+" ms estimated for "+budget.records()+" records"));
        send(message("want_archive").put("count",requested.size()).put("raw_limit",budget.rawBytes()).put("wire_limit",budget.wireBytes()));
        sendBytes(KnowledgeChunk.encodeIds(requested));
        JSONObject header=read();expect(header,"archive");
        int raw=header.getInt("raw_size"),wireSize=header.getInt("wire_size"),count=header.getInt("count");
        if(raw<2||raw>budget.rawBytes()||count<1||count>requested.size())throw new IOException("Invalid archive header");
        byte[] wire=readBytes(wireSize,budget.wireBytes());
        List<MeshItem> items=KnowledgeChunk.unpack(wire,header.getString("codec"),header.getString("sha256"),raw,requested,count,budget.rawBytes(),budget.wireBytes());
        stage="Retaining verified archive";check();
        received+=external.retainBatch(items,channel.peerId,tree,epoch);
        for(MeshItem item:items){heldManifest.add(item.id());pendingReceipts.remove(item.id());}
        check();send(message("archive_ack").put("sha256",header.getString("sha256")).put("count",items.size()));
        long elapsed=Math.max(1,android.os.SystemClock.elapsedRealtime()-start);
        sizing.observe(items.size(),raw,wireSize,elapsed);
        ArchiveSizing.Budget next=sizing.budget();
        progress.accept("Archive · "+items.size()+" records · "+wireSize+" compressed/wire bytes · "+elapsed+" ms · next target "+next.records()+" records (~"+next.estimatedMs()+" ms)");
        return items;
    }
    private record Scan(int bucket,KnowledgeManifest.Part remote,List<String> ids,boolean last){}
    private record CursorProgress(KnowledgeManifest.Part remote,String after,boolean done){}
    private void saveScans(List<Scan> scans)throws Exception{
        Map<Integer,CursorProgress> progress=new LinkedHashMap<>();
        for(Scan scan:scans){
            String after="";int n=0;
            for(String id:scan.ids()){
                if(!heldManifest.has(id))break;after=id;n++;
            }
            boolean done=n==scan.ids().size()&&scan.last();
            if(n>0||done)progress.put(scan.bucket(),new CursorProgress(scan.remote(),done?"":after,done));
            if(n!=scan.ids().size())break;
        }
        check();
        external.saveInventoryProgressBatch(channel.peerId,progress.entrySet().stream().map(entry->{
            var value=entry.getValue();
            try{return new ExternalKnowledgeStore.InventoryUpdate(entry.getKey(),value.remote(),heldManifest.part(entry.getKey()),value.after(),value.done());}
            catch(Exception failure){throw new IllegalStateException("Cannot checkpoint manifest",failure);}
        }).collect(java.util.stream.Collectors.toList()));
    }
    private int flushArchives(List<Scan> scans,List<String> wanted)throws Exception{
        int offset=0;
        if(wanted.isEmpty())saveScans(scans);
        while(offset<wanted.size()){
            ArchiveSizing.Budget budget=sizing.budget();
            int end=Math.min(wanted.size(),offset+budget.records());
            List<MeshItem> items=receiveArchive(new ArrayList<>(wanted.subList(offset,end)),budget);
            offset+=items.size();saveScans(scans);
        }
        scans.clear();wanted.clear();report();return offset;
    }
    private void pullChunks()throws Exception{
        stage="Comparing knowledge manifests";
        send(message("manifest"));
        JSONObject reply=read();expect(reply,"manifest");
        List<KnowledgeManifest.Part> remote=KnowledgeManifest.parse(reply.getJSONArray("parts"));
        remoteTotal=0;for(var part:remote)remoteTotal+=part.count();remotePageSeen=true;
        Map<Integer,KnowledgeManifest.Checkpoint> saved=external.inventoryProgress(channel.peerId);
        int inspected=0,transferred=0,windowIds=0;
        boolean allComplete=true;
        List<Scan> scans=new ArrayList<>();List<String> wanted=new ArrayList<>();
        outer: for(int bucket=0;bucket<KnowledgeManifest.BUCKETS;bucket++){
            check();
            KnowledgeManifest.Part wantedPart=remote.get(bucket),localPart=heldManifest.part(bucket);
            KnowledgeManifest.Checkpoint prior=saved.get(bucket);
            if(wantedPart.count()==0||wantedPart.equals(localPart)||(prior!=null&&prior.complete()&&prior.matches(wantedPart,localPart))){
                skippedParts++;continue;
            }
            String after=prior!=null&&!prior.complete()&&prior.matches(wantedPart,localPart)?prior.after():"";
            while(true){
                int target=Math.min(sizing.budget().records(),maxItems-transferred);
                if(wanted.size()>=target||windowIds>=16384){
                    transferred+=flushArchives(scans,wanted);windowIds=0;target=Math.min(sizing.budget().records(),maxItems-transferred);
                }
                if(inspected>=maxIds||target<=0){allComplete=false;break outer;}
                int limit=Math.min(ARCHIVE_PAGE,Math.min(maxIds-inspected,target-wanted.size()));
                if(limit<1)throw new IOException("Invalid archive inventory budget");
                stage="Reading changed knowledge partition";
                send(message("inventory").put("bucket",bucket).put("root",wantedPart.root()).put("after",after).put("limit",limit));
                JSONObject answer=read();expect(answer,"inventory");
                if(answer.getInt("bucket")!=bucket||!wantedPart.root().equals(answer.getString("root"))||answer.getInt("total")!=wantedPart.count())
                    throw new IOException("Manifest partition changed during session");
                JSONArray ids=answer.getJSONArray("ids");boolean more=answer.getBoolean("more");
                if(ids.length()>limit||(more&&ids.length()==0))throw new IOException("Invalid inventory page");
                List<String> page=new ArrayList<>();String previous=after;
                for(int i=0;i<ids.length();i++){
                    String id=ids.getString(i);
                    if(KnowledgeManifest.bucket(id)!=bucket||id.compareTo(previous)<=0)throw new IOException("Invalid partition order");
                    page.add(id);previous=id;
                    if(!heldManifest.has(id))wanted.add(id);
                }
                scans.add(new Scan(bucket,wantedPart,page,!more));
                inspected+=page.size();examined+=page.size();windowIds+=page.size();after=previous;
                if(!more)break;
            }
        }
        if(!scans.isEmpty())transferred+=flushArchives(scans,wanted);
        complete=allComplete;
        send(message("release").put("more_pending",!complete));report();
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
                int limit=peerChunks&&request.has("bucket")?request.optInt("limit",ARCHIVE_PAGE):PAGE;
                if(limit<1||limit>ARCHIVE_PAGE)throw new IOException("Invalid inventory page limit");
                JSONArray ids=new JSONArray();boolean more=false;offered.clear();
                for(String id:available){
                    if(ids.length()==limit){more=true;break;}
                    ids.put(id);offered.add(id);
                }
                JSONObject inventory=message("inventory").put("root",inventoryRoot).put("ids",ids).put("more",more).put("total",total);
                if(peerChunks&&request.has("bucket"))inventory.put("bucket",bucket);
                send(inventory);
            }else if("want_archive".equals(op)&&peerChunks){
                int count=request.getInt("count"),rawLimit=request.getInt("raw_limit"),wireLimit=request.getInt("wire_limit");
                if(count<1||count>KnowledgeChunk.MAX_ITEMS||count>maxItems-items)throw new IOException("Invalid archive request");
                KnowledgeChunk.limits(rawLimit,wireLimit);
                List<String> ids=KnowledgeChunk.decodeIds(readBytes(count*32,KnowledgeChunk.MAX_ITEMS*32),count);
                List<MeshItem> wanted=new ArrayList<>(count);
                // Any ID in the frozen, authorized public catalog may be
                // requested; the archive can span many inventory partitions.
                for(String id:ids){
                    MeshItem item=catalog.get(id);if(item==null)throw new IOException("Record not offered");wanted.add(item);
                }
                KnowledgeChunk.Packed archive=KnowledgeChunk.pack(wanted,rawLimit,wireLimit);
                send(message("archive").put("codec",archive.codec()).put("sha256",archive.sha()).put("raw_size",archive.rawSize())
                    .put("wire_size",archive.bytes().length).put("count",archive.count()));
                sendBytes(archive.bytes());
                JSONObject ack=read();expect(ack,"archive_ack");
                if(!archive.sha().equals(ack.getString("sha256"))||archive.count()!=ack.getInt("count"))throw new IOException("Invalid archive receipt");
                items+=archive.count();sent+=archive.count();report();
            }else if("want".equals(op)){
                String id=request.getString("id");
                if(++items>maxItems||!offered.remove(id)||!catalog.containsKey(id))throw new IOException("Invalid or excessive want");
                send(message("data").put("item",catalog.get(id).json()));
                JSONObject ack=read();expect(ack,"ack");if(!id.equals(ack.getString("id")))throw new IOException("Invalid receipt");sent++;if((sent&7)==0||sent==1)report();
            }else throw new IOException("Unsupported encounter operation");
        }
    }
}
