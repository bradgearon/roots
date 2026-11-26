package com.wds.sixstars.contextoverlay;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Capability-based routing. Wi-Fi availability is only a transport hint.
 * Identity is possession of the paired key, proven by an encrypted response. */
final class FastestComputeRouter implements TranslationProvider {
    private static volatile FastestComputeRouter instance;
    private static volatile String status="On-device PLaMo";
    private static final java.util.concurrent.atomic.AtomicLong generation=new java.util.concurrent.atomic.AtomicLong();
    private final SecureComputeSocket socket=new SecureComputeSocket();
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile Network selectedNetwork;
    private volatile String selectedFingerprint="";
    private final FamiliarComputeNetworks familiar;
    private volatile boolean maintaining;
    private volatile String connection="Not connected";
    private final Context app;
    private final OverlayPreferences prefs;
    private final LocalPLaMoTranslationProvider local;
    private final ThreadPoolExecutor routes=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(3));
    private final Map<String,Estimate> timings=new HashMap<>();
    private static final class Estimate {double ms;long at;int count;}
    static synchronized FastestComputeRouter get(Context context){
        if(instance==null)instance=new FastestComputeRouter(context.getApplicationContext());
        return instance;
    }
    static String status(){
        FastestComputeRouter router=instance;
        return status+(router==null?"":" · "+router.connection);
    }
    static synchronized void invalidate(){
        generation.incrementAndGet();status="On-device PLaMo";
        if(instance!=null){instance.socket.disconnect();instance.updateNetworkState();}
    }
    synchronized void startBackground(){
        if(maintaining)return;
        maintaining=true;
        ConnectivityManager cm=manager();
        if(cm!=null){
            networkCallback=new ConnectivityManager.NetworkCallback(){
                @Override public void onAvailable(Network n){updateNetworkState();}
                @Override public void onLost(Network n){updateNetworkState();}
                @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities caps){updateNetworkState();}
                @Override public void onLinkPropertiesChanged(Network n,android.net.LinkProperties props){updateNetworkState();}
            };
            try{cm.registerDefaultNetworkCallback(networkCallback);}
            catch(RuntimeException e){networkCallback=null;}
        }
        updateNetworkState();
    }
    synchronized void stopBackground(){
        maintaining=false;generation.incrementAndGet();
        if(networkCallback!=null){
            try{manager().unregisterNetworkCallback(networkCallback);}catch(RuntimeException ignored){}
            networkCallback=null;
        }
        selectedNetwork=null;selectedFingerprint="";socket.disconnect();connection="Compute connection stopped";
    }
    /** Android callbacks change the routing state; no packets sent here. */
    private synchronized void updateNetworkState(){
        Network next=maintaining&&prefs.enabled()&&prefs.desktopTranslationEnabled()?wifi():null;
        String fingerprint=next==null?"":familiar.fingerprint(next);
        if(!java.util.Objects.equals(next,selectedNetwork)||!fingerprint.equals(selectedFingerprint))socket.disconnect();
        selectedNetwork=next;selectedFingerprint=fingerprint;
        if(next==null)connection="Local compute";
        else if(!familiar.contains(prefs.bridgeUrl(),fingerprint))connection="Unfamiliar network · local compute";
        else connection="Familiar network · paired desktop eligible";
    }
    private FastestComputeRouter(Context app){
        this.app=app;prefs=new OverlayPreferences(app);local=new LocalPLaMoTranslationProvider(app);
        familiar=new FamiliarComputeNetworks(app);
    }
    private ConnectivityManager manager(){return (ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE);}
    private Network wifi(){
        ConnectivityManager cm=manager();
        if(cm==null)return null;
        Network n=cm.getActiveNetwork();NetworkCapabilities caps=n==null?null:cm.getNetworkCapabilities(n);
        return caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)?n:null;
    }
    static boolean rememberCurrent(Context context){
        FastestComputeRouter r=get(context);Network n=r.wifi();
        if(n==null||!OverlayTranslationKeyStore.isConfigured(context))return false;
        boolean remembered=r.familiar.remember(r.prefs.bridgeUrl(),r.familiar.fingerprint(n));
        r.updateNetworkState();return remembered;
    }
    static void forgetNetworks(Context context){
        FastestComputeRouter r=get(context);r.familiar.clear();r.updateNetworkState();
    }
    private boolean authorized(String base,String key){
        return prefs.enabled()&&prefs.desktopTranslationEnabled()&&base.equals(prefs.bridgeUrl())&&key.equals(OverlayTranslationKeyStore.read(app));
    }
    private String localProfile(){return "local:"+(prefs.localVulkanEnabled()?"vulkan:":"cpu:")+prefs.localThreads();}
    private static String bucket(String route,int chars){return route+":"+(chars<=40?0:chars<=160?1:chars<=400?2:3);}
    private synchronized void observe(String route,int chars,long ms){
        if(timings.size()>128)timings.clear();
        Estimate e=timings.computeIfAbsent(bucket(route,chars),k->new Estimate());
        e.ms=e.count==0?ms:.75*e.ms+.25*ms;e.count++;e.at=SystemClock.elapsedRealtime();
    }
    private synchronized boolean preferRemote(String remote,String phone,int chars){
        Estimate r=timings.get(bucket(remote,chars)),l=timings.get(bucket(phone,chars));
        if(r==null)return true;if(l==null)return false;
        long now=SystemClock.elapsedRealtime();
        // Re-test a stale alternative on real authorized work, not duplicate
        // requests. Otherwise require a 10% advantage before preferring it.
        if(now-r.at>120000&&now-l.at<120000)return true;
        if(now-l.at>120000&&now-r.at<120000)return false;
        return r.ms<l.ms*.90;
    }
    @Override public void translate(String text,String source,String target,Result callback){
        if(callback==null)return;
        if(!prefs.enabled()){callback.completed(null,"Translation permission is off.");return;}
        if(text==null||text.trim().isEmpty()||text.length()>1200||target==null||!target.matches("[a-z]{2,3}")||text.contains("<|plamo:")){
            callback.completed(null,"Invalid translation request.");return;
        }
        AtomicBoolean finished=new AtomicBoolean();
        try{routes.execute(()->{
            if(!prefs.enabled()){finish(callback,finished,null,"Translation permission is off.");return;}
            String phone=localProfile();
            Network network=prefs.desktopTranslationEnabled()?wifi():null;
            String base=prefs.bridgeUrl(),key=network==null?"":OverlayTranslationKeyStore.read(app);
            String fingerprint=network==null?"":familiar.fingerprint(network);
            boolean remote=network!=null&&!key.isEmpty()&&familiar.contains(base,fingerprint);
            String desktop="desktop:"+base+":"+fingerprint;
            if(remote&&preferRemote(desktop,phone,text.length())){
                if(remoteTranslate(network,base,key,desktop,text,source,target,callback,finished))return;
                remote=false;
            }
            final boolean tryDesktop=remote;
            status=remote?"On-device PLaMo · measured fastest":"On-device PLaMo · paired host unavailable or disabled";
            long start=SystemClock.elapsedRealtime();
            local.translate(text,source,target,new Result(){
                @Override public void started(){if(!finished.get())callback.started();}
                @Override public void partial(String prefix){if(!finished.get()&&prefs.enabled())callback.partial(prefix);}
                @Override public void completed(String translated,String error){
                    if(error==null&&translated!=null&&!translated.trim().isEmpty()){
                        observe(phone,text.length(),SystemClock.elapsedRealtime()-start);
                        finish(callback,finished,translated,null);return;
                    }
                    if(tryDesktop&&authorized(base,key)){
                        try{routes.execute(()->{
                            if(!remoteTranslate(network,base,key,desktop,text,source,target,callback,finished))
                                finish(callback,finished,null,error==null?"No translation returned.":error);
                        });return;}catch(java.util.concurrent.RejectedExecutionException ignored){}
                    }
                    finish(callback,finished,null,error==null?"No translation returned.":error);
                }
            });
        });}catch(java.util.concurrent.RejectedExecutionException e){finish(callback,finished,null,"Compute routing queue is busy.");}
    }
    private boolean remoteTranslate(Network network,String base,String key,String route,String text,String source,String target,Result callback,AtomicBoolean finished){
        if(!authorized(base,key)||!network.equals(wifi())||!familiar.contains(base,familiar.fingerprint(network)))return false;
        long start=SystemClock.elapsedRealtime();
        try{
            JSONObject input=new JSONObject().put("text",text).put("targetLang",target).put("maxTokens",768);
            if(source!=null&&source.matches("[a-z]{2,3}"))input.put("sourceLang",source);
            status="Paired desktop PLaMo · encrypted";
            JSONObject reply=socket.translate(network,base,key,new JSONObject().put("kind","translate").put("request",input),callback::started);
            String translated=reply.optString("translation","").trim();
            if(translated.isEmpty())throw new IllegalStateException("Empty translation");
            if(!authorized(base,key)){finish(callback,finished,null,"Desktop compute permission changed.");return true;}
            familiar.remember(base,familiar.fingerprint(network));
            connection="Familiar network · desktop responding";
            observe(route,text.length(),SystemClock.elapsedRealtime()-start);
            finish(callback,finished,translated,null);return true;
        }catch(Exception e){
            connection="Desktop request failed";
            status="Desktop route unavailable · falling back to phone";return false;
        }
    }
    private static void finish(Result callback,AtomicBoolean finished,String value,String error){
        if(finished.compareAndSet(false,true))callback.completed(value,error);
    }
}
