package com.wds.sixstars.contextoverlay.tree;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Event-driven local projection of durable jobs. Invoke from a service worker,
 * not the UI thread. On another worker's completion notification call refresh.
 * Closing the projection does not cancel durable jobs. */
public final class WorkPromises implements AutoCloseable {
    private final NativeTree tree;
    private final Map<Long,CompletableFuture<NativeTree.Work>> waiting=new HashMap<>();
    private boolean closed;
    public WorkPromises(NativeTree tree){this.tree=tree;}
    public record Promise(long id,CompletableFuture<NativeTree.Work> completion){}
    public synchronized Promise request(long fragment,String op,String profile,long priority,long deadline){
        return attach(tree.requestWork(fragment,op,profile,priority,deadline));
    }
    public synchronized Promise attach(long id){
        if(closed)throw new IllegalStateException("Promise registry closed");
        CompletableFuture<NativeTree.Work> future=waiting.computeIfAbsent(id,k->new CompletableFuture<>());
        refresh(id);
        return new Promise(id,future.thenApply(job->job));
    }
    public void refresh(long id){
        CompletableFuture<NativeTree.Work> future;
        NativeTree.Work job;
        synchronized(this){
            future=waiting.get(id);if(future==null)return;
            job=tree.readWork(id);
            if(job!=null&&(job.state()==NativeTree.WorkState.PENDING||job.state()==NativeTree.WorkState.RUNNING))return;
            waiting.remove(id);
        }
        if(job==null)future.completeExceptionally(new IllegalStateException("Work removed"));
        else future.complete(job);
    }
    public void refreshAll(){
        Long[] ids;
        synchronized(this){ids=waiting.keySet().toArray(new Long[0]);}
        for(long id:ids)refresh(id);
    }
    @Override public void close(){
        CompletableFuture<?>[] futures;
        synchronized(this){closed=true;futures=waiting.values().toArray(new CompletableFuture<?>[0]);waiting.clear();}
        for(CompletableFuture<?> f:futures)f.cancel(false);
    }
}
