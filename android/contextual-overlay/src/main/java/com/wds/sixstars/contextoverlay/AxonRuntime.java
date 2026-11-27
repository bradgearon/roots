package com.wds.sixstars.contextoverlay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.SystemClock;
import com.wds.sixstars.contextoverlay.tree.NativeTree;
import com.wds.sixstars.contextoverlay.tree.TreeRuntime;
import com.wds.sixstars.contextoverlay.tree.KnowledgeWave;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Request-scoped read access to explicitly published peer knowledge.
 * Session credentials stay in RAM. No discovery, polling or background inference. */
final class AxonRuntime {
    private static final ThreadPoolExecutor WORK=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,"RootsRuntimeNeed");t.setDaemon(true);return t;});
    private static volatile Session session;
    private record Session(AxonSubscription policy,List<MeshPeer.Invite> peers,long expires){}
    record Plan(Session session,List<String> topics){}
    record Result(String context,String status,long treeEpoch){
        Result(String context,String status){this(context,status,-1);}
    }
    private record Candidate(RuntimeNeed.Evidence evidence,double rank){}
    private AxonRuntime(){}
    static synchronized void arm(AxonSubscription policy,List<MeshPeer.Invite> peers){
        if(!policy.accepts(peers))throw new IllegalArgumentException("Peer identity is not bound to this Axon.");
        session=new Session(policy,Collections.unmodifiableList(new ArrayList<>(peers)),SystemClock.elapsedRealtime()+30*60*1000L);
    }
    static synchronized void revoke(){session=null;}
    static String status(){
        Session s=session;
        if(s==null)return "Runtime access off";
        long remaining=s.expires()-SystemClock.elapsedRealtime();
        if(remaining<=0)return "Runtime access expired · arm again for runtime requests";
        long seconds=(remaining+999)/1000;
        return "Runtime access armed · "+(seconds/60)+"m "+(seconds%60)+"s remaining · "+s.peers().size()+" peer(s)";
    }
    private static boolean active(Session s){return s!=null&&session==s&&SystemClock.elapsedRealtime()<s.expires();}
    private static boolean same(AxonSubscription a,AxonSubscription b){
        return b!=null&&a.pins.equals(b.pins)&&a.model.sha.equals(b.model.sha)&&a.operation.equals(b.operation)&&a.lines().equals(b.lines());
    }
    static boolean authorized(Context context,Plan plan){return plan!=null&&authorized(context,plan.session());}
    private static boolean authorized(Context context,Session s){
        if(!active(s))return false;
        try{return same(s.policy(),AxonSubscription.load(context));}catch(Exception e){return false;}
    }
    /** The user sees the exact topic labels that may leave this device. */
    static void choose(Activity activity,String request,java.util.function.Consumer<Plan> selected,java.util.function.Consumer<String> error,Runnable cancelled){
        Session current=session;
        if(!authorized(activity,current)){error.accept("Arm runtime access in Roots Mesh with a current invitation first.");return;}
        List<String> labels=new ArrayList<>();
        for(AxonSubscription.Lane lane:current.policy().lanes)if(RuntimeNeed.validTopic(lane.topic()))labels.add(lane.topic());
        if(labels.isEmpty()){error.accept("Add named topics to the Axon. Wildcard bulk sync does not authorize runtime topic disclosure.");return;}
        String[] names=labels.toArray(new String[0]);boolean[] checked=new boolean[names.length];int count=0;
        for(int i=0;i<names.length;i++)if(RuntimeNeed.matches(request,names[i])&&count<2){checked[i]=true;count++;}
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Resolve through Axon")
            .setMultiChoiceItems(names,checked,(d,i,value)->checked[i]=value)
            .setNegativeButton("Cancel",(d,w)->cancelled.run()).setPositiveButton("Request",null).create();
        dialog.setOnCancelListener(d->cancelled.run());
        dialog.setOnShowListener(d->{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                List<String> topics=new ArrayList<>();for(int i=0;i<names.length;i++)if(checked[i])topics.add(names[i]);
                if(topics.isEmpty()||topics.size()>2){
                    android.widget.Toast.makeText(activity,"Select one or two topics",android.widget.Toast.LENGTH_SHORT).show();return;
                }
                if(!authorized(activity,current)){dialog.dismiss();error.accept("Axon session expired or changed.");return;}
                dialog.dismiss();selected.accept(new Plan(current,Collections.unmodifiableList(topics)));
            });
        });
        dialog.show();
    }
    static Future<Result> start(Context context,Plan plan,String request,String language,long deadline){
        Context app=context.getApplicationContext();
        FutureTask<Result> task=new FutureTask<>(()->resolve(app,plan,request,language,deadline));
        try{WORK.execute(task);}catch(java.util.concurrent.RejectedExecutionException e){task.cancel(false);}
        return task;
    }
    private static boolean live(Context c,Session s,long deadline){
        return !Thread.currentThread().isInterrupted()&&RuntimeNeed.remaining(deadline,SystemClock.elapsedRealtime())>0&&authorized(c,s);
    }
    private static Result resolve(Context app,Plan plan,String request,String language,long deadline){
        Session s=plan.session();
        if(!live(app,s,deadline))return new Result("","Axon unavailable · local fallback");
        List<RuntimeNeed.Evidence> evidence=new ArrayList<>();
        int remote=0,failures=0,local=0;
        InferenceHistory.Attempt attempt=InferenceHistory.get(app).begin("Axon · LAN","Published knowledge","Runtime need","Explicit topic-scoped request");
        attempt.running("Resolving");
        try{
            TreeRuntime tree=TreeRuntime.get(app);long epoch=tree.captureEpoch();
            List<NativeTree.Master> records=tree.allRecords("",10000);
            if(!live(app,s,deadline)||epoch!=tree.captureEpoch())throw new IllegalStateException("Request expired");
            AxonSubscription.Allocation allocation=s.policy().allocation(app,tree,records,language);
            List<RuntimeNeed.Topic> topics=new ArrayList<>();
            for(AxonPlanner.Share share:allocation.shares())if(plan.topics().contains(share.topic()))topics.add(new RuntimeNeed.Topic(share.topic(),share.fraction()));
            topics.sort(Comparator.comparingInt((RuntimeNeed.Topic t)->RuntimeNeed.matches(request,t.name())?0:1)
                .thenComparing(Comparator.comparingDouble(RuntimeNeed.Topic::share).reversed()).thenComparing(RuntimeNeed.Topic::name));
            KnowledgeWave.Field field=tree.coordination();long now=SystemClock.elapsedRealtime();
            Set<String> seen=new HashSet<>();
            // Local reference material stays local. Match only explicit topics and
            // localized text. Peer material is never committed or automatically republished.
            for(int topicIndex=0;topicIndex<topics.size();topicIndex++){
                RuntimeNeed.Topic topic=topics.get(topicIndex);
                if(!live(app,s,deadline))break;
                int laneEnd=RuntimeNeed.MAX_ITEMS-(topics.size()-topicIndex-1);
                List<Candidate> candidates=new ArrayList<>();
                for(NativeTree.Master record:records){
                    if(Thread.currentThread().isInterrupted()||RuntimeNeed.remaining(deadline,SystemClock.elapsedRealtime())<=0)break;
                    NativeTree.Fragment body=record.localized(language);
                    if(!language.equals(body.language()))continue;
                    String label=record.latest("topic");
                    if(!RuntimeNeed.matches(label+" "+body.body(),topic.name()))continue;
                    MeshItem published=MeshItem.from(record);
                    String id=published==null?record.key():published.id();
                    KnowledgeWave.Weight weight=language.equals(field.language())?field.weight(record.key(),now):null;
                    double rank=(RuntimeNeed.matches(label,topic.name())?20:0)+(weight==null?0:weight.priority());
                    candidates.add(new Candidate(new RuntimeNeed.Evidence(id,label.isEmpty()?topic.name():label,body.body(),"local"),rank));
                }
                candidates.sort(Comparator.comparingDouble(Candidate::rank).reversed().thenComparing(v->v.evidence().id()));
                int covered=0;
                for(Candidate c:candidates)if(seen.add(c.evidence().id())){
                    if(covered==0&&evidence.size()<laneEnd){evidence.add(c.evidence());local++;}
                    covered++;if(covered>=2)break;
                }
                // Two distinct local matches are only a coverage heuristic, not a
                // correctness claim. Otherwise ask up to two bound live peers.
                if(covered>=2||evidence.size()>=laneEnd)continue;
                int tried=0;
                for(MeshPeer.Invite peer:s.peers()){
                    if(tried++>=2||!live(app,s,deadline)||epoch!=tree.captureEpoch())break;
                    try{
                        long peerDeadline=Math.min(deadline,SystemClock.elapsedRealtime()+2000L);
                        List<MeshItem> items=MeshPeer.get(app).need(peer,topic.name(),language,laneEnd-evidence.size(),peerDeadline);
                        if(!live(app,s,deadline)||epoch!=tree.captureEpoch())break;
                        for(MeshItem item:items){
                            if(evidence.size()>=laneEnd)break;
                            if(!seen.add(item.id()))continue;
                            evidence.add(new RuntimeNeed.Evidence(item.id(),item.topic(),item.text(),"peer "+item.language()));remote++;
                        }
                        if(!items.isEmpty())break;
                    }catch(Exception e){failures++;}
                }
            }
            if(!authorized(app,s)||epoch!=tree.captureEpoch()||Thread.currentThread().isInterrupted()){
                attempt.finish(false,"Revoked or cancelled");return new Result("","Axon revoked · local fallback");
            }
            String context=RuntimeNeed.context(evidence);
            String status="Axon references: "+local+" local · "+remote+" peer · "+failures+" failed exchanges"+(context.isEmpty()?" · local fallback":"");
            attempt.finish(!context.isEmpty(),status);
            return new Result(context,status,epoch);
        }catch(Exception|LinkageError e){
            attempt.finish(false,"Runtime resolution unavailable");
            return new Result("","Axon unavailable · local fallback");
        }
    }
}
