// Copyright (c) 2026 We Develop Software LLC. All rights reserved.
using System.Diagnostics;
using System.Globalization;
using System.Text;
using System.Text.Json;
using Wds.Roots.Tree;

if (!OperatingSystem.IsWindows()) { Console.Error.WriteLine("Surface requires Windows."); return 2; }
var home = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
if (string.IsNullOrWhiteSpace(home)) return 2;
var directory = Path.Combine(home, "Roots", "Experience");
var database = Path.Combine(directory, "experience.db");
try
{
    if (args.Length == 0) args = ["--capture"];
    if (args[0] is "--status" or "--search")
    {
        if (args[0] == "--status" && args.Length != 1 || args[0] == "--search" && args.Length != 2) return Usage();
        if (!File.Exists(database)) { Console.WriteLine("No private Experience Tree yet."); return 0; }
        using var tree = new NativeTree(database);
        Console.WriteLine($"Private Experience Tree: {tree.Count()} fragments.");
        if (args[0] == "--search")
        {
            foreach (var record in tree.Records(args[1], limit: 50).GroupBy(x => x.MasterSequence))
            {
                var body = record.FirstOrDefault(x => x.Fragment.Source == "record.experience.body")?.Fragment;
                var app = record.FirstOrDefault(x => x.Fragment.Source == "record.experience.process")?.Fragment.Body;
                if (body != null)
                    Console.WriteLine($"{DateTimeOffset.FromUnixTimeMilliseconds(body.ObservedMs).ToLocalTime():g} [{app}] {SafeConsole(body.Body)}");
            }
        }
        return 0;
    }
    if (args[0] != "--capture" || Console.IsInputRedirected || Console.IsOutputRedirected) return Usage();
    var excluded = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
    bool noOcr = false;
    for (int i = 1; i < args.Length; i++)
    {
        if (args[i] == "--no-ocr") noOcr = true;
        else if (args[i] == "--ocr") noOcr = false;
        else if (args[i] == "--exclude" && i + 1 < args.Length && SurfaceObservation.ValidProcess(args[i + 1]))
            excluded.Add(args[++i]);
        else return Usage();
    }
    if (excluded.Count > 64) return Usage();
    Console.WriteLine("Roots will follow eligible foreground windows automatically and retain text and activity locally.");
    Console.WriteLine("Known private apps, browsers, protected surfaces and sensitive controls are excluded.");
    Console.WriteLine("Screen text can contain confidential information. Stop capture before opening private content.");
    Console.Write("Start desktop watching? [y/N] ");
    if (!string.Equals(Console.ReadLine()?.Trim(), "y", StringComparison.OrdinalIgnoreCase)) return 0;
    var executable = Environment.GetEnvironmentVariable("ROOTS_SURFACE_AGENT");
    if (string.IsNullOrWhiteSpace(executable) || !Path.IsPathFullyQualified(executable) || !File.Exists(executable))
        throw new InvalidOperationException("Surface agent missing; use launch-surface.cmd.");
    Directory.CreateDirectory(directory);
    // Separate database, separate handle; no DesktopTreeService or Mesh catalog references.
    using var lease = new FileStream(Path.Combine(directory, "capture.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None);
    var devicePath = Path.Combine(directory, "device-id.txt");
    if (!File.Exists(devicePath)) File.WriteAllText(devicePath, Guid.NewGuid().ToString("N"), Encoding.ASCII);
    var device = Guid.Parse(File.ReadAllText(devicePath));
    var run = Guid.NewGuid();
    using var privateTree = new NativeTree(database);
    Console.WriteLine("Roots Surface — LOCAL CAPTURE ACTIVE. Ctrl+C stops capture.");
    Console.WriteLine("Automatically following foreground applications. No application is launched or controlled.");
    if (excluded.Count > 0) Console.WriteLine("Additional exclusions: " + string.Join(", ", excluded));
    Console.WriteLine(noOcr ? "UI Automation static text only." : "UI Automation and window OCR where available.");
    Console.WriteLine("Only semantic text and activity enter the private Tree. No media file or network upload.");
    using var cancellation = new CancellationTokenSource();
    ConsoleCancelEventHandler stop = (_, e) => { e.Cancel = true; cancellation.Cancel(); };
    Console.CancelKeyPress += stop;
    var start = new ProcessStartInfo(executable)
    {
        UseShellExecute = false, CreateNoWindow = true, RedirectStandardInput = true,
        RedirectStandardOutput = true, StandardOutputEncoding = new UTF8Encoding(false, true)
    };
    if (noOcr) start.ArgumentList.Add("--no-ocr");
    foreach (var name in excluded) { start.ArgumentList.Add("--exclude"); start.ArgumentList.Add(name); }
    using var child = Process.Start(start) ?? throw new InvalidOperationException("Cannot start Surface agent.");
    long sequence = 0;
    try
    {
        while (!cancellation.IsCancellationRequested)
        {
            var line = await child.StandardOutput.ReadLineAsync(cancellation.Token);
            if (line == null) break;
            var observation = SurfaceObservation.Parse(line, excluded);
            if (cancellation.IsCancellationRequested) break;
            var rows = observation.ToRows(device, run, ++sequence);
            privateTree.Observe(rows);
        }
        if (!cancellation.IsCancellationRequested)
        {
            await child.WaitForExitAsync();
            if (child.ExitCode != 0) throw new InvalidOperationException("Surface agent exited: " + child.ExitCode);
            Console.WriteLine("Surface agent ended.");
        }
    }
    catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { }
    finally
    {
        Console.CancelKeyPress -= stop;
        try { child.StandardInput.Close(); } catch { }
        if (!child.HasExited && !child.WaitForExit(1500))
        {
            child.Kill(entireProcessTree: true);
            child.WaitForExit(1500);
        }
        Console.WriteLine($"Capture stopped. {sequence} semantic events accepted.");
    }
    return 0;
}
catch (Exception e)
{
    // Do not log raw text or pipe payloads on failure.
    Console.Error.WriteLine("Surface failed: " + e.GetType().Name);
    return 1;
}

static string SafeConsole(string s) => string.Concat(s.Select(c => char.IsControl(c) ? ' ' : c));
static int Usage()
{
    Console.WriteLine("launch-surface.cmd [--capture [--no-ocr] [--exclude app.exe]]");
    Console.WriteLine("launch-surface.cmd --status");
    Console.WriteLine("launch-surface.cmd --search \"text\"");
    return 2;
}

internal sealed record SurfaceObservation(string Kind, ulong Session, uint Pid, ulong Created, ulong Window,
    long StartUtc, long EndUtc, long StartMono, long EndMono, string ProcessName, int[] Bounds, string Text)
{
    private static readonly HashSet<string> Denied = new(StringComparer.OrdinalIgnoreCase)
    {
        "chrome.exe", "msedge.exe", "firefox.exe", "brave.exe", "opera.exe", "vivaldi.exe",
        "roots-surface.exe", "wds.roots.host.exe", "wds.roots.experience.exe", "dotnet.exe", "windowsterminal.exe",
        "conhost.exe", "openconsole.exe", "credentialuibroker.exe", "logonui.exe", "keepass.exe", "keepassxc.exe",
        "1password.exe", "bitwarden.exe", "outlook.exe", "olk.exe", "thunderbird.exe", "ms-teams.exe",
        "teams.exe", "slack.exe", "discord.exe", "signal.exe", "telegram.exe", "whatsapp.exe", "skype.exe",
        "mstsc.exe", "msrdc.exe"
    };
    internal static bool ValidProcess(string name) =>
        name.Length is > 4 and <= 128 && name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) &&
        !name.Any(c => char.IsControl(c) || c is '/' or '\\' or ':' or '*' or '?');
    public static SurfaceObservation Parse(string line, IReadOnlySet<string> excluded)
    {
        if (line.Length > 18000) throw new InvalidDataException("Oversized Surface event.");
        var p = line.Split('\t');
        if (p.Length != 12 || p[0] is not ("uia" or "ocr" or "focused" or "idle"))
            throw new InvalidDataException("Unknown Surface event.");
        static long Signed(string s) => long.Parse(s, NumberStyles.None, CultureInfo.InvariantCulture);
        static ulong Unsigned(string s) => ulong.Parse(s, NumberStyles.None, CultureInfo.InvariantCulture);
        static string Decode(string s) => new UTF8Encoding(false, true).GetString(Convert.FromHexString(s));
        var session = Unsigned(p[1]);
        var pid = uint.Parse(p[2], NumberStyles.None, CultureInfo.InvariantCulture);
        var created = Unsigned(p[3]);
        var window = Unsigned(p[4]);
        var start = Signed(p[5]); var end = Signed(p[6]);
        var startMono = Signed(p[7]); var endMono = Signed(p[8]);
        var process = Decode(p[9]); var text = Decode(p[11]);
        var bounds = p[10].Split(',').Select(x => int.Parse(x, CultureInfo.InvariantCulture)).ToArray();
        bool activity = p[0] is "focused" or "idle";
        if (session == 0 || pid == 0 || created == 0 || window == 0 || !ValidProcess(process) || Denied.Contains(process) || excluded.Contains(process) ||
            start < 0 || end < start || end > DateTimeOffset.UtcNow.AddMinutes(1).ToUnixTimeMilliseconds() ||
            startMono < 0 || endMono < startMono || endMono - startMono > 15_000_000 ||
            bounds.Length != 4 || bounds[2] < bounds[0] || bounds[3] < bounds[1] ||
            string.IsNullOrWhiteSpace(text) || text.Contains('\0') || Encoding.UTF8.GetByteCount(text) > 8192 ||
            (!activity && (start != end || startMono != endMono)))
            throw new InvalidDataException("Invalid Surface event.");
        return new(p[0], session, pid, created, window, start, end, startMono, endMono, process, bounds, text);
    }
    public IReadOnlyList<Observation> ToRows(Guid device, Guid run, long sequence)
    {
        string root = $"local:experience:{device:N}:{run:N}";
        string id = $"{root}:event:{sequence}";
        string parent = $"{root}:session:{Session}";
        bool activity = Kind is "focused" or "idle";
        var result = new List<Observation>();
        void Add(string role, string value) => result.Add(new("record.experience." + role, id, value, "", EndUtc));
        Add("body", activity ? $"{ProcessName}: {Kind}" : Text);
        Add("kind", activity ? "activity.interval" : "observation");
        Add("process", ProcessName);
        Add("parent", parent);
        Add("origin", activity ? "windows.foreground" : "windows." + Kind);
        Add("interval", JsonSerializer.Serialize(new { version = 1, startUtcMs = StartUtc, endUtcMs = EndUtc,
            startMonotonicUs = StartMono, endMonotonicUs = EndMono, state = activity ? Kind : "observed" }));
        Add("evidence", JsonSerializer.Serialize(new { version = 1, processInstance = $"{Pid}:{Created}",
            windowInstance = $"{Session}:{Window}", boundsPx = Bounds,
            coordinateSpace = Kind == "ocr" ? "capture-frame" : Kind == "uia" ? "desktop" : "none",
            method = Kind, confidence = (double?)null }));
        return result;
    }
}
