package com.wds.sixstars.contextoverlay;

import android.content.Context;
import android.net.*;
import java.net.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Public Axon transport, separate from private Mesh port 8233.
 * A broadcast query or GATT read supplies a route hint only. The TCP session
 * must prove possession of the same persistent REP identity. */
final class EncounterLanEndpoint implements Closeable {
    static final int PORT=8235;
    static final String PREFIX="ROOTS-AXON/1 ";
    private final Context app;
    private final KeyPair key;
    private final String identity;
    private final AtomicBoolean gate;
    private final BooleanSupplier active;
    private final ExecutorService workers=Executors.newFixedThreadPool(3);
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    private final ConnectivityManager cm;
    private boolean registered,closed;
    private volatile RouteState state;
    private volatile Socket session;
    private volatile String sessionPeer="";
    private record RouteState(EncounterLanUpgrade.Route route,ServerSocket listener,DatagramSocket discovery){}
    private final ConnectivityManager.NetworkCallback callback=new ConnectivityManager.NetworkCallback(){
        @Override public void onAvailable(Network network){refresh();}
        @Override public void onLost(Network network){refresh();}
        @Override public void onLinkPropertiesChanged(Network network,LinkProperties properties){refresh();}
        @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities capabilities){refresh();}
    };
    EncounterLanEndpoint(Context context,KeyPair key,AtomicBoolean gate,BooleanSupplier active)throws Exception{
        app=context.getApplicationContext();this.key=key;this.gate=gate;this.active=active;
        identity=EncounterChannel.identityId(key.getPublic());
        cm=app.getSystemService(ConnectivityManager.class);
    }
    void start(){
        if(cm!=null)try{cm.registerDefaultNetworkCallback(callback);registered=true;}catch(RuntimeException ignored){}
        refresh();
        timer.scheduleWithFixedDelay(()->{try{refresh();}catch(RuntimeException ignored){}},15,15,TimeUnit.SECONDS);
    }
    synchronized void refresh(){
        if(closed)return;
        var route=active.getAsBoolean()?EncounterLanUpgrade.wifi(app):null;
        if(state!=null&&state.route().equals(route)&&!state.listener().isClosed()&&!state.discovery().isClosed())return;
        stopRoute();
        if(route==null)return;
        ServerSocket tcp=null;DatagramSocket udp=null;
        try{
            tcp=EncounterTls.listener(app);tcp.setReuseAddress(true);tcp.bind(new InetSocketAddress(route.local(),PORT),4);
            udp=new DatagramSocket(null);udp.setReuseAddress(true);udp.setBroadcast(true);
            route.network().bindSocket(udp);udp.bind(new InetSocketAddress(PORT));
            RouteState next=new RouteState(route,tcp,udp);state=next;
            workers.execute(()->accept(next));workers.execute(()->discover(next));
        }catch(Exception failure){closeQuiet(tcp);closeQuiet(udp);state=null;}
    }
    byte[] hint(){
        RouteState current=state;
        if(current==null||!active.getAsBoolean()||current.listener().isClosed())return null;
        byte[] digest=new byte[32];
        for(int i=0;i<32;i++)digest[i]=(byte)Integer.parseInt(identity.substring(i*2,i*2+2),16);
        return ByteBuffer.allocate(38).put(current.route().local().getAddress()).putShort((short)PORT).put(digest).array();
    }
    private boolean valid(RouteState s,InetAddress address){
        return state==s&&active.getAsBoolean()&&address instanceof Inet4Address&&address.isSiteLocalAddress()
            &&EncounterLanUpgrade.sameSubnet(s.route().local().getAddress(),address.getAddress(),s.route().prefix());
    }
    private void discover(RouteState s){
        byte[] bytes=new byte[128];
        long last=0;
        while(state==s&&active.getAsBoolean()){
            try{
                DatagramPacket query=new DatagramPacket(bytes,bytes.length);s.discovery().receive(query);
                if(!valid(s,query.getAddress()))continue;
                String value=new String(query.getData(),query.getOffset(),query.getLength(),StandardCharsets.US_ASCII);
                if(!value.startsWith(PREFIX))continue;
                String nonce=value.substring(PREFIX.length());
                if(!nonce.matches("[0-9a-f]{32}"))continue;
                // At most a small bounded response per query; no knowledge or secrets.
                long now=android.os.SystemClock.elapsedRealtime();
                if(now-last<100)continue;last=now;
                byte[] reply=(PREFIX+nonce+" "+identity+" "+PORT).getBytes(StandardCharsets.US_ASCII);
                s.discovery().send(new DatagramPacket(reply,reply.length,query.getAddress(),query.getPort()));
            }catch(IOException e){break;}
        }
    }
    private void accept(RouteState s){
        while(state==s&&active.getAsBoolean()){
            try{
                Socket socket=s.listener().accept();
                if(!valid(s,socket.getInetAddress())||!gate.compareAndSet(false,true)){closeQuiet(socket);continue;}
                session=socket;
                try{workers.execute(()->transfer(s,socket));}
                catch(RejectedExecutionException stopped){session=null;gate.set(false);closeQuiet(socket);}
            }catch(IOException e){break;}
        }
    }
    private void transfer(RouteState s,Socket socket){
        String peer="",result="LAN exchange interrupted";
        EncounterSync sync=null;ScheduledFuture<?> deadline=null;android.os.PowerManager.WakeLock wake=null;
        try{
            socket.setSoTimeout(8000);socket.setTcpNoDelay(true);
            deadline=timer.schedule(()->closeQuiet(socket),120,TimeUnit.SECONDS);
            android.os.PowerManager power=app.getSystemService(android.os.PowerManager.class);
            if(power!=null)try{wake=power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"Roots:AxonLan");wake.acquire(125000L);}catch(SecurityException unavailable){wake=null;}
            EncounterChannel channel=EncounterTls.authenticate((javax.net.ssl.SSLSocket)socket,key,false,"");
            peer=channel.peerId;sessionPeer=peer;socket.setSoTimeout(45000);
            if(!valid(s,socket.getInetAddress())||EncounterBindings.get(app).blocked(peer)){result="Blocked or stopped";return;}
            final String authenticated=peer;
            EncounterService.lanActive(peer);
            sync=new EncounterSync(app,channel,()->valid(s,socket.getInetAddress()),key,
                message->{EncounterService.lanProgress(message);AxonReceiveCoordinator.progress(authenticated,message);},ignored->{},true);
            sync.exchange(false);
            result="Wi-Fi TLS · "+sync.summary();
        }catch(Exception|LinkageError failure){
            result="LAN interrupted · "+(sync==null?"authentication":sync.stage())+" ("+failure.getClass().getSimpleName()+")";
        }finally{
            if(deadline!=null)deadline.cancel(false);if(sync!=null)sync.close();closeQuiet(socket);
            if(wake!=null&&wake.isHeld())wake.release();
            int received=sync==null?0:sync.received(),sent=sync==null?0:sync.sent();
            if(!peer.isEmpty()&&!result.equals("Blocked or stopped")){
                try{EncounterBindings.get(app).outcome(peer,result,sent,received);}catch(Exception ignored){}
                AxonReceiveCoordinator.completed(peer,received,sent,sync!=null&&sync.completedReceive()&&!result.startsWith("LAN interrupted"),result);
            }
            EncounterService.lanFinished(peer,result+" · "+received+" received · "+sent+" sent");
            session=null;sessionPeer="";gate.set(false);ExternalTreeImport.schedule(app);
        }
    }
    void disconnect(String peer){if(peer.equals(sessionPeer))closeQuiet(session);}
    private void stopRoute(){
        RouteState previous=state;state=null;
        if(previous!=null){closeQuiet(previous.listener());closeQuiet(previous.discovery());}
        closeQuiet(session);
    }
    private static void closeQuiet(Closeable c){if(c!=null)try{c.close();}catch(IOException ignored){}}
    @Override public synchronized void close(){
        closed=true;stopRoute();
        if(registered&&cm!=null)try{cm.unregisterNetworkCallback(callback);}catch(RuntimeException ignored){}
        timer.shutdownNow();workers.shutdown();
    }
}
