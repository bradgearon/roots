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
    private final File directory, snapshot;
    private final ScheduledExecutorService writer=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"RootsTreeWriter"));
    private final ScheduledExecutorService compiler=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"RootsTreeCompiler"));
    private final ArrayBlockingQueue<Queued> queue=new ArrayBlockingQueue<>(QUEUE_LIMIT);
    private final HashSet<String> pending=new HashSet<>();
    private final Object intakeLock=new Object(), mutationLock=new Object(), openLock=new Object();
    private final AtomicBoolean draining=new AtomicBoolean(), compiling=new AtomicBoolean();
    private final AtomicLong captureEpoch=new AtomicLong(), snapshotEpoch=new AtomicLong(), dirty=new AtomicLong();
    private final AtomicLong inserted=new AtomicLong(), duplicate=new AtomicLong(), dropped=new AtomicLong(), scans=new AtomicLong(), rejected=new AtomicLong(), truncated=new AtomicLong();
    private volatile NativeTree nativeTree;
    private volatile boolean clearing;
    private volatile String snapshotStatus="Not compiled", error="";
    private static final class Queued {
        final NativeTree.Observation observation; final long epoch;
        Queued(NativeTree.Observation o,long e){observation=o;epoch=e;}
    }
    private TreeRuntime(Context context) {
        prefs=context.getSharedPreferences("roots_scroll_tree",Context.MODE_PRIVATE);
        overlay=new OverlayPreferences(context);
        directory=new File(context.getNoBackupFilesDir(),"roots-tree");
        snapshot=new File(directory,"tree.gguf");
        // Rebuild the disposable snapshot on a worker after a restart if
        // capture was explicitly enabled previously. No implicit capture consent.
        if(enabled())requestSnapshot();
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
    public boolean enabled(){return NativeTree.available()&&!clearing&&overlay.enabled()&&prefs.getBoolean("capture_enabled",false);}
    public long captureEpoch(){return captureEpoch.get();}
    public void setEnabled(boolean value) {
        synchronized(intakeLock) {
            if(value&&(!NativeTree.available()||clearing||!overlay.enabled()))return;
            prefs.edit().putBoolean("capture_enabled",value).apply();
            captureEpoch.incrementAndGet();
            dropped.addAndGet(queue.size());queue.clear();pending.clear();
        }
        if(value)requestSnapshot();
    }
    public void reportScan(boolean eligible,boolean incomplete){scans.incrementAndGet();if(!eligible)rejected.incrementAndGet();if(incomplete)truncated.incrementAndGet();}
    void accept(List<NativeTree.Observation> observations,long epoch){
        synchronized(intakeLock){
            if(!enabled()||epoch!=captureEpoch.get())return;
            for(NativeTree.Observation o:observations){
                String key=o.body();
                if(!pending.add(key)){duplicate.incrementAndGet();continue;}
                if(!queue.offer(new Queued(o,epoch))){pending.remove(key);dropped.incrementAndGet();}
            }
        }
        scheduleDrain();
    }
    private void scheduleDrain(){if(!queue.isEmpty()&&draining.compareAndSet(false,true))writer.execute(this::drain);}
    private void drain(){
        ArrayList<Queued> batch=new ArrayList<>(BATCH_SIZE);
        try {
            synchronized(intakeLock){
                queue.drainTo(batch,BATCH_SIZE);
                for(Queued q:batch)pending.remove(q.observation.body());
            }
            long epoch=captureEpoch.get();
            List<NativeTree.Observation> observations=new ArrayList<>();
            for(Queued q:batch)if(q.epoch==epoch)observations.add(q.observation);
            if(observations.isEmpty())return;
            synchronized(mutationLock){
                if(!enabled()||captureEpoch.get()!=epoch)return;
                // A native transaction already started may finish after pause.
                // Clear, if requested, is serialized after all in-flight writes.
                long n=tree().observe(observations);
                inserted.addAndGet(n);duplicate.addAndGet(observations.size()-n);
                if(n>0)requestSnapshot();
            }
        }catch(Exception e){dropped.addAndGet(batch.size());error="Tree write refused/failed: "+e.getClass().getSimpleName()+" ("+e.getMessage()+")";}
        finally{draining.set(false);scheduleDrain();}
    }
    public void requestSnapshot(){if(NativeTree.available()){dirty.incrementAndGet();scheduleCompile();}}
    private void scheduleCompile(){if(!clearing&&compiling.compareAndSet(false,true))compiler.schedule(this::compile,4,TimeUnit.SECONDS);}
    private void compile(){
        long target=dirty.get(),epoch=snapshotEpoch.get();File temp=null;
        try{
            if(clearing)return;
            NativeTree t=tree();snapshotStatus="Compiling";
            if(t.count()==0){
                synchronized(mutationLock){
                    if(!clearing&&epoch==snapshotEpoch.get()&&t.count()==0){Files.deleteIfExists(snapshot.toPath());snapshotStatus="Empty Tree";}
                }
                return;
            }
            temp=new File(directory,"tree-"+UUID.randomUUID()+".tmp");
            long revision=t.compile(temp.getAbsolutePath());
            synchronized(mutationLock){
                if(!clearing&&epoch==snapshotEpoch.get()&&t.revision()==revision){
                    Files.move(temp.toPath(),snapshot.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                    snapshotStatus="Revision "+revision+" · "+snapshot.length()+" bytes";
                }else snapshotStatus="Tree changed during compilation; retrying";
            }
        }catch(Exception e){snapshotStatus="Compilation failed";error="GGUF compiler: "+e.getClass().getSimpleName();}
        finally{
            if(temp!=null)try{Files.deleteIfExists(temp.toPath());}catch(Exception ignored){}
            compiling.set(false);if(!clearing&&dirty.get()!=target)scheduleCompile();
        }
    }
    public String status(){
        return "Capture "+(enabled()?"ON":"OFF")+" · Queue "+queue.size()+"/"+QUEUE_LIMIT+
            "\nScans "+scans.get()+" · Rejected "+rejected.get()+" · Bounded "+truncated.get()+
            "\nStored "+inserted.get()+" · Duplicate "+duplicate.get()+" · Dropped/refused "+dropped.get()+
            "\nGGUF "+snapshotStatus+(error.isEmpty()?"":"\n"+error);
    }
    // Call these from a presentation worker, never from the UI thread.
    public List<NativeTree.Fragment> query(String term,long agent,long before,int limit){
        if(clearing)throw new IllegalStateException("Tree is clearing");
        return tree().query(term,agent,before,limit);
    }
    public List<NativeTree.Agent> agents(){if(clearing)throw new IllegalStateException("Tree is clearing");return tree().agents();}
    public long count(){return tree().count();}
    public void createAgent(String name,String term,Consumer<String> done){
        writer.execute(()->{
            try{synchronized(mutationLock){if(clearing)throw new IllegalStateException("Tree is clearing");tree().createAgent(name,term);requestSnapshot();}done.accept(null);}
            catch(Exception e){done.accept(e.getMessage());}
        });
    }
    public void removeAgent(long id,Consumer<String> done){
        writer.execute(()->{
            try{synchronized(mutationLock){if(clearing)throw new IllegalStateException("Tree is clearing");tree().removeAgent(id);Files.deleteIfExists(snapshot.toPath());snapshotStatus="Rebuilding";requestSnapshot();}done.accept(null);}
            catch(Exception e){done.accept("Agent removal incomplete: "+e.getMessage());}
        });
    }
    public void clear(Consumer<String> done){
        synchronized(intakeLock){
            if(clearing){done.accept("Clear already in progress.");return;}
            clearing=true;prefs.edit().putBoolean("capture_enabled",false).apply();
            captureEpoch.incrementAndGet();snapshotEpoch.incrementAndGet();queue.clear();pending.clear();
        }
        writer.execute(()->{
            try{
                synchronized(mutationLock){tree().clear();}
                // Run after any in-flight compiler. Clearing prevents publication.
                compiler.execute(()->{
                    try{
                        File[] files=directory.listFiles((dir,name)->name.equals("tree.gguf")||(name.startsWith("tree-")&&name.endsWith(".tmp")));
                        if(files!=null)for(File f:files)Files.deleteIfExists(f.toPath());
                        snapshotStatus="Empty Tree";error="";clearing=false;done.accept(null);
                    }catch(Exception e){clearing=false;done.accept("Snapshot removal incomplete. Capture remains off; retry clear.");}
                });
            }catch(Exception e){clearing=false;done.accept("Clear incomplete. Capture remains off; retry.");}
        });
    }
}
