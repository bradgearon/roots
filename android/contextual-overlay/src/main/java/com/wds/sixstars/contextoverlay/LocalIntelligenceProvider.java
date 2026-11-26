package com.wds.sixstars.contextoverlay;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One lazily loaded general-purpose intelligence model. All native work and
 * lifecycle changes run on the existing bounded serial inference queue.
 * No periodic polling and no background generation. */
final class LocalIntelligenceProvider {
    private static final long CONTEXT_IDLE_MS=30000, MODEL_IDLE_MS=120000;
    private static final ScheduledThreadPoolExecutor IDLE=new ScheduledThreadPoolExecutor(1,r->{
        Thread t=new Thread(r,"RootsIntelligenceIdle");t.setDaemon(true);return t;
    });
    static {IDLE.setRemoveOnCancelPolicy(true);}
    // Accessed only from WORK except for the read-only UI status.
    private static final EnumMap<IntelligenceModel,Stamp> verified=new EnumMap<>(IntelligenceModel.class);
    private static volatile IntelligenceModel resident;
    private static volatile boolean contextWarm;
    private static long epoch;
    private static ScheduledFuture<?> contextRelease,modelRelease;
    private record Stamp(long length,long modified){}
    private LocalIntelligenceProvider(){}

    interface Result {
        default void partial(String prefix){}
        void completed(String message,String error);
    }
    static String status(Context c,IntelligenceModel model){
        String state=resident==model?(contextWarm?"Warm · model and context loaded":"Weights loaded · context released"):
            resident==null?"Unloaded":"Another intelligence tier loaded";
        File f=file(c,model);
        return model.label+"\n"+(f.isFile()?"Model file present; SHA-256 checked on use":"Download required")+"\n"+state;
    }
    private static File file(Context c,IntelligenceModel model){
        File dir=c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if(dir==null)throw new IllegalStateException("App download directory unavailable.");
        return new File(dir,model.file);
    }
    private static String downloadKey(IntelligenceModel model){return "intelligence_download_"+model.name();}
    private static boolean downloading(Context c,IntelligenceModel model,DownloadManager dm){
        if(dm==null)return false;
        long id=c.getSharedPreferences("roots_models",0).getLong(downloadKey(model),-1);
        if(id<0)return false;
        try(Cursor cur=dm.query(new DownloadManager.Query().setFilterById(id))){
            if(cur!=null&&cur.moveToFirst()){
                int state=cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                return state==DownloadManager.STATUS_PENDING||state==DownloadManager.STATUS_RUNNING||state==DownloadManager.STATUS_PAUSED;
            }
        }
        return false;
    }
    private static boolean verify(File f,IntelligenceModel model)throws Exception{
        if(!f.isFile()||f.length()<=0||f.length()>model.maxBytes)return false;
        MessageDigest sha=MessageDigest.getInstance("SHA-256");byte[] bytes=new byte[262144];
        try(FileInputStream in=new FileInputStream(f)){int n;while((n=in.read(bytes))!=-1)sha.update(bytes,0,n);}
        StringBuilder hex=new StringBuilder();
        for(byte b:sha.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));
        return model.sha.equals(hex.toString());
    }
    static void download(Context context,IntelligenceModel model,Result result){
        Context app=context.getApplicationContext();
        submit(()->{
            try{
                DownloadManager dm=(DownloadManager)app.getSystemService(Context.DOWNLOAD_SERVICE);
                if(dm==null)throw new IllegalStateException("Download service unavailable.");
                if(downloading(app,model,dm)){result.completed("Download already in progress.",null);return;}
                File f=file(app,model);
                if(verify(f,model)){verified.put(model,new Stamp(f.length(),f.lastModified()));result.completed("Model installed and verified.",null);return;}
                if(resident==model)unload();
                verified.remove(model);
                if(f.exists()&&!f.delete())throw new IllegalStateException("Could not replace incomplete model.");
                long id=dm.enqueue(new DownloadManager.Request(Uri.parse(model.url()))
                    .setTitle("Roots: "+model.label).setAllowedOverMetered(false).setAllowedOverRoaming(false)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(app,Environment.DIRECTORY_DOWNLOADS,model.file));
                app.getSharedPreferences("roots_models",0).edit().putLong(downloadKey(model),id).apply();
                result.completed("Download queued. Run after download completes.",null);
            }catch(Exception|LinkageError e){result.completed(null,"Download failed: "+e.getMessage());}
        },()->result.completed(null,"Local inference queue is busy."));
    }
    private static void submit(Runnable task,Runnable rejected){
        try{LocalPLaMoTranslationProvider.WORK.execute(task);}
        catch(RejectedExecutionException e){rejected.run();}
    }
    private static void cancelIdle(){
        ++epoch;
        if(contextRelease!=null)contextRelease.cancel(false);
        if(modelRelease!=null)modelRelease.cancel(false);
        contextRelease=null;modelRelease=null;
    }
    private static void unload(){
        cancelIdle();
        IntelligenceModel old=resident;
        if(old!=null){
            LocalPLaMoTranslationProvider.Native.unloadIntelligence();
            resident=null;contextWarm=false;
            Log.i("RootsIntelligence","Released model "+old.name());
        }
    }
    private static void scheduleIdle(){
        cancelIdle();
        if(resident==null)return;
        long ticket=epoch;
        contextRelease=IDLE.schedule(()->enqueueRelease(ticket,false),CONTEXT_IDLE_MS,TimeUnit.MILLISECONDS);
        modelRelease=IDLE.schedule(()->enqueueRelease(ticket,true),MODEL_IDLE_MS,TimeUnit.MILLISECONDS);
    }
    private static void enqueueRelease(long ticket,boolean weights){
        // The generation check happens on WORK, so a queued or active request
        // can invalidate an old timeout. No native resource is freed mid-call.
        submit(()->{
            if(ticket!=epoch||resident==null)return;
            try{
                if(weights)unload();
                else if(contextWarm){
                    LocalPLaMoTranslationProvider.Native.releaseIntelligenceContext();
                    contextWarm=false;
                    Log.i("RootsIntelligence","Released idle context");
                }
            }catch(RuntimeException|LinkageError e){Log.w("RootsIntelligence","Idle release failed: "+e.getClass().getSimpleName());}
        },()->IDLE.schedule(()->enqueueRelease(ticket,weights),5000,TimeUnit.MILLISECONDS));
    }
    static void release(Result result){
        submit(()->{
            try{unload();result.completed("Intelligence memory released; model files retained.",null);}
            catch(RuntimeException|LinkageError e){result.completed(null,"Release failed: "+e.getClass().getSimpleName());}
        },()->result.completed(null,"Local inference queue is busy."));
    }
    private static void ensure(Context app,IntelligenceModel model,Consumer<String> progress)throws Exception{
        cancelIdle();
        if(downloading(app,model,(DownloadManager)app.getSystemService(Context.DOWNLOAD_SERVICE)))
            throw new IllegalStateException("Model download in progress.");
        File f=file(app,model);
        Stamp stamp=verified.get(model);
        if(!f.isFile()||stamp==null||f.length()!=stamp.length()||f.lastModified()!=stamp.modified()){
            if(resident==model)unload();
            verified.remove(model);
            progress.accept("Verifying model SHA-256…");
            if(!verify(f,model))throw new IllegalStateException("Download the selected intelligence model first.");
            verified.put(model,new Stamp(f.length(),f.lastModified()));
        }
        if(resident!=model){
            unload();
            progress.accept("Loading "+model.label+"…");
            LocalPLaMoTranslationProvider.Native.loadIntelligence(f.getAbsolutePath());
            resident=model;contextWarm=false;
            Log.i("RootsIntelligence","Loaded model "+model.name());
        }
    }
    private static String infer(Context app,IntelligenceModel model,String operation,String user,long[] stats,Consumer<String> partial)throws Exception{
        InferenceHistory.Attempt attempt=InferenceHistory.get(app).begin("Phone · CPU",model.label,operation,"General intelligence · local generation");
        attempt.running("Running");
        try{
            byte[] bytes=LocalPLaMoTranslationProvider.Native.generateIntelligence(
                model.prompt(user).getBytes(StandardCharsets.UTF_8),stats,new OverlayPreferences(app).localThreads(),
                prefix->partial.accept(StreamText.utf8Prefix(prefix)));
            if(bytes==null)throw new IllegalStateException("Native inference returned no buffer.");
            contextWarm=true;
            String answer=new String(bytes,StandardCharsets.UTF_8).trim();
            if(answer.isEmpty())throw new IllegalStateException("Empty intelligence output.");
            attempt.finish(true,"Generation completed · separate validation");
            return answer;
        }catch(Exception|LinkageError e){contextWarm=false;attempt.finish(false,"Local intelligence generation failed");throw e;}
    }
    static void generate(Context context,IntelligenceModel model,String request,Result result){
        Context app=context.getApplicationContext();
        if(request==null||request.trim().isEmpty()||request.length()>3000||request.indexOf('<')>=0){
            result.completed(null,"Enter plain text (up to 3000 characters).");return;
        }
        submit(()->{
            try{
                ensure(app,model,result::partial);
                String answer=infer(app,model,"Intelligence sample",request,new long[8],result::partial);
                result.completed(answer,null);
            }catch(Exception|LinkageError e){result.completed(null,e.getClass().getSimpleName()+": "+e.getMessage());}
            finally{scheduleIdle();}
        },()->result.completed(null,"Local inference queue is busy."));
    }
    static void testMovement(Context context,IntelligenceModel model,PoeticMovement.Input input,LocalFunctionGemmaProvider.MovementResult result){
        Context app=context.getApplicationContext();long submitted=SystemClock.elapsedRealtime();
        submit(()->{
            try{
                ensure(app,model,msg->result.progress("loading",msg));
                long[] firstStats=new long[8],secondStats=new long[8];
                result.progress("select","Request 1 / 2: choosing five movements…");
                String first=infer(app,model,"Poetic selection",PoeticMovement.intelligenceSelection(input),firstStats,
                    prefix->result.progress("selection_stream",prefix));
                List<PoeticMovement.Pair> pairs;
                try{pairs=PoeticMovement.parseSelectionRows(input,rows(first));}
                catch(IllegalArgumentException e){result.progress("invalid_selection",first);throw e;}
                result.progress("selected",PoeticMovement.describe(pairs));
                result.progress("resolve","Request 2 / 2: resolving displaced meanings…");
                String second=infer(app,model,"Poetic resolution",PoeticMovement.intelligenceResolution(input,pairs),secondStats,
                    prefix->result.progress("resolution_stream",prefix));
                List<PoeticMovement.Resolution> resolutions;
                try{resolutions=PoeticMovement.parseResolutionRows(input,rows(second));}
                catch(IllegalArgumentException e){result.progress("invalid_resolution",second);throw e;}
                result.completed(new PoeticMovement.Run(input,pairs,resolutions,firstStats,secondStats,SystemClock.elapsedRealtime()-submitted),null);
            }catch(Exception|LinkageError e){result.completed(null,e.getClass().getSimpleName()+": "+e.getMessage());}
            finally{scheduleIdle();}
        },()->result.completed(null,"Local inference queue is busy."));
    }
    // Accept the requested separator or five plain newline-separated rows.
    // Do not repair invalid indices, invent rows or execute generated text.
    static String rows(String raw){
        String value=raw.trim();
        if(value.endsWith(";"))value=value.substring(0,value.length()-1).trim();
        if(!value.contains(";"))value=value.replaceAll("\\r?\\n+",";");
        return value;
    }
}
