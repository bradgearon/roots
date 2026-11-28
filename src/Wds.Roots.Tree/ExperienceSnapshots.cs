namespace Wds.Roots.Tree;

/// <summary>One rolling, local, integrity-checked SQLite backup. Never copy a
/// live main database file without its WAL. Separate from all Mesh paths.</summary>
public static class ExperienceSnapshots
{
    public static string BackupPath(string database)=>Path.Combine(Path.GetDirectoryName(Path.GetFullPath(database))!,"experience.backup.db");

    public static long Backup(string database)
    {
        string destination=BackupPath(database);
        string directory=Path.GetDirectoryName(destination)!;
        // Serialize automatic and explicit backup publishers across processes.
        using var lease=new FileStream(Path.Combine(directory,"backup.lock"),FileMode.OpenOrCreate,FileAccess.ReadWrite,FileShare.None);
        string temporary=destination+"."+Guid.NewGuid().ToString("N")+".tmp";
        try{
            using var tree=new NativeTree(database);
            long revision=tree.Backup(temporary);
            File.Move(temporary,destination,true);
            return revision;
        }finally{
            if(File.Exists(temporary))File.Delete(temporary);
        }
    }
}
