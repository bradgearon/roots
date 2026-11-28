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
    public static IReadOnlyList<BranchAgent> Branches()
        =>Read<IReadOnlyList<BranchAgent>>(t=>t.Agents(),Array.Empty<BranchAgent>());
    public static float[] Weights(long sequence)
        =>Read(t=>t.FragmentWeights(sequence),Array.Empty<float>());
    private static readonly object CompileGate=new();
    public static (bool Available,long Revision) Compile()
    {
        lock(CompileGate)
        {
            string database=Database();if(!File.Exists(database))return (false,0);
            using var tree=new NativeTree(database);if(tree.Count()==0)return (false,tree.Revision());
            string directory=Path.GetDirectoryName(database)!;
            string output=Path.Combine(directory,"experience.weights.gguf");
            string temporary=Path.Combine(directory,"experience.weights."+Guid.NewGuid().ToString("N")+".tmp");
            try
            {
                long revision=tree.Compile(temporary);
                if(tree.Revision()!=revision)return (false,revision);
                File.Move(temporary,output,true);return (true,revision);
            }
            finally{if(File.Exists(temporary))File.Delete(temporary);}
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
