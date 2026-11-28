using System.Diagnostics;
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
    internal sealed record Line(string Text,int[] Bounds);
    internal sealed record Snapshot(string DocumentKey,string Host,Line[] Rows);
    internal sealed record CaptureSession(string Run,string[] Excluded);
    private sealed record Recent(string Signature,long At);
    private static readonly object Gate=new();
    private static readonly Dictionary<string,Recent> Previous=new(StringComparer.Ordinal);
    private static string ActiveRun="";
    private static long LastWrite;

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
               line.Bounds.Any(n=>n<0||n>32768)||line.Bounds[0]>=line.Bounds[2]||line.Bounds[1]>=line.Bounds[3])
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
            string signature=Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(
                JsonSerializer.Serialize(snapshot.Rows.Select(r=>r.Text)))));
            if(Previous.TryGetValue(key,out var prior)&&prior.Signature==signature&&now-prior.At<60000)
                return new {ok=true,inserted=0UL};
            var parent=$"local:experience:browser:{session.Run}:{key}";
            var screen=$"{parent}:screen:{Guid.NewGuid():N}";
            // One transaction per bounded batch, with all lines tied to the
            // same screen key. Do not merge DOM CSS coordinates with OCR pixels.
            var rows=new List<Observation>();
            int ordinal=0;
            foreach(var line in snapshot.Rows){
                string id=$"{screen}:event:{++ordinal}";
                void Add(string role,string value)=>rows.Add(new("record.experience."+role,id,value,"",now));
                Add("body",line.Text);Add("kind","observation");Add("process",process);
                Add("parent",parent);Add("screen",screen);Add("origin","browser.dom");
                Add("evidence",JsonSerializer.Serialize(new{version=1,host=snapshot.Host,
                    processInstance=$"{pid}:{created}",windowInstance=window.ToInt64(),
                    boundsCssPx=line.Bounds,coordinateSpace="viewport-css",method="dom"}));
            }
            var check=Foreground();
            if(check==null||check.Value.Window!=window||check.Value.Session.Run!=session.Run||
               check.Value.Pid!=pid||check.Value.Created!=created)return new {ok=false,error="capture_off"};
            using var tree=new NativeTree(Path.Combine(DirectoryPath(),"experience.db"));
            ulong inserted=0;
            foreach(var batch in rows.Chunk(252))inserted+=tree.Observe(batch);
            LastWrite=now;
            Previous[key]=new(signature,now);
            if(Previous.Count>256){
                foreach(var expired in Previous.OrderBy(p=>p.Value.At).Take(Previous.Count-256).Select(p=>p.Key).ToArray())
                    Previous.Remove(expired);
            }
            return new {ok=true,inserted};
        }
    }
}
