package com.wds.sixstars.contextoverlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded local metadata only. No prompts, results, network IDs, IPs or keys.
 * Attempt completion, not a route preference, proves where work finished. */
final class InferenceHistory {
    private static final int LIMIT=100;
    private static InferenceHistory instance;
    static synchronized InferenceHistory get(Context context){
        if(instance==null)instance=new InferenceHistory(context.getApplicationContext());
        return instance;
    }
    record Entry(String id,long time,String device,String model,String operation,String state,long ms,String detail){}
    private final Context app;
    private final SharedPreferences prefs;
    private final ArrayList<Entry> rows=new ArrayList<>();
    private final CopyOnWriteArrayList<Runnable> listeners=new CopyOnWriteArrayList<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean notification=new AtomicBoolean();
    private final java.util.concurrent.ExecutorService disk=java.util.concurrent.Executors.newSingleThreadExecutor(r->{
        Thread thread=new Thread(r,"RootsInferenceHistory");thread.setDaemon(true);return thread;
    });
    private final AtomicBoolean saving=new AtomicBoolean();
    private long revision;
    private InferenceHistory(Context app){
        this.app=app;prefs=app.getSharedPreferences("roots_inference_history",Context.MODE_PRIVATE);
        try{
            JSONArray data=new JSONArray(prefs.getString("rows","[]"));
            for(int i=Math.max(0,data.length()-LIMIT);i<data.length();i++){
                JSONObject item=data.getJSONObject(i);
                String state=item.getString("state");
                if("Queued".equals(state)||"Running".equals(state)||"Sent".equals(state))state="Interrupted";
                rows.add(new Entry(item.getString("id"),item.getLong("time_ms"),item.getString("device"),item.getString("model"),item.getString("operation"),state,item.optLong("elapsed_ms",-1),item.optString("detail")));
            }
        }catch(Exception ignored){rows.clear();}
    }
    final class Attempt {
        private final String id=UUID.randomUUID().toString();
        private final long started=SystemClock.elapsedRealtime(),time=System.currentTimeMillis();
        private final String device,model,operation,reason;
        private boolean done;
        Attempt(String device,String model,String operation,String reason){
            this.device=device;this.model=model;this.operation=operation;this.reason=reason;
            update("Queued",-1,reason);
        }
        synchronized void running(String state){if(!done)update(state,-1,reason);}
        synchronized void finish(boolean success,String detail){
            if(done)return;done=true;
            update(success?"Completed":"Failed",Math.max(0,SystemClock.elapsedRealtime()-started),detail);
        }
        private void update(String state,long ms,String detail){
            put(new Entry(id,time,device,model,operation,state,ms,detail));
        }
    }
    Attempt begin(String device,String model,String operation,String reason){return new Attempt(device,model,operation,reason);}
    private synchronized void put(Entry entry){
        int index=-1;for(int i=0;i<rows.size();i++)if(rows.get(i).id().equals(entry.id())){index=i;break;}
        if(index>=0)rows.set(index,entry);
        else {rows.add(entry);while(rows.size()>LIMIT)rows.remove(0);}
        changed();
    }
    synchronized List<Entry> snapshot(){ArrayList<Entry> copy=new ArrayList<>(rows);Collections.reverse(copy);return copy;}
    synchronized void clear(){rows.clear();changed();}
    void listen(Runnable listener){listeners.addIfAbsent(listener);}
    void unlisten(Runnable listener){listeners.remove(listener);}
    private void changed(){
        revision++;
        if(notification.compareAndSet(false,true))main.post(()->{
            notification.set(false);for(Runnable listener:listeners)listener.run();
        });
        if(saving.compareAndSet(false,true))disk.execute(this::persist);
    }
    private void persist(){
        for(;;){
            final List<Entry> copy;final long version;
            synchronized(this){copy=new ArrayList<>(rows);version=revision;}
            try{
                JSONArray array=new JSONArray();StringBuilder jsonl=new StringBuilder();
                for(Entry row:copy){
                    JSONObject j=new JSONObject().put("id",row.id()).put("time_ms",row.time())
                        .put("device",row.device()).put("model",row.model()).put("operation",row.operation())
                        .put("state",row.state()).put("elapsed_ms",row.ms()).put("detail",row.detail());
                    array.put(j);jsonl.append(j).append('\n');
                }
                prefs.edit().putString("rows",array.toString()).apply();
                java.io.File dir=new java.io.File(app.getFilesDir(),"diagnostics");
                if(dir.isDirectory()||dir.mkdirs()){
                    android.util.AtomicFile file=new android.util.AtomicFile(new java.io.File(dir,"inference-history.jsonl"));
                    java.io.FileOutputStream out=null;
                    try{out=file.startWrite();out.write(jsonl.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));file.finishWrite(out);}
                    catch(Exception e){if(out!=null)file.failWrite(out);}
                }
            }catch(Exception ignored){}
            synchronized(this){if(revision==version){saving.set(false);return;}}
        }
    }
}
