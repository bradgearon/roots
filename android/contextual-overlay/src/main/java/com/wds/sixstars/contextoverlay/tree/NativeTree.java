package com.wds.sixstars.contextoverlay.tree;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongFunction;

/** Shared C++ Tree adapter. No platform-specific knowledge database. */
public final class NativeTree implements AutoCloseable {
    private static final boolean AVAILABLE;
    static {
        boolean available=false;
        try {System.loadLibrary("roots_tree");available=nAbi()==1;}
        catch(LinkageError | RuntimeException ignored){}
        AVAILABLE=available;
    }
    public static boolean available(){return AVAILABLE;}
    public record Observation(String source,String externalId,String body,String language,long at){}
    public record Fragment(long sequence,String source,String externalId,String body,String language,long at){}
    public record Agent(long id,String name,String term){}
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
    private static native int nAbi();
    private static native long nOpen(byte[] path);
    private static native void nClose(long h);
    private static native long nObserve(long h,byte[][] rows,long[] times);
    private static native byte[][] nQuery(long h,byte[] term,long agent,long before,int limit);
    private static native byte[][] nAgents(long h);
    private static native long nCreateAgent(long h,byte[] name,byte[] term);
    private static native void nRemoveAgent(long h,long id);
    private static native long nCount(long h);
    private static native long nRevision(long h);
    private static native long nCompile(long h,byte[] path);
    private static native void nClear(long h);
}
