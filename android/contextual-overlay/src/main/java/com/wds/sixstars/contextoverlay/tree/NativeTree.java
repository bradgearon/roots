package com.wds.sixstars.contextoverlay.tree;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongFunction;

/** Shared C++ Tree adapter. No platform-specific knowledge database. */
public final class NativeTree implements AutoCloseable {
    private static final boolean AVAILABLE;
    private static final String UNAVAILABLE_REASON;
    static {
        boolean available=false;
        String reason="";
        try {
            System.loadLibrary("roots_tree");
            int abi=nAbi();
            available=abi==1;
            if(!available)reason="Native Tree ABI mismatch (expected 1, found "+abi+").";
        } catch(LinkageError | RuntimeException error){
            reason="Native Tree could not load: "+error.getClass().getSimpleName()+".";
            androidSafeLog(error);
        }
        AVAILABLE=available;
        UNAVAILABLE_REASON=reason;
    }
    // This class also runs in standalone host JVM contract tests.
    private static void androidSafeLog(Throwable error){
        java.util.logging.Logger.getLogger(NativeTree.class.getName())
            .log(java.util.logging.Level.WARNING,"Native Tree load failure",error);
    }
    public static boolean available(){return AVAILABLE;}
    public static String unavailableReason(){return UNAVAILABLE_REASON;}
    public record Observation(String source,String externalId,String body,String language,long at){}
    public record Fragment(long sequence,String source,String externalId,String body,String language,long at){}
    public record Agent(long id,String name,String term){}
    public record Master(long sequence,String key,List<Fragment> members){
        public String first(String role){
            for(Fragment f:members)if(f.source().endsWith("."+role))return f.body();
            return "";
        }
        public Fragment body(){
            for(Fragment f:members)if(f.source().endsWith(".body"))return f;
            return members.get(0);
        }
    }
    public List<Master> records(String query,long agent,long before,int limit){
        return use(h->{
            byte[][] a=nRecords(h,utf8(query),agent,before,limit);
            List<Master> out=new ArrayList<>();
            long seq=-1;String key="";List<Fragment> members=new ArrayList<>();
            for(int i=0;i<a.length;i+=7){
                long next=Long.parseLong(text(a[i]));
                if(next!=seq){
                    if(!members.isEmpty())out.add(new Master(seq,key,java.util.Collections.unmodifiableList(members)));
                    members=new ArrayList<>();seq=next;key=text(a[i+3]);
                }
                members.add(new Fragment(Long.parseLong(text(a[i+1])),text(a[i+2]),text(a[i+3]),text(a[i+4]),text(a[i+5]),Long.parseLong(text(a[i+6]))));
            }
            if(!members.isEmpty())out.add(new Master(seq,key,java.util.Collections.unmodifiableList(members)));
            return out;
        });
    }
    private final ReentrantReadWriteLock life=new ReentrantReadWriteLock();
    private long handle;
    public NativeTree(String path) {
        if(!AVAILABLE)throw new IllegalStateException("Native Tree is not included in this build");
        handle=nOpen(utf8(path));
        if(handle==0)throw new IllegalStateException("Native Tree open failed");
    }
    private static byte[] utf8(String s) {
        if(s==null||s.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid UTF-8 input");
        return s.getBytes(StandardCharsets.UTF_8);
    }
    private static String text(byte[] b){return new String(b,StandardCharsets.UTF_8);}
    private <T> T use(LongFunction<T> fn){
        life.readLock().lock();
        try{if(handle==0)throw new IllegalStateException("Tree closed");return fn.apply(handle);}
        finally{life.readLock().unlock();}
    }
    public long observe(List<Observation> rows){
        if(rows.isEmpty()||rows.size()>256)throw new IllegalArgumentException("Batch size");
        byte[][] data=new byte[rows.size()*4][];long[] at=new long[rows.size()];
        for(int i=0;i<rows.size();i++){
            Observation o=rows.get(i);int k=i*4;
            data[k]=utf8(o.source());data[k+1]=utf8(o.externalId());data[k+2]=utf8(o.body());data[k+3]=utf8(o.language());at[i]=o.at();
        }
        return use(h->nObserve(h,data,at));
    }
    public List<Fragment> query(String query,long agent,long before,int limit){
        return use(h->{
            byte[][] a=nQuery(h,utf8(query),agent,before,limit);List<Fragment> out=new ArrayList<>();
            for(int i=0;i<a.length;i+=6)out.add(new Fragment(Long.parseLong(text(a[i])),text(a[i+1]),text(a[i+2]),text(a[i+3]),text(a[i+4]),Long.parseLong(text(a[i+5]))));
            return out;
        });
    }
    public List<Agent> agents(){
        return use(h->{byte[][] a=nAgents(h);List<Agent> out=new ArrayList<>();for(int i=0;i<a.length;i+=3)out.add(new Agent(Long.parseLong(text(a[i])),text(a[i+1]),text(a[i+2])));return out;});
    }
    public long createAgent(String name,String term){return use(h->nCreateAgent(h,utf8(name),utf8(term)));}
    public void removeAgent(long id){use(h->{nRemoveAgent(h,id);return null;});}
    public long count(){return use(NativeTree::nCount);}
    public long revision(){return use(NativeTree::nRevision);}
    public long compile(String path){return use(h->nCompile(h,utf8(path)));}
    public void clear(){use(h->{nClear(h);return null;});}
    @Override public void close(){life.writeLock().lock();try{if(handle!=0){nClose(handle);handle=0;}}finally{life.writeLock().unlock();}}
    public enum WorkState { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }
    public record Work(long id,long fragment,long priority,long deadline,long leaseUntil,long token,long attempts,
                       long ready,WorkState state,String operation,String profile,String worker,String output){}
    private static Work work(byte[][] a){
        if(a.length==0)return null;
        return new Work(Long.parseLong(text(a[0])),Long.parseLong(text(a[1])),Long.parseLong(text(a[2])),
            Long.parseLong(text(a[3])),Long.parseLong(text(a[4])),Long.parseLong(text(a[5])),Long.parseLong(text(a[6])),
            Long.parseLong(text(a[7])),WorkState.values()[Integer.parseInt(text(a[8]))],text(a[9]),text(a[10]),text(a[11]),text(a[12]));
    }
    public long requestWork(long fragment,String operation,String profile,long priority,long deadline){
        return use(h->nWorkRequest(h,fragment,utf8(operation),utf8(profile),priority,deadline));
    }
    public Work readWork(long id){return use(h->work(nWorkRead(h,id)));}
    public Work claimWork(String operation,String worker,long now,long duration){
        return use(h->work(nWorkClaim(h,utf8(operation),utf8(worker),now,duration)));
    }
    public boolean finishWork(Work job,String worker,long now,WorkState state,String output,long retry){
        return use(h->nWorkFinish(h,job.id(),job.token(),utf8(worker),now,state.ordinal(),utf8(output),retry));
    }
    public boolean renewWork(Work job,String worker,long now,long duration){
        return use(h->nWorkRenew(h,job.id(),job.token(),utf8(worker),now,duration));
    }
    public boolean cancelWork(long id){return use(h->nWorkCancel(h,id));}
    private static native long nWorkRequest(long h,long fragment,byte[] op,byte[] profile,long priority,long deadline);
    private static native byte[][] nWorkRead(long h,long id);
    private static native byte[][] nWorkClaim(long h,byte[] op,byte[] worker,long now,long duration);
    private static native boolean nWorkFinish(long h,long id,long token,byte[] worker,long now,int state,byte[] output,long retry);
    private static native boolean nWorkRenew(long h,long id,long token,byte[] worker,long now,long duration);
    private static native boolean nWorkCancel(long h,long id);
    private static native int nAbi();
    private static native long nOpen(byte[] path);
    private static native void nClose(long h);
    private static native long nObserve(long h,byte[][] rows,long[] times);
    private static native byte[][] nQuery(long h,byte[] term,long agent,long before,int limit);
    private static native byte[][] nRecords(long h,byte[] term,long agent,long before,int limit);
    private static native byte[][] nAgents(long h);
    private static native long nCreateAgent(long h,byte[] name,byte[] term);
    private static native void nRemoveAgent(long h,long id);
    private static native long nCount(long h);
    private static native long nRevision(long h);
    private static native long nCompile(long h,byte[] path);
    private static native void nClear(long h);
}
