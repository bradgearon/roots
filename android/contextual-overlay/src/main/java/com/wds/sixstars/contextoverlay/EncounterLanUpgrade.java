package com.wds.sixstars.contextoverlay;

import android.content.Context;
import android.net.*;
import java.io.*;
import java.net.*;
import java.security.KeyPair;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Optional same-Wi-Fi bulk transport. A route is offered only inside the
 * authenticated BLE channel, never advertised publicly. A new channel proves
 * the exact same peer key and both ends commit via BLE before changing paths.
 * No private LAN invitations, token exchange or arbitrary Internet dialing. */
final class EncounterLanUpgrade {
    private EncounterLanUpgrade(){}
    record Route(Network network,Inet4Address local,int prefix){}
    record Upgrade(Socket socket,EncounterChannel channel) implements Closeable{
        @Override public void close()throws IOException{socket.close();}
    }
    static Route wifi(Context context){
        try{
            ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);
            Network n=cm==null?null:cm.getActiveNetwork();
            NetworkCapabilities c=n==null?null:cm.getNetworkCapabilities(n);
            if(c==null||!c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))return null;
            LinkProperties lp=cm.getLinkProperties(n);
            if(lp!=null)for(LinkAddress link:lp.getLinkAddresses()){
                if(link.getAddress() instanceof Inet4Address a&&a.isSiteLocalAddress()&&link.getPrefixLength()>=8&&link.getPrefixLength()<=30)
                    return new Route(n,a,link.getPrefixLength());
            }
        }catch(RuntimeException ignored){}
        return null;
    }
    static boolean sameSubnet(byte[] a,byte[] b,int prefix){
        if(a.length!=4||b.length!=4||prefix<8||prefix>30||Arrays.equals(a,b))return false;
        for(int i=0;i<prefix;i++){
            int mask=1<<(7-i%8);
            if((a[i/8]&mask)!=(b[i/8]&mask))return false;
        }
        boolean network=true,broadcast=true;
        for(int i=prefix;i<32;i++){
            int bit=b[i/8]&(1<<(7-i%8));network&=bit==0;broadcast&=bit!=0;
        }
        return !network&&!broadcast;
    }
    static Inet4Address target(Route route,String host)throws IOException{
        if(route==null||host==null||!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))throw new IOException("Invalid local route");
        byte[] bytes=new byte[4];String[] fields=host.split("\\.");
        for(int i=0;i<4;i++){
            int n=Integer.parseInt(fields[i]);if(n>255)throw new IOException("Invalid local address");bytes[i]=(byte)n;
        }
        InetAddress ip=InetAddress.getByAddress(bytes);
        if(!(ip instanceof Inet4Address v4)||!v4.isSiteLocalAddress()||!sameSubnet(route.local().getAddress(),bytes,route.prefix()))throw new IOException("Route not on local Wi-Fi");
        return v4;
    }
    private static void send(EncounterChannel control,JSONObject json)throws Exception{
        control.send(json.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static JSONObject read(EncounterChannel control,String op)throws Exception{
        JSONObject json=new JSONObject(new String(control.receive(),StandardCharsets.UTF_8));
        if(!op.equals(json.optString("op")))throw new IOException("Unexpected route negotiation");
        return json;
    }
    private static void close(Closeable c){if(c!=null)try{c.close();}catch(IOException ignored){}}
    static Upgrade negotiate(Context context,EncounterChannel control,KeyPair key,boolean client,BooleanSupplier active)throws Exception{
        Route route=wifi(context);
        return client?client(route,control,key,active):server(context,route,control,key,active);
    }
    private static Upgrade client(Route route,EncounterChannel control,KeyPair key,BooleanSupplier active)throws Exception{
        Socket socket=null;EncounterChannel channel=null;boolean accepted=false;
        try{
            JSONObject offer=read(control,"route_offer");
            if(active.getAsBoolean()&&route!=null&&offer.optBoolean("available")){
                try{
                    Inet4Address host=target(route,offer.getString("host"));
                    int port=offer.getInt("port");if(port<1024||port>65535)throw new IOException("Invalid route port");
                    socket=route.network().getSocketFactory().createSocket();
                    socket.connect(new InetSocketAddress(host,port),2500);
                    socket.setSoTimeout(3500);socket.setTcpNoDelay(true);
                    socket=EncounterTls.client(socket);socket.setSoTimeout(8000);
                    channel=EncounterTls.authenticate((javax.net.ssl.SSLSocket)socket,key,true,control.peerId);
                    if(!control.peerId.equals(channel.peerId))throw new IOException("Route identity mismatch");
                }catch(Exception failure){close(socket);socket=null;channel=null;}
            }
            send(control,new JSONObject().put("op","route_ready").put("ready",channel!=null&&active.getAsBoolean()));
            boolean commit=read(control,"route_commit").getBoolean("ready");
            if(commit&&channel==null)throw new IOException("Invalid route commitment");
            if(!commit)return null;
            socket.setSoTimeout(45000);accepted=true;return new Upgrade(socket,channel);
        }finally{if(!accepted)close(socket);}
    }
    private static Upgrade server(Context context,Route route,EncounterChannel control,KeyPair key,BooleanSupplier active)throws Exception{
        ServerSocket listener=null;Socket socket=null;EncounterChannel channel=null;boolean accepted=false;
        try{
            if(active.getAsBoolean()&&route!=null){
                try{
                    listener=EncounterTls.listener(context);
                    listener.bind(new InetSocketAddress(route.local(),0),1);listener.setSoTimeout(4000);
                }catch(Exception unavailable){close(listener);listener=null;}
            }
            JSONObject offer=new JSONObject().put("op","route_offer").put("available",listener!=null);
            if(listener!=null)offer.put("host",route.local().getHostAddress()).put("port",listener.getLocalPort());
            send(control,offer);
            if(listener!=null){
                try{
                    socket=listener.accept();
                    if(!sameSubnet(route.local().getAddress(),socket.getInetAddress().getAddress(),route.prefix()))throw new IOException("Invalid local peer");
                    socket.setSoTimeout(3500);socket.setTcpNoDelay(true);
                    socket.setSoTimeout(8000);
                    channel=EncounterTls.authenticate((javax.net.ssl.SSLSocket)socket,key,false,control.peerId);
                    if(!control.peerId.equals(channel.peerId))throw new IOException("Route identity mismatch");
                }catch(Exception failure){close(socket);socket=null;channel=null;}
            }
            close(listener);listener=null;
            boolean ready=read(control,"route_ready").getBoolean("ready")&&channel!=null&&active.getAsBoolean();
            send(control,new JSONObject().put("op","route_commit").put("ready",ready));
            if(!ready)return null;
            socket.setSoTimeout(45000);accepted=true;return new Upgrade(socket,channel);
        }finally{close(listener);if(!accepted)close(socket);}
    }
}
