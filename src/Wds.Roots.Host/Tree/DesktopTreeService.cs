using System.Text;
using System.Text.RegularExpressions;
using Wds.Roots.Tree;

namespace Wds.Roots.Host;

/// <summary>Browser observations are only an adapter to the native Roots Tree.</summary>
internal sealed class DesktopTreeService : IDisposable
{
    internal sealed record BrowserPost(string? Text, string? Author, string? Handle, string? Url, string? Language,
        string? Time, string? ReplyCount, string? RepostCount, string? LikeCount, string? ViewCount,
        string[]? Media, string[]? Context, string[]? Poll, string? Translation, string? Target, bool Localized = false);
    private readonly NativeTree _tree;
    private readonly string _directory;
    private readonly object _gate = new();
    private long _catalogRevision = -1;
    private SortedDictionary<string,MeshGraphItem> _catalog = new(StringComparer.Ordinal);
    private bool _capture;
    public DesktopTreeService(string directory)
    {
        _directory=directory; Directory.CreateDirectory(directory);
        _tree=new NativeTree(Path.Combine(directory,"tree.db"));
        _capture=File.Exists(Path.Combine(directory,"capture.enabled"));
    }
    public bool Capture { get { lock(_gate) return _capture; } }
    public void SetCapture(bool enabled)
    {
        lock(_gate)
        {
            string marker=Path.Combine(_directory,"capture.enabled");
            if(enabled) File.WriteAllText(marker,"1"); else File.Delete(marker);
            _capture=enabled;
        }
    }
    public long Count { get { lock(_gate) return _tree.Count(); } }
    public long Revision { get { lock(_gate) return _tree.Revision(); } }
    public IReadOnlyList<Fragment> Feed(string term,long before,uint limit)
    { lock(_gate) return _tree.Query(term,0,before,limit); }
    public IReadOnlyList<RecordMember> Records(string term,long before,uint limit)
    { lock(_gate) return _tree.Records(term,0,before,limit); }
    public IReadOnlyList<BranchAgent> Branches() { lock(_gate) return _tree.Agents(); }
    public long AddBranch(string name,string term) { lock(_gate) return _tree.CreateAgent(name,term); }
    public void RemoveBranch(long id) { lock(_gate) _tree.RemoveAgent(id); }
    static string Lang(string? code)
    {
        string result=(code??"").ToLowerInvariant();
        return Regex.IsMatch(result,"^[a-z]{2,3}$")&&result!="und"?result:"";
    }
    static string Value(string? value) => MeshGraphItem.JavaTrim((value??"").Replace("\0",""));
    static IEnumerable<string> Parts(string? value)
    {
        string text=Value(value);
        for(int i=0;i<text.Length;)
        {
            int end=Math.Min(i+4000,text.Length);
            if(end<text.Length&&char.IsHighSurrogate(text[end-1])&&char.IsLowSurrogate(text[end]))end--;
            string part=text[i..end]; if(!string.IsNullOrWhiteSpace(part))yield return part;
            i=end;
        }
    }
    public static string PostKey(string handle,string body) => "local:x:"+MeshGraphItem.Hash(
        MeshGraphItem.Normalize(handle.Length+":"+handle+":"+MeshGraphItem.Normalize(body)));
    public ulong Observe(BrowserPost post)
    {
        lock(_gate)
        {
            if(!_capture)throw new InvalidOperationException("Capture is off.");
            string body=MeshGraphItem.Normalize(Value(post.Text));
            if(body.Length==0||body.Length>12000)throw new ArgumentException("Invalid post body.");
            string handle=Value(post.Handle);
            bool identified=Regex.IsMatch(handle,"^@[A-Za-z0-9_]{1,15}$");
            string url=MeshGraphItem.CanonicalLink(post.Url);
            if(url!=""&&identified)
            {
                string owner=url.Split('/')[3];
                if(owner!="i"&&!owner.Equals(handle[1..],StringComparison.OrdinalIgnoreCase))url="";
            }
            // Match the Android provisional identity even when a genuine
            // status URL is available. Never invent a status ID from a hash.
            string key=identified?PostKey(handle,body):"";
            string lang=Lang(post.Language);
            long now=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            var rows=new List<Observation>();
            void Add(string role,string? value,string language="")
            {
                foreach(string part in Parts(value))
                {
                    if(rows.Count>=240)break;
                    rows.Add(new Observation(key==""?"x.web.visible.fragment":"record.x."+role,key,part,language,now));
                }
            }
            Add("body",body,lang);Add("author",post.Author);Add("handle",identified?handle:null);
            Add("link",url);Add("time",post.Time);
            Add("reply-count",post.ReplyCount);Add("repost-count",post.RepostCount);Add("like-count",post.LikeCount);Add("view-count",post.ViewCount);
            foreach(var value in (post.Media??[]).Take(12))Add("media-description",value);
            foreach(var value in (post.Context??[]).Take(16))Add("context",value);
            foreach(var value in (post.Poll??[]).Take(8))Add("poll-option",value);
            string target=Lang(post.Target);
            if(key!=""&&target!=""&&!string.IsNullOrWhiteSpace(post.Translation)&&post.Translation.Length<=12000)
            {
                if(!post.Localized||MeshGraphItem.Normalize(post.Translation)==body)
                    Add(target+(post.Localized?".localized":".translation"),post.Translation,target);
            }
            return _tree.Observe(rows);
        }
    }
    public SortedDictionary<string,MeshGraphItem> Catalog()
    {
        lock(_gate)
        {
            long revision=_tree.Revision();
            if(revision==_catalogRevision)return _catalog;
            var items=new SortedDictionary<string,MeshGraphItem>(StringComparer.Ordinal);
            long before=0;
            while(true)
            {
                var page=_tree.Query("",0,before,100);
                if(page.Count==0)break;
                foreach(var fragment in page)
                {
                    var item=MeshGraphItem.From(fragment);items.TryAdd(item.Id,item);
                }
                long next=page[^1].Sequence;
                if(next<=0||(before!=0&&next>=before))throw new InvalidDataException("Invalid Tree cursor.");
                before=next;if(page.Count<100)break;
            }
            foreach(var branch in _tree.Agents())
            {
                var item=MeshGraphItem.From(branch);items.TryAdd(item.Id,item);
            }
            _catalog=items;_catalogRevision=revision;return items;
        }
    }
    public ulong Import(IReadOnlyList<MeshGraphItem> items)
    {
        lock(_gate)
        {
            ulong count=0;
            var rows=items.Where(i=>i.Kind=="fragment")
                .Select(i=>new Observation(i.Source,i.Key,i.Text,i.ObservedLanguage,i.At)).ToArray();
            if(rows.Length>0)count+=_tree.Observe(rows);
            foreach(var branch in items.Where(i=>i.Kind=="branch"))
            {
                if(_tree.Agents().Any(a=>a.Name==branch.Key&&a.Term==branch.Text))continue;
                _tree.CreateAgent(branch.Key,branch.Text);count++;
            }
            return count;
        }
    }
    public ulong ImportExternal(DesktopPublicItem item,string peer,long receivedAt)
    { lock(_gate) return _tree.Observe(item.Rows(peer,receivedAt)); }
    public object CompileLocalized(string language)
    {
        language=Lang(language);
        if(language=="")throw new ArgumentException("Invalid projection language.");
        lock(_gate)
        {
            string path=Path.Combine(_directory,"tree.gguf");
            string temporary=Path.Combine(_directory,"tree-"+Guid.NewGuid().ToString("N")+".gguf");
            try
            {
                long revision=_tree.CompileLocalized(temporary,language);
                if(revision!=_tree.Revision())throw new InvalidOperationException("Tree changed during compile.");
                if(File.Exists(temporary))File.Move(temporary,path,true);else File.Delete(path);
                return new {revision,language,available=File.Exists(path)};
            }
            finally {File.Delete(temporary);}
        }
    }
    public void Dispose() { lock(_gate) _tree.Dispose(); }
}
