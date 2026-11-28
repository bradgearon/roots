using System.Reflection;
using System.Runtime.ExceptionServices;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace Wds.Roots.Tree;

public sealed record Observation(string Source, string ExternalId, string Body, string Language, long ObservedMs);
public sealed record Fragment(long Sequence, string Source, string ExternalId, string Body, string Language, long ObservedMs);
public sealed record BranchAgent(long Id, string Name, string Term);
public sealed record RecordMember(long MasterSequence, Fragment Fragment);

/// <summary>Desktop binding for the shared C++ ABI. No managed knowledge database.</summary>
public sealed class NativeTree : IDisposable
{
    private const string Lib = "roots_tree";
    private readonly TreeHandle _handle;
    static NativeTree() => NativeLibrary.SetDllImportResolver(typeof(NativeTree).Assembly, Resolve);
    private static IntPtr Resolve(string name, Assembly assembly, DllImportSearchPath? search)
    {
        if (name != Lib) return IntPtr.Zero;
        var explicitPath = Environment.GetEnvironmentVariable("ROOTS_TREE_LIBRARY");
        if (!string.IsNullOrEmpty(explicitPath))
        {
            if (!Path.IsPathFullyQualified(explicitPath)) throw new InvalidOperationException("ROOTS_TREE_LIBRARY must be an absolute trusted path.");
            return NativeLibrary.Load(explicitPath);
        }
        var file = OperatingSystem.IsWindows() ? "roots_tree.dll" : OperatingSystem.IsMacOS() ? "libroots_tree.dylib" : "libroots_tree.so";
        var local = Path.Combine(AppContext.BaseDirectory, file);
        if (File.Exists(local)) return NativeLibrary.Load(local);
        throw new DllNotFoundException("Build the native Tree and set ROOTS_TREE_LIBRARY to its absolute library path.");
    }

    public NativeTree(string path)
    {
        Validate(path);
        if (N.Abi() != 1) throw new InvalidOperationException("Unsupported Tree ABI.");
        Check(N.Open(Path.GetFullPath(path), out var h));
        if (h == IntPtr.Zero) throw new InvalidOperationException("Tree open returned no handle.");
        _handle = new TreeHandle(h);
    }
    public void Dispose() => _handle.Dispose();
    private static void Validate(string s)
    {
        ArgumentNullException.ThrowIfNull(s);
        if (s.Contains('\0')) throw new ArgumentException("Embedded NUL.");
    }
    private static void Check(int code)
    {
        if (code != 0) throw new InvalidOperationException($"Native Tree result {code}.");
    }
    private static string Decode(IntPtr p) => Marshal.PtrToStringUTF8(p) ?? "";
    public ulong Observe(IReadOnlyList<Observation> rows)
    {
        if (rows.Count is < 1 or > 256) throw new ArgumentException("Use 1–256 observations.");
        var allocated = new List<IntPtr>(rows.Count * 4);
        IntPtr Encode(string text)
        {
            Validate(text);
            var p = Marshal.StringToCoTaskMemUTF8(text);
            allocated.Add(p);
            return p;
        }
        try
        {
            var native = new NativeObservation[rows.Count];
            for (var i = 0; i < native.Length; i++)
            {
                var o = rows[i];
                native[i] = new NativeObservation { Source = Encode(o.Source), ExternalId = Encode(o.ExternalId), Body = Encode(o.Body), Language = Encode(o.Language), ObservedMs = o.ObservedMs };
            }
            Check(N.Observe(_handle, native, (nuint)native.Length, out var inserted));
            return (ulong)inserted;
        }
        finally { foreach (var p in allocated) Marshal.FreeCoTaskMem(p); }
    }
    public IReadOnlyList<Fragment> Query(string term = "", long agent = 0, long before = 0, uint limit = 30)
    {
        Validate(term);
        var result = new List<Fragment>();
        Exception? failure = null;
        FragmentCallback cb = (_, p) =>
        {
            try
            {
                var f = Marshal.PtrToStructure<NativeFragment>(p);
                result.Add(new Fragment(f.Sequence, Decode(f.Source), Decode(f.ExternalId), Decode(f.Body), Decode(f.Language), f.ObservedMs));
                return 0;
            }
            catch (Exception e) { failure = e; return 1; }
        };
        Check(N.Query(_handle, term, agent, before, limit, cb, IntPtr.Zero));
        GC.KeepAlive(cb);
        if (failure != null) ExceptionDispatchInfo.Capture(failure).Throw();
        return result;
    }
    public IReadOnlyList<RecordMember> Records(string term="",long agent=0,long before=0,uint limit=30)
    {
        Validate(term); var result=new List<RecordMember>();Exception? failure=null;
        RecordCallback cb=(_,master,p)=>{
            try{var f=Marshal.PtrToStructure<NativeFragment>(p);
                result.Add(new(master,new Fragment(f.Sequence,Decode(f.Source),Decode(f.ExternalId),Decode(f.Body),Decode(f.Language),f.ObservedMs)));return 0;}
            catch(Exception e){failure=e;return 1;}
        };
        Check(N.Records(_handle,term,agent,before,limit,cb,IntPtr.Zero));GC.KeepAlive(cb);
        if(failure!=null)ExceptionDispatchInfo.Capture(failure).Throw();return result;
    }
    public IReadOnlyList<Fragment> QueryByTime(string term="",long agent=0,long beforeMs=0,long beforeSequence=0,uint limit=30)
    {
        Validate(term);var result=new List<Fragment>();Exception? failure=null;
        FragmentCallback cb=(_,p)=>{
            try{var f=Marshal.PtrToStructure<NativeFragment>(p);
                result.Add(new(f.Sequence,Decode(f.Source),Decode(f.ExternalId),Decode(f.Body),Decode(f.Language),f.ObservedMs));return 0;}
            catch(Exception e){failure=e;return 1;}
        };
        Check(N.QueryByTime(_handle,term,agent,beforeMs,beforeSequence,limit,cb,IntPtr.Zero));GC.KeepAlive(cb);
        if(failure!=null)ExceptionDispatchInfo.Capture(failure).Throw();return result;
    }
    public IReadOnlyList<RecordMember> RecordsByTime(string term="",long agent=0,long beforeMs=0,long beforeSequence=0,uint limit=30)
    {
        Validate(term);var result=new List<RecordMember>();Exception? failure=null;
        RecordCallback cb=(_,master,p)=>{
            try{var f=Marshal.PtrToStructure<NativeFragment>(p);
                result.Add(new(master,new Fragment(f.Sequence,Decode(f.Source),Decode(f.ExternalId),Decode(f.Body),Decode(f.Language),f.ObservedMs)));return 0;}
            catch(Exception e){failure=e;return 1;}
        };
        Check(N.RecordsByTime(_handle,term,agent,beforeMs,beforeSequence,limit,cb,IntPtr.Zero));GC.KeepAlive(cb);
        if(failure!=null)ExceptionDispatchInfo.Capture(failure).Throw();return result;
    }
    public float[] FragmentWeights(long sequence)
    {
        var weights=new float[128];Check(N.FragmentWeights(_handle,sequence,weights,(nuint)weights.Length));return weights;
    }
    public IReadOnlyList<BranchAgent> Agents()
    {
        var result = new List<BranchAgent>();
        Exception? failure = null;
        AgentCallback cb = (_, id, name, term) =>
        {
            try { result.Add(new BranchAgent(id, Decode(name), Decode(term))); return 0; }
            catch (Exception e) { failure = e; return 1; }
        };
        Check(N.AgentList(_handle, cb, IntPtr.Zero));
        GC.KeepAlive(cb);
        if (failure != null) ExceptionDispatchInfo.Capture(failure).Throw();
        return result;
    }
    public long CreateAgent(string name, string term) { Validate(name); Validate(term); Check(N.AgentCreate(_handle, name, term, out var id)); return id; }
    public void RemoveAgent(long id) => Check(N.AgentRemove(_handle, id));
    public long Count() { Check(N.Count(_handle, out var n)); return n; }
    public long Revision() { Check(N.Revision(_handle, out var n)); return n; }
    public long Compile(string newPath) { Validate(newPath); Check(N.Compile(_handle, Path.GetFullPath(newPath), out var n)); return n; }
    public long CompileLocalized(string newPath,string language) { Validate(newPath);Validate(language);Check(N.CompileLocalized(_handle,Path.GetFullPath(newPath),language,out var n));return n; }
    public void Clear() => Check(N.Clear(_handle));

    public enum WorkState { Pending, Running, Completed, Failed, Cancelled }
    public sealed record Work(long Id,long Fragment,long Priority,long Deadline,long LeaseUntil,long Token,long Attempts,long Ready,
        WorkState State,string Operation,string Profile,string Worker,string Output);
    [StructLayout(LayoutKind.Sequential)]
    private struct NativeWork { public long Id,Fragment,Priority,Deadline,LeaseUntil,Token,Attempts,Ready; public int State; public IntPtr Operation,Profile,Worker,Output; }
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int WorkCallback(IntPtr user,IntPtr work);
    private Work? ReceiveWork(Func<WorkCallback,int> call){
        Work? result=null; Exception? failure=null;
        WorkCallback cb=(_,p)=>{
            try{var w=Marshal.PtrToStructure<NativeWork>(p);result=new Work(w.Id,w.Fragment,w.Priority,w.Deadline,w.LeaseUntil,w.Token,w.Attempts,w.Ready,(WorkState)w.State,Decode(w.Operation),Decode(w.Profile),Decode(w.Worker),Decode(w.Output));return 0;}
            catch(Exception e){failure=e;return 1;}
        };
        Check(call(cb)); GC.KeepAlive(cb);
        if(failure!=null)ExceptionDispatchInfo.Capture(failure).Throw();
        return result;
    }
    public long RequestWork(long fragment,string operation,string profile,long priority,long deadline=0){
        Validate(operation);Validate(profile);Check(N.WorkRequest(_handle,fragment,operation,profile,priority,deadline,out var id));return id;
    }
    public Work? ReadWork(long id)=>ReceiveWork(cb=>N.WorkRead(_handle,id,cb,IntPtr.Zero));
    public Work? ClaimWork(string operation,string worker,long now,long duration){
        Validate(operation);Validate(worker);return ReceiveWork(cb=>N.WorkClaim(_handle,operation,worker,now,duration,cb,IntPtr.Zero));
    }
    private static bool WorkChanged(int code){if(code==4)return false;Check(code);return true;}
    public bool FinishWork(Work job,string worker,long now,WorkState state,string output="",long retry=0){
        Validate(worker);Validate(output);return WorkChanged(N.WorkFinish(_handle,job.Id,job.Token,worker,now,(int)state,output,retry));
    }
    public bool RenewWork(Work job,string worker,long now,long duration){
        Validate(worker);return WorkChanged(N.WorkRenew(_handle,job.Id,job.Token,worker,now,duration));
    }
    public bool CancelWork(long id)=>WorkChanged(N.WorkCancel(_handle,id));
    [StructLayout(LayoutKind.Sequential)]
    private struct NativeObservation { public IntPtr Source, ExternalId, Body, Language; public long ObservedMs; }
    [StructLayout(LayoutKind.Sequential)]
    private struct NativeFragment { public long Sequence; public IntPtr Source, ExternalId, Body, Language; public long ObservedMs; }
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int FragmentCallback(IntPtr user, IntPtr fragment);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int RecordCallback(IntPtr user,long master,IntPtr fragment);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int AgentCallback(IntPtr user, long id, IntPtr name, IntPtr term);
    private sealed class TreeHandle : SafeHandleZeroOrMinusOneIsInvalid
    {
        public TreeHandle(IntPtr h) : base(true) => SetHandle(h);
        protected override bool ReleaseHandle() { N.Close(handle); return true; }
    }
    private static class N
    {
        [DllImport(Lib, EntryPoint="roots_work_request", CallingConvention=CallingConvention.Cdecl)] internal static extern int WorkRequest(TreeHandle h,long fragment,[MarshalAs(UnmanagedType.LPUTF8Str)] string operation,[MarshalAs(UnmanagedType.LPUTF8Str)] string profile,long priority,long deadline,out long id);
        [DllImport(Lib, EntryPoint="roots_work_read", CallingConvention=CallingConvention.Cdecl)] internal static extern int WorkRead(TreeHandle h,long id,WorkCallback cb,IntPtr user);
        [DllImport(Lib, EntryPoint="roots_work_claim", CallingConvention=CallingConvention.Cdecl)] internal static extern int WorkClaim(TreeHandle h,[MarshalAs(UnmanagedType.LPUTF8Str)] string operation,[MarshalAs(UnmanagedType.LPUTF8Str)] string worker,long now,long duration,WorkCallback cb,IntPtr user);
        [DllImport(Lib, EntryPoint="roots_work_finish", CallingConvention=CallingConvention.Cdecl)] internal static extern int WorkFinish(TreeHandle h,long id,long token,[MarshalAs(UnmanagedType.LPUTF8Str)] string worker,long now,int state,[MarshalAs(UnmanagedType.LPUTF8Str)] string output,long retry);
        [DllImport(Lib, EntryPoint="roots_work_renew", CallingConvention=CallingConvention.Cdecl)] internal static extern int WorkRenew(TreeHandle h,long id,long token,[MarshalAs(UnmanagedType.LPUTF8Str)] string worker,long now,long duration);
        [DllImport(Lib, EntryPoint="roots_work_cancel", CallingConvention=CallingConvention.Cdecl)] internal static extern int WorkCancel(TreeHandle h,long id);
        [DllImport(Lib, EntryPoint="roots_tree_abi_version", CallingConvention=CallingConvention.Cdecl)] internal static extern uint Abi();
        [DllImport(Lib, EntryPoint="roots_tree_open", CallingConvention=CallingConvention.Cdecl)] internal static extern int Open([MarshalAs(UnmanagedType.LPUTF8Str)] string path, out IntPtr h);
        [DllImport(Lib, EntryPoint="roots_tree_close", CallingConvention=CallingConvention.Cdecl)] internal static extern void Close(IntPtr h);
        [DllImport(Lib, EntryPoint="roots_tree_observe", CallingConvention=CallingConvention.Cdecl)] internal static extern int Observe(TreeHandle h, [In] NativeObservation[] rows, nuint n, out nuint inserted);
        [DllImport(Lib, EntryPoint="roots_tree_query", CallingConvention=CallingConvention.Cdecl)] internal static extern int Query(TreeHandle h, [MarshalAs(UnmanagedType.LPUTF8Str)] string term, long agent, long before, uint limit, FragmentCallback cb, IntPtr user);
        [DllImport(Lib, EntryPoint="roots_tree_records", CallingConvention=CallingConvention.Cdecl)] internal static extern int Records(TreeHandle h,[MarshalAs(UnmanagedType.LPUTF8Str)] string term,long agent,long before,uint limit,RecordCallback cb,IntPtr user);
        [DllImport(Lib, EntryPoint="roots_tree_query_time", CallingConvention=CallingConvention.Cdecl)] internal static extern int QueryByTime(TreeHandle h,[MarshalAs(UnmanagedType.LPUTF8Str)] string term,long agent,long beforeMs,long beforeSequence,uint limit,FragmentCallback cb,IntPtr user);
        [DllImport(Lib, EntryPoint="roots_tree_records_time", CallingConvention=CallingConvention.Cdecl)] internal static extern int RecordsByTime(TreeHandle h,[MarshalAs(UnmanagedType.LPUTF8Str)] string term,long agent,long beforeMs,long beforeSequence,uint limit,RecordCallback cb,IntPtr user);
        [DllImport(Lib, EntryPoint="roots_tree_fragment_weights", CallingConvention=CallingConvention.Cdecl)] internal static extern int FragmentWeights(TreeHandle h,long sequence,[Out] float[] weights,nuint count);
        [DllImport(Lib, EntryPoint="roots_tree_compile_localized", CallingConvention=CallingConvention.Cdecl)] internal static extern int CompileLocalized(TreeHandle h,[MarshalAs(UnmanagedType.LPUTF8Str)] string path,[MarshalAs(UnmanagedType.LPUTF8Str)] string language,out long revision);
        [DllImport(Lib, EntryPoint="roots_tree_count", CallingConvention=CallingConvention.Cdecl)] internal static extern int Count(TreeHandle h, out long count);
        [DllImport(Lib, EntryPoint="roots_tree_revision", CallingConvention=CallingConvention.Cdecl)] internal static extern int Revision(TreeHandle h, out long revision);
        [DllImport(Lib, EntryPoint="roots_tree_agent_create", CallingConvention=CallingConvention.Cdecl)] internal static extern int AgentCreate(TreeHandle h, [MarshalAs(UnmanagedType.LPUTF8Str)] string name, [MarshalAs(UnmanagedType.LPUTF8Str)] string term, out long id);
        [DllImport(Lib, EntryPoint="roots_tree_agent_list", CallingConvention=CallingConvention.Cdecl)] internal static extern int AgentList(TreeHandle h, AgentCallback cb, IntPtr user);
        [DllImport(Lib, EntryPoint="roots_tree_agent_remove", CallingConvention=CallingConvention.Cdecl)] internal static extern int AgentRemove(TreeHandle h, long id);
        [DllImport(Lib, EntryPoint="roots_tree_compile", CallingConvention=CallingConvention.Cdecl)] internal static extern int Compile(TreeHandle h, [MarshalAs(UnmanagedType.LPUTF8Str)] string path, out long revision);
        [DllImport(Lib, EntryPoint="roots_tree_clear", CallingConvention=CallingConvention.Cdecl)] internal static extern int Clear(TreeHandle h);
    }
}
