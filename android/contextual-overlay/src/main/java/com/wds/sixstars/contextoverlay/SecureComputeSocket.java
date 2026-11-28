package com.wds.sixstars.contextoverlay;

import android.net.Network;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** One paired compute connection. A standard TLS WebSocket with authenticated upgrade. One in-flight envelope per socket. */
final class SecureComputeSocket {
    private static final long RETRY_MS=8000;
    private static void trace(String message){android.util.Log.i("RootsCompute",message);}
    static final class Failure extends IOException {
        Failure(String reason){super(reason);}
    }
    static String reason(Exception error){
        if(error instanceof Failure)return error.getMessage();
        if(error instanceof javax.net.ssl.SSLException)return "Desktop TLS certificate or connection failed; refresh connection key";
        if(error instanceof java.net.UnknownHostException)return "Desktop address not resolved";
        if(error instanceof java.net.ConnectException)return "Desktop connection refused";
        if(error instanceof java.net.SocketTimeoutException)return "Desktop connection timed out";
        return "Desktop exchange failed ("+error.getClass().getSimpleName()+")";
    }
    private final ReentrantLock exchange=new ReentrantLock();
    private final Object lifecycle=new Object();
    private volatile Session current;
    private volatile long retryAt;
    private volatile Network retryNetwork;
    private volatile String retryBase="",retryKey="";
    private static final class Pending {
        final CountDownLatch done=new CountDownLatch(1);
        volatile String reply;
        final String id=java.util.UUID.randomUUID().toString();
    }
    private static final class Session {
        final Network network;final String base,key;
        final OkHttpClient client;
        final CountDownLatch opened=new CountDownLatch(1);
        final AtomicReference<Pending> pending=new AtomicReference<>();
        volatile WebSocket socket;
        volatile boolean open,ready,closed;
        volatile String failure="Desktop socket disconnected";
        volatile String destination="";
         
        Session(Network network,String base,String key)throws Exception{
            this.network=network;this.base=base;this.key=key;
            if(!key.matches("[0-9a-f]{64}:[0-9a-f]{64}"))throw new Failure("Refresh connection key using get-key.cmd");
            DesktopTlsPin pin=new DesktopTlsPin(key.substring(65));
            javax.net.ssl.SSLContext tls=javax.net.ssl.SSLContext.getInstance("TLS");
            tls.init(null,new javax.net.ssl.TrustManager[]{pin},null);
            client=new OkHttpClient.Builder().socketFactory(network.getSocketFactory())
                .sslSocketFactory(tls.getSocketFactory(),pin)
                .hostnameVerifier((hostname,session)->pin.matchesPeer(session))
                .dns(host->Arrays.asList(network.getAllByName(host)))
                .followRedirects(false).followSslRedirects(false)
                .proxy(java.net.Proxy.NO_PROXY)
                .eventListener(new okhttp3.EventListener(){
                    @Override public void connectStart(okhttp3.Call call,java.net.InetSocketAddress address,java.net.Proxy proxy){
                        destination=address.getAddress()==null?address.getHostString()+":"+address.getPort():address.getAddress().getHostAddress()+":"+address.getPort();
                        trace("TCP connect "+destination);
                    }
                    @Override public void connectFailed(okhttp3.Call call,java.net.InetSocketAddress address,java.net.Proxy proxy,okhttp3.Protocol protocol,IOException error){
                        trace("TCP/TLS connect failed "+destination+" ("+error.getClass().getSimpleName()+")");
                    }
                    @Override public void secureConnectStart(okhttp3.Call call){trace("TLS handshake started "+destination);}
                    @Override public void secureConnectEnd(okhttp3.Call call,okhttp3.Handshake handshake){trace("TLS handshake complete "+destination);}
                })
                .connectTimeout(3000,TimeUnit.MILLISECONDS).build();
        }
        boolean matches(Network n,String b,String k){return network.equals(n)&&base.equals(b)&&key.equals(k);}
    }
    boolean ready(Network network,String base,String key){
        Session s=current;
        return s!=null&&s.matches(network,base,key)&&s.ready&&!s.closed;
    }
    private void fail(Session s){
        synchronized(lifecycle){
            if(s.closed)return;
            s.closed=true;s.open=false;s.ready=false;s.opened.countDown();
            Pending p=s.pending.getAndSet(null);if(p!=null)p.done.countDown();
            if(current==s){
                current=null;retryNetwork=s.network;retryBase=s.base;retryKey=s.key;
                retryAt=SystemClock.elapsedRealtime()+RETRY_MS;
            }
        }
        WebSocket ws=s.socket;if(ws!=null)ws.cancel();
        s.client.connectionPool().evictAll();
        s.client.dispatcher().executorService().shutdown();
    }
    void disconnect(){
        Session old;
        synchronized(lifecycle){old=current;current=null;retryAt=0;retryNetwork=null;retryBase="";retryKey="";}
        if(old!=null)fail(old);
    }
    private Session connect(Network network,String base,String key,String path)throws Exception{
        Session s;
        synchronized(lifecycle){
            Session old=current;
            if(old!=null&&!old.matches(network,base,key)){fail(old);retryAt=0;}
            if(current!=null)return current;
            long now=SystemClock.elapsedRealtime();
            if(now<retryAt&&Objects.equals(retryNetwork,network)&&retryBase.equals(base)&&retryKey.equals(key))throw new Failure("Desktop retry cooldown after failed connection");
            okhttp3.HttpUrl address=okhttp3.HttpUrl.parse(base);
            if(address==null||!address.isHttps())throw new Failure("Desktop URL must use HTTPS");
            String host=address.host();
            if(host.equalsIgnoreCase("localhost")||host.equals("::1")||host.startsWith("127.")||host.equals("0.0.0.0")||host.equals("::"))
                throw new Failure("Saved desktop address points to this phone, not your PC: "+base);
            s=new Session(network,base,key);
            String url;
            if(base.startsWith("https://"))url="wss://"+base.substring(8);
            else throw new Failure("Desktop URL must use HTTPS");
            current=s;
            trace("WebSocket upgrade requested "+url+path);
            try{
                s.socket=s.client.newWebSocket(new Request.Builder().url(url+path).header("X-SixStars-Token",key.substring(0,64)).build(),new WebSocketListener(){
                    @Override public void onOpen(WebSocket ws,Response response){if(s.closed){ws.cancel();return;}trace("WebSocket upgrade accepted");s.open=true;}
                    @Override public void onMessage(WebSocket ws,String message){
                        try{
                            if(message.length()>98304)throw new IOException("Frame too large");
                            JSONObject frame=new JSONObject(message);
                            if("auth".equals(frame.optString("type"))){
                                if(!frame.optBoolean("ok"))throw new IOException("Authentication rejected");
                                s.ready=true;s.opened.countDown();return;
                            }
                            Pending p=s.pending.get();
                            if(p==null||p.reply!=null||!"result".equals(frame.optString("type"))||!p.id.equals(frame.optString("id")))throw new IOException("Unexpected frame");
                            p.reply=message;p.done.countDown();
                        }catch(Exception e){s.failure="Invalid desktop JSON response";fail(s);}
                    }
                    @Override public void onMessage(WebSocket ws,okio.ByteString bytes){fail(s);}
                    @Override public void onClosing(WebSocket ws,int code,String reason){s.failure=closeReason(code);fail(s);}
                    @Override public void onClosed(WebSocket ws,int code,String reason){s.failure=closeReason(code);fail(s);}
                    @Override public void onFailure(WebSocket ws,Throwable error,Response response){
                        s.failure=response!=null?"Desktop WebSocket HTTP "+response.code():
                            error instanceof java.net.ConnectException?"Desktop connection refused; check IP, port 8223 and LAN listener":
                            error instanceof java.net.SocketTimeoutException?"Desktop connection timed out; check firewall":
                            error instanceof java.net.UnknownHostException?"Desktop address not resolved":
                            error instanceof javax.net.ssl.SSLException?"Desktop TLS connection failed":
                            "Desktop socket failed ("+error.getClass().getSimpleName()+")";
                        s.failure += " · "+s.base+(s.destination.isEmpty()?"":" · TCP "+s.destination);
                        trace(s.failure);
                        fail(s);
                    }
                });
            }catch(Exception e){fail(s);throw e;}
        }
        if(!s.opened.await(3500,TimeUnit.MILLISECONDS)){fail(s);throw new Failure("Desktop WebSocket connect timed out · "+base+(s.destination.isEmpty()?"":" · TCP "+s.destination));}
        if(!s.open||!s.ready||s.closed){fail(s);throw new Failure(s.failure);}
        return s;
    }
    private static String closeReason(int code){
        if(code==1008)return "Desktop rejected authentication or request; check connection key";
        if(code==1011)return "Desktop model inference unavailable";
        if(code==1013)return "Desktop temporarily busy";
        return "Desktop closed socket ("+code+")";
    }
    /** No discovery or heartbeat. Connect only to send actual requested work. */
    JSONObject translate(Network network,String base,String key,JSONObject payload,Runnable started)throws Exception{
        return runRequest(network,base,key,payload,30000,started);
    }
    JSONObject detect(Network network,String base,String key,String text)throws Exception{
        return runRequest(network,base,key,new JSONObject().put("kind","detect")
            .put("request",new JSONObject().put("text",text)),2500,()->{});
    }
    private JSONObject runRequest(Network network,String base,String key,JSONObject payload,long timeout,Runnable started)throws Exception{
        if(!exchange.tryLock())throw new IOException("Compute socket busy");
        Session s=null;
        try{
            s=connect(network,base,key,"/api/translation/v1/socket");
            if(s==null)throw new IOException("Compute socket unavailable");
            JSONObject reply=request(s,payload,timeout,started);
            s.ready=true;return reply;
        }catch(Exception e){if(s!=null)fail(s);throw e;}
        finally{exchange.unlock();}
    }
    private JSONObject request(Session s,JSONObject payload,long timeout)throws Exception{
        return request(s,payload,timeout,()->{});
    }
    private JSONObject request(Session s,JSONObject payload,long timeout,Runnable started)throws Exception{
        if(s.closed||s.socket==null)throw new IOException("Compute socket closed");
        Pending pending=new Pending();
        String id=pending.id;
        try{
            JSONObject input=payload.getJSONObject("request");
            input.put("type","detect".equals(payload.optString("kind"))?"detect":"translate").put("id",id);
            if(!s.pending.compareAndSet(null,pending))throw new IOException("Compute socket busy");
            if(s.closed||!s.socket.send(input.toString()))throw new IOException("Compute send failed");
            trace("request="+id+" JSON frame queued over TLS");
            started.run();
            if(!pending.done.await(timeout,TimeUnit.MILLISECONDS))throw new Failure("Desktop inference response timed out");
            if(s.closed||pending.reply==null)throw new Failure(s.failure);
            JSONObject reply=new JSONObject(pending.reply);
            if(!reply.optBoolean("ok"))throw new Failure("Desktop inference rejected: "+safeError(reply.optString("error")));
            trace("request="+id+" TLS response received");
            return reply;
        }catch(Exception e){throw new Failure("Request "+id+": "+reason(e));}
        finally{s.pending.compareAndSet(pending,null);}
    }
    private static String safeError(String error){
        return java.util.Set.of("invalid_request","translation_unavailable","detection_unavailable","too_many_requests","duplicate_id").contains(error)?error:"unknown_error";
    }
}
