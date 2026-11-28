using System.Globalization;
using System.Text.Json;
using Wds.Roots.Tree;

static void Print(object value) => Console.WriteLine(JsonSerializer.Serialize(value));
static void Usage() => Console.Error.WriteLine("Tree CLI: --db PATH observe SOURCE ID LANGUAGE TEXT | feed [TERM] [AGENT] [BEFORE] [LIMIT] | agent-add NAME TERM | agents | agent-remove ID | count | revision | compile NEW_PATH | clear --confirm");
if (args.Length < 3 || args[0] != "--db") { Usage(); return 2; }
try
{
    // Explicit database path: no implicit read of another application or account.
    string path = Path.GetFullPath(args[1]);
    Directory.CreateDirectory(Path.GetDirectoryName(path)!);
    using var tree = new NativeTree(path);
    switch (args[2])
    {
        case "observe" when args.Length == 7:
            Print(new { inserted = tree.Observe(new[] { new Observation(args[3], args[4], args[6], args[5], DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()) }) }); break;
        case "feed" when args.Length <= 7:
            var term = args.Length > 3 ? args[3] : "";
            var agent = args.Length > 4 ? long.Parse(args[4], CultureInfo.InvariantCulture) : 0;
            var before = args.Length > 5 ? long.Parse(args[5], CultureInfo.InvariantCulture) : 0;
            var limit = args.Length > 6 ? uint.Parse(args[6], CultureInfo.InvariantCulture) : 30;
            var page = tree.Query(term, agent, before, limit);
            Print(new { items = page, nextBefore = page.Count > 0 ? page[^1].Sequence : 0, end = page.Count < limit }); break;
        case "agent-add" when args.Length == 5: Print(new { id = tree.CreateAgent(args[3], args[4]) }); break;
        case "agents" when args.Length == 3: Print(tree.Agents()); break;
        case "agent-remove" when args.Length == 4: tree.RemoveAgent(long.Parse(args[3], CultureInfo.InvariantCulture)); Print(new { ok = true }); break;
        case "count" when args.Length == 3: Print(new { count = tree.Count() }); break;
        case "revision" when args.Length == 3: Print(new { revision = tree.Revision() }); break;
        case "compile" when args.Length == 4: Print(new { revision = tree.Compile(args[3]), path = Path.GetFullPath(args[3]) }); break;
        case "clear" when args.Length == 4 && args[3] == "--confirm":
            tree.Clear(); Print(new { ok = true, note = "Logical clear. Remove caller-managed GGUF exports separately." }); break;
        default: Usage(); return 2;
    }
    return 0;
}
catch (Exception ex)
{
    Console.Error.WriteLine(ex.Message);
    return 1;
}
