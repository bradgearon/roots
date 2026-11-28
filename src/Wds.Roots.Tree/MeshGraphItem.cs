using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Wds.Roots.Tree;

/// <summary>The Android Roots Mesh v1 graph contract, not a second Tree format.
/// IDs cover native fragment uniqueness; payload hashes additionally cover provenance.</summary>
public sealed record MeshGraphItem(string Id, string Kind, string Source, string Key,
    string ObservedLanguage, long At, string Text, string Language, string Topic, string Link, string Digest)
{
    public static bool IsHash(string? s) => s is { Length:64 } && Regex.IsMatch(s, "^[0-9a-f]{64}$", RegexOptions.CultureInvariant);
    public static string Hash(string s) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(s))).ToLowerInvariant();
    static string Field(string s) => Encoding.UTF8.GetByteCount(s).ToString(CultureInfo.InvariantCulture) + ":" + s;
    static bool Bounded(string? s, int max, bool required = false) =>
        s != null && (!required || s.Length > 0) && !s.Contains('\0') && Encoding.UTF8.GetByteCount(s) <= max;
    public static string JavaTrim(string s)
    {
        int start = 0, end = s.Length;
        while (start < end && s[start] <= 32) start++;
        while (end > start && s[end-1] <= 32) end--;
        return s[start..end];
    }
    public static string Normalize(string s) => JavaTrim(Regex.Replace(JavaTrim(s), @"[ \t\r\n\f\v]+", " "));
    public static string Safe(string s, int max)
    {
        var b = new StringBuilder();
        foreach (var rune in s.EnumerateRunes())
        {
            int c = rune.Value;
            string text = c == '<' ? "‹" : c == '>' ? "›" :
                c <= 31 || c is >= 127 and <= 159 || Rune.GetUnicodeCategory(rune) == UnicodeCategory.Format ? " " : rune.ToString();
            if (b.Length + text.Length > max) break;
            b.Append(text);
        }
        return JavaTrim(b.ToString());
    }
    public static string CanonicalLink(string? raw)
    {
        if (string.IsNullOrEmpty(raw)) return "";
        // Use the raw path rather than URI path normalization. Match XPostLink.java.
        var m = Regex.Match(JavaTrim(raw), @"^https://(?:www\.)?(?:x\.com|twitter\.com)(/(?:(?:[A-Za-z0-9_]{1,25}/status)|i/(?:web/)?status)/[1-9][0-9]{0,24})/?(?:[?#][^\s]*)?$",
            RegexOptions.CultureInvariant);
        // Java accepts case-insensitive scheme and host, but not path roles.
        if (!m.Success)
        {
            var host = Regex.Match(JavaTrim(raw), @"^(https://(?:www\.)?(?:x\.com|twitter\.com))(/.*)$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
            if (host.Success && host.Groups[1].Value != host.Groups[1].Value.ToLowerInvariant())
                return CanonicalLink(host.Groups[1].Value.ToLowerInvariant() + host.Groups[2].Value);
            return "";
        }
        return "https://x.com" + m.Groups[1].Value;
    }
    public static MeshGraphItem From(Fragment f) => Create("fragment", f.Source, f.ExternalId, f.Language, f.ObservedMs, f.Body);
    public static MeshGraphItem From(BranchAgent a) => Create("branch", "branch", a.Name, "", 0, a.Term);
    public static MeshGraphItem Create(string kind, string source, string key, string observedLanguage, long at, string text)
    {
        bool branch = kind == "branch";
        if (branch ? source != "branch" || observedLanguage != "" || at != 0 || !Bounded(key,160,true) || !Bounded(text,512,true)
            : kind != "fragment" || !Bounded(source,256,true) || !Bounded(key,512) || !Bounded(text,16384,true) || !Bounded(observedLanguage,64) || at < 0)
            throw new ArgumentException("Invalid graph item.");
        string id = Hash("roots.mesh.graph.v1\n" + Field(kind) + Field(source) + Field(key) + Field(text));
        string language = observedLanguage.ToLowerInvariant();
        if (!Regex.IsMatch(language, "^[a-z]{2,3}$")) language = "und";
        string topic = Safe(branch ? key+" "+text : text,120);
        if(topic.Length==0) topic=Safe(source,120);
        if(topic.Length==0) topic="Knowledge";
        string link = source.EndsWith(".link",StringComparison.Ordinal)||source.EndsWith(".link-confirmed",StringComparison.Ordinal) ? CanonicalLink(text) : "";
        string digest = Hash("roots.mesh.graph.payload.v1\n"+Field(id)+Field(kind)+Field(source)+Field(key)+Field(text)+Field(observedLanguage)+Field(at.ToString(CultureInfo.InvariantCulture)));
        return new(id,kind,source,key,observedLanguage,at,text,language,topic,link,digest);
    }
    public object Wire() => new {id=Id,kind=Kind,source=Source,external_id=Key,observed_language=ObservedLanguage,
        observed_ms=At,text=Text,language=Language,topic=Topic,link=Link,payload_sha256=Digest};
    public static MeshGraphItem Parse(JsonElement e)
    {
        string S(string name) => e.GetProperty(name).GetString() ?? throw new InvalidDataException("Missing graph field.");
        var item=Create(S("kind"),S("source"),S("external_id"),S("observed_language"),e.GetProperty("observed_ms").GetInt64(),S("text"));
        if(item.Id!=S("id")||item.Digest!=S("payload_sha256")||item.Language!=S("language")||item.Topic!=S("topic")||item.Link!=S("link"))
            throw new InvalidDataException("Graph payload mismatch.");
        return item;
    }
    public bool Matches(string topic) => Text.Contains(topic,StringComparison.OrdinalIgnoreCase)||Topic.Contains(topic,StringComparison.OrdinalIgnoreCase);
    public int RetrievalRank => Source.EndsWith(".understanding")?0:
        Source.EndsWith(".body")||Source.EndsWith(".translation")||Source.EndsWith(".localized")?1:
        Source.StartsWith("x.android.visible.")||Source.StartsWith("x.web.visible.")?2:3;
    public static bool ValidTopic(string s) => s.Length is >0 and <=80 && s!="*" && !s.Any(c=>char.IsControl(c)||c is '<' or '>' or '|');
}
