using System.Diagnostics;
using System.IO.Compression;
using System.Text.Json;
using System.Text.Json.Nodes;
using Wds.Roots.Tree;

namespace Wds.Roots.Host;

internal sealed partial class DesktopPublicAxonService
{
    const int ArchiveSessionItems=65536,ArchiveSessionIds=262144,ArchivePage=512;
    string ArchiveDirectory=>Path.Combine(_receiptDirectory,"archives");
    string ManifestFile(string peer)=>Path.Combine(_peerDirectory,peer+".inventory.json");
    string ProfileFile(string peer)=>Path.Combine(_peerDirectory,peer+".transfer.json");
    sealed record ArchiveScan(int Bucket,DesktopKnowledgeManifest.Part Remote,List<string> Ids,bool Last);

    void LoadArchiveReceipts()
    {
        Directory.CreateDirectory(ArchiveDirectory);
        if(!OperatingSystem.IsWindows())File.SetUnixFileMode(ArchiveDirectory,UnixFileMode.UserRead|UnixFileMode.UserWrite|UnixFileMode.UserExecute);
        // Committed archives take precedence over recovery copies and old receipts.
        foreach(string path in Directory.EnumerateFiles(ArchiveDirectory,"*.gz").OrderBy(p=>p.EndsWith(".pending.gz",StringComparison.Ordinal)?0:1))
        {
            try
            {
                if(new FileInfo(path).Length>32*1024*1024)continue;
                using var file=File.OpenRead(path);
                using var gzip=new GZipStream(file,CompressionMode.Decompress);
                using var output=new MemoryStream();
                byte[] buffer=new byte[65536];int n;
                while((n=gzip.Read(buffer))>0)
                {
                    if(output.Length+n>32*1024*1024)throw new InvalidDataException("Receipt archive exceeds budget.");
                    output.Write(buffer,0,n);
                }
                var receipts=JsonSerializer.Deserialize<Receipt[]>(output.ToArray())??[];
                if(receipts.Length>DesktopKnowledgeArchive.MaxItems)continue;
                bool committed=!path.EndsWith(".pending.gz",StringComparison.Ordinal);
                foreach(var receipt in receipts)
                {
                    var item=DesktopPublicItem.Parse(receipt.Item);
                    if(!MeshGraphItem.IsHash(receipt.Peer)||receipt.At<0)continue;
                    if(_receipts.TryGetValue(item.Id,out var old)&&old.Promoted)continue;
                    if(_receipts.Count>=MaxReceipts&&!_receipts.ContainsKey(item.Id))continue;
                    _receipts[item.Id]=receipt with{Promoted=committed};
                }
            }
            catch(Exception e){Console.Error.WriteLine("Public Axon receipt archive skipped: "+e.GetType().Name);}
        }
    }
    static void WriteReceiptArchive(string path,IReadOnlyList<Receipt> receipts)
    {
        string temp=path+"."+Guid.NewGuid().ToString("N")+".tmp";
        try
        {
            using(var file=new FileStream(temp,FileMode.CreateNew,FileAccess.Write,FileShare.None))
            {
                using(var gzip=new GZipStream(file,CompressionLevel.Fastest,true))JsonSerializer.Serialize(gzip,receipts);
                file.Flush(true);
            }
            if(!OperatingSystem.IsWindows())File.SetUnixFileMode(temp,UnixFileMode.UserRead|UnixFileMode.UserWrite);
            File.Move(temp,path,true);
        }
        finally{File.Delete(temp);}
    }
    ulong RetainArchive(IReadOnlyList<DesktopPublicItem> items,string peer)
    {
        if(items.Count<1||items.Count>DesktopKnowledgeArchive.MaxItems)throw new InvalidDataException("Invalid receipt archive count.");
        var pending=new List<Receipt>();
        var seen=new HashSet<string>(StringComparer.Ordinal);
        long now=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        int added=0;
        foreach(var item in items)
        {
            if(!seen.Add(item.Id))throw new InvalidDataException("Duplicate receipt.");
            if(_receipts.TryGetValue(item.Id,out var existing))
            {
                if(!existing.Promoted)pending.Add(existing);
            }
            else
            {
                if(_receipts.Count+ ++added>MaxReceipts)throw new IOException("External receipt storage limit reached.");
                pending.Add(new(item.Envelope,peer,now,false));
            }
        }
        if(pending.Count==0)return 0;
        var rows=new List<Observation>();
        foreach(var receipt in pending)rows.AddRange(DesktopPublicItem.Parse(receipt.Item).Rows(receipt.Peer,receipt.At));
        string stem=Path.Combine(ArchiveDirectory,Guid.NewGuid().ToString("N"));
        string path=stem+".pending.gz";
        // Durable compressed receipt precedes the native writes. Replaying a
        // pending archive is safe because native observations are deduplicated.
        WriteReceiptArchive(path,pending);
        foreach(var receipt in pending)_receipts[DesktopPublicItem.Parse(receipt.Item).Id]=receipt;
        ulong inserted=_tree.ImportExternalBatch(rows);
        File.Move(path,stem+".gz",true);
        foreach(var receipt in pending)_receipts[DesktopPublicItem.Parse(receipt.Item).Id]=receipt with{Promoted=true};
        return inserted;
    }
    static T ReadProfile<T>(string path,T fallback)
    {
        try
        {
            if(!File.Exists(path)||new FileInfo(path).Length>128*1024)return fallback;
            return JsonSerializer.Deserialize<T>(File.ReadAllText(path)) is T value?value:fallback;
        }
        catch{return fallback;}
    }
    async Task<(ulong Inserted,bool Pending)> ExchangeArchivesAsync(DesktopRepChannel channel,string peer,CancellationToken ct)
    {
        var catalog=new SortedDictionary<string,Receipt>(_receipts,StringComparer.Ordinal);
        // Pending receipts aren't advertised or treated as held until native retention succeeds.
        foreach(string id in catalog.Where(x=>!x.Value.Promoted).Select(x=>x.Key).ToArray())catalog.Remove(id);
        var offeredManifest=new DesktopKnowledgeManifest(catalog.Keys);
        var held=new DesktopKnowledgeManifest(catalog.Keys);
        var saved=ReadProfile(ManifestFile(peer),new Dictionary<int,DesktopKnowledgeManifest.Checkpoint>());
        var profile=ReadProfile(ProfileFile(peer),new DesktopArchiveProfile());
        await channel.SendAsync(Message("manifest"),ct);
        var manifestReply=await channel.ReadAsync(ct);Expect(manifestReply,"manifest");
        var remote=DesktopKnowledgeManifest.Parse(manifestReply["parts"] as JsonArray??throw new InvalidDataException("Missing manifest."));
        var scans=new List<ArchiveScan>();var wanted=new List<string>();
        int inspected=0,transferred=0,windowIds=0,skipped=0;ulong inserted=0;
        bool complete=true;
        void SaveScans()
        {
            foreach(var scan in scans)
            {
                string after="";int n=0;
                foreach(string id in scan.Ids){if(!held.Has(id))break;after=id;n++;}
                bool done=n==scan.Ids.Count&&scan.Last;
                if(n>0||done)saved[scan.Bucket]=new(scan.Remote.Root,held.Get(scan.Bucket).Root,done?"":after,done);
                if(n!=scan.Ids.Count)break;
            }
            Atomic(ManifestFile(peer),JsonSerializer.Serialize(saved));
        }
        async Task Flush()
        {
            int offset=0;
            if(wanted.Count==0)SaveScans();
            while(offset<wanted.Count)
            {
                Require(peer,ct);var budget=profile.Budget();
                var ids=wanted.Skip(offset).Take(budget.Records).ToArray();
                var watch=Stopwatch.StartNew();
                await channel.SendAsync(new JsonObject{["op"]="want_archive",["count"]=ids.Length,["raw_limit"]=budget.Raw,["wire_limit"]=budget.Wire},ct);
                await DesktopKnowledgeArchive.SendBytes(channel,DesktopKnowledgeArchive.EncodeIds(ids),ct);
                var header=await channel.ReadAsync(ct);Expect(header,"archive");
                int raw=header["raw_size"]!.GetValue<int>(),wireSize=header["wire_size"]!.GetValue<int>(),count=header["count"]!.GetValue<int>();
                if(raw<2||raw>budget.Raw||count<1||count>ids.Length)throw new InvalidDataException("Invalid archive header.");
                byte[] wire=await DesktopKnowledgeArchive.ReadBytes(channel,wireSize,budget.Wire,ct);
                var items=DesktopKnowledgeArchive.Unpack(wire,S(header,"codec"),S(header,"sha256"),raw,ids,count,budget.Raw,budget.Wire);
                Require(peer,ct);
                inserted+=RetainArchive(items,peer);
                foreach(var item in items)held.Add(item.Id);
                offset+=count;transferred+=count;SaveScans();
                Require(peer,ct);
                await channel.SendAsync(new JsonObject{["op"]="archive_ack",["sha256"]=S(header,"sha256"),["count"]=count},ct);
                profile=profile.Observe(count,raw,wireSize,watch.ElapsedMilliseconds);
                Atomic(ProfileFile(peer),JsonSerializer.Serialize(profile));
                var next=profile.Budget();
                Update(peer,p=>p with{Received=p.Received+count,Status="Receiving archives"});
                Note("Archive "+count+" records · "+wireSize+" wire bytes · "+watch.ElapsedMilliseconds+" ms · next "+next.Records+" records (~"+next.Estimate+" ms)");
            }
            scans.Clear();wanted.Clear();windowIds=0;
        }
        for(int bucket=0;bucket<DesktopKnowledgeManifest.Buckets&&complete;bucket++)
        {
            var part=remote[bucket];var local=held.Get(bucket);
            saved.TryGetValue(bucket,out var prior);
            if(part.Count==0||part==local||prior is {Complete:true}&&prior.Matches(part,local)){skipped++;continue;}
            string after=prior is {Complete:false}&&prior.Matches(part,local)?prior.After:"";
            if(after!=""&&(!MeshGraphItem.IsHash(after)||DesktopKnowledgeManifest.Bucket(after)!=bucket))after="";
            while(true)
            {
                Require(peer,ct);
                int target=Math.Min(profile.Budget().Records,ArchiveSessionItems-transferred);
                if(wanted.Count>=target||windowIds>=16384){await Flush();target=Math.Min(profile.Budget().Records,ArchiveSessionItems-transferred);}
                if(inspected>=ArchiveSessionIds||target<=0){complete=false;break;}
                int limit=Math.Min(ArchivePage,Math.Min(ArchiveSessionIds-inspected,target-wanted.Count));
                await channel.SendAsync(new JsonObject{["op"]="inventory",["bucket"]=bucket,["root"]=part.Root,["after"]=after,["limit"]=limit},ct);
                var reply=await channel.ReadAsync(ct);Expect(reply,"inventory");
                if(reply["bucket"]?.GetValue<int>()!=bucket||S(reply,"root")!=part.Root||reply["total"]?.GetValue<int>()!=part.Count||
                    reply["ids"] is not JsonArray entries||entries.Count>limit)throw new InvalidDataException("Invalid partition inventory.");
                bool more=B(reply,"more");if(more&&entries.Count==0)throw new InvalidDataException("Invalid partition continuation.");
                var ids=new List<string>();string previous=after;
                foreach(var entry in entries)
                {
                    string id=entry?.GetValue<string>()??"";
                    if(DesktopKnowledgeManifest.Bucket(id)!=bucket||string.CompareOrdinal(id,previous)<=0)throw new InvalidDataException("Invalid partition order.");
                    ids.Add(id);previous=id;if(!held.Has(id))wanted.Add(id);
                }
                scans.Add(new(bucket,part,ids,!more));inspected+=ids.Count;windowIds+=ids.Count;after=previous;
                if(!more)break;
            }
        }
        if(scans.Count>0)await Flush();
        await channel.SendAsync(new JsonObject{["op"]="release",["more_pending"]=!complete},ct);
        int served=0,pages=0;bool peerMore=false;
        while(true)
        {
            Require(peer,ct);
            var request=await channel.ReadAsync(ct);string op=S(request,"op");
            if(op=="release"){peerMore=B(request,"more_pending");break;}
            if(op=="manifest")
            {
                if(++pages>ArchiveSessionIds+65)throw new InvalidDataException("Manifest budget exceeded.");
                await channel.SendAsync(new JsonObject{["op"]="manifest",["parts"]=offeredManifest.Json()},ct);
            }
            else if(op=="inventory")
            {
                if(++pages>ArchiveSessionIds+65)throw new InvalidDataException("Inventory budget exceeded.");
                int bucket=request["bucket"]!.GetValue<int>(),limit=request["limit"]?.GetValue<int>()??ArchivePage;
                var part=offeredManifest.Get(bucket);string after=S(request,"after");
                if(limit<1||limit>ArchivePage||S(request,"root")!=part.Root)throw new InvalidDataException("Invalid partition request.");
                var next=offeredManifest.After(bucket,after).Take(limit+1).ToArray();
                await channel.SendAsync(new JsonObject{["op"]="inventory",["bucket"]=bucket,["root"]=part.Root,["total"]=part.Count,
                    ["more"]=next.Length>limit,["ids"]=new JsonArray(next.Take(limit).Select(id=>(JsonNode?)JsonValue.Create(id)).ToArray())},ct);
            }
            else if(op=="want_archive")
            {
                int count=request["count"]!.GetValue<int>(),raw=request["raw_limit"]!.GetValue<int>(),wire=request["wire_limit"]!.GetValue<int>();
                if(count<1||count>DesktopKnowledgeArchive.MaxItems||count>ArchiveSessionItems-served)throw new InvalidDataException("Invalid archive request.");
                DesktopKnowledgeArchive.Limits(raw,wire);
                var ids=DesktopKnowledgeArchive.DecodeIds(await DesktopKnowledgeArchive.ReadBytes(channel,count*32,DesktopKnowledgeArchive.MaxItems*32,ct),count);
                var items=ids.Select(id=>catalog.TryGetValue(id,out var receipt)?DesktopPublicItem.Parse(receipt.Item):throw new InvalidDataException("Record not offered.")).ToList();
                var archive=DesktopKnowledgeArchive.Pack(items,raw,wire);
                await channel.SendAsync(new JsonObject{["op"]="archive",["codec"]=archive.Codec,["sha256"]=archive.Sha,["raw_size"]=archive.RawSize,["wire_size"]=archive.Bytes.Length,["count"]=archive.Count},ct);
                await DesktopKnowledgeArchive.SendBytes(channel,archive.Bytes,ct);
                var ack=await channel.ReadAsync(ct);Expect(ack,"archive_ack");
                if(S(ack,"sha256")!=archive.Sha||ack["count"]?.GetValue<int>()!=archive.Count)throw new InvalidDataException("Invalid archive receipt.");
                served+=archive.Count;
            }
            else throw new InvalidDataException("Unsupported archive operation.");
        }
        Update(peer,p=>p with{Status=complete?"Manifest current · "+skipped+" partitions skipped":"Archive transfer pending",LastSeen=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()});
        if(inserted>0)Console.WriteLine("ROOTS PUBLIC AXON: "+peer[..12]+" · "+transferred+" archive records received · "+inserted+" Tree fragments added.");
        return (inserted,!complete||peerMore);
    }
}
