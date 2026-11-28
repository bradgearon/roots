using System.Globalization;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Wds.Roots.Tree;

namespace Wds.Roots.Host;

/// <summary>External content envelope. Main Tree storage is an isolated
/// reference projection; foreign branch definitions do not become commands.</summary>
internal sealed record DesktopPublicItem(string Id,string Language,string Topic,string Text,string Link,MeshGraphItem? Graph,JsonElement Envelope)
{
    internal static readonly JsonSerializerOptions WireOptions=new(JsonSerializerDefaults.Web){Encoder=JavaScriptEncoder.UnsafeRelaxedJsonEscaping};
    static string Field(string s)=>Encoding.UTF8.GetByteCount(s).ToString(CultureInfo.InvariantCulture)+":"+s;
    public static DesktopPublicItem Parse(JsonElement element)
    {
        string S(string name)=>element.GetProperty(name).GetString()??throw new InvalidDataException("Missing publication field.");
        if(element.TryGetProperty("kind",out _))
        {
            var g=MeshGraphItem.Parse(element);return new(g.Id,g.Language,g.Topic,g.Text,g.Link,g,element.Clone());
        }
        string id=S("id"),lang=S("language"),topic=S("topic"),text=S("text"),link=S("link");
        if(!Regex.IsMatch(lang,"^[a-z]{2,3}$")||topic.Length is <1 or >120||text.Length is <1 or >3000||
            topic.Contains('\0')||text.Contains('\0')||topic!=MeshGraphItem.JavaTrim(topic)||text!=MeshGraphItem.JavaTrim(text)||
            (link!=""&&MeshGraphItem.CanonicalLink(link)!=link))throw new InvalidDataException("Invalid publication.");
        string hash=MeshGraphItem.Hash("roots.mesh.item.v1\n"+Field(lang)+Field(topic)+Field(text)+Field(link));
        if(id!=hash)throw new InvalidDataException("Publication hash mismatch.");
        return new(id,lang,topic,text,link,null,element.Clone());
    }
    public JsonObject Wire()=>JsonNode.Parse(Envelope.GetRawText())!.AsObject();
    public IReadOnlyList<Observation> Rows(string peer,long at)
    {
        if(!MeshGraphItem.IsHash(peer)||at<0)throw new ArgumentException("Invalid external peer.");
        var rows=new List<Observation>();
        string key="";
        void Add(string source,string value,string lang="",long? observed=null)=>rows.Add(new(source,key,value,lang,observed??at));
        if(Graph is null)
        {
            key="local:mesh:"+Id;Add("record.mesh.body",Text,Language);Add("record.mesh.topic",Topic);
            if(Link!="")Add("record.mesh.link",Link);
            string offer=JsonSerializer.Serialize(Envelope,WireOptions);
            if(Encoding.UTF8.GetByteCount(offer)>16384)throw new InvalidDataException("Publication too large.");
            Add("record.mesh.offer",offer);
        }
        else
        {
            var g=Graph;string identity=g.Kind=="fragment"&&g.Key!=""?"group:"+g.Key:"item:"+Id;
            key="local:mesh:graph:"+MeshGraphItem.Hash("roots.external.group.v1\n"+identity);
            string role=g.Source[(g.Source.LastIndexOf('.')+1)..];
            string mapped=g.Kind=="branch"||g.Key==""?"record.mesh.body":
                Regex.IsMatch(g.Source,@"^record\.(x|mesh)\.[a-z]{2,3}\.(translation|localized)$")&&Regex.IsMatch(g.ObservedLanguage,"^[a-z]{2,3}$")?
                    "record.mesh."+g.ObservedLanguage+"."+role:
                g.Source.StartsWith("record.",StringComparison.Ordinal)&&role=="body"?"record.mesh.body":
                (role is "link" or "link-confirmed")&&MeshGraphItem.CanonicalLink(Text)!=""?"record.mesh.link":"record.mesh.graph-reference";
            Add(mapped,Text,g.ObservedLanguage,g.At);
            if(g.Kind=="branch")Add("record.mesh.topic","External branch · "+g.Key);
            Add("record.mesh.graph-id",Id);Add("record.mesh.graph-source",g.Source);
        }
        Add("record.mesh.external-peer",peer);Add("record.mesh.trust","External reference · unverified claims");return rows;
    }
}
