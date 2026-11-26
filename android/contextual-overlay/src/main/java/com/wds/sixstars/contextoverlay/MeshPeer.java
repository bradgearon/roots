package com.wds.sixstars.contextoverlay;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
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
import java.security.KeyPairGenerator;
import java.security.KeyStore;
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
import javax.security.auth.x500.X500Principal;

/** Roots Mesh v1: direct Wi-Fi peers, standard TLS, immutable content-addressed
 * items, inventory and explicit wants. No DHT, trackers, raw-tree access or
 * custom message encryption. Invitation authorizes reading published items. */
final class MeshPeer {
    static final int PORT=8233, MAX_INVENTORY=256, MAX_FETCH=32, MAX_FRAME=256*1024;
    private static final String ALIAS="roots.mesh.tls.v1";
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
    private volatile Map<String,Integer> observedReplicas=Map.of(), observedDemand=Map.of();
    private volatile String invitation="";
    private volatile String status="Not serving";
    private volatile Server active;
    private MeshPeer(Context app){this.app=app;tree=TreeRuntime.get(app);cm=(ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE);}
    String invitation(){return invitation;}
    String status(){return status;}
    int replicas(String id){return observedReplicas.getOrDefault(id,0);}
    int demand(String id){return Math.max(requested.getOrDefault(id,0),observedDemand.getOrDefault(id,0));}

    private Network wifi(){
        if(cm==null)return null;
        Network n=cm.getActiveNetwork();NetworkCapabilities c=n==null?null:cm.getNetworkCapabilities(n);
        return c!=null&&c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)?n:null;
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
            Uri uri=Uri.parse(value.trim());
            if(!"roots".equals(uri.getScheme())||!"mesh".equals(uri.getAuthority())||!"/v1".equals(uri.getPath())||uri.getFragment()!=null)
                throw new IllegalArgumentException("Use a roots://mesh/v1 invitation.");
            String host=uri.getQueryParameter("host"),pin=uri.getQueryParameter("pin"),token=uri.getQueryParameter("token"),port=uri.getQueryParameter("port");
            if(host==null||!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")||!"8233".equals(port)||pin==null||!pin.matches("[0-9a-f]{64}")||token==null||!token.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid Roots Mesh invitation.");
            for(String part:host.split("\\."))if(Integer.parseInt(part)>255)throw new IllegalArgumentException("Invalid IPv4 address.");
            return new Invite(host,pin,token);
        }
        String uri(){return new Uri.Builder().scheme("roots").authority("mesh").path("/v1").appendQueryParameter("host",host)
            .appendQueryParameter("port",Integer.toString(PORT)).appendQueryParameter("pin",pin).appendQueryParameter("token",token).build().toString();}
    }
    private record Identity(PrivateKey key,X509Certificate certificate){}
    private Identity identity()throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(ALIAS)){
            KeyPairGenerator generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new java.security.spec.ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA384,KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(new X500Principal("CN=Roots Mesh"))
                .setCertificateSerialNumber(new java.math.BigInteger(128,random).add(java.math.BigInteger.ONE))
                .setCertificateNotBefore(new java.util.Date(System.currentTimeMillis()-86400000L))
                .setCertificateNotAfter(new java.util.Date(System.currentTimeMillis()+5L*365*86400000L))
                .build());
            generator.generateKeyPair();
        }
        X509Certificate certificate=(X509Certificate)store.getCertificate(ALIAS);certificate.checkValidity();
        return new Identity((PrivateKey)store.getKey(ALIAS,null),certificate);
    }
    private static final class PeerKeyManager extends X509ExtendedKeyManager {
        private final Identity identity;
        PeerKeyManager(Identity identity){this.identity=identity;}
        private String alias(String keyType){return keyType!=null&&(keyType.startsWith("EC")||keyType.equalsIgnoreCase("ECDSA"))?ALIAS:null;}
        @Override public String[] getClientAliases(String type,Principal[] issuers){return null;}
        @Override public String chooseClientAlias(String[] types,Principal[] issuers,Socket socket){return null;}
        @Override public String[] getServerAliases(String type,Principal[] issuers){return alias(type)==null?null:new String[]{ALIAS};}
        @Override public String chooseServerAlias(String type,Principal[] issuers,Socket socket){return alias(type);}
        @Override public String chooseEngineServerAlias(String type,Principal[] issuers,javax.net.ssl.SSLEngine engine){return alias(type);}
        @Override public X509Certificate[] getCertificateChain(String alias){return ALIAS.equals(alias)?new X509Certificate[]{identity.certificate()}:null;}
        @Override public PrivateKey getPrivateKey(String alias){return ALIAS.equals(alias)?identity.key():null;}
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
        Identity identity=identity();SSLContext context=SSLContext.getInstance("TLS");
        context.init(new KeyManager[]{new PeerKeyManager(identity)},null,random);
        String pin=MeshItem.hex(MessageDigest.getInstance("SHA-256").digest(identity.certificate().getEncoded()));
        byte[] tokenBytes=new byte[32];random.nextBytes(tokenBytes);String token=MeshItem.hex(tokenBytes);
        SSLServerSocket socket=(SSLServerSocket)context.getServerSocketFactory().createServerSocket();
        try{
            socket.setReuseAddress(true);socket.setEnabledProtocols(protocols(socket.getSupportedProtocols()));
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
        Map<String,MeshItem> items=new LinkedHashMap<>();
        // Explicit offer records only. No raw observations, original post bodies,
        // private branch terms, GGUF, or SQLite are ever served.
        for(NativeTree.Master record:tree.allRecords("",10000)){
            MeshItem item=MeshItem.from(record);if(item!=null)items.putIfAbsent(item.id(),item);
            if(items.size()>=MAX_INVENTORY)break;
        }
        return items;
    }
    int offeredCount(){return catalog().size();}
    private static JSONObject read(Socket socket)throws Exception{
        DataInputStream in=new DataInputStream(socket.getInputStream());
        int size=in.readInt();if(size<2||size>MAX_FRAME)throw new IOException("Invalid mesh frame.");
        byte[] bytes=new byte[size];in.readFully(bytes);
        return new JSONObject(new String(bytes,StandardCharsets.UTF_8));
    }
    private static void write(Socket socket,JSONObject json)throws Exception{
        byte[] bytes=json.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_FRAME)throw new IOException("Mesh response too large.");
        DataOutputStream out=new DataOutputStream(socket.getOutputStream());out.writeInt(bytes.length);out.write(bytes);out.flush();
    }
    private void handle(Server server,SSLSocket socket){
        try(SSLSocket client=socket){
            client.setSoTimeout(8000);client.startHandshake();
            JSONObject request=read(client);
            if(active!=server||request.optInt("version")!=1||!MessageDigest.isEqual(server.token.getBytes(StandardCharsets.US_ASCII),request.optString("token").getBytes(StandardCharsets.US_ASCII)))return;
            String op=request.optString("op");
            if(!op.equals("inventory")&&!op.equals("want"))return;
            Map<String,MeshItem> catalog=catalog();
            JSONObject response=new JSONObject().put("version",1);
            if(op.equals("inventory")){
                JSONArray entries=new JSONArray();
                for(MeshItem item:catalog.values())entries.put(new JSONObject().put("id",item.id()).put("language",item.language())
                    .put("topic",item.topic()).put("demand",Math.min(1000,requested.getOrDefault(item.id(),0))));
                response.put("items",entries);
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
        }catch(Exception ignored){/* No content or invitation in logs. */}
        finally{server.clients.remove(socket);}
    }
    private JSONObject exchange(Network network,Invite invite,JSONObject request)throws Exception{
        InetAddress destination=InetAddress.getByName(invite.host());
        if(!network.equals(wifi())||!sameSubnet(network,destination))throw new IOException("Peer is not on the current Wi-Fi subnet.");
        DesktopTlsPin pin=new DesktopTlsPin(invite.pin());
        SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,new javax.net.ssl.TrustManager[]{pin},random);
        try(Socket plain=network.getSocketFactory().createSocket()){
            plain.connect(new InetSocketAddress(destination,PORT),3500);plain.setSoTimeout(8000);
            try(SSLSocket socket=(SSLSocket)tls.getSocketFactory().createSocket(plain,invite.host(),PORT,true)){
                socket.setEnabledProtocols(protocols(socket.getSupportedProtocols()));socket.setSoTimeout(8000);socket.startHandshake();
                request.put("version",1).put("token",invite.token());write(socket,request);
                JSONObject response=read(socket);
                if(response.optInt("version")!=1)throw new IOException("Unsupported mesh protocol.");
                return response;
            }
        }
    }
    private static final class Offer {
        final String id,language,topic;
        final List<Invite> sources=new ArrayList<>();
        int demand,interest;
        Offer(String id,String language,String topic){this.id=id;this.language=language;this.topic=topic;}
    }
    record SyncResult(int peers,int wanted,int received,int failed){}
    SyncResult sync(List<Invite> invites,String interests,String language,java.util.function.Consumer<String> progress)throws Exception{
        if(invites.isEmpty()||invites.size()>8)throw new IllegalArgumentException("Enter one to eight peer invitations.");
        Network network=wifi();if(network==null)throw new IOException("Connect to Wi-Fi first.");
        Map<String,Offer> offers=new HashMap<>();int peers=0,failed=0;
        Set<String> visited=new HashSet<>();
        // The inventory carries approved topic labels, never the receiver's private interests.
        for(Invite invite:invites){
            if(!visited.add(invite.host()))continue;
            progress.accept("Reading peer inventory "+(peers+failed+1)+"…");
            try{
                JSONArray inventory=exchange(network,invite,new JSONObject().put("op","inventory")).getJSONArray("items");
                if(inventory.length()>MAX_INVENTORY)throw new IOException("Oversized inventory.");
                peers++;
                Set<String> seen=new HashSet<>();
                for(int i=0;i<inventory.length();i++){
                    JSONObject entry=inventory.getJSONObject(i);String id=entry.getString("id"),lang=entry.getString("language"),topic=entry.getString("topic");
                    if(!id.matches("[0-9a-f]{64}")||!lang.matches("[a-z]{2,3}")||topic.isEmpty()||topic.length()>120||!seen.add(id))continue;
                    Offer offer=offers.computeIfAbsent(id,k->new Offer(k,lang,topic));
                    offer.sources.add(invite);offer.demand=Math.max(offer.demand,Math.max(0,Math.min(1000,entry.optInt("demand"))));
                }
            }catch(Exception e){failed++;}
        }
        if(peers==0)throw new IOException("No peer inventory was reachable. Check invitations and Wi-Fi.");
        Map<String,Integer> replicas=new HashMap<>(),demand=new HashMap<>();
        for(Offer o:offers.values()){replicas.put(o.id,o.sources.size());demand.put(o.id,o.demand);}
        observedReplicas=Map.copyOf(replicas);observedDemand=Map.copyOf(demand);
        Set<String> known=new HashSet<>();
        for(NativeTree.Master record:tree.allRecords("",10000))if(record.key().startsWith("local:mesh:"))known.add(record.key().substring(11));
        List<String> terms=new ArrayList<>();
        for(NativeTree.Agent agent:tree.agents())if(!agent.term().isBlank())terms.add(agent.term().toLowerCase(Locale.ROOT));
        for(String word:interests.toLowerCase(Locale.ROOT).split("[,;\\n]"))if(!word.isBlank())terms.add(word.trim());
        List<Offer> want=new ArrayList<>();
        for(Offer o:offers.values())if(!known.contains(o.id)){
            for(String term:terms)if(o.topic.toLowerCase(Locale.ROOT).contains(term))o.interest++;
            want.add(o);
        }
        // Want locally relevant material first, then rarest among the offered
        // items, then observed demand and local-language preference.
        want.sort(Comparator.comparingInt((Offer o)->o.interest).reversed()
            .thenComparingInt(o->o.sources.size()).thenComparing(Comparator.comparingInt((Offer o)->o.demand).reversed())
            .thenComparingInt(o->o.language.equals(language)?0:1).thenComparing(o->o.id));
        int received=0,attempted=0;
        for(Offer o:want){
            if(attempted++>=MAX_FETCH)break;
            if(!network.equals(wifi()))break;
            progress.accept("Receiving "+(received+1)+" · "+o.sources.size()+" available peer"+(o.sources.size()==1?"":"s"));
            boolean ok=false;
            for(Invite source:o.sources){
                try{
                    JSONObject answer=exchange(network,source,new JSONObject().put("op","want").put("id",o.id));
                    MeshItem item=MeshItem.parse(answer.getJSONObject("item"));
                    if(!item.id().equals(o.id))throw new IOException("Unexpected content ID.");
                    item.store(tree);received++;ok=true;break;
                }catch(Exception e){/* Try another advertised replica. */}
            }
            if(!ok)failed++;
        }
        return new SyncResult(peers,want.size(),received,failed);
    }
}
