using System.Diagnostics;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Wds.Roots.Tree;

namespace Wds.Roots.Host;

/// <summary>Explicitly authorized extension input into the private Experience
/// Tree. No URL paths, form values, hidden DOM, screenshots or peer routing.
/// The browser lane is available only while the local Surface lease is held.</summary>
internal static class DesktopBrowserExperience
{
    internal sealed record Line(string Text,int[] Bounds,int? Anchor=null,int? Start=null,int? End=null,
        int? PageX=null,int? PageY=null,int? Order=null)
    {
        public bool Positioned=>Anchor.HasValue&&Start.HasValue&&End.HasValue&&PageX.HasValue&&PageY.HasValue&&Order.HasValue;
        public bool ValidPosition()
        {
            if(Anchor==null&&Start==null&&End==null&&PageX==null&&PageY==null&&Order==null)return true;
            return Positioned&&Anchor is >=1 and <=1000000000&&Start is >=0&&End>Start&&End<=1000000000&&
                PageX is >=-1000000000 and <=1000000000&&PageY is >=-1000000000 and <=1000000000&&Order is >=0 and <=1000000000;
        }
        public string OrderKey()=>FormattableString.Invariant($"{(long)PageY!.Value+1000000000:D10}:{(long)PageX!.Value+1000000000:D10}:{Order!.Value:D10}:{Anchor!.Value:D10}:{Start!.Value:D10}");
    }
    internal sealed record Snapshot(string DocumentKey,string Host,Line[] Rows);
    internal sealed record CaptureSession(string Run,string[] Excluded);
    private sealed record Recent(string Signature,long At);
    private static readonly object Gate=new();
    private static readonly Dictionary<string,Recent> Previous=new(StringComparer.Ordinal);
    private static string ActiveRun="";
    private static long LastWrite;
    private static string Hash(string value)=>Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value)));

    [DllImport("user32.dll")] private static extern nint GetForegroundWindow();
    [DllImport("user32.dll")] [return:MarshalAs(UnmanagedType.Bool)] private static extern bool GetWindowDisplayAffinity(nint hwnd,out uint affinity);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(nint hwnd,out uint pid);
    [DllImport("user32.dll")] [return:MarshalAs(UnmanagedType.Bool)] private static extern bool IsWindowVisible(nint hwnd);
    [DllImport("user32.dll")] [return:MarshalAs(UnmanagedType.Bool)] private static extern bool IsIconic(nint hwnd);
    [DllImport("user32.dll",CharSet=CharSet.Unicode)] private static extern int GetClassName(nint hwnd,StringBuilder name,int max);
    [DllImport("user32.dll",SetLastError=true)] private static extern nint OpenInputDesktop(uint flags,bool inherit,uint access);
    [DllImport("user32.dll",CharSet=CharSet.Unicode,SetLastError=true)]
    [return:MarshalAs(UnmanagedType.Bool)] private static extern bool GetUserObjectInformation(nint handle,int index,StringBuilder value,uint length,out uint needed);
    [DllImport("user32.dll")] [return:MarshalAs(UnmanagedType.Bool)] private static extern bool CloseDesktop(nint desktop);

    private static string DirectoryPath()=>Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"Roots","Experience");

    private static bool DesktopUnlocked()
    {
        if(!OperatingSystem.IsWindows())return false;
        nint desktop=OpenInputDesktop(0,false,1); // DESKTOP_READOBJECTS
        if(desktop==0)return false;
        try{
            var name=new StringBuilder(128);
            return GetUserObjectInformation(desktop,2,name,256,out _)&&name.ToString().Equals("Default",StringComparison.OrdinalIgnoreCase);
        }finally{CloseDesktop(desktop);}
    }

    private static CaptureSession? Session()
    {
        var directory=DirectoryPath();
        var path=Path.Combine(directory,"capture.lock");
        if(!File.Exists(path))return null;
        try{
            using var unused=new FileStream(path,FileMode.Open,FileAccess.ReadWrite,FileShare.None);
            return null; // nobody owns the Surface lease
        }catch(IOException e) when((e.HResult&0xffff) is 32 or 33){}
        catch{return null;}
        try{
            var session=JsonSerializer.Deserialize<CaptureSession>(File.ReadAllText(Path.Combine(directory,"capture.session.json")));
            return session!=null&&Guid.TryParse(session.Run,out _)&&session.Excluded is {Length:<=64}?session:null;
        }catch{return null;}
    }

    private static (CaptureSession Session,string Process,nint Window,uint Pid,long Created)? Foreground()
    {
        if(!DesktopUnlocked())return null;
        var session=Session();if(session==null)return null;
        nint window=GetForegroundWindow();
        if(window==0||!IsWindowVisible(window)||IsIconic(window))return null;
        if(GetWindowDisplayAffinity(window,out uint affinity)&&affinity!=0)return null;
        // Verify an actual Chromium top-level window, not an arbitrary
        // background tab claiming to be foreground. Attribution comes from OS.
        var name=new StringBuilder(128);
        if(GetClassName(window,name,name.Capacity)==0||
           !name.ToString().StartsWith("Chrome_WidgetWin_",StringComparison.Ordinal))return null;
        GetWindowThreadProcessId(window,out uint pid);
        if(pid==0)return null;
        try{
            using var process=Process.GetProcessById(checked((int)pid));
            using var self=Process.GetCurrentProcess();
            if(process.SessionId!=self.SessionId)return null;
            string app=process.ProcessName+".exe";
            if(session.Excluded.Contains(app,StringComparer.OrdinalIgnoreCase))return null;
            long created=process.StartTime.ToUniversalTime().ToFileTimeUtc();
            if(GetForegroundWindow()!=window)return null;
            return (session,app,window,pid,created);
        }catch{return null;}
    }

    public static bool Enabled()=>Foreground()!=null;

    public static object Observe(Snapshot? snapshot)
    {
        if(snapshot==null||!Guid.TryParse(snapshot.DocumentKey,out Guid document)||
           string.IsNullOrWhiteSpace(snapshot.Host)||snapshot.Host.Length>253||
           Uri.CheckHostName(snapshot.Host)==UriHostNameType.Unknown||
           snapshot.Rows is not {Length:>=1 and <=80})throw new ArgumentException("Invalid snapshot.");
        int bytes=0;
        foreach(var line in snapshot.Rows){
            if(line==null||string.IsNullOrWhiteSpace(line.Text)||line.Text.Length>1800||
               line.Text.Contains('\0')||line.Bounds is not {Length:4}||
               line.Bounds.Any(n=>n<0||n>32768)||line.Bounds[0]>=line.Bounds[2]||line.Bounds[1]>=line.Bounds[3]||!line.ValidPosition())
                throw new ArgumentException("Invalid visible text line.");
            bytes+=Encoding.UTF8.GetByteCount(line.Text);
            if(bytes>64000)throw new ArgumentException("Snapshot too large.");
        }
        lock(Gate){
            var current=Foreground();
            if(current==null)return new {ok=false,error="capture_off"};
            var (session,process,window,pid,created)=current.Value;
            if(ActiveRun!=session.Run){Previous.Clear();ActiveRun=session.Run;LastWrite=0;}
            long now=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            // Rate limit any sender to one snapshot per second; deduplicate
            // the same page viewport, but retain a later repeated occurrence.
            if(now-LastWrite<1000)return new {ok=false,error="capture_rate_limited"};
            string key=document.ToString("N")+":"+window.ToInt64();
            string signature=Hash(JsonSerializer.Serialize(snapshot.Rows.Select(r=>new{r.Text,r.Anchor,r.Start,r.End,r.PageX,r.PageY})));
            if(Previous.TryGetValue(key,out var prior)&&prior.Signature==signature&&now-prior.At<60000)
                return new {ok=true,inserted=0UL};
            var parent=$"local:experience:browser:{session.Run}:{key}";
            var screen=$"{parent}:screen:{Guid.NewGuid():N}";
            // One transaction per bounded batch, with all lines tied to the
            // same screen key. Do not merge DOM CSS coordinates with OCR pixels.
            using var tree=new NativeTree(Path.Combine(DirectoryPath(),"experience.db"));
            var rows=new List<Observation>();
            var seenIds=new HashSet<string>(StringComparer.Ordinal);
            var seenPositions=new HashSet<string>(StringComparer.Ordinal);
            int ordinal=0,ignored=0;
            foreach(var line in snapshot.Rows){
                string id=$"{screen}:event:{++ordinal}";
                string positionKey="";
                if(line.Positioned){
                    // First sight owns the observation. A stable DOM text node
                    // and span survives scrolling/reflow; position also catches
                    // recreated nodes at the same document location.
                    id=parent+":line:"+Hash(FormattableString.Invariant($"{line.Anchor}:{line.Start}:{line.End}:{line.Text}"));
                    positionKey=Hash(FormattableString.Invariant($"{parent}:{line.PageX}:{line.PageY}:{line.Text}"));
                    if(!seenIds.Add(id)||!seenPositions.Add(positionKey)||tree.ExperienceSeen(id,positionKey)){
                        ignored++;continue;
                    }
                }
                void Add(string role,string value)=>rows.Add(new("record.experience."+role,id,value,"",now));
                Add("body",line.Text);Add("kind","observation");Add("process",process);
                Add("parent",parent);Add("screen",screen);Add("origin","browser.dom");
                if(line.Positioned){Add("page-order",line.OrderKey());Add("page-key",positionKey);}
                Add("evidence",JsonSerializer.Serialize(new{version=1,host=snapshot.Host,
                    processInstance=$"{pid}:{created}",windowInstance=window.ToInt64(),
                    boundsCssPx=line.Bounds,coordinateSpace="viewport-css",method="dom",
                    pageX=line.PageX,pageY=line.PageY,anchor=line.Anchor,start=line.Start,end=line.End}));
            }
            var check=Foreground();
            if(check==null||check.Value.Window!=window||check.Value.Session.Run!=session.Run||
               check.Value.Pid!=pid||check.Value.Created!=created)return new {ok=false,error="capture_off"};
            ulong inserted=0;
            // Up to nine fields per line. The Tree batch limit is 256; keep
            // each observation atomic, including its page-order metadata.
            foreach(var group in rows.GroupBy(r=>r.ExternalId).Chunk(28))
                inserted+=tree.Observe(group.SelectMany(g=>g).ToArray());
            LastWrite=now;
            Previous[key]=new(signature,now);
            if(Previous.Count>256){
                foreach(var expired in Previous.OrderBy(p=>p.Value.At).Take(Previous.Count-256).Select(p=>p.Key).ToArray())
                    Previous.Remove(expired);
            }
            return new {ok=true,inserted,ignored};
        }
    }
}
