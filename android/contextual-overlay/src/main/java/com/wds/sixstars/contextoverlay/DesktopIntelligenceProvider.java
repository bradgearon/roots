package com.wds.sixstars.contextoverlay;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import org.json.JSONObject;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Explicit general intelligence over the separately paired desktop listener.
 * No polling, redirects, translation route, or automatic local/cloud fallback. */
final class DesktopIntelligenceProvider {
    private static final ThreadPoolExecutor WORK=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(3),
        runnable->{Thread thread=new Thread(runnable,"RootsDesktopIntelligence");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    private static final Set<Call> CALLS=ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.atomic.AtomicLong REVISION=new java.util.concurrent.atomic.AtomicLong();
    private static final ThreadLocal<Long> REQUEST_REVISION=new ThreadLocal<>();
    private static volatile String lastStatus="No desktop intelligence connection test yet.";
    private static volatile IntelligenceModel lastStatusModel;
    private static volatile String lastTransport="LAN TLS";
    private DesktopIntelligenceProvider(){}

    static String status(Context context,IntelligenceModel model){
        OverlayPreferences prefs=new OverlayPreferences(context);
        return "Desktop intelligence · "+model.label+"\n"+(prefs.desktopIntelligenceUrl().isEmpty()?"Set the PC's HTTPS address on port 8221.":prefs.desktopIntelligenceUrl())+
            "\n"+(DesktopIntelligenceKeyStore.configured(context)?"Intelligence key saved.":"Intelligence key missing or unreadable.")+"\n"+
            (lastStatusModel==null||lastStatusModel==model?lastStatus:"No desktop connection result for the selected model yet.");
    }
    static void disconnect(){REVISION.incrementAndGet();DesktopWhirlwind.cancel();for(Call call:CALLS)call.cancel();lastStatusModel=null;lastStatus="Connection settings changed; test the saved desktop connection.";}
    static void submit(Runnable task,LocalIntelligenceProvider.Result result){
        long revision=REVISION.get();
        try{WORK.execute(()->{
            if(revision!=REVISION.get()){result.completed(null,"Desktop intelligence settings changed. Run the request again.");return;}
            REQUEST_REVISION.set(revision);
            try{task.run();}finally{REQUEST_REVISION.remove();}
        });}catch(RejectedExecutionException error){result.completed(null,"Desktop intelligence queue is busy. Try again shortly.");}
    }
    static void generate(Context context,IntelligenceModel model,String prompt,LocalIntelligenceProvider.Result result){
        Context app=context.getApplicationContext();
        submit(()->{
            try{result.partial("Running intelligence on the paired desktop…");result.completed(generateBlocking(app,model,prompt),null);}
            catch(Exception error){result.completed(null,reason(error));}
        },result);
    }
    static String generateBlocking(Context context,IntelligenceModel model,String prompt)throws Exception{
        if(!new OverlayPreferences(context).desktopIntelligenceEnabled())throw new DesktopIntelligenceProtocol.Failure("Desktop intelligence is off. Enable it in Intelligence settings or use the local model.");
        JSONObject payload=DesktopIntelligenceProtocol.request(model,prompt);
        InferenceHistory.Attempt history=InferenceHistory.get(context).begin("Desktop · LLM",model.label,"Intelligence sample","General intelligence · paired desktop");
        history.running("Waiting for desktop");
        try{
            String text=DesktopIntelligenceProtocol.answer(exchange(context,DesktopIntelligenceProtocol.GENERATE,payload,true),model);
            history.finish(true,"Desktop intelligence completed");lastStatusModel=model;lastStatus="Last desktop intelligence request succeeded · "+lastTransport+".";return text;
        }catch(Exception error){history.finish(false,"Desktop intelligence failed");lastStatusModel=model;lastStatus=reason(error);throw error;}
    }
    static void testConnection(Context context,IntelligenceModel model,LocalIntelligenceProvider.Result result){
        Context app=context.getApplicationContext();
        submit(()->{
            try{
                DesktopIntelligenceProtocol.health(exchange(app,DesktopIntelligenceProtocol.HEALTH,null,false),model);
                lastStatusModel=model;lastStatus="Connected · "+lastTransport+" · pinned TLS, intelligence key and selected model verified.";
                result.completed(lastStatus,null);
            }catch(Exception error){lastStatusModel=model;lastStatus=reason(error);result.completed(null,lastStatus);}
        },result);
    }
    private static JSONObject exchange(Context context,String path,JSONObject payload,boolean generation)throws Exception{
        checkRevision();
        OverlayPreferences prefs=new OverlayPreferences(context);
        if(generation&&!prefs.desktopIntelligenceEnabled())throw new DesktopIntelligenceProtocol.Failure("Desktop intelligence is off.");
        String base=DesktopIntelligenceProtocol.baseUrl(prefs.desktopIntelligenceUrl());
        String key=DesktopIntelligenceKeyStore.read(context);
        if(key.isEmpty())throw new DesktopIntelligenceProtocol.Failure("Save the intelligence connection key from get-intelligence-key.cmd in Intelligence settings.");
        boolean remote=DesktopWhirlwind.remote(base);
        DesktopWhirlwind.Tunnel tunnel=null;
        OkHttpClient client=null;
        try{
            OkHttpClient.Builder http=new OkHttpClient.Builder();
            if(remote){
                tunnel=DesktopWhirlwind.open(context,DesktopWhirlwind.route(base));
                base=tunnel.base();lastTransport="Internal Mesh · "+tunnel.transport();
                // The tunnel is a one-shot loopback listener. End-to-end HTTPS
                // still authenticates the exact paired desktop, not the relay.
                checkRevision();
            }else{
                ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
                Network network=manager==null?null:manager.getActiveNetwork();
                NetworkCapabilities caps=network==null?null:manager.getNetworkCapabilities(network);
                if(caps==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
                    throw new DesktopIntelligenceProtocol.Failure("For away-from-home GPU use, save the desktop's roots+iroh:// Internal Mesh address. LAN addresses require desktop Wi-Fi.");
                lastTransport="LAN TLS";
                http.socketFactory(network.getSocketFactory()).dns(host->{
                    InetAddress[] addresses=network.getAllByName(host);
                    for(InetAddress address:addresses)if(address.isLoopbackAddress()||address.isAnyLocalAddress()||address.isMulticastAddress())
                        throw new java.net.UnknownHostException("Invalid desktop address");
                    return Arrays.asList(addresses);
                });
            }
            DesktopTlsPin pin=new DesktopTlsPin(key.substring(65));
            SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,new javax.net.ssl.TrustManager[]{pin},null);
            client=http.sslSocketFactory(tls.getSocketFactory(),pin).hostnameVerifier((host,session)->pin.matchesPeer(session))
                .proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(4,TimeUnit.SECONDS).readTimeout(generation?85:8,TimeUnit.SECONDS)
                .writeTimeout(8,TimeUnit.SECONDS).callTimeout(generation?90:12,TimeUnit.SECONDS).build();
        Request.Builder builder=new Request.Builder().url(base+path).header("Authorization","Bearer "+key.substring(0,64)).header("Accept","application/json");
        if(payload!=null)builder.post(RequestBody.create(payload.toString(),MediaType.get("application/json; charset=utf-8")));
        Call call=client.newCall(builder.build());CALLS.add(call);
        try{
            checkRevision();
            if(generation&&!prefs.desktopIntelligenceEnabled()){call.cancel();throw new DesktopIntelligenceProtocol.Failure("Desktop intelligence was switched off before sending.");}
            try(Response response=call.execute()){
                if(response.code()!=200)throw DesktopIntelligenceProtocol.httpFailure(response.code());
                if(response.body()==null)throw new DesktopIntelligenceProtocol.Failure("Desktop intelligence returned an empty response.");
                return new JSONObject(DesktopIntelligenceProtocol.readLimited(response.body().byteStream()));
            }
        }finally{CALLS.remove(call);}
        }finally{
            if(client!=null){client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}
            if(tunnel!=null)tunnel.close();
        }
    }
    private static void checkRevision()throws DesktopIntelligenceProtocol.Failure{
        Long revision=REQUEST_REVISION.get();
        if(revision!=null&&revision!=REVISION.get())throw new DesktopIntelligenceProtocol.Failure("Desktop intelligence settings changed. Run the request again.");
    }
    static String reason(Exception error){
        if(error instanceof DesktopIntelligenceProtocol.Failure)return error.getMessage();
        if(error instanceof IllegalArgumentException)return "Check the saved desktop HTTPS or Internal Mesh address and its separate connection key.";
        if(error instanceof IOException&&error.getMessage()!=null&&(error.getMessage().startsWith("Whirlwind")||error.getMessage().startsWith("Invalid tunnel")))return error.getMessage();
        if(error instanceof javax.net.ssl.SSLException)return "Desktop intelligence TLS verification failed. Update the PC, then pair its current get-intelligence-key.cmd key.";
        if(error instanceof java.net.SocketTimeoutException||error instanceof java.io.InterruptedIOException)return "Desktop intelligence timed out or was cancelled. Check the PC's intelligence console.";
        if(error instanceof java.net.ConnectException)return "Desktop intelligence connection refused. Start launch-intelligence.cmd and allow private-LAN TCP 8221.";
        if(error instanceof java.net.UnknownHostException)return "Desktop intelligence address could not be resolved. Check the PC address in Intelligence settings.";
        return "Desktop intelligence exchange failed. Check the listener on port 8221 and update both Roots installations.";
    }
}
