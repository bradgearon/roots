package com.wds.sixstars.contextoverlay;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import com.wds.sixstars.contextoverlay.tree.NativeTree;
import com.wds.sixstars.contextoverlay.tree.TreeRuntime;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedKeyManager;
import static com.wds.sixstars.contextoverlay.MeshConnectionDiagnostics.*;

/** Roots Mesh v1: direct Wi-Fi peers, standard TLS, immutable content-addressed
 * items, inventory and explicit wants. No DHT, trackers, raw-tree access or
 * custom message encryption. Invitation authorizes reading published items. */
final class MeshPeer {
    static final int PORT=8233, MAX_INVENTORY=256, MAX_FETCH=32, MAX_FRAME=256*1024;
    private static MeshPeer instance;
    static synchronized MeshPeer get(Context context){
        if(instance==null)instance=new MeshPeer(context.getApplicationContext());
        return instance;
    }
    private final Context app;
    private final TreeRuntime tree;
    private final ConnectivityManager cm;
    private final SecureRandom random=new SecureRandom();
    private final Map<String,Integer> requested=new ConcurrentHashMap<>();
    private volatile Map<String,Integer> observedReplicas=java.util.Collections.emptyMap(), observedDemand=java.util.Collections.emptyMap();
    private volatile String invitation="";
    private volatile String status="Not serving";
    private volatile Server active;
    private volatile Report lastConnectionReport;
    private volatile Check incomingTlsFailure;
    private volatile String tlsIdentityState=SKIP;
    private volatile String tlsIdentityStatus="Not checked; start listening to verify this device's TLS signing key.";
    private MeshPeer(Context app){this.app=app;tree=TreeRuntime.get(app);cm=(ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE);}
    String invitation(){return invitation;}
    String status(){return status;}
    Report lastConnectionReport(){return lastConnectionReport;}
    String tlsIdentityStatus(){return tlsIdentityStatus;}
    int replicas(String id){return observedReplicas.getOrDefault(id,0);}
    int demand(String id){return Math.max(requested.getOrDefault(id,0),observedDemand.getOrDefault(id,0));}

    private Network wifi(){
        if(cm==null)return null;
        Network n=cm.getActiveNetwork();NetworkCapabilities c=n==null?null:cm.getNetworkCapabilities(n);
        return c!=null&&c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)?n:null;
    }
    private Check networkCheck(){
        if(cm==null)return check("Wi-Fi",FAIL,"Android network information is unavailable.","Check the device network settings and reopen Roots.");
        Network n=cm.getActiveNetwork();NetworkCapabilities capabilities=n==null?null:cm.getNetworkCapabilities(n);
        if(capabilities==null)return check("Wi-Fi",FAIL,"There is no active network.","Connect both devices to the same Wi-Fi.");
        if(!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)||capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            return check("Wi-Fi",FAIL,"The active network is a VPN; Mesh requires a direct Wi-Fi route.","Use a direct Wi-Fi connection for this local peer test.");
        if(!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            return check("Wi-Fi",FAIL,"Wi-Fi is not the active network used by Roots Mesh.","Make the same Wi-Fi the active connection on both devices.");
        return check("Wi-Fi",PASS,"Direct Wi-Fi is the active network.","");
    }
    private String localAddresses(Network network){
        LinkProperties properties=network==null||cm==null?null:cm.getLinkProperties(network);
        List<String> addresses=new ArrayList<>();
        if(properties!=null)for(LinkAddress link:properties.getLinkAddresses())
            if(link.getAddress() instanceof Inet4Address&&!link.getAddress().isLoopbackAddress()&&!link.getAddress().isLinkLocalAddress())
                addresses.add(link.getAddress().getHostAddress()+"/"+link.getPrefixLength());
        java.util.Collections.sort(addresses);
        return String.join(", ",addresses);
    }
    /** Stable network/listener facts only, so the view can detect stale reports. */
    String connectionSummary(){
        String network;
        try{
            Check check=networkCheck();Network wifi=wifi();String addresses=localAddresses(wifi);
            LinkProperties lp=wifi==null?null:cm.getLinkProperties(wifi);
            String iface=lp==null||lp.getInterfaceName()==null?"":lp.getInterfaceName().replaceAll("[^a-zA-Z0-9_.:-]","");
            if(iface.length()>40)iface=iface.substring(0,40);
            network=check.detail()+(iface.isEmpty()?"":" Interface "+iface+".")+(addresses.isEmpty()?"":" IPv4 "+addresses+".");
        }catch(RuntimeException e){network="Current network information unavailable.";}
        Server server=active;
        return network+"\n"+(server==null?"Local listener: stopped. Receiving does not require a local listener.":
            "Local listener: serving at "+server.address.getHostAddress()+":"+PORT+". Keep Roots running.");
    }
    String connectionStateKey(){
        long handle=0;try{Network n=cm==null?null:cm.getActiveNetwork();if(n!=null)handle=n.getNetworkHandle();}catch(RuntimeException ignored){}
        return handle+"|"+connectionSummary();
    }
    private List<Check> localChecks(){
        List<Check> checks=new ArrayList<>();
        try{
            Check network=networkCheck();checks.add(network);String addresses=localAddresses(wifi());
            checks.add(check("Local IPv4",network.state().equals(PASS)?(addresses.isEmpty()?FAIL:PASS):SKIP,
                addresses.isEmpty()?"No usable Wi-Fi IPv4 address is available.":"Local address and prefix: "+addresses,
                addresses.isEmpty()?"Join a Wi-Fi network that assigns an IPv4 address.":""));
        }catch(RuntimeException e){checks.add(failure("Wi-Fi",e,0));}
        Server server=active;
        checks.add(check("Local listener",server==null?SKIP:PASS,server==null?
            "Not serving on this device. A local listener is not required to receive.":"Serving at "+server.address.getHostAddress()+":"+PORT+".",""));
        checks.add(check("Local TLS identity",tlsIdentityState,tlsIdentityStatus,FAIL.equals(tlsIdentityState)?"Update Roots on this device and try Start listening again.":""));
        Check incoming=incomingTlsFailure;if(server!=null&&incoming!=null)checks.add(incoming);
        boolean nativeReady=false;try{nativeReady=NativeTree.available();}catch(LinkageError ignored){}
        checks.add(check("Native Tree",nativeReady?PASS:WARN,nativeReady?"Native Tree library is available; database writes are not tested.":
            "Native Tree library is unavailable. The network test can still run.",nativeReady?"":"Install a build with Native Tree before receiving knowledge."));
        checks.add(check("Runtime Axon",SKIP,AxonRuntime.status()+". Runtime arming is separate from Test connections and Receive.",""));
        return checks;
    }
    private static String shortIdentity(Invite invite){return invite.pin().substring(0,12)+"…";}
    private static Trace trace(Invite invite,java.util.function.Consumer<String> progress){
        Trace trace=new Trace(invite.host()+":"+PORT,shortIdentity(invite),progress);
        trace.add(check("Invitation",PASS,"Mesh v1 invitation parsed; access token present and omitted from this report.",""));
        return trace;
    }
    private Report remember(long checkedAt,List<Check> local,List<Trace> traces){return report(checkedAt,local,traces,true);}
    private Report report(long checkedAt,List<Check> local,List<Trace> traces,boolean remember){
        List<PeerReport> peers=new ArrayList<>();for(Trace trace:traces)peers.add(trace.snapshot());
        Report report=new Report(checkedAt,local,peers);if(remember)lastConnectionReport=report;return report;
    }
    /** An explicit, bounded inventory-only check. Selected bound invitations
     * refresh encrypted saved access; no item requests, discovery or Tree writes. */
    Report testConnections(String rawInvitations,boolean useSavedAxon,java.util.function.Consumer<String> progress){
        return testConnections(rawInvitations,useSavedAxon,progress,true);
    }
    /** Foreground health checks must not overwrite an explicit receive/test snapshot. */
    Report refreshBoundConnections(){return testConnections("",true,null,false);}
    private Report testConnections(String rawInvitations,boolean useSavedAxon,java.util.function.Consumer<String> progress,boolean remember){
        long checkedAt=System.currentTimeMillis(),testDeadline=android.os.SystemClock.elapsedRealtime()+30000L;
        List<Check> local=localChecks();List<Trace> traces=new ArrayList<>();
        if(remember)lastConnectionReport=new Report(checkedAt,local,new ArrayList<>());
        MeshPeerAccess.Resolution access=MeshPeerAccess.resolve(app,rawInvitations,useSavedAxon);
        local.addAll(access.checks());
        if(!access.ready())return report(checkedAt,local,traces,remember);
        AxonSubscription policy=access.policy();List<Invite> invites=access.peers();
        for(Invite invite:invites){
            Trace trace=trace(invite,progress);
            trace.add(check("Peer access",PASS,access.fromSaved()?"Using the saved peer connection; no invitation text is required.":
                "Using the entered peer invitation.",""));
            traces.add(trace);
        }
        Network network=null;try{network=wifi();}catch(RuntimeException ignored){}
        for(int i=0;i<invites.size();i++){
            Invite invite=invites.get(i);Trace trace=traces.get(i);
            boolean accepted=!useSavedAxon||policy!=null&&policy.pins.contains(invite.pin());
            trace.add(check("Axon binding",!useSavedAxon?SKIP:accepted?PASS:FAIL,!useSavedAxon?"No saved policy requested.":
                accepted?"Invitation identity matches the saved Axon.":"Invitation identity is not authorized by the selected saved Axon.",
                accepted?"":"Verify the intended peer, then edit the Axon binding or disable the saved policy."));
            trace.prepare();
            if(trace.failed()||network==null)continue;
            long now=android.os.SystemClock.elapsedRealtime();
            if(now>=testDeadline){trace.add(check("Subnet",SKIP,"The 30-second test budget was used by earlier peers.","Test this peer separately."));continue;}
            try{inventory(network,invite,"",Math.min(testDeadline,now+8000L),trace);}
            catch(Exception e){/* The shared exchange/inventory stages recorded a safe failure. */}
        }
        return report(checkedAt,local,traces,remember);
    }
    private Inet4Address address(Network network)throws IOException{
        LinkProperties properties=cm.getLinkProperties(network);
        if(properties!=null)for(LinkAddress link:properties.getLinkAddresses())
            if(link.getAddress() instanceof Inet4Address&&!link.getAddress().isLoopbackAddress()&&!link.getAddress().isLinkLocalAddress())return (Inet4Address)link.getAddress();
        throw new IOException("Wi-Fi IPv4 address unavailable.");
    }
    private boolean sameSubnet(Network network,InetAddress target){
        LinkProperties lp=cm.getLinkProperties(network);
        if(lp==null||!(target instanceof Inet4Address)||target.isLoopbackAddress()||target.isAnyLocalAddress()||target.isMulticastAddress())return false;
        byte[] peer=target.getAddress();
        for(LinkAddress l:lp.getLinkAddresses()){
            if(!(l.getAddress() instanceof Inet4Address)||l.getAddress().equals(target))continue;
            byte[] own=l.getAddress().getAddress();int prefix=l.getPrefixLength();
            if(prefix<8||prefix>32)continue;
            boolean match=true;
            for(int i=0;i<4;i++){
                int bits=Math.max(0,Math.min(8,prefix-i*8)),mask=bits==0?0:0xff<<(8-bits);
                if((own[i]&mask)!=(peer[i]&mask)){match=false;break;}
            }
            if(match)return true;
        }
        return false;
    }
    record Invite(String host,String pin,String token){
        static Invite parse(String value)throws Exception{
            if(value==null||value.length()>1024)throw new IllegalArgumentException("Invalid Roots Mesh invitation.");
            Uri uri=Uri.parse(value.trim());
            if(!"roots".equals(uri.getScheme())||!"mesh".equals(uri.getAuthority())||!"/v1".equals(uri.getPath())||uri.getFragment()!=null)
                throw new IllegalArgumentException("Use a roots://mesh/v1 invitation.");
            for(String key:new String[]{"host","port","pin","token"})if(uri.getQueryParameters(key).size()!=1)
                throw new IllegalArgumentException("Invitation fields must occur exactly once.");
            String host=uri.getQueryParameter("host"),pin=uri.getQueryParameter("pin"),token=uri.getQueryParameter("token"),port=uri.getQueryParameter("port");
            if(host==null||!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")||!"8233".equals(port)||pin==null||!pin.matches("[0-9a-f]{64}")||token==null||!token.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid Roots Mesh invitation.");
            StringBuilder normalized=new StringBuilder();
            for(String part:host.split("\\.")){
                int octet=Integer.parseInt(part);if(octet>255)throw new IllegalArgumentException("Invalid IPv4 address.");
                if(normalized.length()>0)normalized.append('.');normalized.append(octet);
            }
            host=normalized.toString();
            return new Invite(host,pin,token);
        }
        @Override public String toString(){return "Roots Mesh invitation for "+host+":"+PORT+" (credentials omitted)";}
        String uri(){return new Uri.Builder().scheme("roots").authority("mesh").path("/v1").appendQueryParameter("host",host)
            .appendQueryParameter("port",Integer.toString(PORT)).appendQueryParameter("pin",pin).appendQueryParameter("token",token).build().toString();}
    }
    private static final class PeerKeyManager extends X509ExtendedKeyManager {
        private final MeshTlsIdentity.Identity identity;
        PeerKeyManager(MeshTlsIdentity.Identity identity){this.identity=identity;}
        private String alias(String keyType){return keyType!=null&&(keyType.startsWith("EC")||keyType.equalsIgnoreCase("ECDSA"))?identity.alias():null;}
        @Override public String[] getClientAliases(String type,Principal[] issuers){return null;}
        @Override public String chooseClientAlias(String[] types,Principal[] issuers,Socket socket){return null;}
        @Override public String[] getServerAliases(String type,Principal[] issuers){return alias(type)==null?null:new String[]{identity.alias()};}
        @Override public String chooseServerAlias(String type,Principal[] issuers,Socket socket){return alias(type);}
        @Override public String chooseEngineServerAlias(String type,Principal[] issuers,javax.net.ssl.SSLEngine engine){return alias(type);}
        @Override public X509Certificate[] getCertificateChain(String alias){return identity.alias().equals(alias)?new X509Certificate[]{identity.certificate()}:null;}
        @Override public PrivateKey getPrivateKey(String alias){return identity.alias().equals(alias)?identity.key():null;}
    }
    private static String[] protocols(String[] supported){
        return Arrays.stream(supported).filter(p->p.equals("TLSv1.2")||p.equals("TLSv1.3")).toArray(String[]::new);
    }
    private final class Server {
        final Network network;final Inet4Address address;final String token;final SSLServerSocket socket;
        final Set<Socket> clients=ConcurrentHashMap.newKeySet();
        final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(4));
        ConnectivityManager.NetworkCallback callback;
        Server(Network n,Inet4Address a,String t,SSLServerSocket s){network=n;address=a;token=t;socket=s;}
    }
    synchronized String start()throws Exception{
        if(active!=null)return invitation;
        if(!NativeTree.available())throw new IOException("Native Tree unavailable.");
        Network network=wifi();if(network==null)throw new IOException("Connect to Wi-Fi without VPN first.");
        Inet4Address address=address(network);
        MeshTlsIdentity.Identity identity;
        try{identity=MeshTlsIdentity.loadOrCreate();}
        catch(Exception e){tlsIdentityState=FAIL;tlsIdentityStatus="TLS signing self-check failed; this device's listener has not started.";throw e;}
        incomingTlsFailure=null;
        SSLContext context=SSLContext.getInstance("TLS");
        context.init(new KeyManager[]{new PeerKeyManager(identity)},null,random);
        String pin=MeshItem.hex(MessageDigest.getInstance("SHA-256").digest(identity.certificate().getEncoded()));
        String[] working;
        try{
            SSLContext localClient=SSLContext.getInstance("TLS");
            localClient.init(null,new javax.net.ssl.TrustManager[]{new MeshTlsPin(pin)},random);
            working=MeshTlsHandshake.workingProtocols(context,localClient);
        }catch(Exception error){
            tlsIdentityState=FAIL;
            tlsIdentityStatus="Signing succeeded, but the pinned TLS loopback handshake failed; no invitation was advertised. "+failure("TLS",error,0).detail();
            throw new IOException(tlsIdentityStatus);
        }
        tlsIdentityState=PASS;
        tlsIdentityStatus="Pinned TLS loopback handshake passed: "+String.join(", ",working)+". Identity "+pin.substring(0,12)+"…."+
            (identity.upgraded()?" TLS identity upgraded. Share the new invitation and update the receiver's Axon binding once.":"");
        byte[] tokenBytes=new byte[32];random.nextBytes(tokenBytes);String token=MeshItem.hex(tokenBytes);
        SSLServerSocket socket=(SSLServerSocket)context.getServerSocketFactory().createServerSocket();
        try{
            socket.setUseClientMode(false);socket.setNeedClientAuth(false);
            socket.setReuseAddress(true);socket.setEnabledProtocols(working);
            socket.bind(new InetSocketAddress(address,PORT),8);
            Server server=new Server(network,address,token,socket);
            active=server;
            invitation=new Invite(address.getHostAddress(),pin,token).uri();
            status="Serving published knowledge on Wi-Fi";
            server.callback=new ConnectivityManager.NetworkCallback(){
                @Override public void onLost(Network n){if(n.equals(network))stopIfCurrent(server);}
                @Override public void onLinkPropertiesChanged(Network n,LinkProperties lp){
                    if(!n.equals(network))return;
                    boolean found=false;for(LinkAddress l:lp.getLinkAddresses())if(address.equals(l.getAddress()))found=true;
                    if(!found)stopIfCurrent(server);
                }
                @Override public void onAvailable(Network n){if(!n.equals(network))stopIfCurrent(server);}
            };
            try{cm.registerDefaultNetworkCallback(server.callback);}catch(RuntimeException e){stopIfCurrent(server);throw e;}
            Thread thread=new Thread(()->accept(server),"RootsMeshListener");thread.setDaemon(true);thread.start();
            return invitation;
        }catch(Exception e){try{socket.close();}catch(Exception ignored){}throw e;}
    }
    private synchronized void stopIfCurrent(Server server){if(active==server)stop();}
    synchronized void stop(){
        Server server=active;active=null;invitation="";status="Not serving";
        if(server==null)return;
        try{cm.unregisterNetworkCallback(server.callback);}catch(Exception ignored){}
        try{server.socket.close();}catch(Exception ignored){}
        for(Socket client:server.clients)try{client.close();}catch(Exception ignored){}
        server.workers.shutdownNow();
    }
    private void accept(Server server){
        try{
            while(active==server){
                SSLSocket client=(SSLSocket)server.socket.accept();
                if(active!=server||!sameSubnet(server.network,client.getInetAddress())){client.close();continue;}
                server.clients.add(client);
                try{server.workers.execute(()->handle(server,client));}
                catch(java.util.concurrent.RejectedExecutionException e){server.clients.remove(client);client.close();}
            }
        }catch(IOException e){if(active==server)stopIfCurrent(server);}
    }
    private Map<String,MeshItem> catalog(){
        Map<String,MeshItem> items=new java.util.TreeMap<>();
        // Explicit offer records only. No raw observations, original post bodies,
        // private branch terms, GGUF, or SQLite are ever served.
        for(NativeTree.Master record:tree.allRecords("",10000)){
            MeshItem item=MeshItem.from(record);if(item!=null)items.putIfAbsent(item.id(),item);

        }
        return items;
    }
    int offeredCount(){return catalog().size();}
    static JSONObject read(Socket socket)throws Exception{
        DataInputStream in=new DataInputStream(socket.getInputStream());
        int size=in.readInt();if(size<2||size>MAX_FRAME)throw new Failure("Protocol","The peer sent an invalid Mesh frame length.","Update both Roots installations and test again.");
        byte[] bytes=new byte[size];in.readFully(bytes);
        try{return new JSONObject(new String(bytes,StandardCharsets.UTF_8));}
        catch(org.json.JSONException e){throw new Failure("Protocol","The peer sent malformed Mesh JSON.","Update both Roots installations and test again.");}
    }
    private static void write(Socket socket,JSONObject json)throws Exception{
        byte[] bytes=json.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_FRAME)throw new IOException("Mesh response too large.");
        DataOutputStream out=new DataOutputStream(socket.getOutputStream());out.writeInt(bytes.length);out.write(bytes);out.flush();
    }
    private void reject(Server server,Socket client,String code)throws Exception{
        if(active==server)write(client,new JSONObject().put("version",1).put("error",code));
    }
    private void handle(Server server,SSLSocket socket){
        boolean handshaken=false;long handshakeStarted=android.os.SystemClock.elapsedRealtime();
        try(SSLSocket client=socket){
            client.setSoTimeout(8000);client.startHandshake();
            handshaken=true;if(active==server)incomingTlsFailure=null;
            JSONObject request=read(client);
            if(active!=server)return;
            if(!MessageDigest.isEqual(server.token.getBytes(StandardCharsets.US_ASCII),request.optString("token").getBytes(StandardCharsets.US_ASCII))){reject(server,client,"unauthorized");return;}
            if(request.optInt("version")!=1){reject(server,client,"unsupported_version");return;}
            String op=request.optString("op");
            if(!op.equals("inventory")&&!op.equals("want")&&!op.equals("need")){reject(server,client,"unsupported_operation");return;}
            Map<String,MeshItem> catalog=catalog();
            JSONObject response=new JSONObject().put("version",1);
            if(op.equals("inventory")){
                String after=request.optString("after");if(!after.isEmpty()&&!after.matches("[0-9a-f]{64}")){reject(server,client,"invalid_request");return;}
                JSONArray entries=new JSONArray();String next="";
                for(MeshItem item:catalog.values()){
                    if(item.id().compareTo(after)<=0)continue;
                    if(entries.length()>=MAX_INVENTORY){next=entries.getJSONObject(entries.length()-1).getString("id");break;}
                    entries.put(new JSONObject().put("id",item.id()).put("language",item.language())
                        .put("topic",item.topic()).put("demand",Math.min(1000,requested.getOrDefault(item.id(),0))));
                }
                response.put("items",entries).put("next",next);
            }else if(op.equals("need")){
                // One bounded request/response, no arbitrary search query, file access or computation.
                String id=request.optString("request_id"),topic=request.optString("topic"),language=request.optString("language");
                int limit=request.optInt("limit",0);
                if(!id.matches("[0-9a-f-]{36}")||!RuntimeNeed.validTopic(topic)||!language.matches("[a-z]{2,3}")||limit<1||limit>3){reject(server,client,"invalid_request");return;}
                List<MeshItem> candidates=new ArrayList<>();
                for(MeshItem item:catalog.values())if(RuntimeNeed.matches(item.topic(),topic))candidates.add(item);
                candidates.sort(Comparator.comparingInt((MeshItem item)->item.language().equals(language)?0:1)
                    .thenComparing(Comparator.comparingInt((MeshItem item)->requested.getOrDefault(item.id(),0)).reversed()).thenComparing(MeshItem::id));
                JSONArray delivered=new JSONArray();
                for(int i=0;i<Math.min(limit,candidates.size());i++)delivered.put(candidates.get(i).json());
                response.put("op","deliver").put("request_id",id).put("items",delivered);
            }else{
                String id=request.optString("id");MeshItem item=catalog.get(id);
                if(item==null){response.put("error","unavailable");}
                else{
                    if(requested.size()>4096)requested.clear();
                    requested.merge(id,1,(a,b)->Math.min(1000,a+b));
                    response.put("item",item.json());
                }
            }
            if(active==server)write(client,response);
        }catch(Exception error){
            if(!handshaken&&active==server){
                Check failed=failure("TLS",error,android.os.SystemClock.elapsedRealtime()-handshakeStarted);
                incomingTlsFailure=new Check("Last incoming TLS",WARN,failed.detail(),failed.nextStep(),failed.elapsedMs());
            }
            // No request, content, invitation or arbitrary provider message is logged.
        }
        finally{server.clients.remove(socket);}
    }
    private JSONObject exchange(Network network,Invite invite,JSONObject request)throws Exception{
        return exchange(network,invite,request,0,null);
    }
    private static final java.util.concurrent.ScheduledThreadPoolExecutor DEADLINES=new java.util.concurrent.ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"RootsNeedDeadline");t.setDaemon(true);return t;});
    static {DEADLINES.setRemoveOnCancelPolicy(true);}
    private JSONObject exchange(Network network,Invite invite,JSONObject request,long deadline)throws Exception{
        return exchange(network,invite,request,deadline,null);
    }
    private static final class RetryTls12 extends IOException {
        final Check first;
        RetryTls12(Check first){super(first.detail());this.first=first;}
    }
    private JSONObject exchange(Network network,Invite invite,JSONObject request,long deadline,Trace suppliedTrace)throws Exception{
        Trace trace=suppliedTrace==null?trace(invite,null):suppliedTrace;
        long stopAt=deadline==0?android.os.SystemClock.elapsedRealtime()+8000L:deadline;
        try{return exchangeOnce(network,invite,request,stopAt,trace,false);}
        catch(RetryTls12 retry){
            trace.add(check("TLS compatibility",WARN,"Initial TLS negotiation failed: "+retry.first.detail()+" Retrying with TLS 1.2 and the same certificate pin.",""));
            return exchangeOnce(network,invite,request,stopAt,trace,true);
        }
    }
    private JSONObject exchangeOnce(Network network,Invite invite,JSONObject request,long stopAt,Trace trace,boolean tls12Only)throws Exception{
        trace.prepare();trace.begin("Subnet",android.os.SystemClock.elapsedRealtime());
        MeshTlsPin pin=null;
        try{
            InetAddress destination=InetAddress.getByName(invite.host());
            if(network==null||!network.equals(wifi()))throw new Failure("Subnet","The active Wi-Fi network changed or is unavailable.","Reconnect to the peer's Wi-Fi and test again.");
            LinkProperties properties=cm.getLinkProperties(network);
            if(properties!=null)for(LinkAddress link:properties.getLinkAddresses())if(link.getAddress().equals(destination))
                throw new Failure("Subnet","This invitation points to this device, not another peer.","Paste the invitation created by the other device.");
            if(!sameSubnet(network,destination))throw new Failure("Subnet","The invited address is outside the current Wi-Fi subnet or is not a usable peer address.",
                "Connect both devices to the same Wi-Fi subnet and copy the peer's current invitation.");
            trace.pass("Subnet","Peer is another device on the current Wi-Fi IPv4 subnet.",android.os.SystemClock.elapsedRealtime());
            trace.begin("TCP",android.os.SystemClock.elapsedRealtime());
            try(Socket plain=network.getSocketFactory().createSocket()){
                int remaining=RuntimeNeed.remaining(stopAt,android.os.SystemClock.elapsedRealtime());
                if(remaining<=0)throw new java.net.SocketTimeoutException();
                java.util.concurrent.ScheduledFuture<?> expiry=DEADLINES.schedule(()->{try{plain.close();}catch(IOException ignored){}},remaining,TimeUnit.MILLISECONDS);
                try{
                    plain.connect(new InetSocketAddress(destination,PORT),Math.min(3500,remaining));plain.setSoTimeout(remaining);
                    trace.pass("TCP","Connected to the invited peer on TCP port "+PORT+".",android.os.SystemClock.elapsedRealtime());
                    trace.begin("TLS",android.os.SystemClock.elapsedRealtime());
                    pin=new MeshTlsPin(invite.pin());
                    SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,new javax.net.ssl.TrustManager[]{pin},random);
                    try(SSLSocket socket=(SSLSocket)tls.getSocketFactory().createSocket(plain,invite.host(),PORT,true)){
                        remaining=RuntimeNeed.remaining(stopAt,android.os.SystemClock.elapsedRealtime());if(remaining<=0)throw new java.net.SocketTimeoutException();
                        socket.setUseClientMode(true);
                        socket.setEnabledProtocols(tls12Only?new String[]{"TLSv1.2"}:protocols(socket.getSupportedProtocols()));
                        socket.setSoTimeout(remaining);socket.startHandshake();
                        // Verify the negotiated session too, before any invitation token is sent.
                        pin.verifySession(socket.getSession());trace.add(pin.observation());
                        String protocol=socket.getSession().getProtocol();
                        trace.pass("TLS","Pinned certificate and certificate dates verified; "+(protocol.equals("TLSv1.3")?"TLS 1.3":"TLS 1.2")+" established.",android.os.SystemClock.elapsedRealtime());
                        trace.begin("Authentication",android.os.SystemClock.elapsedRealtime());
                        request.put("version",1).put("token",invite.token());write(socket,request);
                        JSONObject response=read(socket);
                        if(response.optInt("version")!=1)throw new Failure("Protocol","The peer returned an unsupported Mesh protocol version.","Update both Roots installations to compatible versions.");
                        trace.pass("Protocol","Mesh protocol version 1 accepted.",android.os.SystemClock.elapsedRealtime());
                        if(response.has("error"))throw protocolFailure(response.optString("error"));
                        if(RuntimeNeed.remaining(stopAt,android.os.SystemClock.elapsedRealtime())<=0)throw new java.net.SocketTimeoutException();
                        trace.pass("Authentication","The peer accepted the invitation and returned a response.",android.os.SystemClock.elapsedRealtime());
                        return response;
                    }
                }finally{expiry.cancel(false);}
            }
        }catch(Exception e){
            Exception failure=e;
            if(!(e instanceof Failure)&&RuntimeNeed.remaining(stopAt,android.os.SystemClock.elapsedRealtime())<=0)failure=new java.net.SocketTimeoutException();
            if("TLS".equals(trace.currentStage())&&pin!=null){
                trace.add(pin.observation());
                if(pin.rejected())failure=new Failure("TLS",pin.observation().detail(),pin.observation().nextStep());
                else if(!tls12Only&&MeshConnectionDiagnostics.canRetryTls12(failure)&&RuntimeNeed.remaining(stopAt,android.os.SystemClock.elapsedRealtime())>=1000)
                    throw new RetryTls12(MeshConnectionDiagnostics.failure("TLS",failure,0));
            }
            trace.fail(failure,android.os.SystemClock.elapsedRealtime());
            Check safe=MeshConnectionDiagnostics.failure(trace.currentStage(),failure,0);
            throw failure instanceof Failure?failure:new Failure(safe.stage(),safe.detail(),safe.nextStep());
        }
    }
    record InventoryPage(JSONArray items,String next){}
    private InventoryPage inventory(Network network,Invite invite,String after,long deadline,Trace trace)throws Exception{
        JSONObject response=exchange(network,invite,new JSONObject().put("op","inventory").put("after",after),deadline,trace);
        trace.begin("Inventory",android.os.SystemClock.elapsedRealtime());
        try{
            InventoryPage page=validateInventory(response,after);
            trace.pass("Inventory",page.items().length()==0&&page.next().isEmpty()?"Connected. The peer currently has no published items.":
                "Inventory accepted: "+page.items().length()+" published item(s) on this page."+(page.next().isEmpty()?"":" More pages are available."),android.os.SystemClock.elapsedRealtime());
            return page;
        }catch(Exception e){trace.fail(e,android.os.SystemClock.elapsedRealtime());throw new Failure("Inventory","The peer inventory was malformed or incompatible.","Update both Roots installations and test again.");}
    }
    /** Shared by receive and diagnostics: validation must have the same meaning. */
    static InventoryPage validateInventory(JSONObject response,String after)throws Exception{
        JSONArray rows=response.getJSONArray("items");if(rows.length()>MAX_INVENTORY)throw new IOException("Oversized inventory.");
        Set<String> seen=new HashSet<>();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);String id=row.getString("id"),language=row.getString("language"),topic=row.getString("topic");
            if(!id.matches("[0-9a-f]{64}")||!language.matches("[a-z]{2,3}")||topic.isEmpty()||topic.length()>120||!seen.add(id))throw new IOException("Invalid inventory entry.");
        }
        String next=response.optString("next");
        if(!next.isEmpty()&&(!next.matches("[0-9a-f]{64}")||next.compareTo(after)<=0||rows.length()==0))throw new IOException("Invalid inventory cursor.");
        return new InventoryPage(rows,next);
    }
    List<MeshItem> need(Invite invite,String topic,String language,int limit,long deadline)throws Exception{
        if(!RuntimeNeed.validTopic(topic)||limit<1||limit>3)throw new IllegalArgumentException("Invalid need");
        Network network=wifi();if(network==null)throw new IOException("No Wi-Fi");
        String id=java.util.UUID.randomUUID().toString();
        JSONObject response=exchange(network,invite,new JSONObject().put("op","need").put("request_id",id)
            .put("topic",topic).put("language",language).put("limit",limit),deadline);
        if(!"deliver".equals(response.optString("op"))||!id.equals(response.optString("request_id")))throw new IOException("Unexpected need response");
        JSONArray rows=response.getJSONArray("items");if(rows.length()>limit)throw new IOException("Too many need items");
        List<MeshItem> items=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(int i=0;i<rows.length();i++){
            MeshItem item=MeshItem.parse(rows.getJSONObject(i));
            if(!RuntimeNeed.matches(item.topic(),topic))throw new IOException("Unexpected topic");
            if(seen.add(item.id()))items.add(item);
        }
        return items;
    }
    private static final class Offer {
        final String id,language,topic;
        final List<Invite> sources=new ArrayList<>();
        int demand,interest;
        Offer(String id,String language,String topic){this.id=id;this.language=language;this.topic=topic;}
    }
    record SyncResult(int peers,int wanted,int received,int failed){}
    SyncResult sync(List<Invite> invites,String interests,String language,java.util.function.Consumer<String> progress)throws Exception{
        return sync(invites,interests,language,null,progress);
    }
    SyncResult sync(List<Invite> invites,String interests,String language,AxonSubscription axon,java.util.function.Consumer<String> progress)throws Exception{
        long checkedAt=System.currentTimeMillis();List<Check> local=localChecks();List<Trace> traces=new ArrayList<>();
        lastConnectionReport=new Report(checkedAt,local,new ArrayList<>());
        if(invites==null||invites.isEmpty()||invites.size()>8){
            local.add(check("Invitations",FAIL,"Enter one to eight peer invitations.","Paste one current invitation for each peer."));
            throw new IOException(failureSummary(remember(checkedAt,local,traces)));
        }
        Map<String,Integer> endpoints=new HashMap<>();for(Invite invite:invites)endpoints.merge(invite.host(),1,Integer::sum);
        boolean valid=true;
        for(Invite invite:invites){
            Trace trace=trace(invite,progress);traces.add(trace);
            boolean bound=axon==null||axon.pins.contains(invite.pin());
            trace.add(check("Axon binding",axon==null?SKIP:bound?PASS:FAIL,axon==null?"No saved policy requested.":
                bound?"Invitation identity matches the saved Axon.":"Invitation identity is not authorized by the saved Axon.",bound?"":"Verify the intended peer and edit the Axon binding."));
            if(endpoints.get(invite.host())>1)trace.add(check("Invitation",FAIL,"More than one invitation targets this endpoint.","Keep one freshly copied invitation for this peer."));
            trace.prepare();if(trace.failed())valid=false;
        }
        remember(checkedAt,local,traces);
        if(!valid)throw new IOException(failureSummary(lastConnectionReport));
        Network network;
        try{network=wifi();}catch(RuntimeException e){local.add(failure("Wi-Fi",e,0));network=null;}
        if(network==null)throw new IOException(failureSummary(remember(checkedAt,local,traces)));
        final long epoch=tree.captureEpoch();
        Map<String,Offer> offers=new HashMap<>();int peers=0,failed=0;
        // The inventory carries approved topic labels, never the receiver's private interests.
        for(int peerIndex=0;peerIndex<invites.size();peerIndex++){
            Invite invite=invites.get(peerIndex);Trace trace=traces.get(peerIndex);
            progress.accept("Reading peer inventory "+(peerIndex+1)+" of "+invites.size()+"…");
            try{
                Set<String> seen=new HashSet<>();String after="";
                for(int page=0;page<16;page++){
                    InventoryPage inventoryPage=inventory(network,invite,after,0,trace);
                    JSONArray inventory=inventoryPage.items();
                    if(page==0)peers++;
                    for(int i=0;i<inventory.length();i++){
                        JSONObject entry=inventory.getJSONObject(i);String id=entry.getString("id"),lang=entry.getString("language"),topic=entry.getString("topic");
                        if(!id.matches("[0-9a-f]{64}")||!lang.matches("[a-z]{2,3}")||topic.isEmpty()||topic.length()>120||!seen.add(id))continue;
                        Offer offer=offers.computeIfAbsent(id,k->new Offer(k,lang,topic));
                        offer.sources.add(invite);offer.demand=Math.max(offer.demand,Math.max(0,Math.min(1000,entry.optInt("demand"))));
                    }
                    String next=inventoryPage.next();
                    if(next.isEmpty())break;
                    after=next;
                }
            }catch(Exception e){failed++;/* Staged failures are retained in this peer's trace. */}
            remember(checkedAt,local,traces);
        }
        if(peers==0)throw new IOException("No peer inventory was reachable. "+failureSummary(lastConnectionReport));
        try{
            Map<String,Integer> replicas=new HashMap<>(),demand=new HashMap<>();
            for(Offer o:offers.values()){replicas.put(o.id,o.sources.size());demand.put(o.id,o.demand);}
            observedReplicas=java.util.Collections.unmodifiableMap(new HashMap<>(replicas));observedDemand=java.util.Collections.unmodifiableMap(new HashMap<>(demand));
            Set<String> known=new HashSet<>();
            List<String> terms=new ArrayList<>();
            List<NativeTree.Master> records=tree.allRecords("",10000);
            for(NativeTree.Master record:records){
                if(record.key().startsWith("local:mesh:"))known.add(record.key().substring(11));
                if(!record.latest("want").isEmpty()){
                    String topic=record.latest("topic");
                    if(!topic.trim().isEmpty())terms.add(topic.toLowerCase(Locale.ROOT));
                }
            }
            for(NativeTree.Agent agent:tree.agents())if(!agent.term().trim().isEmpty())terms.add(agent.term().toLowerCase(Locale.ROOT));
            for(String word:interests.toLowerCase(Locale.ROOT).split("[,;\\n]"))if(!word.trim().isEmpty())terms.add(word.trim());
            List<Offer> want=new ArrayList<>();
            for(Offer o:offers.values())if(!known.contains(o.id)){
                for(String term:terms)if(o.topic.toLowerCase(Locale.ROOT).contains(term))o.interest++;
                want.add(o);
            }
            // Want locally relevant material first, then rarest among the offered
            // items, then observed demand and local-language preference.
            want.sort((a,b)->MeshPriority.compare(
                new MeshPriority.Score(a.id,a.interest,a.sources.size(),a.demand,a.language.equals(language)),
                new MeshPriority.Score(b.id,b.interest,b.sources.size(),b.demand,b.language.equals(language))));
            int wanted=want.size();
            if(axon!=null){
                AxonSubscription.Allocation allocation=axon.allocation(app,tree,records,language);
                List<AxonPlanner.Offer> candidates=new ArrayList<>();
                for(Offer o:want)candidates.add(new AxonPlanner.Offer(o.id,o.topic,o.sources.size(),o.demand,o.language.equals(language)));
                wanted=0;for(AxonPlanner.Offer o:candidates)if(!AxonPlanner.lane(o.topic(),allocation.shares()).isEmpty())wanted++;
                List<String> ids=AxonPlanner.order(candidates,allocation.shares(),MAX_FETCH);
                want=new ArrayList<>();
                for(String id:ids)want.add(offers.get(id));
                progress.accept(axon.describe(allocation));
            }
            int received=0,attempted=0;
            for(Offer o:want){
                if(attempted++>=MAX_FETCH)break;
                if(!network.equals(wifi())||tree.captureEpoch()!=epoch)break;
                progress.accept("Receiving "+(received+1)+" · "+o.sources.size()+" available peer"+(o.sources.size()==1?"":"s"));
                boolean ok=false;
                for(Invite source:o.sources){
                    Trace trace=traces.get(invites.indexOf(source));
                    try{
                        JSONObject answer=exchange(network,source,new JSONObject().put("op","want").put("id",o.id),0,trace);
                        trace.begin("Receive",android.os.SystemClock.elapsedRealtime());
                        MeshItem item=MeshItem.parse(answer.getJSONObject("item"));
                        if(!item.id().equals(o.id))throw new IOException("Unexpected content ID.");
                        if(!item.topic().equals(o.topic)||!item.language().equals(o.language))throw new IOException("Inventory does not match verified content.");
                        long added=item.store(tree,epoch);if(added>0)received++;ok=true;
                        trace.pass("Receive","Published content verified and processed locally.",android.os.SystemClock.elapsedRealtime());break;
                    }catch(Exception e){
                        // exchange() already records transport errors. Parsing/storage
                        // failures happen at Receive and never expose peer content.
                        if(trace.currentStage().equals("Receive"))trace.fail(e,android.os.SystemClock.elapsedRealtime());
                    }
                }
                if(!ok)failed++;
            }
            remember(checkedAt,local,traces);
            return new SyncResult(peers,wanted,received,failed);
        }catch(Exception|LinkageError e){
            local.add(check("Local receive",FAIL,"Peer inventories were reached, but local receive processing failed.",
                "Check local storage space and the Native Tree installation, then retry Receive."));
            remember(checkedAt,local,traces);
            throw new IOException("Local receive processing failed. See connection details.");
        }
    }
}
