using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.DataProtection;
using Wds.Roots.Tree;

namespace Wds.Roots.Host;

internal sealed record MeshReceiveResult(int Peers,ulong Inserted,int Missing,int Failed);
internal sealed record DesktopAxonPeerView(string Host,string Pin,string Transport,bool Blocked,long Received,string Status);

/// <summary>Receive-first public Axon service. Public receipts may be forwarded;
/// private local Tree content and private invitations are never offered here.</summary>
internal sealed class DesktopPublicAxonService : IAsyncDisposable
{
    const int Page=32,MaxItems=4096,MaxIds=65536,MaxReceipts=100000;
    sealed record Peer(string Id,string Root,string After,long Received,bool Blocked,string Host,string Via,string Status,long LastSeen);
    sealed record Receipt(JsonElement Item,string Peer,long At,bool Promoted);
    readonly DesktopTreeService _tree;
    readonly string _root,_directory,_peerDirectory,_receiptDirectory;
    readonly byte[] _privateKey;
    readonly string _id;
    readonly Dictionary<string,Peer> _peers=new(StringComparer.Ordinal);
    readonly SortedDictionary<string,Receipt> _receipts=new(StringComparer.Ordinal);
    readonly Dictionary<string,(PublicAxonRoute Route,long Seen)> _routes=new(StringComparer.Ordinal);
    readonly object _gate=new();
    readonly CancellationTokenSource _lifetime=new();
    readonly SemaphoreSlim _wake=new(0,1);
    readonly Task _worker;
    TaskCompletionSource<MeshReceiveResult>? _manual;
    string _status="Discovering public Axons";
    bool _receiving;
    public DesktopPublicAxonService(DesktopTreeService tree,string repositoryRoot,string directory)
    {
        _tree=tree;_root=repositoryRoot;_directory=directory;
        _peerDirectory=Path.Combine(directory,"subscriptions");_receiptDirectory=Path.Combine(directory,"receipts");
        Directory.CreateDirectory(_peerDirectory);Directory.CreateDirectory(_receiptDirectory);
        var keys=Directory.CreateDirectory(Path.Combine(directory,"keys"));
        if(!OperatingSystem.IsWindows())
            foreach(string path in new[]{directory,_peerDirectory,_receiptDirectory,keys.FullName})
                File.SetUnixFileMode(path,UnixFileMode.UserRead|UnixFileMode.UserWrite|UnixFileMode.UserExecute);
        var provider=DataProtectionProvider.Create(keys,o=>{o.SetApplicationName("Wds.Roots.ExternalAxon");if(OperatingSystem.IsWindows())o.ProtectKeysWithDpapi();});
        var protector=provider.CreateProtector("roots.rep.desktop.identity.v1");
        string file=Path.Combine(directory,"identity.protected"),marker=Path.Combine(directory,"identity.id");
        if(!File.Exists(file))
        {
            if(File.Exists(marker))throw new InvalidOperationException("Public Axon identity missing; refusing automatic rotation.");
            using var generated=ECDsa.Create(ECCurve.NamedCurves.nistP256);
            byte[] bytes=generated.ExportPkcs8PrivateKey();
            try{Atomic(file,protector.Protect(Convert.ToBase64String(bytes)));}finally{CryptographicOperations.ZeroMemory(bytes);}
        }
        _privateKey=Convert.FromBase64String(protector.Unprotect(File.ReadAllText(file)));
        using var key=Key();_id=DesktopRepChannel.Fingerprint(key);
        if(File.Exists(marker)&&File.ReadAllText(marker).Trim()!=_id)throw new InvalidOperationException("Public Axon identity changed.");
        if(!File.Exists(marker))Atomic(marker,_id);
        foreach(string path in Directory.EnumerateFiles(_peerDirectory,"*.axon").Take(1024))
        {
            string id=Path.GetFileNameWithoutExtension(path);
            if(!MeshGraphItem.IsHash(id))continue;
            try
            {
                var peer=JsonSerializer.Deserialize<Peer>(File.ReadAllText(path))??throw new InvalidDataException();
                if(peer.Id!=id||!(peer.Root==""||MeshGraphItem.IsHash(peer.Root))||!(peer.After==""||MeshGraphItem.IsHash(peer.After)))throw new InvalidDataException();
                _peers[id]=peer;
            }
            catch{_peers[id]=new(id,"","",0,true,"","Nearby","Unreadable binding · blocked",0);}
        }
        foreach(string path in Directory.EnumerateFiles(_receiptDirectory,"*.json").Take(MaxReceipts))
        {
            try
            {
                if(new FileInfo(path).Length>256*1024)continue;
                var receipt=JsonSerializer.Deserialize<Receipt>(File.ReadAllText(path))??throw new InvalidDataException();
                var item=DesktopPublicItem.Parse(receipt.Item);
                if(Path.GetFileNameWithoutExtension(path)!=item.Id||!MeshGraphItem.IsHash(receipt.Peer))continue;
                _receipts[item.Id]=receipt;
            }catch(Exception){/* malformed receipt does not prevent other peers */}
        }
        Console.WriteLine("ROOTS PUBLIC AXONS: "+_id[..12]+" · automatic BLE and local IP discovery; receive public knowledge into Tree.");
        _worker=Task.Run(()=>RunAsync(_lifetime.Token));
    }
    ECDsa Key(){var key=ECDsa.Create();key.ImportPkcs8PrivateKey(_privateKey,out _);return key;}
    static void Atomic(string path,string text)
    {
        string temp=path+"."+Guid.NewGuid().ToString("N")+".tmp";
        try
        {
            using(var stream=new FileStream(temp,FileMode.CreateNew,FileAccess.Write,FileShare.None))
            {byte[] bytes=Encoding.UTF8.GetBytes(text);stream.Write(bytes);stream.Flush(true);}
            if(!OperatingSystem.IsWindows())File.SetUnixFileMode(temp,UnixFileMode.UserRead|UnixFileMode.UserWrite);
            File.Move(temp,path,true);
        }
        finally{File.Delete(temp);}
    }
    void Save(Peer p)
    {
        lock(_gate){Atomic(Path.Combine(_peerDirectory,p.Id+".axon"),JsonSerializer.Serialize(p));_peers[p.Id]=p;}
    }
    void Update(string id,Func<Peer,Peer> change)
    {
        lock(_gate)if(_peers.TryGetValue(id,out var p))Save(change(p));
    }
    bool Blocked(string id){lock(_gate)return _peers.TryGetValue(id,out var p)&&p.Blocked;}
    void Require(string id,CancellationToken ct){ct.ThrowIfCancellationRequested();if(Blocked(id))throw new IOException("Public Axon blocked.");}
    public bool Contains(string id){lock(_gate)return _peers.ContainsKey(id);}
    public void SetBlocked(string id,bool blocked)
    {
        Update(id,p=>p with{Blocked=blocked,Status=blocked?"Blocked":"Waiting for route"});
        if(!blocked)Wake();
    }
    public DesktopAxonPeerView[] Peers()
    {
        lock(_gate)return _peers.Values.OrderByDescending(p=>p.LastSeen).Select(p=>new DesktopAxonPeerView(p.Host,p.Id,
            p.Via=="BLE"?"BLE → Wi-Fi":"Public LAN",p.Blocked,p.Received,p.Status)).ToArray();
    }
    public string Status{get{lock(_gate)return _status;}}
    public bool Receiving{get{lock(_gate)return _receiving;}}
    void Note(string status){lock(_gate)_status=status;}
    void Wake(){try{_wake.Release();}catch(SemaphoreFullException){}catch(ObjectDisposedException){}}
    public async Task<MeshReceiveResult> RequestAsync(CancellationToken ct)
    {
        Task<MeshReceiveResult> wait;
        lock(_gate){_manual??=new(TaskCreationOptions.RunContinuationsAsynchronously);wait=_manual.Task;}
        Wake();
        try{return await wait.WaitAsync(TimeSpan.FromSeconds(150),ct);}
        catch(TimeoutException){return new(0,0,1,0);}
    }
    void Finish(MeshReceiveResult result){lock(_gate){_manual?.TrySetResult(result);_manual=null;}}
    ulong Retain(DesktopPublicItem item,string peer)
    {
        if(!_receipts.TryGetValue(item.Id,out var receipt))
        {
            if(_receipts.Count>=MaxReceipts)throw new IOException("External receipt storage limit reached.");
            receipt=new(item.Envelope,peer,DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),false);
            Atomic(Path.Combine(_receiptDirectory,item.Id+".json"),JsonSerializer.Serialize(receipt));
            _receipts[item.Id]=receipt;
        }
        if(receipt.Promoted)return 0;
        ulong inserted=_tree.ImportExternal(item,receipt.Peer,receipt.At);
        receipt=receipt with{Promoted=true};
        Atomic(Path.Combine(_receiptDirectory,item.Id+".json"),JsonSerializer.Serialize(receipt));_receipts[item.Id]=receipt;
        return inserted;
    }
    async Task RunAsync(CancellationToken ct)
    {
        // Recover prior interrupted writes, never replay already promoted receipts.
        foreach(var receipt in _receipts.Values.Where(r=>!r.Promoted).ToArray())
        {
            if(ct.IsCancellationRequested)return;
            try{Retain(DesktopPublicItem.Parse(receipt.Item),receipt.Peer);}catch(Exception){}
        }
        bool pending=false;long lastBle=0;
        while(!ct.IsCancellationRequested)
        {
            int peers=0,missing=0,failed=0;ulong inserted=0;
            try
            {
                lock(_gate)_receiving=true;
                Note("Searching local network for Roots Axons");
                // Discover IP peers each sweep. BLE discovery is bounded and
                // complements broadcast, rather than delaying every continuation.
                long now=Environment.TickCount64;
                Task<List<PublicAxonRoute>> ble=(lastBle==0||now-lastBle>=45000)?
                    DesktopAxonDiscovery.BleAsync(_root,ct):Task.FromResult(new List<PublicAxonRoute>());
                if(lastBle==0||now-lastBle>=45000)lastBle=now;
                var lan=await DesktopAxonDiscovery.LanAsync(ct);
                foreach(var r in lan)if(r.Id!=_id)_routes[r.Id]=(r,Environment.TickCount64);
                foreach(var r in await ble)if(r.Id!=_id)_routes[r.Id]=(r,Environment.TickCount64);
                now=Environment.TickCount64;
                foreach(var id in _routes.Where(x=>now-x.Value.Seen>120000).Select(x=>x.Key).ToArray())_routes.Remove(id);
                foreach(var hint in _routes.Values.OrderBy(x=>x.Route.Id,StringComparer.Ordinal).Take(64).ToArray())
                {
                    if(Blocked(hint.Route.Id))continue;
                    ct.ThrowIfCancellationRequested();
                    try
                    {
                        var result=await ExchangeAsync(hint.Route,ct);
                        peers++;inserted+=result.Inserted;if(result.Pending)missing++;
                    }
                    catch(Exception e) when(!ct.IsCancellationRequested)
                    {
                        failed++;Update(hint.Route.Id,p=>p with{Status="Waiting for route · "+e.GetType().Name});
                    }
                }
                int unavailable;lock(_gate)unavailable=Math.Max(0,_peers.Values.Count(p=>!p.Blocked)-peers-failed);
                failed+=unavailable;
                pending=missing>0;
                Note(peers+" public Axon(s) reached · "+inserted+" fragments added"+(pending?" · inventory remaining":"")+
                    (failed>0?" · "+failed+" unavailable":""));
            }
            catch(OperationCanceledException) when(ct.IsCancellationRequested){break;}
            catch(Exception e){failed++;pending=false;Note("Public Axon discovery retrying · "+e.GetType().Name);}
            finally{lock(_gate)_receiving=false;Finish(new(peers,inserted,missing,failed));}
            try{await _wake.WaitAsync(TimeSpan.FromSeconds(pending?2:30),ct);}
            catch(OperationCanceledException){break;}
        }
        Finish(new(0,0,0,0));
    }
    static JsonObject Message(string op)=>new(){["op"]=op};
    static string S(JsonObject o,string field)=>o[field]?.GetValue<string>()??"";
    static bool B(JsonObject o,string field)=>o[field]?.GetValue<bool>()??false;
    static void Expect(JsonObject o,string op){if(S(o,"op")!=op)throw new InvalidDataException("Unexpected public Axon operation.");}
    static string InventoryRoot(IEnumerable<string> ids)
    {
        using var hash=IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        foreach(string id in ids)hash.AppendData(Encoding.ASCII.GetBytes(id));
        return Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant();
    }
    async Task<(ulong Inserted,bool Pending)> ExchangeAsync(PublicAxonRoute route,CancellationToken token)
    {
        if(!DesktopAxonDiscovery.Private(route.Address)||!DesktopMeshNetwork.SameSubnet(route.Address))throw new IOException("Route outside local subnet.");
        using var deadline=CancellationTokenSource.CreateLinkedTokenSource(token);deadline.CancelAfter(TimeSpan.FromSeconds(120));var ct=deadline.Token;
        using var client=new TcpClient(AddressFamily.InterNetwork);
        using(var setup=CancellationTokenSource.CreateLinkedTokenSource(ct)){setup.CancelAfter(5000);await client.ConnectAsync(route.Address,DesktopAxonDiscovery.Port,setup.Token);}
        client.NoDelay=true;
        using var key=Key();
        using var channel=await DesktopRepChannel.OpenAsync(client.GetStream(),key,true,route.Id,ct);
        string peer=channel.PeerId;Require(peer,ct);
        bool firstBinding=!Contains(peer);
        var bind=Message("bind");bind["root_graph"]=true;bind["bulk_receive_v1"]=true;bind["lan_upgrade_v1"]=false;
        await channel.SendAsync(bind,ct);var response=await channel.ReadAsync(ct);Expect(response,"bound");
        if(!B(response,"root_graph")||!B(response,"bulk_receive_v1"))throw new IOException("Phone needs current public Axon protocol.");
        lock(_gate)
        {
            var existing=_peers.GetValueOrDefault(peer)??new Peer(peer,"","",0,false,"",route.Via,"Bound",0);
            if(existing.Blocked)throw new IOException("Public Axon blocked.");
            Save(existing with{Host=route.Address.ToString(),Via=route.Via,Status="Receiving",LastSeen=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()});
        }
        if(firstBinding)Console.WriteLine("ROOTS PUBLIC AXON: bound "+peer[..12]+" via "+route.Via+" · "+route.Address);
        // Freeze the offer inventory before pulling, matching Android's session snapshot.
        var catalog=new SortedDictionary<string,Receipt>(_receipts,StringComparer.Ordinal);
        string root=InventoryRoot(catalog.Keys);
        Peer saved;lock(_gate)saved=_peers[peer];
        string after=saved.After,remoteRoot=saved.Root;int examined=0,transferred=0;ulong inserted=0;
        bool complete=false;
        while(examined<MaxIds&&transferred<MaxItems)
        {
            Require(peer,ct);
            var request=Message("inventory");request["root"]=remoteRoot;request["after"]=after;
            await channel.SendAsync(request,ct);
            var inventory=await channel.ReadAsync(ct);Expect(inventory,"inventory");
            remoteRoot=S(inventory,"root");if(!MeshGraphItem.IsHash(remoteRoot)||inventory["ids"] is not JsonArray ids||ids.Count>Page)throw new InvalidDataException("Invalid inventory.");
            bool more=B(inventory,"more");if(more&&ids.Count==0)throw new InvalidDataException("Invalid continuation.");
            var page=new List<string>();string previous=after;
            foreach(var entry in ids)
            {
                string id=entry?.GetValue<string>()??"";
                if(!MeshGraphItem.IsHash(id)||string.CompareOrdinal(id,previous)<=0)throw new InvalidDataException("Invalid inventory ordering.");
                page.Add(id);previous=id;
            }
            int processed=0;
            foreach(string id in page)
            {
                if(examined>=MaxIds||transferred>=MaxItems)break;
                Require(peer,ct);
                if(!_receipts.TryGetValue(id,out var receipt))
                {
                    var want=Message("want");want["id"]=id;await channel.SendAsync(want,ct);
                    var reply=await channel.ReadAsync(ct);Expect(reply,"data");
                    using var doc=JsonDocument.Parse(reply["item"]?.ToJsonString(DesktopPublicItem.WireOptions)??throw new InvalidDataException("Missing item."));
                    var item=DesktopPublicItem.Parse(doc.RootElement);if(item.Id!=id)throw new InvalidDataException("Unexpected publication.");
                    Require(peer,ct);inserted+=Retain(item,peer);transferred++;
                    Update(peer,p=>p with{Received=p.Received+1,Root=remoteRoot,After=id,Status="Receiving"});
                    var ack=Message("ack");ack["id"]=id;await channel.SendAsync(ack,ct);
                }
                else if(!receipt.Promoted)inserted+=Retain(DesktopPublicItem.Parse(receipt.Item),peer);
                after=id;processed++;examined++;
            }
            if(processed==page.Count&&!more){after="";complete=true;}
            Update(peer,p=>p with{Root=remoteRoot,After=after,Status="Received "+transferred+" · "+examined+" examined"});
            Note("Receiving "+peer[..12]+" · "+transferred+" items · "+examined+" checked");
            if(complete||processed<page.Count)break;
        }
        var release=Message("release");release["more_pending"]=!complete;await channel.SendAsync(release,ct);
        bool peerMore=false;int pages=0,served=0;var offered=new HashSet<string>(StringComparer.Ordinal);
        while(true)
        {
            Require(peer,ct);
            var request=await channel.ReadAsync(ct);string op=S(request,"op");
            if(op=="release"){peerMore=B(request,"more_pending");break;}
            if(op=="inventory")
            {
                if(++pages>2049)throw new IOException("Inventory budget exceeded.");
                string cursor=S(request,"after"),known=S(request,"root");
                if((cursor!=""&&!MeshGraphItem.IsHash(cursor))||(known!=""&&!MeshGraphItem.IsHash(known)))throw new IOException("Invalid inventory cursor.");
                var remaining=catalog.Keys.Where(id=>string.CompareOrdinal(id,cursor)>0).Take(Page+1).ToArray();
                var page=remaining.Take(Page).ToArray();offered=page.ToHashSet(StringComparer.Ordinal);
                var inventory=Message("inventory");inventory["root"]=root;inventory["ids"]=new JsonArray(page.Select(id=>(JsonNode?)JsonValue.Create(id)).ToArray());
                inventory["more"]=remaining.Length>Page;inventory["total"]=catalog.Count;await channel.SendAsync(inventory,ct);
            }
            else if(op=="want")
            {
                string id=S(request,"id");
                if(++served>MaxItems||!offered.Remove(id)||!catalog.TryGetValue(id,out var item))throw new IOException("Invalid public want.");
                var data=Message("data");data["item"]=JsonNode.Parse(item.Item.GetRawText());await channel.SendAsync(data,ct);
                var ack=await channel.ReadAsync(ct);Expect(ack,"ack");if(S(ack,"id")!=id)throw new InvalidDataException("Invalid receipt.");
            }
            else throw new IOException("Unsupported public Axon operation.");
        }
        Update(peer,p=>p with{Status=complete?"Inventory checked":"More inventory pending",LastSeen=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()});
        if(inserted>0)Console.WriteLine("ROOTS PUBLIC AXON: "+peer[..12]+" · "+transferred+" items received · "+inserted+" Tree fragments added.");
        return (inserted,!complete||peerMore);
    }
    public async ValueTask DisposeAsync()
    {
        _lifetime.Cancel();Wake();try{await _worker;}catch{}
        CryptographicOperations.ZeroMemory(_privateKey);_wake.Dispose();_lifetime.Dispose();
    }
}
