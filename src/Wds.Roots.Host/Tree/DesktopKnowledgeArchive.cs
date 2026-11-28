using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Wds.Roots.Tree;

namespace Wds.Roots.Host;

internal static class DesktopKnowledgeArchive
{
    public const int Frame=65536, Initial=4096, MaxItems=16384, MaxRaw=16*1024*1024, MaxWire=8*1024*1024;
    internal sealed record Packed(byte[] Bytes,string Codec,string Sha,int RawSize,int Count);
    public static string Hash(byte[] bytes)=>Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
    public static void Limits(int raw,int wire)
    {
        if(raw<Frame||raw>MaxRaw||wire<Frame||wire>MaxWire)throw new InvalidDataException("Invalid archive byte limits.");
    }
    public static Packed Pack(IReadOnlyList<DesktopPublicItem> items,int rawLimit,int wireLimit)
    {
        Limits(rawLimit,wireLimit);
        if(items.Count<1||items.Count>MaxItems)throw new InvalidDataException("Invalid archive count.");
        using var ms=new MemoryStream();ms.WriteByte((byte)'[');
        var ends=new List<int>();
        foreach(var item in items)
        {
            byte[] row=JsonSerializer.SerializeToUtf8Bytes(item.Envelope,DesktopPublicItem.WireOptions);
            if(ms.Length+row.Length+2>rawLimit)break;
            if(ends.Count>0)ms.WriteByte((byte)',');
            ms.Write(row);ends.Add((int)ms.Length);
        }
        byte[] prefix=ms.ToArray();
        for(int n=ends.Count;n>=1;)
        {
            byte[] raw=new byte[ends[n-1]+1];Array.Copy(prefix,raw,raw.Length-1);raw[^1]=(byte)']';
            using var zipped=new MemoryStream();
            using(var gzip=new GZipStream(zipped,CompressionLevel.Fastest,true))gzip.Write(raw);
            byte[] compressed=zipped.ToArray();bool use=compressed.Length<raw.Length;
            byte[] wire=use?compressed:raw;
            if(wire.Length<=wireLimit)return new(wire,use?"gzip":"identity",Hash(raw),raw.Length,n);
            if(n==1)break;
            n=Math.Max(1,Math.Min(n-1,(int)((long)n*wireLimit/wire.Length*0.9)));
        }
        throw new InvalidDataException("Record does not fit archive.");
    }
    public static List<DesktopPublicItem> Unpack(byte[] wire,string codec,string sha,int rawSize,IReadOnlyList<string> requested,int count,int rawLimit,int wireLimit)
    {
        Limits(rawLimit,wireLimit);
        if(wire.Length<1||wire.Length>wireLimit||rawSize<2||rawSize>rawLimit||!MeshGraphItem.IsHash(sha)||count<1||count>requested.Count||count>MaxItems)
            throw new InvalidDataException("Invalid archive header.");
        byte[] raw;
        if(codec=="identity")raw=wire;
        else if(codec=="gzip")
        {
            raw=new byte[rawSize];using var input=new MemoryStream(wire,false);using var gzip=new GZipStream(input,CompressionMode.Decompress);
            gzip.ReadExactly(raw);if(gzip.ReadByte()!=-1)throw new InvalidDataException("Archive exceeds declared size.");
        }
        else throw new InvalidDataException("Unknown archive codec.");
        if(raw.Length!=rawSize||Hash(raw)!=sha)throw new InvalidDataException("Archive digest mismatch.");
        using var doc=JsonDocument.Parse(raw);
        if(doc.RootElement.ValueKind!=JsonValueKind.Array||doc.RootElement.GetArrayLength()!=count)throw new InvalidDataException("Archive member count.");
        var items=new List<DesktopPublicItem>(count);int i=0;
        foreach(var row in doc.RootElement.EnumerateArray())
        {
            var item=DesktopPublicItem.Parse(row);
            if(item.Id!=requested[i++])throw new InvalidDataException("Unexpected archive member.");
            items.Add(item);
        }
        return items;
    }
    public static byte[] EncodeIds(IReadOnlyList<string> ids)
    {
        if(ids.Count<1||ids.Count>MaxItems||ids.Distinct(StringComparer.Ordinal).Count()!=ids.Count)throw new InvalidDataException("Invalid archive IDs.");
        byte[] bytes=new byte[ids.Count*32];
        for(int i=0;i<ids.Count;i++){if(!MeshGraphItem.IsHash(ids[i]))throw new InvalidDataException("Invalid ID.");Convert.FromHexString(ids[i]).CopyTo(bytes,i*32);}
        return bytes;
    }
    public static List<string> DecodeIds(byte[] bytes,int count)
    {
        if(count<1||count>MaxItems||bytes.Length!=count*32)throw new InvalidDataException("Invalid archive request size.");
        var ids=new List<string>(count);
        for(int i=0;i<count;i++)ids.Add(Convert.ToHexString(bytes.AsSpan(i*32,32)).ToLowerInvariant());
        if(ids.Distinct(StringComparer.Ordinal).Count()!=count)throw new InvalidDataException("Duplicate archive IDs.");
        return ids;
    }
    public static async Task SendBytes(DesktopRepChannel channel,byte[] bytes,CancellationToken ct)
    {
        for(int offset=0;offset<bytes.Length;offset+=Frame)
            await channel.SendFrameAsync(bytes.AsSpan(offset,Math.Min(Frame,bytes.Length-offset)).ToArray(),ct);
    }
    public static async Task<byte[]> ReadBytes(DesktopRepChannel channel,int size,int limit,CancellationToken ct)
    {
        if(size<1||size>limit)throw new InvalidDataException("Invalid archive wire size.");
        var data=new byte[size];int offset=0;
        while(offset<size)
        {
            var frame=await channel.ReadFrameAsync(ct);
            if(frame.Length!=Math.Min(Frame,size-offset))throw new InvalidDataException("Invalid archive frame.");
            frame.CopyTo(data,offset);offset+=frame.Length;
        }
        return data;
    }
}
internal sealed class DesktopKnowledgeManifest
{
    public const int Buckets=64;
    internal sealed record Part(string Root,int Count);
    internal sealed record Checkpoint(string Remote,string Local,string After,bool Complete)
    {public bool Matches(Part remote,Part local)=>Remote==remote.Root&&Local==local.Root;}
    readonly SortedSet<string>[] _parts=Enumerable.Range(0,Buckets).Select(_=>new SortedSet<string>(StringComparer.Ordinal)).ToArray();
    public DesktopKnowledgeManifest(IEnumerable<string> ids){foreach(string id in ids)Add(id);}
    public static int Bucket(string id)
    {
        if(!MeshGraphItem.IsHash(id))throw new InvalidDataException("Invalid knowledge identity.");
        return Convert.ToInt32(id[..2],16)>>2;
    }
    public void Add(string id)=>_parts[Bucket(id)].Add(id);
    public bool Has(string id)=>_parts[Bucket(id)].Contains(id);
    public Part Get(int bucket)
    {
        if(bucket<0||bucket>=Buckets)throw new InvalidDataException("Invalid manifest bucket.");
        var ids=_parts[bucket];using var sha=IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        foreach(string id in ids)sha.AppendData(Encoding.ASCII.GetBytes(id));
        return new(Convert.ToHexString(sha.GetHashAndReset()).ToLowerInvariant(),ids.Count);
    }
    public IEnumerable<string> After(int bucket,string after)
    {
        if(bucket<0||bucket>=Buckets||(after!=""&&Bucket(after)!=bucket))throw new InvalidDataException("Invalid manifest cursor.");
        return _parts[bucket].Where(id=>string.CompareOrdinal(id,after)>0);
    }
    public JsonArray Json()=>new(Enumerable.Range(0,Buckets).Select(i=>{
        var p=Get(i);return (JsonNode?)new JsonObject{["root"]=p.Root,["count"]=p.Count};
    }).ToArray());
    public static Part[] Parse(JsonArray array)
    {
        if(array.Count!=Buckets)throw new InvalidDataException("Invalid manifest.");
        long total=0;var result=new Part[Buckets];string empty=DesktopKnowledgeArchive.Hash([]);
        for(int i=0;i<Buckets;i++)
        {
            string root=array[i]?["root"]?.GetValue<string>()??"";
            int count=array[i]?["count"]?.GetValue<int>()??-1;
            if(!MeshGraphItem.IsHash(root)||count<0||(count==0&&root!=empty)||(total+=count)>4000000)throw new InvalidDataException("Invalid manifest part.");
            result[i]=new(root,count);
        }
        return result;
    }
}
internal sealed record DesktopArchiveProfile(int Previous=4096,double MillisPerRecord=0,double RawPerRecord=0,double WirePerRecord=0)
{
    public (int Records,int Raw,int Wire,int Estimate) Budget()
    {
        int raw=(int)Math.Clamp(GC.GetGCMemoryInfo().TotalAvailableMemoryBytes/12,65536,DesktopKnowledgeArchive.MaxRaw);
        int wire=Math.Min(raw,DesktopKnowledgeArchive.MaxWire);
        double ms=MillisPerRecord,rb=RawPerRecord,wb=WirePerRecord;
        int count=ms>0&&rb>0&&wb>0&&double.IsFinite(ms)&&double.IsFinite(rb)&&double.IsFinite(wb)?
            Math.Clamp((int)Math.Min(Math.Min(8000/ms,Math.Min(raw/rb,wire/wb)),Math.Clamp(Previous,64,16384)*2.0),64,16384):4096;
        return (count,raw,wire,ms>0&&double.IsFinite(ms)?(int)Math.Min(int.MaxValue,Math.Ceiling(count*ms)):-1);
    }
    static double Blend(double old,double next)=>old>0&&double.IsFinite(old)?old*.65+next*.35:next;
    public DesktopArchiveProfile Observe(int count,int raw,int wire,long elapsed)
    {
        var next=new DesktopArchiveProfile(Previous,Blend(MillisPerRecord,(double)Math.Max(1,elapsed)/count),Blend(RawPerRecord,(double)raw/count),Blend(WirePerRecord,(double)wire/count));
        return next with{Previous=next.Budget().Records};
    }
}
