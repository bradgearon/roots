package com.wds.sixstars.contextoverlay;
import android.app.DownloadManager;
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
    private static final ThreadPoolExecutor WORK = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(3));
    private static long verifiedLength=-1, verifiedModified=-1;
    private static String loadedPath;
    private final Context app;
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
            try {
                DownloadManager dm=(DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE);
                if(downloading(c,dm)){callback.completed("Download already in progress.",null);return;}
                File file=model(c);
                if(file.isFile() && verify(c,file)) { callback.completed("Model is installed.",null); return; }
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
                long id=dm.enqueue(new DownloadManager.Request(Uri.parse(URL)).setTitle("Roots: PLaMo 0.8B").setAllowedOverMetered(false).setAllowedOverRoaming(false).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationInExternalFilesDir(c,Environment.DIRECTORY_DOWNLOADS,FILE));
                c.getSharedPreferences("roots_models",0).edit().putLong("plamo_download",id).apply();
                callback.completed("Model download queued over Wi-Fi. Return after completion and test translation.",null);
            } catch(Exception|LinkageError ex){callback.completed(null,"Model download unavailable: "+ex.getClass().getSimpleName());}
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
        try{WORK.execute(()->{
            try {
                if(!new OverlayPreferences(app).enabled() || new OverlayPreferences(app).desktopTranslationEnabled()){callback.completed(null,"Translation mode changed.");return;}
                File file=model(app);
                if(downloading(app,(DownloadManager)app.getSystemService(Context.DOWNLOAD_SERVICE))){callback.completed(null,"Local model download is still in progress.");return;}
                if(!file.isFile()||file.length()!=verifiedLength||file.lastModified()!=verifiedModified){
                    if(!verify(app,file)){callback.completed(null,"Download the local PLaMo model from Roots settings first.");return;}
                    verifiedLength=file.length();verifiedModified=file.lastModified();
                    if(loadedPath!=null){Native.unload();loadedPath=null;}
                }
                Native.ensure();
                String path=file.getAbsolutePath();
                if(!path.equals(loadedPath)){Native.load(path);loadedPath=path;}
                String prompt="<|plamo:tag|>dataset<|plamo:msg|>translation<|plamo:tag|>input"+
                    (source!=null&&source.matches("[a-z]{2,3}")?"<|plamo:key|>lang<|plamo:val|>"+source:"")+
                    "<|plamo:msg|>"+text+"<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>"+target+"<|plamo:msg|>";
                byte[] result=Native.generate(prompt.getBytes(StandardCharsets.UTF_8));
                String translated=new String(result,StandardCharsets.UTF_8).trim();
                if(translated.isEmpty())callback.completed(null,"No translation returned.");else callback.completed(translated,null);
            } catch(Exception|LinkageError ex){callback.completed(null,"Local inference unavailable: "+ex.getClass().getSimpleName());}
        });}catch(Exception busy){callback.completed(null,"Translation queue is busy.");}
    }
    private static class Native {
        static { System.loadLibrary("roots_plamo"); }
        static void ensure(){}
        static native void load(String path);
        static native void unload();
        static native byte[] generate(byte[] prompt);
    }
}
