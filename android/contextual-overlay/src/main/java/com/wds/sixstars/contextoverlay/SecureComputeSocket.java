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

/** One paired compute connection. A successful encrypted reply, not the
 * WebSocket upgrade, establishes readiness. One in-flight envelope per socket. */
final class SecureComputeSocket {
    private static final long RETRY_MS=8000;
    private final ReentrantLock exchange=new ReentrantLock();
    private final Object lifecycle=new Object();
    private volatile Session current;
    private volatile long retryAt;
    private volatile Network retryNetwork;
    private volatile String retryBase="",retryKey="";
    private static final class Pending {
        final CountDownLatch done=new CountDownLatch(1);
        volatile String reply;
    }
    private static final class Session {
        final Network network;final String base,key;
        final OkHttpClient client;
        final CountDownLatch opened=new CountDownLatch(1);
        final AtomicReference<Pending> pending=new AtomicReference<>();
        volatile WebSocket socket;
        volatile boolean open,ready,closed;
         
        Session(Network network,String base,String key){
            this.network=network;this.base=base;this.key=key;
            client=new OkHttpClient.Builder().socketFactory(network.getSocketFactory())
                .dns(host->Arrays.asList(network.getAllByName(host)))
                .followRedirects(false).followSslRedirects(false)
                .connectTimeout(1200,TimeUnit.MILLISECONDS).build();
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
            if(now<retryAt&&Objects.equals(retryNetwork,network)&&retryBase.equals(base)&&retryKey.equals(key))return null;
            s=new Session(network,base,key);
            String url;
            if(base.startsWith("https://"))url="wss://"+base.substring(8);
            else if(base.startsWith("http://"))url="ws://"+base.substring(7);
            else throw new IOException("Invalid bridge URL");
            current=s;
            try{
                s.socket=s.client.newWebSocket(new Request.Builder().url(url+path).build(),new WebSocketListener(){
                    @Override public void onOpen(WebSocket ws,Response response){if(s.closed){ws.cancel();return;}s.open=true;s.opened.countDown();}
                    @Override public void onMessage(WebSocket ws,String message){
                        Pending p=s.pending.get();
                        if(p==null||message.length()>98304||p.reply!=null){fail(s);return;}
                        p.reply=message;p.done.countDown();
                    }
                    @Override public void onMessage(WebSocket ws,okio.ByteString bytes){fail(s);}
                    @Override public void onClosing(WebSocket ws,int code,String reason){fail(s);}
                    @Override public void onClosed(WebSocket ws,int code,String reason){fail(s);}
                    @Override public void onFailure(WebSocket ws,Throwable error,Response response){fail(s);}
                });
            }catch(Exception e){fail(s);throw e;}
        }
        if(!s.opened.await(1800,TimeUnit.MILLISECONDS)||!s.open||s.closed){fail(s);return null;}
        return s;
    }
    /** No discovery or heartbeat. Connect only to send actual requested work. */
    JSONObject translate(Network network,String base,String key,JSONObject payload,Runnable started)throws Exception{
        if(!exchange.tryLock())throw new IOException("Compute socket busy");
        Session s=null;
        try{
            s=connect(network,base,key,"/api/compute/v1/socket");
            if(s==null)throw new IOException("Compute socket unavailable");
            JSONObject reply=request(s,payload,30000,started);
            s.ready=true;return reply;
        }catch(Exception e){if(s!=null)fail(s);throw e;}
        finally{exchange.unlock();}
    }
    private JSONObject request(Session s,JSONObject payload,long timeout)throws Exception{
        return request(s,payload,timeout,()->{});
    }
    private JSONObject request(Session s,JSONObject payload,long timeout,Runnable started)throws Exception{
        if(s.closed||s.socket==null)throw new IOException("Compute socket closed");
        SecureLanCompute.Packet packet=SecureLanCompute.seal(s.key,payload);
        Pending pending=new Pending();
        try{
            if(!s.pending.compareAndSet(null,pending))throw new IOException("Compute socket busy");
            if(s.closed||!s.socket.send(packet.wire))throw new IOException("Compute send failed");
            started.run();
            if(!pending.done.await(timeout,TimeUnit.MILLISECONDS)||s.closed||pending.reply==null)throw new IOException("Compute reply unavailable");
            return packet.open(pending.reply);
        }finally{s.pending.compareAndSet(pending,null);packet.clear();}
    }
}
