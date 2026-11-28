package com.wds.sixstars.contextoverlay;

import android.content.Context;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One receive operation over the saved public Axon registry, independent of
 * how a peer was discovered. Radio/transport owns the session; UI observes
 * actual completed transfers rather than treating a scan request as receipt. */
final class AxonReceiveCoordinator {
    static final long REQUEST_SECONDS=150;
    private static final ScheduledExecutorService TIMER=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"RootsAxonReceiveDeadline");t.setDaemon(true);return t;
    });
    private static Job current;
    private AxonReceiveCoordinator(){}
    record Result(int targets,int complete,int reached,long received,long sent,String reason){
        String summary(){
            String count=received+" received · "+sent+" sent · "+complete+" of "+targets+" Axons checked";
            return count+(reason.isEmpty()?"":" · "+reason);
        }
    }
    static final class Job {
        final Set<String> targets=new HashSet<>(),complete=new HashSet<>(),reached=new HashSet<>();
        final CompletableFuture<Result> finished=new CompletableFuture<>();
        final Consumer<String> progress;
        long received,sent;
        String latest="";
        ScheduledFuture<?> timeout;
        Job(Collection<String> peers,Consumer<String> progress){targets.addAll(peers);this.progress=progress;}
        boolean accepts(String peer){
            if(targets.isEmpty())targets.add(peer);
            return targets.contains(peer)&&!complete.contains(peer);
        }
        void publish(String detail){
            latest=detail;
            try{progress.accept("Axons: "+received+" received · "+complete.size()+"/"+targets.size()+" checked · "+detail);}
            catch(RuntimeException ignored){}
        }
    }
    /** Call from a visible Activity so the foreground discovery service can
     * be started legally; callbacks are marshalled by the caller. */
    static Job start(Context context,Consumer<String> progress){
        List<String> ids=new ArrayList<>();
        for(var peer:EncounterBindings.get(context).subscriptions())if(!peer.blocked())ids.add(peer.binding().peer());
        Job job=new Job(ids,progress);
        if(!EncounterService.available(context)||!EncounterSettings.enabled(context)||!EncounterSettings.eligible(context)){
            String why=EncounterService.requestReceive(context);
            job.finished.complete(new Result(ids.size(),0,0,0,0,why));return job;
        }
        synchronized(AxonReceiveCoordinator.class){
            if(current!=null&&!current.finished.isDone())finish(current,"Superseded by a new receive request");
            current=job;
            job.timeout=TIMER.schedule(()->expire(job),REQUEST_SECONDS,TimeUnit.SECONDS);
            job.publish(ids.isEmpty()?"Discovering available Axons…":"Connecting to saved Axons…");
        }
        EncounterService.requestReceive(context);
        return job;
    }
    static synchronized boolean requested(){return current!=null&&!current.finished.isDone();}
    static synchronized void progress(String peer,String detail){
        Job job=current;
        if(job==null||!job.accepts(peer))return;
        job.reached.add(peer);job.publish(detail);
    }
    static synchronized void completed(String peer,int received,int sent,boolean complete,String detail){
        Job job=current;
        if(job==null||!job.accepts(peer))return;
        job.reached.add(peer);job.received+=received;job.sent+=sent;
        if(complete)job.complete.add(peer);
        job.publish(detail);
        if(!job.targets.isEmpty()&&job.complete.containsAll(job.targets))finish(job,"Return Home to read.");
    }
    private static synchronized void expire(Job job){
        if(current!=job||job.finished.isDone())return;
        String reason=job.reached.isEmpty()?"No subscribed Axon reachable; background discovery continues.":
            "Some inventories remain pending; automatic synchronization continues.";
        finish(job,reason);
    }
    private static void finish(Job job,String reason){
        if(job.timeout!=null)job.timeout.cancel(false);
        if(current==job)current=null;
        job.finished.complete(new Result(job.targets.size(),job.complete.size(),job.reached.size(),job.received,job.sent,reason));
    }
}
