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
import javax.crypto.SecretKey;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedKeyManager;
import static com.wds.sixstars.contextoverlay.MeshConnectionDiagnostics.*;

/** Direct Wi-Fi peers, pinned TLS and content-addressed knowledge. A current
 * invitation authorizes the Tree graph; legacy clients can still read offers. */
final class MeshPeer {
    static final int PORT=8233, MAX_INVENTORY=256, MAX_FETCH=32, MAX_FRAME=256*1024;
    private static final int SESSION_IDLE_MS=45000, TRANSFER_TIMEOUT_MS=15000;
    private static MeshPeer instance;
    static synchronized MeshPeer get(Context context){
        if(instance==null)instance=new MeshPeer(context.getApplicationContext());
        return instance;
    }
    private final Context app;
    private final TreeRuntime tree;
    private final ConnectivityManager cm;
    private final SecureRandom random=new SecureRandom();
    // Only an authenticated enrollment response may replace a saved grant.
    // This also avoids redeeming the same in-memory bootstrap during a sync.
    private static final class DerivedGrant {
        final String original,derived;
        DerivedGrant(String original,String derived){this.original=original;this.derived=derived;}
    }
    private final Map<String,DerivedGrant> derivedGrants=new ConcurrentHashMap<>();
    private final Map<String,Integer> requested=new ConcurrentHashMap<>();
    private volatile Map<String,Integer> observedReplicas=java.util.Collections.emptyMap(), observedDemand=java.util.Collections.emptyMap();
    private final Object catalogLock=new Object();
    private record GraphCatalog(long revision,long epoch,Map<String,MeshItem> items){}
    private GraphCatalog graphCache;
    private volatile String invitation="";
    private volatile String status="Not serving";
    private volatile Server active;
    private volatile Report lastConnectionReport;
    private volatile Check incomingTlsFailure;
    private volatile Check incomingTlsLast;
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
        return network+"\n"+(server==null?"Local listener: "+MeshServingService.status(app)+".":
            "Local listener: serving at "+server.address.getHostAddress()+":"+PORT+". Available in background.");
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
        boolean servingEnabled=MeshServingService.enabled(app);
        checks.add(check("Local listener",server==null?(servingEnabled?WARN:SKIP):PASS,server==null?
            (servingEnabled?"Automatic serving enabled. "+MeshServingService.status(app)+".":"Not serving on this device. A local listener is not required to receive."):"Serving at "+server.address.getHostAddress()+":"+PORT+" in the background.",""));
        checks.add(check("Local TLS identity",tlsIdentityState,tlsIdentityStatus,FAIL.equals(tlsIdentityState)?"Update Roots on this device and try Start listening again.":""));
        Check incoming=incomingTlsLast;
        if(server!=null){
            checks.add(incoming==null?check("Last incoming TLS",SKIP,
                "No incoming connection has reached this listener since it started.",
                "Run the receiving device's connection test while this device is listening."):incoming);
            Check previous=incomingTlsFailure;
            if(previous!=null&&previous!=incoming)checks.add(new Check("Most recent incoming failure",
                WARN,previous.detail(),previous.nextStep(),previous.elapsedMs()));
        }
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
    /** Local-only diagnostics use the live listener's TLS context and pin.
     * No outgoing peer, subscription, invitation refresh, or identity rotation. */
    Report testLocal(){
        long checkedAt=System.currentTimeMillis();
        Server server=active;
        Check probe;
        if(server==null)probe=check("Local TLS live self-check",SKIP,
            "Listener is not running.","Start listening and check this device again.");
        else{
            long start=android.os.SystemClock.elapsedRealtime();
            try{
                SSLContext client=SSLContext.getInstance("TLS");
                client.init(null,new javax.net.ssl.TrustManager[]{new MeshTlsPin(server.pin)},random);
                String[] enabled=server.socket.getEnabledProtocols();
                String[] working=MeshTlsHandshake.workingProtocols(server.context,client,enabled);
                if(active!=server)probe=check("Local TLS live self-check",WARN,"Listener changed during the check.","Check this device again.");
                else probe=new Check("Local TLS live self-check",working.length==enabled.length?PASS:WARN,
                    "Live listener TLS context and certificate pin tested over loopback: "+String.join(", ",working)+
                    (working.length==enabled.length?".":"; not every enabled protocol succeeded."),
                    working.length==enabled.length?"":"Check the listening device's TLS provider.",android.os.SystemClock.elapsedRealtime()-start);
            }catch(Exception|LinkageError e){
                Check detail=failure("TLS",e,android.os.SystemClock.elapsedRealtime()-start);
                probe=new Check("Local TLS live self-check",FAIL,detail.detail(),detail.nextStep(),detail.elapsedMs());
            }
        }
        List<Check> local=localChecks();local.add(probe);
        local.add(check("Peer connections",SKIP,"Local check only; no subscription or peer is required.",""));
        return remember(checkedAt,local,new ArrayList<>());
    }
    /** An explicit, bounded inventory-only check. Selected bound invitations
     * refresh encrypted saved access; no item requests, discovery or Tree writes. */
    Report testConnections(String rawInvitations,boolean useSavedAxon,java.util.function.Consumer<String> progress){
        if(!useSavedAxon&&(rawInvitations==null||rawInvitations.trim().isEmpty()))return testLocal();
        return testConnections(rawInvitations,useSavedAxon,progress,true,()->true);
    }
    /** Foreground health checks must not overwrite an explicit receive/test snapshot.
     * Leaving the screen or changing the binding stops starting new exchanges. */
    Report refreshBoundConnections(java.util.function.BooleanSupplier active){return testConnections("",true,null,false,active);}
    private Report testConnections(String rawInvitations,boolean useSavedAxon,java.util.function.Consumer<String> progress,boolean remember,java.util.function.BooleanSupplier continueChecking){
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
            if(!continueChecking.getAsBoolean())break;
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
        final SSLContext context;final String pin;final SecretKey networkRoot;
        final Set<Socket> clients=ConcurrentHashMap.newKeySet();
        final ThreadPoolExecutor workers=new ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(4));
        Server(Network n,Inet4Address a,String t,SSLServerSocket s,SSLContext context,String pin,SecretKey networkRoot){
            network=n;address=a;token=t;socket=s;this.context=context;this.pin=pin;this.networkRoot=networkRoot;
        }
    }
    synchronized String restoreListener()throws Exception{
        if(!MeshServingService.enabled(app)){stop();return "";}
        Network network=wifi();
        if(network==null){stop();return "";}
        Inet4Address currentAddress;
        try{currentAddress=address(network);}catch(IOException unavailable){stop();return "";}
        Server server=active;
        if(server!=null&&(!network.equals(server.network)||!currentAddress.equals(server.address)||server.socket.isClosed()))stop();
        if(!MeshServingService.enabled(app))return "";
        return start();
    }
    private synchronized String start()throws Exception{
        if(!MeshServingService.enabled(app))return "";
        if(active!=null)return invitation;
        if(!NativeTree.available())throw new IOException("Native Tree unavailable.");
        Network network=wifi();if(network==null)throw new IOException("Connect to Wi-Fi without VPN first.");
        Inet4Address address=address(network);
        MeshTlsIdentity.Identity identity;
        try{identity=MeshTlsIdentity.loadOrCreate(app);}
        catch(Exception e){tlsIdentityState=FAIL;tlsIdentityStatus="Saved TLS identity could not be loaded or verified; the listener has not started.";throw e;}
        incomingTlsFailure=null;incomingTlsLast=null;
        SSLContext context=SSLContext.getInstance("TLS");
        context.init(new KeyManager[]{new PeerKeyManager(identity)},null,random);
        String pin=MeshTlsIdentity.fingerprint(identity);
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
        tlsIdentityStatus="Pinned TLS loopback handshake passed: "+String.join(", ",working)+". Persistent identity "+pin.substring(0,12)+"…."+
            (identity.upgraded()?" TLS identity upgraded. Share the new invitation and update the receiver's Axon binding once.":"");
        SecretKey networkRoot=MeshNetworkAccess.loadOrCreate(app);
        String token=MeshNetworkAccess.enrollment(networkRoot,pin);
        SSLServerSocket socket=(SSLServerSocket)context.getServerSocketFactory().createServerSocket();
        try{
            socket.setUseClientMode(false);socket.setNeedClientAuth(false);
            socket.setReuseAddress(true);socket.setEnabledProtocols(working);
            socket.bind(new InetSocketAddress(address,PORT),8);
            Server server=new Server(network,address,token,socket,context,pin,networkRoot);
            if(!MeshServingService.enabled(app)){socket.close();server.workers.shutdownNow();return "";}
            active=server;
            invitation=new Invite(address.getHostAddress(),pin,token).uri();
            status="Sharing Tree graph on Wi-Fi";
            Thread thread=new Thread(()->accept(server),"RootsMeshListener");thread.setDaemon(true);thread.start();
            return invitation;
        }catch(Exception e){try{socket.close();}catch(Exception ignored){}throw e;}
    }
    private synchronized void stopIfCurrent(Server server){
        if(active==server){stop();MeshServingService.listenerLost();}
    }
    synchronized void stop(){
        Server server=active;active=null;invitation="";status="Not serving";
        if(server==null)return;
        try{server.socket.close();}catch(Exception ignored){}
        for(Socket client:server.clients)try{client.close();}catch(Exception ignored){}
        server.workers.shutdownNow();
    }
    private void accept(Server server){
        try{
            while(active==server){
                SSLSocket client=(SSLSocket)server.socket.accept();
                if(active!=server||!MeshServingService.enabled(app)){client.close();continue;}
                boolean allowed;
                try{allowed=sameSubnet(server.network,client.getInetAddress());}
                catch(RuntimeException error){
                    recordIncomingFailure(server,check("Last incoming TLS",FAIL,
                        "Incoming TCP connection closed before TLS: local subnet lookup failed.",
                        "Check the listening device's Wi-Fi state."));
                    client.close();continue;
                }
                if(!allowed){
                    recordIncomingFailure(server,check("Last incoming TLS",FAIL,
                        "Incoming TCP connection closed before TLS: remote address did not pass the local IPv4 subnet check.",
                        "Compare both devices' IPv4 addresses and network prefixes."));
                    client.close();continue;
                }
                server.clients.add(client);
                try{server.workers.execute(()->handle(server,client));}
                catch(java.util.concurrent.RejectedExecutionException e){
                    recordIncomingFailure(server,check("Last incoming TLS",FAIL,
                        "Incoming TCP connection closed before TLS: listener worker queue is full.",
                        "Retry after the current peer requests finish."));
                    server.clients.remove(client);client.close();
                }
            }
        }catch(IOException e){if(active==server)stopIfCurrent(server);}
    }
    // The graph is the default for current clients. No per-record publication
    // gate; this is the extension point for future outbound graph restrictions.
    private Map<String,MeshItem> graphCatalog()throws Exception{
        synchronized(catalogLock){
            long epoch=tree.captureEpoch(),revision=tree.graphRevision();
            if(graphCache!=null&&graphCache.revision()==revision&&graphCache.epoch()==epoch)return graphCache.items();
            Map<String,MeshItem> items=new java.util.TreeMap<>();
            long before=0;
            while(true){
                List<NativeTree.Fragment> page=tree.query("",0,before,100);
                if(page.isEmpty())break;
                for(NativeTree.Fragment fragment:page){
                    MeshItem item=MeshItem.fromFragment(fragment);
                    items.putIfAbsent(item.id(),item);
                }
                long next=page.get(page.size()-1).sequence();
                if(next<=0||(before!=0&&next>=before))throw new IOException("Invalid Tree cursor");
                before=next;
                if(tree.captureEpoch()!=epoch)throw new IOException("Tree changed during graph inventory");
                if(page.size()<100)break;
            }
            for(NativeTree.Agent agent:tree.agents()){
                MeshItem item=MeshItem.fromBranch(agent);
                items.putIfAbsent(item.id(),item);
            }
            if(tree.captureEpoch()!=epoch)throw new IOException("Tree changed during graph inventory");
            graphCache=new GraphCatalog(revision,epoch,java.util.Collections.unmodifiableMap(items));
            return graphCache.items();
        }
    }
    private Map<String,MeshItem> legacyCatalog(){
        Map<String,MeshItem> items=new java.util.TreeMap<>();
        for(NativeTree.Master record:tree.allRecords("",10000)){
            MeshItem item=MeshItem.from(record);if(item!=null)items.putIfAbsent(item.id(),item);
        }
        return items;
    }
    /** Public encounters never use graphCatalog(), which contains raw captures. */
    Map<String,MeshItem> encounterCatalog(){return legacyCatalog();}
    private final Object encounterCountLock=new Object();
    private long encounterCountAt=-1;
    private int encounterCount;
    // UI-only cache: actual session inventory always reads a fresh catalog.
    int encounterPublicationCount(){
        synchronized(encounterCountLock){
            long now=android.os.SystemClock.elapsedRealtime();
            if(encounterCountAt<0||now-encounterCountAt>=30000){
                encounterCount=encounterCatalog().size();encounterCountAt=now;
            }
            return encounterCount;
        }
    }
    void invalidateEncounterPublicationCount(){synchronized(encounterCountLock){encounterCountAt=-1;}}
    int offeredCount()throws Exception{return graphCatalog().size();}
    // Keep batches within the existing framed TLS transport, including JSON
    // escaping for large raw fragments.
    private static int appendWithinFrame(JSONArray entries,MeshItem item,int bytes)throws Exception{
        JSONObject json=item.json();
        int size=json.toString().getBytes(StandardCharsets.UTF_8).length+1;
        if(bytes+size>MAX_FRAME-4096)return -1;
        entries.put(json);return bytes+size;
    }
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
    private void recordIncomingFailure(Server server,Check check){
        if(active!=server)return;
        incomingTlsFailure=check;incomingTlsLast=check;
    }
    private void handle(Server server,SSLSocket socket){
        boolean handshaken=false;long handshakeStarted=android.os.SystemClock.elapsedRealtime();
        android.os.PowerManager.WakeLock requestLock=null;
        try(SSLSocket client=socket){
            if(active!=server||!MeshServingService.enabled(app))return;
            android.os.PowerManager power=app.getSystemService(android.os.PowerManager.class);
            if(power!=null){
                requestLock=power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"Roots:MeshRequest");
                requestLock.acquire(30000L);
            }
            client.setSoTimeout(8000);client.startHandshake();
            handshaken=true;
            if(active==server)incomingTlsLast=new Check("Last incoming TLS",PASS,
                "Incoming TLS handshake completed using "+client.getSession().getProtocol()+".","",
                android.os.SystemClock.elapsedRealtime()-handshakeStarted);
            JSONObject request=read(client);
            if(active!=server||!MeshServingService.enabled(app))return;
            if(request.optInt("version")!=1){reject(server,client,"unsupported_version");return;}
            String member=request.optString("member");
            if(!MeshNetworkAccess.validHex(member)){reject(server,client,"member_required");return;}
            String grant=MeshNetworkAccess.member(server.networkRoot,server.pin,member);
            String offered=request.optString("token");
            boolean enroll=MeshNetworkAccess.equal(server.token,offered);
            if(!enroll&&!MeshNetworkAccess.equal(grant,offered)){reject(server,client,"unauthorized");return;}
            // Prove possession of the member's identity over the already
            // pinned TLS channel. Never send the network root itself.
            String nonce=MeshNetworkAccess.nonce(random);
            write(client,new JSONObject().put("version",1).put("op","member_challenge").put("nonce",nonce));
            JSONObject proof=read(client);
            if(active!=server||!MeshServingService.enabled(app))return;
            if(proof.optInt("version")!=1||!"member_proof".equals(proof.optString("op"))||
                !MeshNetworkAccess.verifyProof(proof.optString("certificate"),proof.optString("proof"),server.pin,member,nonce)){
                reject(server,client,"member_proof_failed");return;
            }
            boolean persistent=request.optBoolean("session",false);
            boolean first=true;
            while(true){
            if(active!=server||!MeshServingService.enabled(app)||!server.network.equals(wifi())||!sameSubnet(server.network,client.getInetAddress()))return;
            if(request.optInt("version")!=1){reject(server,client,"unsupported_version");return;}
            String op=request.optString("op");
            if(!op.equals("inventory")&&!op.equals("want")&&!op.equals("want_batch")&&!op.equals("need")){reject(server,client,"unsupported_operation");return;}
            boolean graph=request.optBoolean("graph",false);
            if(op.equals("want_batch")&&!graph){reject(server,client,"unsupported_operation");return;}
            long epoch=tree.captureEpoch();
            Map<String,MeshItem> catalog=graph?graphCatalog():legacyCatalog();
            JSONObject response=new JSONObject().put("version",1);
            if(first&&enroll)response.put("member_token",grant);
            if(first&&persistent)response.put("session",true);
            if(op.equals("inventory")){
                String after=request.optString("after");if(!after.isEmpty()&&!after.matches("[0-9a-f]{64}")){reject(server,client,"invalid_request");return;}
                JSONArray entries=new JSONArray();String next="";
                for(MeshItem item:catalog.values()){
                    if(item.id().compareTo(after)<=0)continue;
                    if(entries.length()>=MAX_INVENTORY){next=entries.getJSONObject(entries.length()-1).getString("id");break;}
                    entries.put(new JSONObject().put("id",item.id()).put("language",item.language())
                        .put("topic",item.topic()).put("demand",Math.min(1000,requested.getOrDefault(item.id(),0))));
                }
                response.put("items",entries).put("next",next).put("graph",graph).put("total",catalog.size());
            }else if(op.equals("want_batch")){
                JSONArray ids=request.getJSONArray("ids");
                if(ids.length()<1||ids.length()>MAX_FETCH){reject(server,client,"invalid_request");return;}
                Set<String> unique=new HashSet<>();JSONArray delivered=new JSONArray();
                int processed=0,bytes=0;
                for(int i=0;i<ids.length();i++){
                    String id=ids.getString(i);
                    if(!id.matches("[0-9a-f]{64}")||!unique.add(id)){reject(server,client,"invalid_request");return;}
                    MeshItem item=catalog.get(id);
                    if(item!=null){
                        int size=appendWithinFrame(delivered,item,bytes);
                        if(size<0){if(processed==0){reject(server,client,"invalid_request");return;}break;}
                        bytes=size;
                        if(requested.size()>4096)requested.clear();
                        requested.merge(id,1,(a,b)->Math.min(1000,a+b));
                    }
                    processed++;
                }
                response.put("items",delivered).put("processed",processed);
            }else if(op.equals("need")){
                // One bounded request/response, no arbitrary search query, file access or computation.
                String id=request.optString("request_id"),topic=request.optString("topic"),language=request.optString("language");
                int limit=request.optInt("limit",0);
                if(!id.matches("[0-9a-f-]{36}")||!RuntimeNeed.validTopic(topic)||!language.matches("[a-z]{2,3}")||limit<1||limit>3){reject(server,client,"invalid_request");return;}
                List<MeshItem> candidates=new ArrayList<>();
                for(MeshItem item:catalog.values())if(item.matchesTopic(topic))candidates.add(item);
                candidates.sort(Comparator.comparingInt((MeshItem item)->item.language().equals(language)?0:1)
                    .thenComparingInt(MeshItem::retrievalRank)
                    .thenComparing(Comparator.comparingInt((MeshItem item)->requested.getOrDefault(item.id(),0)).reversed()).thenComparing(MeshItem::id));
                JSONArray delivered=new JSONArray();int bytes=0;
                for(int i=0;i<Math.min(limit,candidates.size());i++){
                    int size=appendWithinFrame(delivered,candidates.get(i),bytes);if(size<0)break;bytes=size;
                }
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
            if(active!=server||!MeshServingService.enabled(app)||tree.captureEpoch()!=epoch)return;
            write(client,response);
            if(!persistent)return;
            first=false;
            // A transfer can keep the socket open without keeping the CPU
            // awake between requests. The idle timeout releases unused slots.
            if(requestLock!=null&&requestLock.isHeld())requestLock.release();
            client.setSoTimeout(SESSION_IDLE_MS);
            request=read(client);
            if(requestLock!=null)requestLock.acquire(30000L);
            }
        }catch(Exception error){
            if(!handshaken&&active==server){
                Check failed=failure("TLS",error,android.os.SystemClock.elapsedRealtime()-handshakeStarted);
                recordIncomingFailure(server,new Check("Last incoming TLS",FAIL,failed.detail(),failed.nextStep(),failed.elapsedMs()));
            }
            // No request, content, invitation or arbitrary provider message is logged.
        }
        finally{
            if(requestLock!=null&&requestLock.isHeld())try{requestLock.release();}catch(RuntimeException ignored){}
            server.clients.remove(socket);
        }
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
        return exchange(network,invite,request,deadline,suppliedTrace,null);
    }
    private JSONObject exchange(Network network,Invite invite,JSONObject request,long deadline,Trace suppliedTrace,TransferSessions sessions)throws Exception{
        Trace trace=suppliedTrace==null?trace(invite,null):suppliedTrace;
        if(sessions!=null){
            sessions.check(invite);
            SSLSocket existing=sessions.sockets.get(invite);
            if(existing!=null){
                try{return sessions.request(invite,existing,request,trace);}
                catch(Failure rejected){sessions.drop(invite);throw rejected;}
                catch(IOException broken){
                    sessions.drop(invite);
                    sessions.check(invite);
                    trace.add(check("Session recovery",WARN,"Connection interrupted; reconnecting to the same pinned peer.",""));
                }
            }
            request.put("session",true);
        }
        long stopAt=deadline==0?android.os.SystemClock.elapsedRealtime()+(sessions==null?8000L:TRANSFER_TIMEOUT_MS):deadline;
        try{return exchangeOnce(network,invite,request,stopAt,trace,false,sessions);}
        catch(RetryTls12 retry){
            trace.add(check("TLS compatibility",WARN,"Initial TLS negotiation failed: "+retry.first.detail()+" Retrying with TLS 1.2 and the same certificate pin.",""));
            return exchangeOnce(network,invite,request,stopAt,trace,true,sessions);
        }
    }
    private static int exchangeRemaining(long stopAt,TransferSessions sessions){
        long now=android.os.SystemClock.elapsedRealtime();
        return sessions==null?RuntimeNeed.remaining(stopAt,now):(int)Math.max(0,Math.min(TRANSFER_TIMEOUT_MS,stopAt-now));
    }
    private JSONObject exchangeOnce(Network network,Invite invite,JSONObject request,long stopAt,Trace trace,boolean tls12Only,TransferSessions sessions)throws Exception{
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
            Socket plain=network.getSocketFactory().createSocket();
            boolean retained=false;
            try{
                int remaining=exchangeRemaining(stopAt,sessions);
                if(remaining<=0)throw new java.net.SocketTimeoutException();
                java.util.concurrent.ScheduledFuture<?> expiry=DEADLINES.schedule(()->{try{plain.close();}catch(IOException ignored){}},remaining,TimeUnit.MILLISECONDS);
                try{
                    plain.connect(new InetSocketAddress(destination,PORT),Math.min(3500,remaining));plain.setSoTimeout(remaining);
                    trace.pass("TCP","Connected to the invited peer on TCP port "+PORT+".",android.os.SystemClock.elapsedRealtime());
                    trace.begin("TLS",android.os.SystemClock.elapsedRealtime());
                    pin=new MeshTlsPin(invite.pin());
                    SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,new javax.net.ssl.TrustManager[]{pin},random);
                    SSLSocket socket=(SSLSocket)tls.getSocketFactory().createSocket(plain,invite.host(),PORT,true);
                    try{
                        remaining=exchangeRemaining(stopAt,sessions);if(remaining<=0)throw new java.net.SocketTimeoutException();
                        socket.setUseClientMode(true);
                        socket.setEnabledProtocols(tls12Only?new String[]{"TLSv1.2"}:protocols(socket.getSupportedProtocols()));
                        socket.setSoTimeout(remaining);socket.startHandshake();
                        // Verify the negotiated session too, before any invitation token is sent.
                        pin.verifySession(socket.getSession());trace.add(pin.observation());
                        String protocol=socket.getSession().getProtocol();
                        trace.pass("TLS","Pinned certificate and certificate dates verified; "+(protocol.equals("TLSv1.3")?"TLS 1.3":"TLS 1.2")+" established.",android.os.SystemClock.elapsedRealtime());
                        trace.begin("Authentication",android.os.SystemClock.elapsedRealtime());
                        MeshTlsIdentity.Identity own=MeshTlsIdentity.loadOrCreate(app);
                        String member=MeshTlsIdentity.fingerprint(own);
                        DerivedGrant cached=derivedGrants.get(invite.pin());
                        String offered=cached!=null&&MeshNetworkAccess.equal(cached.original,invite.token())?cached.derived:invite.token();
                        request.put("version",1).put("token",offered).put("member",member).put("graph",true);write(socket,request);
                        JSONObject response=read(socket);
                        boolean memberVerified=false;
                        if(response.optInt("version")==1&&"member_challenge".equals(response.optString("op"))){
                            String nonce=response.getString("nonce");
                            if(!MeshNetworkAccess.validHex(nonce))throw new Failure("Authentication","The peer sent an invalid member challenge.","Update both Roots installations.");
                            String signature=MeshNetworkAccess.proof(own.key(),invite.pin(),member,nonce);
                            String certificate=java.util.Base64.getEncoder().encodeToString(own.certificate().getEncoded());
                            write(socket,new JSONObject().put("version",1).put("op","member_proof")
                                .put("certificate",certificate).put("proof",signature));
                            response=read(socket);memberVerified=true;
                        }
                        if(response.optInt("version")!=1)throw new Failure("Protocol","The peer returned an unsupported Mesh protocol version.","Update both Roots installations to compatible versions.");
                        trace.pass("Protocol","Mesh protocol version 1 accepted.",android.os.SystemClock.elapsedRealtime());
                        if(response.has("error"))throw protocolFailure(response.optString("error"));
                        String grantToSave=null;
                        if(response.has("member_token")){
                            String grant=response.getString("member_token");
                            if(!memberVerified||!MeshNetworkAccess.validHex(grant))
                                throw new Failure("Authentication","The peer returned an invalid member grant.","Update both Roots installations.");
                            if(derivedGrants.size()>=64)derivedGrants.clear();
                            derivedGrants.put(invite.pin(),new DerivedGrant(invite.token(),grant));
                            grantToSave=grant;
                            response.remove("member_token");
                        }else if(memberVerified&&!offered.equals(invite.token())){
                            // The current receive loop may still carry the
                            // original invitation, or a previous save failed.
                            grantToSave=offered;
                        }
                        if(grantToSave!=null){
                            try{
                                if(AxonPeerStore.refreshDerivedGrant(app,invite,grantToSave))
                                    trace.add(check("Member grant",PASS,"Stable member-specific access saved.",""));
                            }catch(IOException e){
                                trace.add(check("Member grant",WARN,"Member access received but could not be saved.","Retry the connection check."));
                            }
                        }
                        if(exchangeRemaining(stopAt,sessions)<=0)throw new java.net.SocketTimeoutException();
                        trace.pass("Authentication",memberVerified?"Member identity and derived network access accepted.":"The peer accepted the invitation and returned a response.",android.os.SystemClock.elapsedRealtime());
                        if(sessions!=null&&memberVerified&&response.optBoolean("session",false)){
                            sessions.check(invite);
                            sessions.sockets.put(invite,socket);retained=true;
                            trace.add(check("Transfer session",PASS,"Authenticated session reused across inventory and graph batches.",""));
                        }
                        return response;
                    }finally{if(!retained)try{socket.close();}catch(IOException ignored){}}
                }finally{expiry.cancel(false);}
            }finally{if(!retained)try{plain.close();}catch(IOException ignored){}}
        }catch(Exception e){
            Exception failure=e;
            if(!(e instanceof Failure)&&exchangeRemaining(stopAt,sessions)<=0)failure=new java.net.SocketTimeoutException();
            if("TLS".equals(trace.currentStage())&&pin!=null){
                trace.add(pin.observation());
                if(pin.rejected())failure=new Failure("TLS",pin.observation().detail(),pin.observation().nextStep());
                else if(!tls12Only&&MeshConnectionDiagnostics.canRetryTls12(failure)&&exchangeRemaining(stopAt,sessions)>=1000)
                    throw new RetryTls12(MeshConnectionDiagnostics.failure("TLS",failure,0));
            }
            trace.fail(failure,android.os.SystemClock.elapsedRealtime());
            Check safe=MeshConnectionDiagnostics.failure(trace.currentStage(),failure,0);
            throw failure instanceof Failure?failure:new Failure(safe.stage(),safe.detail(),safe.nextStep());
        }
    }
    private final class TransferSessions implements AutoCloseable {
        final Network network;
        final AxonSubscription binding;
        final long epoch;
        final Map<Invite,SSLSocket> sockets=new HashMap<>();
        TransferSessions(Network network,AxonSubscription binding,long epoch){this.network=network;this.binding=binding;this.epoch=epoch;}
        void check(Invite invite)throws Exception{
            if(Thread.currentThread().isInterrupted()||tree.captureEpoch()!=epoch||!network.equals(wifi()))
                throw new IOException("Transfer interrupted by local Tree or network change.");
            if(!sameSubnet(network,InetAddress.getByName(invite.host())))throw new Failure("Subnet","The peer is no longer on this Wi-Fi subnet.","Reconnect to the peer's Wi-Fi.");
            if(binding!=null){
                AxonSubscription current=AxonSubscription.load(app);
                if(current==null||!current.pins.equals(binding.pins)||!current.pins.contains(invite.pin()))
                    throw new Failure("Axon binding","The saved binding changed during transfer.","Restart Receive with the current binding.");
            }
        }
        JSONObject request(Invite invite,SSLSocket socket,JSONObject request,Trace trace)throws Exception{
            check(invite);
            trace.begin("Transfer session",android.os.SystemClock.elapsedRealtime());
            socket.setSoTimeout(TRANSFER_TIMEOUT_MS);
            java.util.concurrent.ScheduledFuture<?> expiry=DEADLINES.schedule(()->{
                try{socket.close();}catch(IOException ignored){}
            },TRANSFER_TIMEOUT_MS,TimeUnit.MILLISECONDS);
            try{
                request.put("version",1).put("graph",true);
                request.remove("token");request.remove("member");request.remove("session");
                write(socket,request);
                JSONObject response=read(socket);
                if(response.optInt("version")!=1)throw new Failure("Protocol","Unsupported Mesh protocol version.","Update both Roots installations.");
                if(response.has("error"))throw protocolFailure(response.optString("error"));
                check(invite);
                trace.pass("Transfer session","Reused authenticated peer connection.",android.os.SystemClock.elapsedRealtime());
                return response;
            }finally{expiry.cancel(false);}
        }
        void drop(Invite peer){SSLSocket socket=sockets.remove(peer);if(socket!=null)try{socket.close();}catch(IOException ignored){}}
        @Override public void close(){for(Invite peer:new ArrayList<>(sockets.keySet()))drop(peer);}
    }

    record InventoryPage(JSONArray items,String next,boolean graph,int total){
        InventoryPage(JSONArray items,String next){this(items,next,false,items.length());}
    }
    private InventoryPage inventory(Network network,Invite invite,String after,long deadline,Trace trace)throws Exception{
        return inventory(network,invite,after,deadline,trace,null);
    }
    private InventoryPage inventory(Network network,Invite invite,String after,long deadline,Trace trace,TransferSessions sessions)throws Exception{
        JSONObject response=exchange(network,invite,new JSONObject().put("op","inventory").put("after",after),deadline,trace,sessions);
        trace.begin("Inventory",android.os.SystemClock.elapsedRealtime());
        try{
            InventoryPage page=validateInventory(response,after);
            String label=page.graph()?"graph items":"legacy published items";
            trace.pass("Inventory","Connected · "+page.total()+" "+label+" available."
                +(page.next().isEmpty()?"":" "+page.items().length()+" on this page."),android.os.SystemClock.elapsedRealtime());
            return page;
        }catch(Exception e){trace.fail(e,android.os.SystemClock.elapsedRealtime());throw new Failure("Inventory","The peer inventory was malformed or incompatible.","Update both Roots installations and test again.");}
    }
    /** Shared by receive and diagnostics: validation must have the same meaning. */
    static InventoryPage validateInventory(JSONObject response,String after)throws Exception{
        JSONArray rows=response.getJSONArray("items");if(rows.length()>MAX_INVENTORY)throw new IOException("Oversized inventory.");
        Set<String> seen=new HashSet<>();String previous=after;
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);String id=row.getString("id"),language=row.getString("language"),topic=row.getString("topic");
            if(!id.matches("[0-9a-f]{64}")||!language.matches("[a-z]{2,3}")||topic.isEmpty()||topic.length()>120||!seen.add(id)||id.compareTo(previous)<=0)throw new IOException("Invalid inventory entry.");
            previous=id;
        }
        String next=response.optString("next");
        if(!next.isEmpty()&&(!next.equals(previous)||rows.length()==0))throw new IOException("Invalid inventory cursor.");
        boolean graph=response.optBoolean("graph",false);int total=response.optInt("total",rows.length());
        if(total<rows.length())throw new IOException("Invalid inventory count");
        return new InventoryPage(rows,next,graph,total);
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
            if(!item.matchesTopic(topic))throw new IOException("Unexpected topic");
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
    private static MeshItem verifyOffer(JSONObject json,Offer offer)throws Exception{
        MeshItem item=MeshItem.parse(json);
        if(!item.id().equals(offer.id)||!item.topic().equals(offer.topic)
                ||(item.graph()==null&&!item.language().equals(offer.language)))
            throw new IOException("Inventory does not match verified content.");
        return item;
    }
    private List<MeshItem> fetchGraphBatch(Network network,Invite peer,List<Offer> offers,Trace trace,TransferSessions sessions)throws Exception{
        List<MeshItem> delivered=new ArrayList<>();
        int offset=0;
        while(offset<offers.size()){
            JSONArray ids=new JSONArray();
            for(int i=offset;i<offers.size();i++)ids.put(offers.get(i).id);
            JSONObject answer=exchange(network,peer,new JSONObject().put("op","want_batch").put("ids",ids),0,trace,sessions);
            trace.begin("Receive",android.os.SystemClock.elapsedRealtime());
            int processed=answer.getInt("processed");
            if(processed<1||processed>offers.size()-offset)throw new IOException("Invalid graph batch cursor");
            Map<String,Offer> expected=new HashMap<>();
            for(int i=offset;i<offset+processed;i++)expected.put(offers.get(i).id,offers.get(i));
            JSONArray rows=answer.getJSONArray("items");
            if(rows.length()>processed)throw new IOException("Oversized graph batch");
            for(int i=0;i<rows.length();i++){
                JSONObject json=rows.getJSONObject(i);
                Offer offer=expected.remove(json.getString("id"));
                if(offer==null)throw new IOException("Unexpected or duplicate graph item");
                MeshItem item=verifyOffer(json,offer);
                if(item.graph()==null)throw new IOException("Expected graph item");
                delivered.add(item);
            }
            offset+=processed;
            trace.pass("Receive","Graph batch verified.",android.os.SystemClock.elapsedRealtime());
        }
        return delivered;
    }
    private int storeReceived(List<MeshItem> items,Map<String,Offer> pending,long epoch,Trace trace)throws Exception{
        List<NativeTree.Observation> observations=new ArrayList<>();
        List<String> observationIds=new ArrayList<>();
        int received=0;
        for(MeshItem item:items){
            MeshItem.Graph graph=item.graph();
            if(graph!=null&&graph.kind().equals("fragment")){
                observations.add(new NativeTree.Observation(graph.source(),graph.key(),item.text(),graph.language(),graph.at()));
                observationIds.add(item.id());
            }
        }
        if(!observations.isEmpty()){
            received+=(int)tree.storeExplicit(observations,epoch);
            for(String id:observationIds)pending.remove(id);
        }
        for(MeshItem item:items){
            if(item.graph()!=null&&item.graph().kind().equals("fragment"))continue;
            try{
                if(item.store(tree,epoch)>0)received++;
                pending.remove(item.id());
            }catch(Exception e){trace.fail(e,android.os.SystemClock.elapsedRealtime());}
        }
        return received;
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
        try(TransferSessions sessions=new TransferSessions(network,axon,epoch)){
        Map<String,Offer> offers=new HashMap<>();Set<Invite> graphPeers=new HashSet<>();int peers=0,failed=0;
        // Read all inventory pages. Receiver preferences affect order, not eligibility.
        for(int peerIndex=0;peerIndex<invites.size();peerIndex++){
            Invite invite=invites.get(peerIndex);Trace trace=traces.get(peerIndex);
            progress.accept("Reading peer inventory "+(peerIndex+1)+" of "+invites.size()+"…");
            try{
                Set<String> seen=new HashSet<>();String after="";
                for(int page=0;;page++){
                    if(Thread.currentThread().isInterrupted()||!network.equals(wifi())||tree.captureEpoch()!=epoch)throw new IOException("Receive interrupted");
                    InventoryPage inventoryPage=inventory(network,invite,after,0,trace,sessions);
                    if(inventoryPage.graph())graphPeers.add(invite);
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
            Set<String> known=new HashSet<>(graphCatalog().keySet());
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
                List<String> ids=AxonPlanner.order(candidates,allocation.shares(),want.size());
                // Even when no named lane matches, every missing item is wanted.
                Map<String,Offer> remaining=new LinkedHashMap<>();
                for(Offer o:want)remaining.put(o.id,o);
                want=new ArrayList<>();
                for(String id:ids){Offer item=remaining.remove(id);if(item!=null)want.add(item);}
                want.addAll(remaining.values());
                progress.accept(axon.describe(allocation));
            }
            int received=0;
            Set<Invite> unavailable=new HashSet<>();
            for(int offset=0;offset<want.size();offset+=MAX_FETCH){
                if(Thread.currentThread().isInterrupted()||!network.equals(wifi())||tree.captureEpoch()!=epoch){
                    failed+=want.size()-offset;break;
                }
                Map<String,Offer> pending=new LinkedHashMap<>();
                for(int i=offset;i<Math.min(offset+MAX_FETCH,want.size());i++){
                    Offer offer=want.get(i);pending.put(offer.id,offer);
                }
                progress.accept("Receiving graph · "+received+" / "+wanted);
                for(Invite source:invites){
                    if(pending.isEmpty()||unavailable.contains(source))continue;
                    if(Thread.currentThread().isInterrupted()||!network.equals(wifi())||tree.captureEpoch()!=epoch)break;
                    List<Offer> batch=new ArrayList<>();
                    for(Offer offer:pending.values())if(offer.sources.contains(source))batch.add(offer);
                    if(batch.isEmpty())continue;
                    Trace trace=traces.get(invites.indexOf(source));
                    if(graphPeers.contains(source)){
                        List<MeshItem> items;
                        try{items=fetchGraphBatch(network,source,batch,trace,sessions);}
                        catch(Exception e){
                            if(trace.currentStage().equals("Receive"))trace.fail(e,android.os.SystemClock.elapsedRealtime());
                            unavailable.add(source);continue;
                        }
                        trace.begin("Receive",android.os.SystemClock.elapsedRealtime());
                        try{
                            received+=storeReceived(items,pending,epoch,trace);
                            trace.pass("Receive","Verified graph merged into the local Tree.",android.os.SystemClock.elapsedRealtime());
                        }catch(Exception e){trace.fail(e,android.os.SystemClock.elapsedRealtime());}
                    }else{
                        for(Offer offer:batch){
                            if(Thread.currentThread().isInterrupted()||!network.equals(wifi())||tree.captureEpoch()!=epoch)break;
                            try{
                                JSONObject answer=exchange(network,source,new JSONObject().put("op","want").put("id",offer.id),0,trace,sessions);
                                trace.begin("Receive",android.os.SystemClock.elapsedRealtime());
                                MeshItem item=verifyOffer(answer.getJSONObject("item"),offer);
                                if(item.store(tree,epoch)>0)received++;
                                pending.remove(offer.id);
                                trace.pass("Receive","Verified knowledge merged.",android.os.SystemClock.elapsedRealtime());
                            }catch(Exception e){
                                if(trace.currentStage().equals("Receive"))trace.fail(e,android.os.SystemClock.elapsedRealtime());
                                else {unavailable.add(source);break;}
                            }
                        }
                    }
                }
                failed+=pending.size();
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
}
