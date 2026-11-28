using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Wds.Roots.Tree;

namespace Wds.Roots.Host;

internal sealed partial class DesktopPublicAxonService
{
    const int MaxFootprints=4096,MaxFootprintLinks=8,MaxFootprintPage=9,MaxFootprintBytes=2048;
    const string FootprintDomain="roots.public.footprint.v1\n";
    internal sealed record Footprint(string Id,long Revision,string[] Links,string Wire);
    readonly SortedDictionary<string,Footprint> _footprints=new(StringComparer.Ordinal);
    bool _footprintsLoaded;
    string FootprintDirectory=>Path.Combine(_directory,"public-footprints-v1");

    static byte[] FootprintBody(string id,long revision,IEnumerable<string> links)
        =>Encoding.ASCII.GetBytes(FootprintDomain+id+"\n"+revision.ToString(System.Globalization.CultureInfo.InvariantCulture)+"\n"+string.Concat(links.Select(x=>x+"\n")));
    static byte[] FootprintBase64(string value,int max)
    {
        if(value.Length>((max+2)/3)*4)throw new InvalidDataException("Oversized footprint encoding.");
        byte[] data=Convert.FromBase64String(value);
        if(data.Length==0||data.Length>max||Convert.ToBase64String(data)!=value)throw new InvalidDataException("Invalid footprint encoding.");
        return data;
    }
    internal static Footprint VerifyFootprint(string wire,long now)
    {
        if(Encoding.UTF8.GetByteCount(wire)>MaxFootprintBytes)throw new InvalidDataException("Oversized footprint.");
        using var doc=JsonDocument.Parse(wire,new JsonDocumentOptions{MaxDepth=5});
        var row=doc.RootElement;
        if(row.ValueKind!=JsonValueKind.Object||row.EnumerateObject().Count()!=5||
            row.EnumerateObject().Select(p=>p.Name).Distinct(StringComparer.Ordinal).Count()!=5)throw new InvalidDataException("Invalid footprint fields.");
        string id=row.GetProperty("id").GetString()??"";
        long revision=row.GetProperty("revision").GetInt64();
        if(!MeshGraphItem.IsHash(id)||revision<0||revision>now+300000L)throw new InvalidDataException("Invalid footprint identity or revision.");
        var array=row.GetProperty("links");
        if(array.GetArrayLength()>MaxFootprintLinks)throw new InvalidDataException("Too many footprint links.");
        var links=new List<string>();string previous="";
        foreach(var item in array.EnumerateArray())
        {
            string link=item.GetString()??"";
            if(!MeshGraphItem.IsHash(link)||link==id||string.CompareOrdinal(link,previous)<=0)throw new InvalidDataException("Invalid footprint links.");
            links.Add(link);previous=link;
        }
        byte[] encoded=FootprintBase64(row.GetProperty("key").GetString()??"",256);
        using var key=ECDsa.Create();key.ImportSubjectPublicKeyInfo(encoded,out int read);
        var parameters=key.ExportParameters(false);
        if(read!=encoded.Length||parameters.Curve.Oid.Value!="1.2.840.10045.3.1.7"||
            parameters.Q.X?.Length!=32||parameters.Q.Y?.Length!=32||
            !key.ExportSubjectPublicKeyInfo().AsSpan().SequenceEqual(encoded)||DesktopRepChannel.Fingerprint(key)!=id)
            throw new CryptographicException("Footprint owner mismatch.");
        byte[] signature=FootprintBase64(row.GetProperty("sig").GetString()??"",80);
        if(!key.VerifyData(FootprintBody(id,revision,links),signature,HashAlgorithmName.SHA256,DSASignatureFormat.Rfc3279DerSequence))
            throw new CryptographicException("Invalid footprint signature.");
        return new(id,revision,links.ToArray(),wire);
    }
    internal static Footprint SignFootprint(ECDsa key,IEnumerable<string> candidates,long now)
    {
        string id=DesktopRepChannel.Fingerprint(key);
        string[] links=candidates.Where(x=>MeshGraphItem.IsHash(x)&&x!=id).Distinct(StringComparer.Ordinal)
            .Take(MaxFootprintLinks).OrderBy(x=>x,StringComparer.Ordinal).ToArray();
        var row=new JsonObject{
            ["id"]=id,["revision"]=now,
            ["links"]=new JsonArray(links.Select(x=>(JsonNode?)JsonValue.Create(x)).ToArray()),
            ["key"]=Convert.ToBase64String(key.ExportSubjectPublicKeyInfo()),
            ["sig"]=Convert.ToBase64String(key.SignData(FootprintBody(id,now,links),HashAlgorithmName.SHA256,DSASignatureFormat.Rfc3279DerSequence))
        };
        return VerifyFootprint(row.ToJsonString(),now);
    }
    void LoadFootprints()
    {
        if(_footprintsLoaded)return;
        if(Directory.Exists(FootprintDirectory))
        {
            long now=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            foreach(string path in Directory.EnumerateFiles(FootprintDirectory,"*.json").OrderBy(p=>p,StringComparer.Ordinal))
            {
                if(_footprints.Count>=MaxFootprints)break;
                try
                {
                    if(new FileInfo(path).Length>MaxFootprintBytes)continue;
                    var card=VerifyFootprint(File.ReadAllText(path),now);
                    if(card.Id==Path.GetFileNameWithoutExtension(path)&&card.Id!=_id)_footprints[card.Id]=card;
                }
                catch(Exception){/* skip damaged public cache entries */}
            }
        }
        _footprintsLoaded=true;
    }
    void RetainFootprint(Footprint card)
    {
        if(_footprints.TryGetValue(card.Id,out var old)&&old.Revision>=card.Revision)return;
        if(old==null&&_footprints.Count>=MaxFootprints)return;
        Directory.CreateDirectory(FootprintDirectory);
        Atomic(Path.Combine(FootprintDirectory,card.Id+".json"),card.Wire);
        _footprints[card.Id]=card;
    }
    JsonArray FootprintOffer(ECDsa key,string peer)
    {
        LoadFootprints();
        string[] links;
        lock(_gate)links=_peers.Values.Where(p=>!p.Blocked).OrderByDescending(p=>p.LastSeen).ThenBy(p=>p.Id,StringComparer.Ordinal).Select(p=>p.Id).ToArray();
        var own=SignFootprint(key,links,DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        var page=new JsonArray(JsonNode.Parse(own.Wire));
        foreach(var card in _footprints.Values.Where(c=>c.Id!=peer&&c.Id!=_id&&!Blocked(c.Id))
            .OrderBy(_=>Random.Shared.Next()).Take(MaxFootprintPage-1))
            page.Add(JsonNode.Parse(card.Wire));
        return page;
    }
    void ReceiveFootprints(JsonObject response,string peer)
    {
        if(response["footprints_v1"] is not JsonArray page||page.Count<1||page.Count>MaxFootprintPage||Blocked(peer))return;
        long now=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        Footprint direct;
        try{direct=VerifyFootprint(page[0]?.ToJsonString()??"",now);}catch(Exception){return;}
        if(direct.Id!=peer||direct.Id==_id)return;
        LoadFootprints();RetainFootprint(direct);
        foreach(var entry in page.Skip(1))
        {
            Footprint card;
            try{card=VerifyFootprint(entry?.ToJsonString()??"",now);}catch(Exception){continue;}
            if(card.Id!=_id&&!Blocked(card.Id))RetainFootprint(card);
        }
    }
}
