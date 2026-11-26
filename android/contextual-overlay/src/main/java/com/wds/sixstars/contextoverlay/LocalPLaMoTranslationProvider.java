package com.wds.sixstars.contextoverlay;
import android.app.DownloadManager;
import android.os.SystemClock;
import android.util.Log;
import java.io.FileOutputStream;
import org.json.JSONObject;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

public final class LocalPLaMoTranslationProvider implements TranslationProvider {
    private static final String FILE = "plamo-3-translate-0.8b-Q4_0.gguf";
    private static final String URL = "https://huggingface.co/pfnet/plamo-3-translate-0.8b-GGUF/resolve/7de4409/plamo-3-translate-0.8b-Q4_0.gguf";
    // One bounded inference queue shared with the function model.
    static final ThreadPoolExecutor WORK = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(3));
    private static long verifiedLength=-1, verifiedModified=-1;
    private static String loadedPath;
    private static boolean loadedVulkan;
    private static String loadedAcceleration="";
    public static boolean vulkanBuilt() {
        try { return Native.vulkanBuilt(); } catch (LinkageError | RuntimeException e) { return false; }
    }
    private final Context app;
    private static final String TAG = "RootsPLaMo";
    // Targeted model lifecycle trace only. No prompts, translations, credentials or tweet text.
    private static void trace(Context c,String stage,String status,long started,String detail) {
        trace(c,stage,status,started,detail,null,-1);
    }
    private static void trace(Context c,String stage,String status,long started,String detail,long[] stats,long queueMs) {
        try {
            File dir=new File(c.getFilesDir(),"diagnostics");
            if(!dir.isDirectory()&&!dir.mkdirs())return;
            File log=new File(dir,"plamo-lifecycle.jsonl");
            if(log.length()>512*1024L){
                File old=new File(dir,"plamo-lifecycle.previous.jsonl");
                if(old.exists()&&!old.delete())return;
                if(!log.renameTo(old))return;
            }
            JSONObject entry=new JSONObject();
            entry.put("time_ms",System.currentTimeMillis());entry.put("component","local-plamo");
            entry.put("stage",stage);entry.put("status",status);
            entry.put("elapsed_ms",Math.max(0,SystemClock.elapsedRealtime()-started));
            entry.put("detail",detail);
            if(queueMs>=0)entry.put("queue_ms",queueMs);
            if(stats!=null&&stats.length==8){
                entry.put("context_reused",stats[0]!=0);
                entry.put("context_setup_ms",stats[1]);
                entry.put("tokenize_ms",stats[2]);
                entry.put("prefill_ms",stats[3]);
                entry.put("generation_ms",stats[4]);
                entry.put("prompt_tokens",stats[5]);
                entry.put("sampled_tokens",stats[6]);
                entry.put("threads",stats[7]);
            }
            try(FileOutputStream stream=new FileOutputStream(log,true)){
                stream.write((entry.toString()+"\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch(Exception ignored) { Log.w(TAG,"Could not write lifecycle diagnostic"); }
    }
    private static String explain(Throwable error) {
        String message=error.getMessage();
        if(message==null||message.trim().isEmpty())return error.getClass().getSimpleName();
        return error.getClass().getSimpleName()+": "+message.substring(0,Math.min(message.length(),2200));
    }
    LocalPLaMoTranslationProvider(Context context) { app=context.getApplicationContext(); }
    private static File model(Context c) { return new File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FILE); }
    private static String expected(Context c) { return c.getSharedPreferences("roots_models",0).getString("plamo_sha256",""); }
    private static boolean downloading(Context c, DownloadManager dm) {
        long id=c.getSharedPreferences("roots_models",0).getLong("plamo_download",-1);
        if(id==-1)return false;
        try(android.database.Cursor cursor=dm.query(new DownloadManager.Query().setFilterById(id))) {
            if(cursor.moveToFirst()){
                int status=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                return status==DownloadManager.STATUS_PENDING||status==DownloadManager.STATUS_RUNNING||status==DownloadManager.STATUS_PAUSED;
            }
        }
        return false;
    }
    public static void downloadModel(Context context, Result callback) {
        Context c=context.getApplicationContext();
        try { WORK.execute(() -> {
            long started=SystemClock.elapsedRealtime();
            String stage="download_check";
            try {
                DownloadManager dm=(DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE);
                if(downloading(c,dm)){callback.completed("Download already in progress.",null);return;}
                File file=model(c);
                if(file.isFile() && verify(c,file)) { callback.completed("Model is installed.",null); return; }
                stage="publisher_digest";
                try (Response response=new OkHttpClient.Builder().followRedirects(false).build().newCall(new Request.Builder().url(URL).head().build()).execute()) {
                    String digest=response.header("X-Linked-Etag","");
                    digest=digest.replace("\"","").trim();
                    if(!digest.matches("(?i)[0-9a-f]{64}")) throw new IllegalStateException("Publisher model SHA-256 unavailable.");
                    c.getSharedPreferences("roots_models",0).edit().putString("plamo_sha256",digest).apply();
                }
                if(file.isFile() && verify(c,file)){callback.completed("Model is installed.",null);return;}
                if(loadedPath!=null) { Native.unload(); loadedPath=null; }
                verifiedLength=-1; verifiedModified=-1;
                if(file.exists()&&!file.delete()) throw new IllegalStateException("Cannot replace model.");
                stage="download_enqueue";
                long id=dm.enqueue(new DownloadManager.Request(Uri.parse(URL)).setTitle("Roots: PLaMo 0.8B").setAllowedOverMetered(false).setAllowedOverRoaming(false).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationInExternalFilesDir(c,Environment.DIRECTORY_DOWNLOADS,FILE));
                c.getSharedPreferences("roots_models",0).edit().putLong("plamo_download",id).apply();
                trace(c,stage,"ok",started,"DownloadManager accepted model download");
                callback.completed("Model download queued over Wi-Fi. Return after completion and test translation.",null);
            } catch(Exception|LinkageError ex){trace(c,stage,"error",started,ex.getClass().getSimpleName());callback.completed(null,"Model download unavailable at "+stage+": "+explain(ex));}
        }); } catch(Exception busy){callback.completed(null,"Translation queue is busy.");}
    }
    private static boolean verify(Context c, File file) throws Exception {
        String sha=expected(c); if(!sha.matches("(?i)[0-9a-f]{64}") || !file.isFile() || file.length()>800000000L) return false;
        MessageDigest md=MessageDigest.getInstance("SHA-256"); byte[] bytes=new byte[262144];
        try(FileInputStream in=new FileInputStream(file)){int n;while((n=in.read(bytes))!=-1)md.update(bytes,0,n);}
        StringBuilder h=new StringBuilder();for(byte b:md.digest())h.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return sha.equalsIgnoreCase(h.toString());
    }
    @Override public void translate(String text,String source,String target,Result callback) {
        if(callback==null)return;
        if(!new OverlayPreferences(app).enabled()){callback.completed(null,"Grant X text processing consent first.");return;}
        if(text==null || text.trim().isEmpty() || text.length()>1200 || target==null || !target.matches("[a-z]{2,3}") || text.contains("<|plamo:")){callback.completed(null,"Invalid translation request.");return;}
        long submitted=SystemClock.elapsedRealtime();
        try{WORK.execute(()->{
            long started=SystemClock.elapsedRealtime();
            long queueMs=started-submitted;
            String stage="verify_model";
            try {
                OverlayPreferences preferences=new OverlayPreferences(app);
                if(!preferences.enabled() || preferences.desktopTranslationEnabled()){callback.completed(null,"Translation mode changed.");return;}
                boolean useVulkan=preferences.localVulkanEnabled();
                int threads=preferences.localThreads();
                File file=model(app);
                if(downloading(app,(DownloadManager)app.getSystemService(Context.DOWNLOAD_SERVICE))){callback.completed(null,"Local model download is still in progress.");return;}
                if(!file.isFile()||file.length()!=verifiedLength||file.lastModified()!=verifiedModified){
                    if(!verify(app,file)){callback.completed(null,"Download the local PLaMo model from Roots settings first.");return;}
                    verifiedLength=file.length();verifiedModified=file.lastModified();
                    if(loadedPath!=null){Native.unload();loadedPath=null;}
                }
                stage="native_library";
                Native.ensure();
                String revision=Native.revision();
                String path=file.getAbsolutePath();
                if(useVulkan&&!Native.vulkanBuilt())throw new IllegalStateException("Vulkan is not compiled into this APK; choose CPU in settings.");
                String requestedBackend=useVulkan?"vulkan":"cpu";
                if(!path.equals(loadedPath)||loadedVulkan!=useVulkan){
                    stage="model_load";loadedPath=null;
                    long loadStart=SystemClock.elapsedRealtime();
                    trace(app,stage,"start",loadStart,"runtime="+revision+" requested_backend="+requestedBackend+" bytes="+file.length());
                    Native.load(path,useVulkan);loadedPath=path;loadedVulkan=useVulkan;
                    loadedAcceleration=Native.accelerationInfo();
                    trace(app,stage,"ok",loadStart,"runtime="+revision+" "+loadedAcceleration);
                }
                String prompt="<|plamo:tag|>dataset<|plamo:msg|>translation<|plamo:tag|>input"+
                    (source!=null&&source.matches("[a-z]{2,3}")?"<|plamo:key|>lang<|plamo:val|>"+source:"")+
                    "<|plamo:msg|>"+text+"<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>"+target+"<|plamo:msg|>";
                stage="inference";
                callback.started();
                long inferenceStart=SystemClock.elapsedRealtime();
                trace(app,stage,"start",inferenceStart,"runtime="+revision+" requested_backend="+requestedBackend+" requested_threads="+threads,null,queueMs);
                long[] stats=new long[8];
                byte[] result=Native.generateStreaming(prompt.getBytes(StandardCharsets.UTF_8),stats,threads,bytes->{if(preferences.enabled()&&!preferences.desktopTranslationEnabled())callback.partial(StreamText.translationPreview(bytes));});
                if(result==null)throw new IllegalStateException("Native runtime returned no result buffer");
                trace(app,stage,"ok",inferenceStart,"runtime="+revision+" "+loadedAcceleration+" result_bytes="+result.length,stats,queueMs);
                String translated=new String(result,StandardCharsets.UTF_8).trim();
                if(translated.isEmpty())callback.completed(null,"No translation returned.");else callback.completed(translated,null);
            } catch(Exception|LinkageError ex){
                // Native load/decode errors contain stage and return code, not source content.
                trace(app,stage,"error",started,stage.equals("model_load")?explain(ex):ex.getClass().getSimpleName());
                Log.w(TAG,"Local inference failed at "+stage+" ("+ex.getClass().getSimpleName()+")");
                callback.completed(null,"Local inference unavailable at "+stage+": "+explain(ex));
            }
        });}catch(Exception busy){callback.completed(null,"Translation queue is busy.");}
    }
    // Keep all JNI declarations here so the separate brain build validates its complete ABI.
    interface PrefixListener { void onPrefix(byte[] prefix); }
    static class Native {
        static { System.loadLibrary("roots_plamo"); }
        static void ensure(){}
        static native String revision();
        static native boolean vulkanBuilt();
        static native String accelerationInfo();
        static native void load(String path,boolean useVulkan);
        static native void unload();
        static native byte[] generate(byte[] prompt,long[] stats,int threads);
        static native void loadFunction(String path);
        static native void unloadFunction();
        static native byte[] generateFunction(byte[] prompt,long[] stats,int threads);
        static native byte[] generateStreaming(byte[] prompt,long[] stats,int threads,PrefixListener listener);
        static native byte[] generateFunctionStreaming(byte[] prompt,long[] stats,int threads,PrefixListener listener);
    }
}
