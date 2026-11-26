package com.wds.sixstars.contextoverlay.tree;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Process-local service: bounded intake, serialized durable writes, independent compiler. */
public final class ScrollTreeService {
    private static final int QUEUE_LIMIT=2048, BATCH_SIZE=64;
    private static ScrollTreeService instance;
    public static synchronized ScrollTreeService get(Context context) {
        if(instance==null) instance=new ScrollTreeService(context.getApplicationContext());
        return instance;
    }
    final TreeStore store;
    private final SharedPreferences prefs;
    private final File directory, snapshot;
    private final ScheduledExecutorService writer=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"RootsTreeWriter"));
    private final ScheduledExecutorService compiler=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"RootsGgufCompiler"));
    private final ArrayBlockingQueue<Queued> queue=new ArrayBlockingQueue<>(QUEUE_LIMIT);
    private final HashSet<String> pending=new HashSet<>();
    private final Object intakeLock=new Object(), publishLock=new Object();
    private final AtomicBoolean draining=new AtomicBoolean(), compiling=new AtomicBoolean();
    private final AtomicLong captureEpoch=new AtomicLong(), snapshotEpoch=new AtomicLong(), dirty=new AtomicLong();
    private final AtomicLong inserted=new AtomicLong(), duplicate=new AtomicLong(), dropped=new AtomicLong(), full=new AtomicLong(), rejected=new AtomicLong(), scans=new AtomicLong(), truncated=new AtomicLong();
    private volatile boolean clearing;
    private volatile String snapshotStatus="Not compiled";
    private volatile String error="";
    private static final class Queued {
        final TreeStore.Observation observation; final long epoch;
        Queued(TreeStore.Observation observation,long epoch){this.observation=observation;this.epoch=epoch;}
    }
    private ScrollTreeService(Context context) {
        prefs=context.getSharedPreferences("roots_scroll_tree",Context.MODE_PRIVATE);
        store=new TreeStore(context);
        directory=new File(context.getNoBackupFilesDir(),"roots-tree");
        snapshot=new File(directory,"tree.lexical.gguf");
        // Rebuild disposable projection on restart; no access to other apps.
        requestSnapshot();
    }
    public boolean enabled() {return !clearing && prefs.getBoolean("capture_enabled",false);}
    public long captureEpoch() { return captureEpoch.get(); }
    public void setEnabled(boolean enabled) {
        synchronized(intakeLock) {
            if(enabled && clearing) return;
            prefs.edit().putBoolean("capture_enabled",enabled).apply();
            captureEpoch.incrementAndGet();
            queue.clear(); pending.clear();
        }
    }
    public void reportScan(boolean eligible, boolean incomplete) {
        scans.incrementAndGet(); if(!eligible) rejected.incrementAndGet();if(incomplete) truncated.incrementAndGet();
    }
    void accept(List<TreeStore.Observation> observations,long epoch) {
        synchronized(intakeLock) {
            if(!enabled() || captureEpoch.get()!=epoch) return;
            for(TreeStore.Observation o:observations) {
                if(!pending.add(o.key)){duplicate.incrementAndGet();continue;}
                if(!queue.offer(new Queued(o,epoch))) {pending.remove(o.key);dropped.incrementAndGet();}
            }
        }
        scheduleDrain();
    }
    private void scheduleDrain() {
        if(!queue.isEmpty() && draining.compareAndSet(false,true)) writer.execute(this::drain);
    }
    private void drain() {
        ArrayList<Queued> batch=new ArrayList<>(BATCH_SIZE);
        try {
            synchronized(intakeLock) {
                queue.drainTo(batch,BATCH_SIZE);
                for(Queued q:batch) pending.remove(q.observation.key);
            }
            if(batch.isEmpty()) return;
            long epoch=captureEpoch.get();
            List<TreeStore.Observation> observations=new ArrayList<>();
            for(Queued q:batch) if(q.epoch==epoch) observations.add(q.observation);
            if(!enabled()||observations.isEmpty()) return;
            TreeStore.Batch result=store.insert(observations,()->enabled()&&captureEpoch.get()==epoch);
            inserted.addAndGet(result.inserted);duplicate.addAndGet(result.duplicate);full.addAndGet(result.full);
            if(result.inserted>0) requestSnapshot();
        } catch(Exception e) {
            dropped.addAndGet(batch.size()); error="Tree write failed ("+e.getClass().getSimpleName()+")";
            Log.w("RootsTree","Tree write failed");
        } finally {
            draining.set(false);scheduleDrain();
        }
    }
    public void requestSnapshot() {
        dirty.incrementAndGet();
        scheduleCompile();
    }
    private void scheduleCompile() {
        if(!clearing && compiling.compareAndSet(false,true)) compiler.schedule(this::compile,4,TimeUnit.SECONDS);
    }
    private void compile() {
        long target=dirty.get(), epoch=snapshotEpoch.get();
        File temp=null;
        try {
            if(clearing) return;
            snapshotStatus="Compiling";
            TreeStore.Snapshot data=store.snapshot();
            if(data.rows.isEmpty()) {
                synchronized(publishLock) {
                    if(!clearing && epoch==snapshotEpoch.get()) {
                        Files.deleteIfExists(snapshot.toPath());snapshotStatus="Empty Tree";
                    }
                }
                return;
            }
            if(!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Storage unavailable");
            temp=File.createTempFile("tree-", ".tmp",directory);
            try(FileOutputStream out=new FileOutputStream(temp)) {
                TreeGgufWriter.write(out,data.rows,data.revision);
                out.getFD().sync();
            }
            synchronized(publishLock) {
                if(!clearing&&epoch==snapshotEpoch.get()) {
                    Files.move(temp.toPath(),snapshot.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                    snapshotStatus=data.rows.size()+" records · revision "+data.revision+" · "+snapshot.length()+" bytes";
                }
            }
        } catch(Exception e) {
            snapshotStatus="Compilation failed; previous snapshot retained";
            error="GGUF compiler failed ("+e.getClass().getSimpleName()+")";
        } finally {
            if(temp!=null) try {Files.deleteIfExists(temp.toPath());}catch(Exception ignored){}
            compiling.set(false);
            if(!clearing && dirty.get()!=target) scheduleCompile();
        }
    }
    String status() {
        return "Tree capture: "+(enabled()?"ON":"OFF")+" · queue "+queue.size()+"/"+QUEUE_LIMIT+
                "\nScans "+scans.get()+" · unqualified "+rejected.get()+" · bounded "+truncated.get()+
                "\nSession inserts "+inserted.get()+" · duplicate fragments "+duplicate.get()+
                " · queue/write drops "+dropped.get()+" · capacity refusals "+full.get()+
                "\nGGUF: "+snapshotStatus+(error.isEmpty()?"":"\n"+error);
    }
    void createAgent(String name,String term,Consumer<String> done) {
        writer.execute(()->{
            try {if(clearing)throw new IllegalStateException("Tree is clearing");store.createAgent(name,term);done.accept(null);}
            catch(Exception e){done.accept(e.getMessage());}
        });
    }
    void deleteAgent(long id,Consumer<String> done) {
        writer.execute(()->{try {store.deleteAgent(id);done.accept(null);}catch(Exception e){done.accept("Could not delete agent.");}});
    }
    void clear(Consumer<String> done) {
        setEnabled(false);
        synchronized(publishLock) {
            clearing=true;snapshotEpoch.incrementAndGet();
            try {Files.deleteIfExists(snapshot.toPath());}catch(Exception e){error="Could not remove old snapshot";}
        }
        writer.execute(()->{
            try {
                store.clear();
                // Retry removal; an in-flight compiler cannot publish old content.
                synchronized(publishLock) {
                    Files.deleteIfExists(snapshot.toPath());snapshotStatus="Empty Tree";clearing=false;
                }
                done.accept(null);
            }catch(Exception e){done.accept("Clear incomplete; capture remains off. Retry.");}
        });
    }
}
