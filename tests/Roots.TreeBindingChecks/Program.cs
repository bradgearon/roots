using Wds.Roots.Tree;
static void Need(bool value, string reason) { if (!value) throw new Exception(reason); }
string dir = Path.Combine(Path.GetTempPath(), "roots-tree-binding-" + Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(dir);
try
{
    string db = Path.Combine(dir, "tree.db");
    string path = Path.Combine(dir, "tree.gguf");
    long agent;
    using (var tree = new NativeTree(db))
    {
        var rows = new[] { new Observation("fixture", "1", "音楽 🎼 music", "ja", 1), new Observation("fixture", "2", "Software engineering", "en", 2) };
        Need(tree.Observe(rows) == 2 && tree.Observe(rows) == 0, "batch/dedup");
        Need(tree.Query("音楽").Single().Body == "音楽 🎼 music", "UTF-8 round trip");
        agent = tree.CreateAgent("Music", "music");
        Need(tree.Agents().Single().Id == agent && tree.Query(agent: agent).Count == 1, "agent backfill");
        Need(tree.Observe(new[] {new Observation("fixture","3","New music","en",3)}) == 1, "append");
        Need(tree.Query(agent:agent).Count == 2, "agent growth");
        var first = tree.Query(limit:1).Single();
        Need(tree.Query(before:first.Sequence).Count == 2, "cursor");
        Need(tree.Compile(path) == tree.Revision() && File.ReadAllBytes(path).Take(4).SequenceEqual("GGUF"u8.ToArray()), "GGUF");
    }
    using (var reopened = new NativeTree(db))
    {
        Need(reopened.Count() == 3 && reopened.Query(agent:agent).Count == 2, "persistence");
        reopened.Clear();
        Need(reopened.Count() == 0, "clear");
    }
    Console.WriteLine("Managed Tree binding checks passed.");
}
finally { Directory.Delete(dir, true); }
