using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Wds.Roots.Tree;

namespace Wds.Roots.Host;

internal static class DesktopPublicFootprintCodec
{
    internal const int MaxFootprintLinks=8,MaxFootprintPage=9,MaxFootprintBytes=2048;
    const string FootprintDomain="roots.public.footprint.v1\n";
    internal sealed record Footprint(string Id,long Revision,string[] Links,string Wire);
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
}
