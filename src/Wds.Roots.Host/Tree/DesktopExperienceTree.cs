using Wds.Roots.Tree;

namespace Wds.Roots.Host;

/// <summary>
/// Local-only view of Surface experience. No catalog, Mesh, export, or
/// peer-compute reference. The watcher is an independent WAL writer.
/// </summary>
internal static class DesktopExperienceTree
{
    private static string Database()
    {
        string local=Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        if(string.IsNullOrWhiteSpace(local))throw new InvalidOperationException("Missing local data directory.");
        return Path.Combine(local,"Roots","Experience","experience.db");
    }
    private static T Read<T>(Func<NativeTree,T> read,T empty)
    {
        string path=Database();
        if(!File.Exists(path))return empty;
        using var tree=new NativeTree(path);
        return read(tree);
    }
    public static (long Count,long Revision) Status()=>Read(t=>(t.Count(),t.Revision()),(0L,0L));
    public static IReadOnlyList<Fragment> Feed(string term,long beforeMs,long beforeSequence,uint limit,long agent)
        =>Read<IReadOnlyList<Fragment>>(t=>t.QueryByTime(term,agent,beforeMs,beforeSequence,limit),Array.Empty<Fragment>());
    public static IReadOnlyList<RecordMember> Records(string term,long beforeMs,long beforeSequence,uint limit,long agent)
        =>Read<IReadOnlyList<RecordMember>>(t=>t.RecordsByTime(term,agent,beforeMs,beforeSequence,limit),Array.Empty<RecordMember>());
    public static IReadOnlyList<RecordMember> Screens(string term,long beforeMs,long beforeSequence,uint limit,long agent)
        =>Read<IReadOnlyList<RecordMember>>(t=>t.ScreensByTime(term,agent,beforeMs,beforeSequence,limit),Array.Empty<RecordMember>());
    public static IReadOnlyList<BranchAgent> Branches()
        =>Read<IReadOnlyList<BranchAgent>>(t=>t.Agents(),Array.Empty<BranchAgent>());
    public static (float[] Weights,long Occurrences) Weights(long sequence)
        =>Read(t=>(t.FragmentWeights(sequence),t.TextOccurrences(sequence)),(Array.Empty<float>(),0L));
    private static readonly object CompileGate=new();
    public static (bool Available,long Revision,string Path,string? ContentPath) Compile()
    {
        lock(CompileGate)
        {
            string database=Database(),directory=Path.GetDirectoryName(database)!;
            string output=Path.Combine(directory,"experience.weights.gguf");
            string content=Path.Combine(directory,"experience.content.gguf");
            if(!File.Exists(database))return (false,0,output,null);
            using var tree=new NativeTree(database);if(tree.Count()==0)return (false,tree.Revision(),output,null);
            string temporary=output+"."+Guid.NewGuid().ToString("N")+".tmp";
            string contentTemp=content+"."+Guid.NewGuid().ToString("N")+".tmp";
            try
            {
                // Native compile is an internally consistent read transaction.
                // Publish its own revision, not an impossible requirement that
                // the live watcher stop writing while compilation runs.
                long revision=tree.Compile(temporary);
                File.Move(temporary,output,true);
                long contentRevision=tree.CompileExperience(contentTemp);
                if(File.Exists(contentTemp))File.Move(contentTemp,content,true);
                else if(File.Exists(content))File.Delete(content);
                // Each GGUF has its own actual snapshot revision; they need not
                // be identical during live capture.
                return (true,revision,output,File.Exists(content)?content:null);
            }
            finally{
                if(File.Exists(temporary))File.Delete(temporary);
                if(File.Exists(contentTemp))File.Delete(contentTemp);
            }
        }
    }
    public static long AddBranch(string name,string term)
    {
        string path=Database();
        if(!File.Exists(path))throw new InvalidOperationException("Start Surface before creating an Experience branch.");
        using var tree=new NativeTree(path);return tree.CreateAgent(name,term);
    }
    public static void RemoveBranch(long id)
    {
        string path=Database();if(!File.Exists(path))return;
        using var tree=new NativeTree(path);tree.RemoveAgent(id);
    }
}
