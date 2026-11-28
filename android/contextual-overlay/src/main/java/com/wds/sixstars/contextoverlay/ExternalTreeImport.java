package com.wds.sixstars.contextoverlay;

import android.content.Context;
import com.wds.sixstars.contextoverlay.tree.NativeTree;
import com.wds.sixstars.contextoverlay.tree.TreeRuntime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** Recover durable external receipts left pending by an old installation or
 * interrupted Tree write. Never recreate already-imported records on Tree clear. */
final class ExternalTreeImport {
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"RootsExternalImport");t.setDaemon(true);return t;});
    private static final AtomicBoolean RUNNING=new AtomicBoolean();
    private static final CopyOnWriteArrayList<Runnable> LISTENERS=new CopyOnWriteArrayList<>();
    private ExternalTreeImport(){}
    static void addListener(Runnable r){LISTENERS.addIfAbsent(r);}
    static void removeListener(Runnable r){LISTENERS.remove(r);}
    static void notifyImported(){for(Runnable r:LISTENERS)try{r.run();}catch(RuntimeException ignored){}}
    static void schedule(Context context){
        if(!NativeTree.available()||!RUNNING.compareAndSet(false,true))return;
        Context app=context.getApplicationContext();
        TreeRuntime tree=TreeRuntime.get(app);
        final long epoch=tree.captureEpoch();
        WORK.execute(()->{
            try{
                ExternalKnowledgeStore store=ExternalKnowledgeStore.get(app);
                String after="";
                for(int pages=0;pages<100&&tree.captureEpoch()==epoch;pages++){
                    List<String> ids=store.pendingIds(after,100);
                    if(ids.isEmpty())break;
                    for(String id:ids){
                        if(tree.captureEpoch()!=epoch)return;
                        try{store.promote(id,tree,epoch);}
                        catch(Exception|LinkageError failure){android.util.Log.w("RootsExternalImport","Pending receipt could not be imported: "+failure.getClass().getSimpleName());}
                        after=id;
                    }
                    if(ids.size()<100)break;
                }
            }catch(Exception|LinkageError failure){android.util.Log.w("RootsExternalImport","Receipt recovery failed: "+failure.getClass().getSimpleName());}
            finally{RUNNING.set(false);}
        });
    }
}
