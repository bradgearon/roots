package com.wds.sixstars.contextoverlay.tree;

import android.content.Context;
import android.content.SharedPreferences;
import com.wds.sixstars.contextoverlay.OverlayPreferences;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Host threading, permission and publication boundary; all Tree storage is C++. */
public final class TreeRuntime {
    private static final int QUEUE_LIMIT=2048, BATCH_SIZE=64;
    private static TreeRuntime instance;
    public static synchronized TreeRuntime get(Context context) {
        if(instance==null)instance=new TreeRuntime(context.getApplicationContext());
        return instance;
    }
    private final SharedPreferences prefs;
    private final OverlayPreferences overlay;
    private final File directory, snapshot, diagnostics;
    private final Object statsLock=new Object();
    private final AtomicBoolean statsScheduled=new AtomicBoolean();
    private final AtomicLong discovered=new AtomicLong(), accepted=new AtomicLong(),
        queued=new AtomicLong(), queueDrops=new AtomicLong(), cancelled=new AtomicLong(),
        invalidText=new AtomicLong(), writeAttempts=new AtomicLong(), writeFailures=new AtomicLong(),
        noHome=new AtomicLong(), privateScans=new AtomicLong();
    private final ScheduledExecutorService writer=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"RootsTreeWriter"));
    private final ScheduledExecutorService compiler=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"RootsTreeCompiler"));
    private final ArrayBlockingQueue<Queued> queue=new ArrayBlockingQueue<>(QUEUE_LIMIT);
    private final HashSet<String> pending=new HashSet<>();
    // Only successfully committed recent observations; SQLite remains authoritative.
    private final java.util.LinkedHashMap<String,Boolean> committed=new java.util.LinkedHashMap<>(4096,0.75f,true);
    private final Object intakeLock=new Object(), mutationLock=new Object(), openLock=new Object();
    private final AtomicBoolean draining=new AtomicBoolean(), compiling=new AtomicBoolean();
    private final AtomicLong captureEpoch=new AtomicLong(), snapshotEpoch=new AtomicLong(), dirty=new AtomicLong();
    private final AtomicLong inserted=new AtomicLong(), duplicate=new AtomicLong(), dropped=new AtomicLong(), scans=new AtomicLong(), rejected=new AtomicLong(), truncated=new AtomicLong();
    private volatile NativeTree nativeTree;
    private volatile boolean clearing;
    private volatile String snapshotStatus="Not compiled", error="";
    private volatile String captureReaderState="Waiting for accessibility service";
    private final AtomicLong lastVisibleScanAt=new AtomicLong(), lastAcceptedScanAt=new AtomicLong(),
        lastNewCaptureAt=new AtomicLong(), lastVisibleCount=new AtomicLong();
    /** Live service status contains no captured text. Historical timestamps survive a process restart. */
    public void noteCaptureReaderState(String state){captureReaderState=state;}
    private final KnowledgeWave wave=new KnowledgeWave();
    private volatile KnowledgeWave.Field coordination=KnowledgeWave.Field.empty();
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> waveListeners=new java.util.concurrent.CopyOnWriteArrayList<>();
    public KnowledgeWave.Field coordination(){return clearing?KnowledgeWave.Field.empty():coordination;}
    public void addWaveListener(Runnable listener){waveListeners.addIfAbsent(listener);}
    public void removeWaveListener(Runnable listener){waveListeners.remove(listener);}
    private void notifyWave(){for(Runnable listener:waveListeners)try{listener.run();}catch(RuntimeException ignored){}}
    // Only the existing snapshot compiler drives this observer. No file polling.
    private void observeWave(long epoch,long revision,String language){
        try{
            KnowledgeGguf.Snapshot graph=KnowledgeGguf.read(snapshot);
            if(graph.revision()!=revision||!graph.language().equals(language)||!graph.scope().equals("localized-private"))throw new IllegalStateException("Unexpected knowledge projection");
            KnowledgeWave.Field next=wave.update(graph,android.os.SystemClock.elapsedRealtime());
            synchronized(mutationLock){
                if(clearing||epoch!=snapshotEpoch.get()||!language.equals(overlay.targetLanguage()))return;
                coordination=next;
            }
            trace("wave_updated",next.weights().size(),next.edges(),"revision="+revision+" stimulated="+next.stimulated());
            notifyWave();
        }catch(Exception|LinkageError ex){
            synchronized(mutationLock){if(epoch!=snapshotEpoch.get()||clearing)return;coordination=KnowledgeWave.Field.empty();}
            wave.clear();trace("wave_failed",0,0,ex.getClass().getSimpleName());notifyWave();
        }
    }
    private void emptyWave(){wave.clear();coordination=KnowledgeWave.Field.empty();notifyWave();}
    private static final class Queued {
        final NativeTree.Observation observation; final long epoch;
        Queued(NativeTree.Observation o,long e){observation=o;epoch=e;}
    }
    private TreeRuntime(Context context) {
        prefs=context.getSharedPreferences("roots_scroll_tree",Context.MODE_PRIVATE);
        overlay=new OverlayPreferences(context);
        directory=new File(context.getNoBackupFilesDir(),"roots-tree");
        snapshot=new File(directory,"tree.gguf");
        // First upgrade from the old lossless GGUF; originals stay in SQLite.
        if(!prefs.getBoolean("localized_snapshot_v1",false)){
            try{Files.deleteIfExists(snapshot.toPath());prefs.edit().putBoolean("localized_snapshot_v1",true).apply();}
            catch(Exception ignored){}
        }
        diagnostics=new File(context.getFilesDir(),"diagnostics");
        restoreStats();
        // Rebuild the disposable snapshot on a worker after a restart if
        // capture was explicitly enabled previously. No implicit capture consent.
        if(directory.exists()||enabled()){
            requestSnapshot();
            if(!prefs.getBoolean("english_hint_backfill_v1",false))writer.execute(()->backfillEnglish(0,captureEpoch.get()));
        }
    }
    /** Recover previously unlabeled English bodies without modifying originals. */
    private void backfillEnglish(long before,long epoch){
        if(clearing||captureEpoch.get()!=epoch)return;
        try{
            List<NativeTree.Master> page=records("",0,before,100);
            List<NativeTree.Observation> rows=new ArrayList<>();
            for(NativeTree.Master record:page){
                if(!record.key().startsWith("local:x:"))continue;
                NativeTree.Fragment body=record.body();
                if(!body.source().endsWith(".body")||!body.language().isEmpty()||"en".equals(record.localized("en").language()))continue;
                if(!"en".equals(com.wds.sixstars.contextoverlay.KnowledgeLanguage.englishHint(body.body())))continue;
                rows.add(new NativeTree.Observation("record.x.en.localized",record.key(),body.body(),"en",body.at()));
            }
            if(!rows.isEmpty())storeExplicit(rows,epoch);
            if(clearing||captureEpoch.get()!=epoch)return;
            if(page.size()==100){
                long cursor=page.get(page.size()-1).sequence();
                writer.execute(()->backfillEnglish(cursor,epoch));
            }else{
                prefs.edit().putBoolean("english_hint_backfill_v1",true).apply();
                requestSnapshot();
            }
        }catch(Exception|LinkageError ex){trace("language_backfill_failed",0,0,ex.getClass().getSimpleName());}
    }
    private NativeTree tree() {
        synchronized(openLock) {
            if(nativeTree==null) {
                if(!directory.isDirectory()&&!directory.mkdirs())throw new IllegalStateException("Tree directory unavailable");
                nativeTree=new NativeTree(new File(directory,"tree.db").getAbsolutePath());
            }
            return nativeTree;
        }
    }
    private String[] statNames(){return new String[]{"inserted","duplicate","dropped","scans","rejected","bounded",
        "discovered","accepted","queued","queue_drops","cancelled","invalid","write_attempts","write_failures","no_home","private_scans",
        "last_visible_scan_at","last_accepted_scan_at","last_new_capture_at","last_visible_count"};}
    private AtomicLong[] stats(){return new AtomicLong[]{inserted,duplicate,dropped,scans,rejected,truncated,
        discovered,accepted,queued,queueDrops,cancelled,invalidText,writeAttempts,writeFailures,noHome,privateScans,
        lastVisibleScanAt,lastAcceptedScanAt,lastNewCaptureAt,lastVisibleCount};}
    private void restoreStats(){
        String[] names=statNames();AtomicLong[] values=stats();
        for(int i=0;i<names.length;i++)values[i].set(prefs.getLong("stat_"+names[i],0));
        error=prefs.getString("last_tree_error","");
    }
    private void saveStats(){
        synchronized(statsLock){
            SharedPreferences.Editor e=prefs.edit();
            String[] names=statNames();AtomicLong[] values=stats();
            for(int i=0;i<names.length;i++)e.putLong("stat_"+names[i],values[i].get());
            e.putString("last_tree_error",error);
            e.commit();
        }
    }
    private void scheduleStats(){
        if(statsScheduled.compareAndSet(false,true))writer.schedule(()->{
            statsScheduled.set(false);saveStats();
        },1,TimeUnit.SECONDS);
    }
    private void trace(String event,int size,long n,String detail){
        synchronized(statsLock){
            try{
                if(!diagnostics.isDirectory()&&!diagnostics.mkdirs())return;
                File log=new File(diagnostics,"tree-lifecycle.jsonl");
                if(log.length()>512*1024L){
                    File previous=new File(diagnostics,"tree-lifecycle.previous.jsonl");
                    if(previous.exists())previous.delete();
                    if(!log.renameTo(previous))return;
                }
                org.json.JSONObject obj=new org.json.JSONObject();
                obj.put("at_ms",System.currentTimeMillis());obj.put("event",event);
                obj.put("batch",size);obj.put("result",n);obj.put("detail",detail);
                String[] names=statNames();AtomicLong[] values=stats();
                for(int i=0;i<names.length;i++)obj.put(names[i],values[i].get());
                try(java.io.FileOutputStream out=new java.io.FileOutputStream(log,true)){
                    out.write((obj.toString()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }catch(Exception ignored){}
        }
    }
    private static boolean valid(NativeTree.Observation o){
        return o!=null&&o.source()!=null&&!o.source().isEmpty()&&o.body()!=null&&!o.body().isEmpty()
            && o.externalId()!=null&&o.language()!=null&&o.at()>=0
            && o.source().indexOf(0)<0&&o.body().indexOf(0)<0&&o.externalId().indexOf(0)<0&&o.language().indexOf(0)<0
            && o.source().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=256
            && o.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=16384
            && o.externalId().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=512
            && o.language().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=64;
    }
    public boolean enabled(){return NativeTree.available()&&!clearing&&overlay.enabled()&&prefs.getBoolean("capture_enabled",false)&&prefs.getInt("capture_scope_version",0)==2;}
    public long captureEpoch(){return captureEpoch.get();}
    public void setEnabled(boolean value) {
        synchronized(intakeLock) {
            if(value&&(!NativeTree.available()||clearing||!overlay.enabled()))return;
            SharedPreferences.Editor consent=prefs.edit().putBoolean("capture_enabled",value);
            if(value)consent.putInt("capture_scope_version",2);
            consent.apply();
            captureEpoch.incrementAndGet();
            cancelled.addAndGet(queue.size());dropped.addAndGet(queue.size());queue.clear();pending.clear();committed.clear();
        }
        if(value)requestSnapshot();
        writer.execute(()->{trace(value?"capture_enabled":"capture_paused",0,0,"");saveStats();});
    }
    public void reportScan(boolean eligible,boolean incomplete){reportScan(eligible,incomplete,0,false,false);}
    public void reportScan(boolean eligible,boolean incomplete,int found,boolean home,boolean privateSurface){
        scans.incrementAndGet();discovered.addAndGet(found);
        if(!eligible)rejected.incrementAndGet();if(incomplete)truncated.incrementAndGet();
        if(!home)noHome.incrementAndGet();if(privateSurface)privateScans.incrementAndGet();
        scheduleStats();
        long n=scans.get();
        if(n==1||n%25==0)writer.execute(()->trace("scan_summary",found,eligible?1:0,
            "home="+home+" private="+privateSurface+" bounded="+incomplete));
    }
    /** Raw shared-reader snapshots. Bounded scans retain what they actually
     * observed; no Home, language, interest, or post-ID gate. */
    public void acceptVisibleSnapshot(List<NativeTree.Observation> observations,long epoch,
            boolean foregroundX,boolean incomplete){
        if(!enabled() || epoch!=captureEpoch.get())return;
        long now=System.currentTimeMillis();
        lastVisibleScanAt.set(now);lastVisibleCount.set(observations.size());
        if(foregroundX){lastAcceptedScanAt.set(now);captureReaderState="Foreground X scan accepted";}
        else captureReaderState="Foreground changed during scan; discarded";
        scans.incrementAndGet();discovered.addAndGet(observations.size());
        if(incomplete)truncated.incrementAndGet();
        if(!foregroundX)rejected.incrementAndGet();
        scheduleStats();
        long n=scans.get();
        if(n==1||n%25==0)writer.execute(()->trace("visible_scan",observations.size(),foregroundX?1:0,
                "scope=visible_x bounded="+incomplete+" foreground="+foregroundX));
        if(foregroundX)accept(observations,epoch);
    }

    boolean accept(List<NativeTree.Observation> observations,long epoch){
        boolean acceptedAll=true;
        synchronized(intakeLock){
            if(!enabled()||epoch!=captureEpoch.get())return false;
            accepted.addAndGet(observations.size());
            for(NativeTree.Observation o:observations){
                if(!valid(o)){invalidText.incrementAndGet();dropped.incrementAndGet();acceptedAll=false;continue;}
                String key=observationKey(o);
                if(committed.get(key)!=null||!pending.add(key)){duplicate.incrementAndGet();continue;}
                if(!queue.offer(new Queued(o,epoch))){pending.remove(key);dropped.incrementAndGet();queueDrops.incrementAndGet();acceptedAll=false;}
                else queued.incrementAndGet();
            }
        }
        scheduleStats();scheduleDrain();return acceptedAll;
    }
    /** Final translation, never a streamed prefix. Same master as source body. */
    public boolean recordTranslation(String key,String original,String source,String target,String translated,long epoch){
        if(!enabled()||captureEpoch.get()!=epoch||key==null||!key.startsWith("local:x:")||!target.matches("[a-z]{2,3}"))return false;
        long now=System.currentTimeMillis();
        NativeTree.Observation body=new NativeTree.Observation("record.x.body",key,original,source,now);
        NativeTree.Observation text=new NativeTree.Observation("record.x."+target+".translation",key,translated,target,now);
        if(!valid(body)||!valid(text))return false;
        return accept(java.util.Arrays.asList(body,text),epoch);
    }
    public boolean recordLocalized(String key,String original,String target,long epoch){
        if(!enabled()||captureEpoch.get()!=epoch||key==null||!key.startsWith("local:x:")||target==null||!target.matches("[a-z]{2,3}"))return false;
        long now=System.currentTimeMillis();
        NativeTree.Observation body=new NativeTree.Observation("record.x.body",key,original,target,now);
        NativeTree.Observation row=new NativeTree.Observation("record.x."+target+".localized",key,original,target,now);
        return valid(body)&&valid(row)&&accept(java.util.Arrays.asList(body,row),epoch);
    }
    /** Explicit local edits/imports do not require screen-capture permission.
     * Call from a worker, never the accessibility/UI thread. */
    public long storeExplicit(List<NativeTree.Observation> observations){return storeExplicit(observations,captureEpoch.get());}
    public long storeExplicit(List<NativeTree.Observation> observations,long expectedEpoch){
        if(observations.isEmpty()||observations.size()>256)throw new IllegalArgumentException("Invalid batch");
        for(NativeTree.Observation observation:observations)if(!valid(observation))throw new IllegalArgumentException("Invalid record");
        synchronized(mutationLock){
            if(clearing||captureEpoch.get()!=expectedEpoch)throw new IllegalStateException("Tree changed during operation");
            long added=tree().observe(observations);
            if(added>0)requestSnapshot();
            return added;
        }
    }
    private static String observationKey(NativeTree.Observation o){
        return o.source()+"\u001f"+o.externalId()+"\u001f"+o.body();
    }
    public List<NativeTree.Master> records(String term,long agent,long before,int limit){
        if(clearing)throw new IllegalStateException("Tree is clearing");
        return tree().records(term,agent,before,limit);
    }
    public List<NativeTree.Master> allRecords(String term,int max){
        if(max<1||max>10000)throw new IllegalArgumentException("Invalid record limit");
        List<NativeTree.Master> out=new ArrayList<>();long cursor=0;
        while(out.size()<max){
            int limit=Math.min(100,max-out.size());List<NativeTree.Master> page=records(term,0,cursor,limit);
            if(page.isEmpty())break;out.addAll(page);cursor=page.get(page.size()-1).sequence();
            if(page.size()<limit)break;
        }
        return out;
    }
    private void scheduleDrain(){if(!queue.isEmpty()&&draining.compareAndSet(false,true))writer.execute(this::drain);}
    private void drain(){
        ArrayList<Queued> batch=new ArrayList<>(BATCH_SIZE);
        try {
            synchronized(intakeLock){
                queue.drainTo(batch,BATCH_SIZE);
                // Keep in-flight observations pending until the transaction finishes.
            }
            long epoch=captureEpoch.get();
            List<NativeTree.Observation> observations=new ArrayList<>();
            for(Queued q:batch)if(q.epoch==epoch)observations.add(q.observation);
            int stale=batch.size()-observations.size();
            cancelled.addAndGet(stale);dropped.addAndGet(stale);
            if(observations.isEmpty())return;
            synchronized(mutationLock){
                if(!enabled()||captureEpoch.get()!=epoch){
                    cancelled.addAndGet(observations.size());dropped.addAndGet(observations.size());return;
                }
                // A native transaction already started may finish after pause.
                // Clear, if requested, is serialized after all in-flight writes.
                writeAttempts.addAndGet(observations.size());
                try{
                    long n=tree().observe(observations);
                    inserted.addAndGet(n);duplicate.addAndGet(observations.size()-n);
                    if(n>0)lastNewCaptureAt.set(System.currentTimeMillis());
                    error="";
                    synchronized(intakeLock){
                        if(captureEpoch.get()==epoch&&!clearing)for(NativeTree.Observation o:observations){
                            String key=observationKey(o);
                            if(key.length()>2048)continue;
                            committed.put(key,true);
                            if(committed.size()>4096)committed.remove(committed.keySet().iterator().next());
                        }
                    }
                    trace("write_ok",observations.size(),n,"");
                    if(n>0)requestSnapshot();
                }catch(Exception | LinkageError e){
                    writeFailures.addAndGet(observations.size());dropped.addAndGet(observations.size());
                    // Native errors report status/code rather than source content.
                    error="Tree write refused/failed: "+e.getClass().getSimpleName()+" ("+e.getMessage()+")";
                    trace("write_error",observations.size(),0,error);
                    android.util.Log.e("RootsTree",error);
                }
            }
        }finally{
            synchronized(intakeLock){for(Queued q:batch)if(q.epoch==captureEpoch.get())pending.remove(observationKey(q.observation));}
            try{saveStats();}finally{draining.set(false);scheduleDrain();}
        }
    }
    public void languageChanged(){
        snapshotEpoch.incrementAndGet();coordination=KnowledgeWave.Field.empty();notifyWave();
        compiler.execute(()->{emptyWave();try{synchronized(mutationLock){Files.deleteIfExists(snapshot.toPath());snapshotStatus="Rebuilding for "+overlay.targetLanguage();}}catch(Exception ignored){}requestSnapshot();});
    }
    public void requestSnapshot(){if(NativeTree.available()){dirty.incrementAndGet();scheduleCompile();}}
    private void scheduleCompile(){if(!clearing&&compiling.compareAndSet(false,true))compiler.schedule(this::compile,4,TimeUnit.SECONDS);}
    private void compile(){
        long target=dirty.get(),epoch=snapshotEpoch.get();File temp=null;
        try{
            if(clearing)return;
            NativeTree t=tree();snapshotStatus="Compiling";
            if(t.count()==0){
                boolean emptied=false;
                synchronized(mutationLock){
                    if(!clearing&&epoch==snapshotEpoch.get()&&t.count()==0){Files.deleteIfExists(snapshot.toPath());snapshotStatus="Empty Tree";emptied=true;}
                }
                if(emptied)emptyWave();
                return;
            }
            temp=new File(directory,"tree-"+UUID.randomUUID()+".tmp");
            String language=overlay.targetLanguage();
            long revision=t.compileLocalized(temp.getAbsolutePath(),language);
            boolean published=false,emptyProjection=false;
            synchronized(mutationLock){
                if(!clearing&&epoch==snapshotEpoch.get()&&t.revision()==revision&&language.equals(overlay.targetLanguage())){
                    if(temp.exists()){
                        Files.move(temp.toPath(),snapshot.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                        snapshotStatus="Localized "+language+" · revision "+revision+" · "+snapshot.length()+" bytes · active window ≤10000 nodes";published=true;
                    }else {Files.deleteIfExists(snapshot.toPath());snapshotStatus="Waiting for localized knowledge ("+language+")";emptyProjection=true;}
                }else snapshotStatus="Tree changed during compilation; retrying";
            }
            if(published)observeWave(epoch,revision,language);
            else if(emptyProjection)emptyWave();
        }catch(Exception|LinkageError e){snapshotStatus="Compilation failed";trace("compile_error",0,0,e.getClass().getSimpleName());}
        finally{
            if(temp!=null)try{Files.deleteIfExists(temp.toPath());}catch(Exception ignored){}
            compiling.set(false);if(!clearing&&dirty.get()!=target)scheduleCompile();
        }
    }
    private static String diagnosticTime(long millis){
        return millis==0?"Not recorded yet":java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.SHORT,java.text.DateFormat.MEDIUM).format(new java.util.Date(millis));
    }
    public String status(){
        return "Capture "+(enabled()?"ON":"OFF")+" · Queue "+queue.size()+"/"+QUEUE_LIMIT+
            "\nReader: "+captureReaderState+
            "\nLast visible scan: "+diagnosticTime(lastVisibleScanAt.get())+" · "+lastVisibleCount.get()+" fragments"+
            "\nLast accepted scan: "+diagnosticTime(lastAcceptedScanAt.get())+
            "\nLast new capture: "+diagnosticTime(lastNewCaptureAt.get())+
            "\nScans "+scans.get()+" · Rejected "+rejected.get()+" · Bounded "+truncated.get()+
            "\nDiscovered "+discovered.get()+" · Accepted "+accepted.get()+" · Queued "+queued.get()+
            "\nWrites attempted "+writeAttempts.get()+" · Lifetime failed "+writeFailures.get()+
            "\nLifetime inserted "+inserted.get()+" · Duplicate "+duplicate.get()+" · Dropped/refused "+dropped.get()+
            "\nQueue full "+queueDrops.get()+" · Cancelled "+cancelled.get()+" · Invalid "+invalidText.get()+
            "\nScope: visible X text · legacy Home rejections "+noHome.get()+
            "\nGGUF "+snapshotStatus+"\nCoordination: "+coordination.weights().size()+" records · "+coordination.edges()+" weighted links · revision "+coordination.revision()+(error.isEmpty()?"":"\n"+error);
    }
    // Call these from a presentation worker, never from the UI thread.
    public List<NativeTree.Fragment> query(String term,long agent,long before,int limit){
        if(clearing)throw new IllegalStateException("Tree is clearing");
        return tree().query(term,agent,before,limit);
    }
    public List<NativeTree.Agent> agents(){if(clearing)throw new IllegalStateException("Tree is clearing");return tree().agents();}
    /** Mesh revision: native observations and branch definitions change this value. */
    public long graphRevision(){
        if(clearing)throw new IllegalStateException("Tree is clearing");
        return tree().revision();
    }
    /** Import a peer branch by its definition; numeric agent IDs are device-local.
     * The native core rebuilds membership for existing and future fragments. */
    public long storeSharedBranch(String name,String term,long expectedEpoch){
        synchronized(mutationLock){
            if(clearing||captureEpoch.get()!=expectedEpoch)throw new IllegalStateException("Tree changed during receive");
            NativeTree t=tree();
            for(NativeTree.Agent a:t.agents())if(a.name().equals(name)&&a.term().equals(term))return 0;
            t.createAgent(name,term);requestSnapshot();return 1;
        }
    }
    public long count(){return tree().count();}
    public void createAgent(String name,String term,Consumer<String> done){
        writer.execute(()->{
            try{synchronized(mutationLock){if(clearing)throw new IllegalStateException("Tree is clearing");tree().createAgent(name,term);requestSnapshot();}done.accept(null);}
            catch(Exception e){done.accept(e.getMessage());}
        });
    }
    public void removeAgent(long id,Consumer<String> done){
        writer.execute(()->{
            try{synchronized(mutationLock){if(clearing)throw new IllegalStateException("Tree is clearing");tree().removeAgent(id);snapshotEpoch.incrementAndGet();coordination=KnowledgeWave.Field.empty();Files.deleteIfExists(snapshot.toPath());snapshotStatus="Rebuilding";requestSnapshot();}notifyWave();done.accept(null);}
            catch(Exception e){done.accept("Agent removal incomplete: "+e.getMessage());}
        });
    }
    public void clear(Consumer<String> done){
        synchronized(intakeLock){
            if(clearing){done.accept("Clear already in progress.");return;}
            clearing=true;prefs.edit().putBoolean("capture_enabled",false).apply();
            captureEpoch.incrementAndGet();snapshotEpoch.incrementAndGet();coordination=KnowledgeWave.Field.empty();queue.clear();pending.clear();committed.clear();
        }
        writer.execute(()->{
            try{
                notifyWave();
                synchronized(mutationLock){tree().clear();}
                // Run after any in-flight compiler. Clearing prevents publication.
                compiler.execute(()->{
                    try{
                        File[] files=directory.listFiles((dir,name)->name.equals("tree.gguf")||(name.startsWith("tree-")&&name.endsWith(".tmp")));
                        if(files!=null)for(File f:files)Files.deleteIfExists(f.toPath());
                        emptyWave();snapshotStatus="Empty Tree";error="";clearing=false;done.accept(null);
                    }catch(Exception e){clearing=false;done.accept("Snapshot removal incomplete. Capture remains off; retry clear.");}
                });
            }catch(Exception e){clearing=false;done.accept("Clear incomplete. Capture remains off; retry.");}
        });
    }
}
