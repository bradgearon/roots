using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;
using Wds.Roots.Tree;

namespace Wds.Roots.Host;

/// <summary>Roots Mesh v1 on TCP 8233. No HTTP, plaintext, or model endpoint.
/// Authenticated sessions exchange the same graph fragments as Android.</summary>
internal sealed class DesktopMeshService : IAsyncDisposable
{
    const int Port=8233, FrameLimit=256*1024, PageSize=256, BatchSize=32;
    static readonly JsonSerializerOptions WireOptions=new(JsonSerializerDefaults.Web){Encoder=JavaScriptEncoder.UnsafeRelaxedJsonEscaping};
    readonly DesktopTreeService _tree;
    readonly DesktopMeshIdentity _identity;
    readonly string _directory;
    readonly bool _lan;
    readonly object _gate=new();
    readonly SemaphoreSlim _receive=new(1,1);
    readonly ConcurrentDictionary<string,int> _demand=new(StringComparer.Ordinal);
    readonly CancellationTokenSource _lifetime=new();
    CancellationTokenSource? _serving;
    TcpListener? _listener;
    Task? _accept;
    List<MeshInvitation> _peers;
    bool _enabled;
    string _status="Sharing off";
    public DesktopMeshService(DesktopTreeService tree,string directory,bool lan)
    {
        _tree=tree;_directory=directory;_lan=lan;
        _identity=new DesktopMeshIdentity(directory);
        string file=Path.Combine(directory,"peers.protected");
        _peers=File.Exists(file)?JsonSerializer.Deserialize<List<MeshInvitation>>(_identity.ReadProtected(file))??[]:[];
        if(_peers.Count>8||_peers.Any(p=>!MeshGraphItem.IsHash(p.Pin)||!MeshGraphItem.IsHash(p.Token)))
            throw new InvalidDataException("Invalid saved Axon peers.");
        _enabled=File.Exists(Path.Combine(directory,"serving.enabled"));
        if(_enabled&&_lan)Start();
    }
    public object Status()
    {
        lock(_gate)return new { enabled=_enabled,lan=_lan,listening=_listener!=null,status=_status,pin=_identity.Pin,
            peers=_peers.Select(p=>new {host=p.Host,pin=p.Pin}).ToArray() };
    }
    public string[] Invitations()
    {
        lock(_gate)return _listener==null?[]:DesktopMeshNetwork.Hosts().Select(h=>new MeshInvitation(h,_identity.Pin,_identity.Enrollment).Uri).ToArray();
    }
    public void Enable(bool enabled)
    {
        lock(_gate)
        {
            if(enabled&&!_lan)throw new InvalidOperationException("Desktop host is in local-only mode.");
            string path=Path.Combine(_directory,"serving.enabled");
            if(enabled)File.WriteAllText(path,"1");else File.Delete(path);
            _enabled=enabled;
            if(enabled)Start();else Stop();
        }
    }
    void Start()
    {
        if(_listener!=null||!_lan||!_enabled)return;
        var listener=new TcpListener(IPAddress.Any,Port);
        try
        {
            listener.Start(8);
            var stop=CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
            _listener=listener;_serving=stop;_status="Listening";
            _accept=AcceptAsync(listener,stop.Token);
        }
        catch
        {
            listener.Stop();_status="Mesh port unavailable";
            // A failed Mesh bind must not prevent the translation host starting.
        }
    }
    void Stop()
    {
        _serving?.Cancel();_listener?.Stop();_listener=null;_status="Sharing off";
    }
    public void Bind(string invitation)
    {
        var peer=MeshInvitation.Parse(invitation);
        if(peer.Pin==_identity.Pin)throw new ArgumentException("Cannot bind this device to itself.");
        lock(_gate)
        {
            var updated=_peers.Where(p=>p.Pin!=peer.Pin).Append(peer).ToList();
            if(updated.Count>8)throw new ArgumentException("At most eight bound peers.");
            SavePeers(updated);_peers=updated;
        }
    }
    public void Unbind(string pin)
    {
        lock(_gate)
        {
            var updated=_peers.Where(p=>p.Pin!=pin).ToList();
            SavePeers(updated);_peers=updated;
        }
    }
    void SavePeers(List<MeshInvitation> peers)
    {
        _identity.SaveProtected(Path.Combine(_directory,"peers.protected"),JsonSerializer.Serialize(peers));
        // Bulk wildcard subscription; credential material stays separate.
        string path=Path.Combine(_directory,"subscription.axon");
        string temp=path+".tmp";
        File.WriteAllText(temp,JsonSerializer.Serialize(new {type="roots.axon",version=1,
            peers=peers.Select(p=>p.Pin).Order(StringComparer.Ordinal),lanes="*",scope="bulk"}));
        File.Move(temp,path,true);
    }
    void SaveGrant(MeshInvitation original,string grant)
    {
        if(!MeshGraphItem.IsHash(grant))throw new InvalidDataException("Invalid member grant.");
        lock(_gate)
        {
            if(!_peers.Contains(original))throw new IOException("Axon binding changed.");
            var updated=_peers.Select(p=>p==original?p with {Token=grant}:p).ToList();
            SavePeers(updated);_peers=updated;
        }
    }
    bool Bound(string pin) { lock(_gate)return _peers.Any(p=>p.Pin==pin); }
    static async Task<JsonObject> ReadAsync(Stream stream,CancellationToken ct)
    {
        byte[] header=new byte[4];await stream.ReadExactlyAsync(header,ct);
        int count=BinaryPrimitives.ReadInt32BigEndian(header);
        if(count is <2 or >FrameLimit)throw new InvalidDataException("Invalid Mesh frame.");
        byte[] bytes=new byte[count];await stream.ReadExactlyAsync(bytes,ct);
        return JsonNode.Parse(bytes,documentOptions:new JsonDocumentOptions{MaxDepth=32}) as JsonObject
            ??throw new InvalidDataException("Invalid Mesh message.");
    }
    static async Task WriteAsync(Stream stream,JsonObject value,CancellationToken ct)
    {
        byte[] bytes=JsonSerializer.SerializeToUtf8Bytes(value,WireOptions);
        if(bytes.Length>FrameLimit)throw new InvalidDataException("Mesh frame too large.");
        byte[] header=new byte[4];BinaryPrimitives.WriteInt32BigEndian(header,bytes.Length);
        await stream.WriteAsync(header,ct);await stream.WriteAsync(bytes,ct);await stream.FlushAsync(ct);
    }
    static JsonObject Message(string? op=null)=>op==null?new(){["version"]=1}:new(){["version"]=1,["op"]=op};
    static string S(JsonObject o,string key)=>o[key]?.GetValue<string>()??"";
    static int N(JsonObject o,string key)=>o[key]?.GetValue<int>()??0;
    static bool B(JsonObject o,string key)=>o[key]?.GetValue<bool>()??false;
    static JsonNode Wire(MeshGraphItem item)=>JsonSerializer.SerializeToNode(item.Wire(),WireOptions)!;
    static JsonObject Error(string error)=>new(){["version"]=1,["error"]=error};
    static void Check(JsonObject response)
    {
        if(N(response,"version")!=1||response.ContainsKey("error"))throw new IOException("Mesh protocol or peer request failed.");
    }
    static CancellationTokenSource Deadline(CancellationToken ct,int ms=15000)
    {
        var deadline=CancellationTokenSource.CreateLinkedTokenSource(ct);deadline.CancelAfter(ms);return deadline;
    }
    readonly ConcurrentDictionary<int,Task> _handlers=new();
    int _serial;
    async Task AcceptAsync(TcpListener listener,CancellationToken ct)
    {
        try
        {
            while(!ct.IsCancellationRequested)
            {
                var client=await listener.AcceptTcpClientAsync(ct);
                if(_handlers.Count>=8||client.Client.RemoteEndPoint is not IPEndPoint remote||
                    client.Client.LocalEndPoint is not IPEndPoint local||!DesktopMeshNetwork.SameSubnet(remote.Address,local.Address))
                {client.Dispose();continue;}
                int id=Interlocked.Increment(ref _serial);
                Task job=ServeAsync(client,ct);_handlers[id]=job;
                _=job.ContinueWith(_=>_handlers.TryRemove(id,out var ignored),CancellationToken.None,TaskContinuationOptions.ExecuteSynchronously,TaskScheduler.Default);
            }
        }
        catch(OperationCanceledException){}
        catch(ObjectDisposedException){}
        catch(SocketException){}
        finally
        {
            lock(_gate)if(ReferenceEquals(_listener,listener)){_listener=null;_status=_enabled?"Listener interrupted":"Sharing off";}
        }
    }
    async Task ServeAsync(TcpClient client,CancellationToken ct)
    {
        using(client)
        using(var tls=new SslStream(client.GetStream(),false))
        {
            try
            {
                JsonObject request;string member,grant;bool enroll,persistent;
                using(var initial=Deadline(ct,8000))
                {
                    await tls.AuthenticateAsServerAsync(new SslServerAuthenticationOptions{
                        ServerCertificate=_identity.Certificate,EnabledSslProtocols=SslProtocols.Tls12|SslProtocols.Tls13,
                        ClientCertificateRequired=false},initial.Token);
                    request=await ReadAsync(tls,initial.Token);
                    if(N(request,"version")!=1){await WriteAsync(tls,Error("unsupported_version"),initial.Token);return;}
                    member=S(request,"member");
                    if(!MeshGraphItem.IsHash(member)){await WriteAsync(tls,Error("member_required"),initial.Token);return;}
                    grant=_identity.Grant(member);
                    enroll=DesktopMeshIdentity.Equal(_identity.Enrollment,S(request,"token"));
                    if(!enroll&&!DesktopMeshIdentity.Equal(grant,S(request,"token")))
                    {await WriteAsync(tls,Error("unauthorized"),initial.Token);return;}
                    string nonce=DesktopMeshIdentity.Nonce();
                    var challenge=Message("member_challenge");challenge["nonce"]=nonce;await WriteAsync(tls,challenge,initial.Token);
                    var proof=await ReadAsync(tls,initial.Token);
                    if(N(proof,"version")!=1||S(proof,"op")!="member_proof"||!DesktopMeshIdentity.Verify(S(proof,"certificate"),S(proof,"proof"),_identity.Pin,member,nonce))
                    {await WriteAsync(tls,Error("member_proof_failed"),initial.Token);return;}
                    persistent=B(request,"session");
                }
                bool first=true;
                while(!ct.IsCancellationRequested)
                {
                    if(client.Client.RemoteEndPoint is not IPEndPoint remote||client.Client.LocalEndPoint is not IPEndPoint local||
                        !DesktopMeshNetwork.SameSubnet(remote.Address,local.Address))return;
                    using(var work=Deadline(ct))
                    {
                        var response=Respond(request);
                        if(first&&enroll&&!response.ContainsKey("error"))response["member_token"]=grant;
                        if(first&&persistent&&!response.ContainsKey("error"))response["session"]=true;
                        await WriteAsync(tls,response,work.Token);
                        if(!persistent||response.ContainsKey("error"))return;
                    }
                    first=false;
                    using var idle=Deadline(ct,45000);
                    request=await ReadAsync(tls,idle.Token);
                }
            }
            catch(Exception e) when(e is IOException or SocketException or AuthenticationException or OperationCanceledException or InvalidOperationException or JsonException or ArgumentException)
            { /* Never log peer content or tokens. The next request can reconnect. */ }
        }
    }
    JsonObject Respond(JsonObject request)
    {
        if(N(request,"version")!=1)return Error("unsupported_version");
        if(!B(request,"graph"))return Error("unsupported_operation");
        var catalog=_tree.Catalog();var response=Message();
        string op=S(request,"op");
        if(op=="inventory")
        {
            string after=S(request,"after");if(after!=""&&!MeshGraphItem.IsHash(after))return Error("invalid_request");
            var selected=catalog.Values.Where(i=>string.CompareOrdinal(i.Id,after)>0).Take(PageSize+1).ToArray();
            var items=new JsonArray();
            foreach(var item in selected.Take(PageSize))items.Add(new JsonObject{["id"]=item.Id,["language"]=item.Language,["topic"]=item.Topic,["demand"]=_demand.GetValueOrDefault(item.Id)});
            response["items"]=items;response["graph"]=true;response["total"]=catalog.Count;
            response["next"]=selected.Length>PageSize?selected[PageSize-1].Id:"";
        }
        else if(op=="want")
        {
            if(!catalog.TryGetValue(S(request,"id"),out var item))return Error("unavailable");
            response["item"]=Wire(item);Demand(item.Id);
        }
        else if(op=="want_batch")
        {
            if(request["ids"] is not JsonArray ids||ids.Count is <1 or >BatchSize)return Error("invalid_request");
            var wanted=ids.Select(n=>n?.GetValue<string>()??"").ToArray();
            if(wanted.Any(id=>!MeshGraphItem.IsHash(id))||wanted.Distinct(StringComparer.Ordinal).Count()!=wanted.Length)return Error("invalid_request");
            var items=new JsonArray();int processed=0,bytes=0;
            foreach(string id in wanted)
            {
                if(catalog.TryGetValue(id,out var item))
                {
                    if(!Append(items,item,ref bytes))break;
                    Demand(id);
                }
                processed++;
            }
            if(processed==0)return Error("invalid_request");
            response["items"]=items;response["processed"]=processed;
        }
        else if(op=="need")
        {
            string id=S(request,"request_id"),topic=S(request,"topic"),language=S(request,"language");int limit=N(request,"limit");
            if(!Guid.TryParseExact(id,"D",out _)||!MeshGraphItem.ValidTopic(topic)||!System.Text.RegularExpressions.Regex.IsMatch(language,"^[a-z]{2,3}$")||limit is <1 or >3)
                return Error("invalid_request");
            var items=new JsonArray();int bytes=0;
            foreach(var item in catalog.Values.Where(i=>i.Matches(topic)).OrderBy(i=>i.Language==language?0:1)
                .ThenBy(i=>i.RetrievalRank).ThenByDescending(i=>_demand.GetValueOrDefault(i.Id)).ThenBy(i=>i.Id,StringComparer.Ordinal).Take(limit))
                if(!Append(items,item,ref bytes))break;
            response["op"]="deliver";response["request_id"]=id;response["items"]=items;
        }
        else return Error("unsupported_operation");
        return response;
    }
    static bool Append(JsonArray items,MeshGraphItem item,ref int bytes)
    {
        var value=Wire(item);int length=JsonSerializer.SerializeToUtf8Bytes(value,WireOptions).Length+1;
        if(bytes+length>FrameLimit-4096)return false;
        items.Add(value);bytes+=length;return true;
    }
    void Demand(string id)
    {
        if(_demand.Count>=4096)_demand.Clear();
        _demand.AddOrUpdate(id,1,(_,v)=>Math.Min(1000,v+1));
    }

    sealed class Session(TcpClient client,SslStream tls) : IDisposable
    {
        public SslStream Tls=>tls;
        public void Dispose(){tls.Dispose();client.Dispose();}
    }
    async Task<(Session,JsonObject)> ConnectAsync(MeshInvitation peer,JsonObject request,CancellationToken ct)
    {
        var ip=IPAddress.Parse(peer.Host);
        if(!_lan||!Bound(peer.Pin)||!DesktopMeshNetwork.SameSubnet(ip))throw new IOException("Peer is not bound on the local subnet.");
        var client=new TcpClient(AddressFamily.InterNetwork);
        SslStream? tls=null;
        try
        {
            using var deadline=Deadline(ct);
            await client.ConnectAsync(ip,Port,deadline.Token);
            tls=new SslStream(client.GetStream(),false,(_,cert,_,_)=>{
                if(cert==null)return false;
                using var received=X509CertificateLoader.LoadCertificate(cert.GetRawCertData());
                return DesktopMeshIdentity.Current(received)&&DesktopMeshIdentity.Equal(peer.Pin,DesktopMeshIdentity.Fingerprint(received));
            });
            await tls.AuthenticateAsClientAsync(new SslClientAuthenticationOptions{
                TargetHost=peer.Host,EnabledSslProtocols=SslProtocols.Tls12|SslProtocols.Tls13},deadline.Token);
            request["token"]=peer.Token;request["member"]=_identity.Pin;request["session"]=true;
            await WriteAsync(tls,request,deadline.Token);
            var challenge=await ReadAsync(tls,deadline.Token);Check(challenge);
            if(S(challenge,"op")!="member_challenge")throw new IOException("Peer member proof is required.");
            string nonce=S(challenge,"nonce");
            var proof=Message("member_proof");proof["certificate"]=Convert.ToBase64String(_identity.Certificate.RawData);
            proof["proof"]=_identity.Proof(peer.Pin,nonce);await WriteAsync(tls,proof,deadline.Token);
            var answer=await ReadAsync(tls,deadline.Token);Check(answer);
            if(!B(answer,"session"))throw new IOException("Peer does not support persistent Mesh sessions.");
            if(answer.ContainsKey("member_token"))SaveGrant(peer,S(answer,"member_token"));
            return(new Session(client,tls),answer);
        }
        catch{tls?.Dispose();client.Dispose();throw;}
    }
    MeshInvitation Current(string pin)
    {
        lock(_gate)return _peers.FirstOrDefault(p=>p.Pin==pin)??throw new IOException("Axon binding removed.");
    }
    async Task<JsonObject> ExchangeAsync(string pin,Dictionary<string,Session> sessions,JsonObject request,CancellationToken ct)
    {
        request["graph"]=true;var peer=Current(pin);
        if(!_lan||!DesktopMeshNetwork.SameSubnet(IPAddress.Parse(peer.Host)))throw new IOException("Peer no longer on local subnet.");
        if(sessions.TryGetValue(pin,out var session))
        {
            try
            {
                using var deadline=Deadline(ct);
                await WriteAsync(session.Tls,request,deadline.Token);
                var reply=await ReadAsync(session.Tls,deadline.Token);Check(reply);
                if(Current(pin).Host!=peer.Host)throw new IOException("Axon binding changed.");
                return reply;
            }
            catch(Exception e) when(!ct.IsCancellationRequested&&(e is IOException or SocketException or OperationCanceledException or AuthenticationException))
            {sessions.Remove(pin);session.Dispose();}
        }
        var connected=await ConnectAsync(Current(pin),request,ct);sessions[pin]=connected.Item1;return connected.Item2;
    }
    public async Task<object> ReceiveAsync(CancellationToken cancellationToken)
    {
        if(!_lan)throw new InvalidOperationException("Desktop host is in local-only mode.");
        if(!await _receive.WaitAsync(0,cancellationToken))throw new InvalidOperationException("Receive already in progress.");
        var sessions=new Dictionary<string,Session>(StringComparer.Ordinal);
        try
        {
            using var lifetime=CancellationTokenSource.CreateLinkedTokenSource(cancellationToken,_lifetime.Token);
            var ct=lifetime.Token;
            string[] pins;lock(_gate)pins=_peers.Select(p=>p.Pin).ToArray();
            var known=_tree.Catalog().Keys.ToHashSet(StringComparer.Ordinal);
            var offers=new Dictionary<string,List<string>>(StringComparer.Ordinal);
            var demand=new Dictionary<string,int>(StringComparer.Ordinal);
            var topics=new Dictionary<string,string>(StringComparer.Ordinal);
            int peers=0,failed=0;ulong inserted=0;
            foreach(string pin in pins)
            {
                try
                {
                    string after="";
                    do
                    {
                        ct.ThrowIfCancellationRequested();
                        var request=Message("inventory");request["after"]=after;
                        var reply=await ExchangeAsync(pin,sessions,request,ct);
                        if(!B(reply,"graph")||reply["items"] is not JsonArray rows||rows.Count>PageSize||N(reply,"total")<rows.Count)
                            throw new InvalidDataException("Invalid inventory.");
                        string last=after;
                        foreach(var row in rows.OfType<JsonObject>())
                        {
                            string id=S(row,"id"),topic=S(row,"topic"),language=S(row,"language");
                            if(!MeshGraphItem.IsHash(id)||string.CompareOrdinal(id,last)<=0||topic.Length is <1 or >120||
                                !System.Text.RegularExpressions.Regex.IsMatch(language,"^[a-z]{2,3}$"))throw new InvalidDataException("Invalid inventory.");
                            last=id;
                            if(known.Contains(id))continue;
                            if(!offers.TryGetValue(id,out var sources))offers[id]=sources=[];
                            if(!sources.Contains(pin))sources.Add(pin);
                            demand[id]=Math.Max(demand.GetValueOrDefault(id),Math.Clamp(N(row,"demand"),0,1000));
                            topics[id]=topic;
                        }
                        string next=S(reply,"next");
                        if(next!=""&&(next!=last||next==after))throw new InvalidDataException("Invalid inventory cursor.");
                        after=next;
                    }while(after!="");
                    peers++;
                }
                catch(Exception) when(!ct.IsCancellationRequested){failed++;}
            }
            if(peers==0&&pins.Length>0)throw new IOException("No bound peer inventory reachable.");
            var branches=_tree.Branches();
            var ordered=offers.Keys.OrderByDescending(id=>branches.Count(b=>topics[id].Contains(b.Term,StringComparison.OrdinalIgnoreCase)))
                .ThenBy(id=>offers[id].Count).ThenByDescending(id=>demand[id]).ThenBy(id=>id,StringComparer.Ordinal).ToArray();
            var pending=new HashSet<string>(ordered,StringComparer.Ordinal);
            foreach(string id in ordered)
            {
                ct.ThrowIfCancellationRequested();
                if(!pending.Contains(id))continue;
                foreach(string pin in offers[id])
                {
                    if(!pending.Contains(id))break;
                    try
                    {
                        var batch=ordered.Where(x=>pending.Contains(x)&&offers[x].Contains(pin)).Take(BatchSize).ToArray();
                        var request=Message("want_batch");request["ids"]=new JsonArray(batch.Select(x=>(JsonNode?)JsonValue.Create(x)).ToArray());
                        var reply=await ExchangeAsync(pin,sessions,request,ct);
                        int processed=N(reply,"processed");
                        if(processed<1||processed>batch.Length||reply["items"] is not JsonArray rows||rows.Count>processed)
                            throw new InvalidDataException("Invalid batch.");
                        var permitted=batch.Take(processed).ToHashSet(StringComparer.Ordinal);
                        var items=new List<MeshGraphItem>();
                        foreach(var row in rows)
                        {
                            using var doc=JsonDocument.Parse(row!.ToJsonString(WireOptions));
                            var item=MeshGraphItem.Parse(doc.RootElement);
                            if(!permitted.Remove(item.Id))throw new InvalidDataException("Unexpected or duplicate graph item.");
                            items.Add(item);
                        }
                        ct.ThrowIfCancellationRequested();if(!Bound(pin))throw new IOException("Axon binding removed.");
                        inserted+=_tree.Import(items);
                        foreach(var item in items)pending.Remove(item.Id);
                    }
                    catch(Exception) when(!ct.IsCancellationRequested){failed++;}
                }
            }
            return new {peers,inserted,missing=pending.Count,failed};
        }
        finally{foreach(var session in sessions.Values)session.Dispose();_receive.Release();}
    }
    public async ValueTask DisposeAsync()
    {
        _lifetime.Cancel();lock(_gate)Stop();
        if(_accept!=null)try{await _accept;}catch{}
        await Task.WhenAll(_handlers.Values.ToArray());
        await _receive.WaitAsync();
        _identity.Dispose();_serving?.Dispose();_lifetime.Dispose();_receive.Dispose();
    }
}
