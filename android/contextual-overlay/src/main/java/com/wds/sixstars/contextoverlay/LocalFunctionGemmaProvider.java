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
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.json.JSONObject;

/** User-initiated local operation proposals. Never executes a model output. */
public final class LocalFunctionGemmaProvider {
    public static final String MODEL="functiongemma-270m-it-q8_0.gguf";
    public static final String MODEL_PAGE="https://huggingface.co/ggml-org/functiongemma-270m-it-GGUF";
    public static final String TERMS="https://ai.google.dev/gemma/terms";
    // ggml-org's 292 MB Q8_0 artifact, with published file SHA-256.
    private static final String URL=MODEL_PAGE+"/resolve/aa87651/"+MODEL;
    private static final String SHA256="83940d4dd9676710856f43523bed096164a595a96f6b34771610a03937de5270";
    private static final String KEY="functiongemma_download";
    private static long verifiedLength=-1,verifiedModified=-1;
    private static boolean loaded=false;
    private LocalFunctionGemmaProvider(){}
    public interface Result { default void partial(String prefix){} void completed(String message,String error); }
    private static File model(Context c){
        File dir=c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if(dir==null)throw new IllegalStateException("App download directory unavailable.");
        return new File(dir,MODEL);
    }
    private static boolean downloading(Context c,DownloadManager dm){
        long id=c.getSharedPreferences("roots_models",0).getLong(KEY,-1);
        if(id<0)return false;
        try(Cursor cursor=dm.query(new DownloadManager.Query().setFilterById(id))){
            if(cursor!=null&&cursor.moveToFirst()){
                int status=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                return status==DownloadManager.STATUS_PENDING||status==DownloadManager.STATUS_RUNNING||status==DownloadManager.STATUS_PAUSED;
            }
        }
        return false;
    }
    private static boolean verify(File f)throws Exception{
        if(!f.isFile()||f.length()==0||f.length()>350_000_000L)return false;
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        byte[] b=new byte[262144];
        try(FileInputStream in=new FileInputStream(f)){int n;while((n=in.read(b))!=-1)md.update(b,0,n);}
        StringBuilder h=new StringBuilder();for(byte value:md.digest())h.append(String.format(Locale.ROOT,"%02x",value&255));
        return SHA256.equals(h.toString());
    }
    private static void trace(Context c,String stage,String status,long start,long[] stats,long queueMs){
        // Metadata only: never persist input, tool arguments, model output or secrets.
        try{
            File dir=new File(c.getFilesDir(),"diagnostics");
            if(!dir.isDirectory()&&!dir.mkdirs())return;
            File log=new File(dir,"functiongemma-lifecycle.jsonl");
            if(log.length()>512*1024L){
                File old=new File(dir,"functiongemma-lifecycle.previous.jsonl");
                if(old.exists()&&!old.delete())return;
                if(!log.renameTo(old))return;
            }
            JSONObject j=new JSONObject();
            j.put("time_ms",System.currentTimeMillis());j.put("component","local-functiongemma");
            j.put("stage",stage);j.put("status",status);j.put("backend","cpu");
            j.put("elapsed_ms",Math.max(0,SystemClock.elapsedRealtime()-start));
            if(queueMs>=0)j.put("queue_ms",queueMs);
            if(stats!=null){
                j.put("context_reused",stats[0]!=0);j.put("context_setup_ms",stats[1]);
                j.put("tokenize_ms",stats[2]);j.put("prefill_ms",stats[3]);j.put("generation_ms",stats[4]);
                j.put("prompt_tokens",stats[5]);j.put("sampled_tokens",stats[6]);j.put("threads",stats[7]);
            }
            try(FileOutputStream out=new FileOutputStream(log,true)){out.write((j.toString()+"\n").getBytes(StandardCharsets.UTF_8));}
        }catch(Exception ignored){Log.w("RootsFunctionGemma","Could not write diagnostic");}
    }
    private static void unload(){
        if(loaded){
            loaded=false;
            LocalPLaMoTranslationProvider.Native.unloadFunction();
        }
    }
    /** Called only after the download dialog confirms the model terms. */
    public static void download(Context context,Result result){
        Context c=context.getApplicationContext();
        try{LocalPLaMoTranslationProvider.WORK.execute(()->{
            long start=SystemClock.elapsedRealtime();
            try{
                DownloadManager dm=(DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE);
                if(downloading(c,dm)){result.completed("FunctionGemma download in progress.",null);return;}
                File f=model(c);
                if(verify(f)){result.completed("FunctionGemma is installed and verified.",null);return;}
                unload();verifiedLength=-1;verifiedModified=-1;
                if(f.exists()&&!f.delete())throw new IllegalStateException("Cannot replace invalid model.");
                long id=dm.enqueue(new DownloadManager.Request(Uri.parse(URL)).setTitle("Roots: FunctionGemma 270M")
                    .setAllowedOverMetered(false).setAllowedOverRoaming(false)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(c,Environment.DIRECTORY_DOWNLOADS,MODEL));
                c.getSharedPreferences("roots_models",0).edit().putLong(KEY,id).apply();
                trace(c,"download","queued",start,null,-1);
                result.completed("Download queued over Wi-Fi. Test after it finishes.",null);
            }catch(Exception|LinkageError e){
                trace(c,"download","error",start,null,-1);
                result.completed(null,"FunctionGemma download failed: "+e.getClass().getSimpleName()+": "+e.getMessage());
            }
        });}catch(java.util.concurrent.RejectedExecutionException e){result.completed(null,"Local inference queue is busy.");}
    }
    public static void release(Context context,Result result){
        try{LocalPLaMoTranslationProvider.WORK.execute(()->{
            try{unload();result.completed("FunctionGemma memory released; downloaded model retained.",null);}
            catch(Exception|LinkageError e){result.completed(null,"Could not release FunctionGemma: "+e.getClass().getSimpleName());}
        });}catch(java.util.concurrent.RejectedExecutionException e){result.completed(null,"Local inference queue is busy.");}
    }
    /** Two bounded, fresh-context calls on the existing shared local inference queue. */
    public interface MovementResult {
        void progress(String stage,String message);
        void completed(PoeticMovement.Run run,String error);
    }
    public static void testMovement(Context context,PoeticMovement.Input input,MovementResult result){
        Context app=context.getApplicationContext();
        long submitted=SystemClock.elapsedRealtime();
        try{LocalPLaMoTranslationProvider.WORK.execute(()->{
            String stage="verify_model";
            long start=SystemClock.elapsedRealtime();
            try{
                File f=model(app);
                DownloadManager dm=(DownloadManager)app.getSystemService(Context.DOWNLOAD_SERVICE);
                if(downloading(app,dm))throw new IllegalStateException("FunctionGemma is still downloading.");
                if(!f.isFile()||f.length()!=verifiedLength||f.lastModified()!=verifiedModified){
                    unload();verifiedLength=-1;verifiedModified=-1;
                    if(!verify(f))throw new IllegalStateException("Download the verified FunctionGemma model first.");
                    verifiedLength=f.length();verifiedModified=f.lastModified();
                }
                stage="model_load";
                if(!loaded){
                    long loadStart=SystemClock.elapsedRealtime();
                    LocalPLaMoTranslationProvider.Native.loadFunction(f.getAbsolutePath());loaded=true;
                    trace(app,stage,"ok",loadStart,null,start-submitted);
                }
                int threads=new OverlayPreferences(app).localThreads();
                stage="movement_select";
                result.progress(stage,"Request 1 / 2: selecting pairs and ordinary actions…");
                String firstPrompt=PoeticMovement.selectionPrompt(input);
                long[] firstStats=new long[8];
                long firstStart=SystemClock.elapsedRealtime();
                String first=movementGenerate(firstPrompt,firstStats,threads,prefix->result.progress("selection_stream",prefix));
                trace(app,stage,"ok",firstStart,firstStats,start-submitted);
                java.util.List<PoeticMovement.Pair> pairs;
                try{pairs=PoeticMovement.parseSelection(input,first);}
                catch(IllegalArgumentException e){
                    result.progress("invalid_selection",first);
                    throw new IllegalArgumentException("Request 1 returned invalid selection: "+e.getMessage());
                }
                result.progress("selected",PoeticMovement.describe(pairs));
                stage="movement_resolve";
                result.progress(stage,"Request 2 / 2: replacing ordinary actions and resolving differences…");
                long[] secondStats=new long[8];long secondStart=SystemClock.elapsedRealtime();
                String second=movementGenerate(PoeticMovement.resolutionPrompt(input,pairs),secondStats,threads,prefix->result.progress("resolution_stream",prefix));
                trace(app,stage,"ok",secondStart,secondStats,0);
                java.util.List<PoeticMovement.Resolution> resolutions;
                try{resolutions=PoeticMovement.parseResolution(input,second);}
                catch(IllegalArgumentException e){
                    result.progress("invalid_resolution",second);
                    throw new IllegalArgumentException("Request 2 returned invalid resolution: "+e.getMessage());
                }
                result.completed(new PoeticMovement.Run(input,pairs,resolutions,firstStats,secondStats,SystemClock.elapsedRealtime()-submitted),null);
            }catch(Exception|LinkageError e){
                trace(app,stage,"error",start,null,start-submitted);
                result.completed(null,e.getClass().getSimpleName()+": "+e.getMessage());
            }
        });}catch(java.util.concurrent.RejectedExecutionException e){result.completed(null,"Local inference queue is busy.");}
    }
    private static String movementGenerate(String prompt,long[] stats,int threads,java.util.function.Consumer<String> progress){
        byte[] out=LocalPLaMoTranslationProvider.Native.generateFunctionStreaming(prompt.getBytes(StandardCharsets.UTF_8),stats,threads,bytes->progress.accept(StreamText.functionPreview(bytes)));
        if(out==null)throw new IllegalStateException("Native runtime returned no result.");
        return new String(out,StandardCharsets.UTF_8);
    }
    public static void propose(Context context,String request,Result result){
        final String prompt;
        try{prompt=FunctionGemmaProtocol.prompt(request);}catch(IllegalArgumentException e){result.completed(null,e.getMessage());return;}
        Context c=context.getApplicationContext();long submitted=SystemClock.elapsedRealtime();
        try{LocalPLaMoTranslationProvider.WORK.execute(()->{
            long start=SystemClock.elapsedRealtime();long queueMs=start-submitted;String stage="verify_model";long[] stats=null;
            try{
                File f=model(c);
                DownloadManager dm=(DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE);
                if(downloading(c,dm))throw new IllegalStateException("FunctionGemma is still downloading.");
                if(!f.isFile()||f.length()!=verifiedLength||f.lastModified()!=verifiedModified){
                    unload();verifiedLength=-1;verifiedModified=-1;
                    if(!verify(f))throw new IllegalStateException("Download the verified FunctionGemma model first.");
                    verifiedLength=f.length();verifiedModified=f.lastModified();
                }
                stage="model_load";
                if(!loaded){
                    long loadStart=SystemClock.elapsedRealtime();
                    LocalPLaMoTranslationProvider.Native.loadFunction(f.getAbsolutePath());loaded=true;
                    trace(c,"model_load","ok",loadStart,null,queueMs);
                }
                stage="inference";long inferenceStart=SystemClock.elapsedRealtime();stats=new long[8];
                byte[] output=LocalPLaMoTranslationProvider.Native.generateFunctionStreaming(prompt.getBytes(StandardCharsets.UTF_8),stats,new OverlayPreferences(c).localThreads(),bytes->result.partial(StreamText.functionPreview(bytes)));
                if(output==null)throw new IllegalStateException("Native runtime returned no result.");
                trace(c,"inference","ok",inferenceStart,stats,queueMs);
                stage="validate_proposal";
                FunctionGemmaProtocol.Proposal proposal=FunctionGemmaProtocol.parse(new String(output,StandardCharsets.UTF_8));
                trace(c,stage,"ok",start,null,queueMs);
                result.completed("Proposal only — not executed\n"+proposal+"\nInference: "+(stats[1]+stats[2]+stats[3]+stats[4])+" ms\nEnd-to-end: "+(SystemClock.elapsedRealtime()-submitted)+" ms",null);
            }catch(Exception|LinkageError e){
                trace(c,stage,"error",start,stats,queueMs);
                // Invalid tool syntax is safe failure; do not display raw generated commands.
                String message=stage.equals("validate_proposal")?"Model returned no valid allowed operation. Try a more explicit request.":e.getClass().getSimpleName()+": "+e.getMessage();
                result.completed(null,message);
            }
        });}catch(java.util.concurrent.RejectedExecutionException e){result.completed(null,"Local inference queue is busy.");}
    }
}
