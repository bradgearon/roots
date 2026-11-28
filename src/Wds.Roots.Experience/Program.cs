// Copyright (c) 2026 We Develop Software LLC. All rights reserved.
using System.Diagnostics;
using System.Globalization;
using System.Text;
using System.Text.Json;
using Wds.Roots.Tree;
using Wds.Roots.Host.Runtime;

if (!OperatingSystem.IsWindows()) { Console.Error.WriteLine("Surface requires Windows."); return 2; }
var home = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
if (string.IsNullOrWhiteSpace(home)) return 2;
var directory = Path.Combine(home, "Roots", "Experience");
var database = Path.Combine(directory, "experience.db");
try
{
    if (args.Length == 0) args = ["--capture"];
    if (args[0] is "--status" or "--search" or "--backup")
    {
        if ((args[0] is "--status" or "--backup") && args.Length != 1 || args[0] == "--search" && args.Length != 2) return Usage();
        if (!File.Exists(database)) { Console.WriteLine("No private Experience Tree yet."); return 0; }
        if(args[0]=="--backup"){
            long revision=ExperienceSnapshots.Backup(database);
            Console.WriteLine($"Consistent Experience backup (revision {revision}): {ExperienceSnapshots.BackupPath(database)}");
            return 0;
        }
        using var tree = new NativeTree(database);
        Console.WriteLine($"Private Experience Tree: {tree.Count()} fragments.");
        if(args[0]=="--status"){
            var diagnostic=Path.Combine(directory,"surface-diagnostics.txt");
            if(File.Exists(diagnostic))Console.WriteLine(SafeConsole(File.ReadAllText(diagnostic)));
            Console.WriteLine("Database: "+database);
            foreach(var file in new[]{"experience.backup.db","experience.weights.gguf","experience.content.gguf"}){
                var path=Path.Combine(directory,file);
                Console.WriteLine((File.Exists(path)?"Available: ":"Not generated: ")+path);
            }
        }
        if (args[0] == "--search")
        {
            foreach (var record in tree.RecordsByTime(args[1], limit: 50).GroupBy(x => x.MasterSequence))
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
    bool noOcr = false, diagnostics = false;
    for (int i = 1; i < args.Length; i++)
    {
        if (args[i] == "--diagnostics") diagnostics = true;
        else if (args[i] == "--no-ocr") noOcr = true;
        else if (args[i] == "--ocr") noOcr = false;
        else if (args[i] == "--exclude" && i + 1 < args.Length && SurfaceObservation.ValidProcess(args[i + 1]))
            excluded.Add(args[++i]);
        else return Usage();
    }
    if (excluded.Count > 64) return Usage();
    Console.WriteLine("Roots will follow eligible foreground windows automatically and retain text and activity locally.");
    Console.WriteLine("No applications are excluded by name unless you explicitly specify an exclusion. Protected surfaces and sensitive controls remain excluded.");
    Console.WriteLine("Screen text can contain confidential information. Stop capture before opening private content.");
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
    // This descriptor is usable by the host only while the OS-held lease is live.
    File.WriteAllText(Path.Combine(directory,"capture.session.json"),JsonSerializer.Serialize(new {Run=run.ToString("N"),Excluded=excluded.ToArray()}));
    using var privateTree = new NativeTree(database);
    Console.WriteLine("Roots Surface — LOCAL CAPTURE ACTIVE. Ctrl+C stops capture.");
    Console.WriteLine("Automatically following foreground applications. No application is launched or controlled.");
    if (excluded.Count > 0) Console.WriteLine("Additional exclusions: " + string.Join(", ", excluded));
    Console.WriteLine(noOcr ? "UI Automation static text only." : "UI Automation and window OCR where available.");
    Console.WriteLine("Only semantic text and activity enter the private Tree. No media file or network upload.");
    Console.WriteLine("Coverage diagnostics are recorded separately. Use --status or --diagnostics to inspect them.");
    using var cancellation = new CancellationTokenSource();
    using var shutdown = new ConsoleShutdown(cancellation, "Roots Surface", TimeSpan.FromSeconds(5));
    using var childJob = ChildProcessJob.Create();
    var start = new ProcessStartInfo(executable)
    {
        UseShellExecute = false, CreateNoWindow = true, RedirectStandardInput = true,
        RedirectStandardOutput = true, RedirectStandardError = true, StandardOutputEncoding = new UTF8Encoding(false, true)
    };
    if (noOcr) start.ArgumentList.Add("--no-ocr");
    foreach (var name in excluded) { start.ArgumentList.Add("--exclude"); start.ArgumentList.Add(name); }
    using var child = Process.Start(start) ?? throw new InvalidOperationException("Cannot start Surface agent.");
    long sequence = 0, textEvents = 0, activityEvents = 0;
    Task? backupTask=null;
    try
    {
        childJob.Attach(child);
        backupTask=Task.Run(()=>MaintainBackups(database,cancellation.Token));
        // Only native coverage diagnostics are shown; do not log text or
        // arbitrary backend messages from the diagnostic pipe.
        child.ErrorDataReceived += (_, e) =>
        {
            if (e.Data is { } line && line.StartsWith("Surface: ", StringComparison.Ordinal))
            {
                var message=line.Length<=512?line:line[..512];
                try { File.WriteAllText(Path.Combine(directory,"surface-diagnostics.txt"),message); } catch(IOException) { }
                if(diagnostics) Console.Error.WriteLine(message);
            }
        };
        child.BeginErrorReadLine();
        Console.WriteLine($"Surface receiver PID {Environment.ProcessId}; native agent PID {child.Id}.");
        while (!cancellation.IsCancellationRequested)
        {
            // WaitAsync cancels our wait even if Windows' anonymous pipe read
            // itself cannot be interrupted. Closing the child ends that read.
            var line = await child.StandardOutput.ReadLineAsync().WaitAsync(cancellation.Token);
            if (line == null) break;
            var observation = SurfaceObservation.Parse(line, excluded);
            if (cancellation.IsCancellationRequested) break;
            // Do not turn our changing counters into new knowledge. Ordinary
            // terminal text remains eligible; only recognizable own diagnostics are skipped.
            if (observation.IsOwnDiagnostic()) continue;
            var rows = observation.ToRows(device, run, ++sequence);
            privateTree.Observe(rows);
            if(observation.Kind is "uia" or "ocr")textEvents++;else activityEvents++;
            if(diagnostics&&(sequence==1||sequence%100==0))
                Console.WriteLine($"Surface: {textEvents} text events, {activityEvents} activity intervals committed.");
        }
        if (!cancellation.IsCancellationRequested)
        {
            await child.WaitForExitAsync(cancellation.Token);
            if (child.ExitCode != 0) throw new InvalidOperationException("Surface agent exited: " + child.ExitCode);
            Console.WriteLine("Surface agent ended.");
        }
    }
    catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { }
    finally
    {
        try
        {
            cancellation.Cancel();
            // Signal the native agent via EOF. Do not wait for a blocked
            // UIA/OCR provider indefinitely and do not accept further records.
            try { child.StandardInput.Close(); } catch (IOException) { }
            if (!child.HasExited && !child.WaitForExit(1000))
            {
                try { child.Kill(entireProcessTree: true); }
                catch (InvalidOperationException) when (child.HasExited) { }
            }
        }
        finally
        {
            // Also covers a crashed receiver or partially stopped descendant.
            childJob.Dispose();
        }
        if(backupTask!=null){try{await backupTask.WaitAsync(TimeSpan.FromMilliseconds(500));}catch(TimeoutException){}catch(OperationCanceledException){}}
        if (!child.WaitForExit(1000))
            Console.Error.WriteLine($"Surface agent PID {child.Id} did not confirm exit.");
        Console.WriteLine($"Capture stopped. {textEvents} text events, {activityEvents} activity intervals committed.");
    }
    return 0;
}
catch (Exception e)
{
    // Do not log raw text or pipe payloads on failure.
    Console.Error.WriteLine("Surface failed: " + e.GetType().Name);
    return 1;
}

static async Task MaintainBackups(string database,CancellationToken ct)
{
    try{
        // Initial backup after capture has started, then a rolling refresh.
        await Task.Delay(TimeSpan.FromMinutes(1),ct);
        long last=-1;
        while(!ct.IsCancellationRequested){
            try{
                long revision;
                using(var tree=new NativeTree(database))revision=tree.Revision();
                if(revision!=last)last=ExperienceSnapshots.Backup(database);
            }catch(Exception e){Console.Error.WriteLine("Experience backup failed: "+e.GetType().Name);}
            await Task.Delay(TimeSpan.FromMinutes(15),ct);
        }
    }catch(OperationCanceledException) when(ct.IsCancellationRequested){}
}

static string SafeConsole(string s) => string.Concat(s.Select(c => char.IsControl(c) ? ' ' : c));
static int Usage()
{
    Console.WriteLine("launch-surface.cmd [--capture [--no-ocr] [--diagnostics] [--exclude app.exe]]");
    Console.WriteLine("launch-surface.cmd --status");
    Console.WriteLine("launch-surface.cmd --backup");
    Console.WriteLine("launch-surface.cmd --search \"text\"");
    return 2;
}

internal sealed record SurfaceObservation(string Kind, ulong Session, uint Pid, ulong Created, ulong Window,
    long StartUtc, long EndUtc, long StartMono, long EndMono, string ProcessName, int[] Bounds, string Text)
{
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
        if (session == 0 || pid == 0 || created == 0 || window == 0 || !ValidProcess(process) || excluded.Contains(process) ||
            start < 0 || end < start || end > DateTimeOffset.UtcNow.AddMinutes(1).ToUnixTimeMilliseconds() ||
            startMono < 0 || endMono < startMono || endMono - startMono > 15_000_000 ||
            bounds.Length != 4 || bounds[2] < bounds[0] || bounds[3] < bounds[1] ||
            string.IsNullOrWhiteSpace(text) || text.Contains('\0') || Encoding.UTF8.GetByteCount(text) > 8192 ||
            (!activity && (start != end || startMono != endMono)))
            throw new InvalidDataException("Invalid Surface event.");
        return new(p[0], session, pid, created, window, start, end, startMono, endMono, process, bounds, text);
    }
    internal bool IsOwnDiagnostic()
    {
        if(Kind is not ("uia" or "ocr"))return false;
        string text=Text.Trim();
        return text.StartsWith("Surface: process ",StringComparison.OrdinalIgnoreCase)||
            text.StartsWith("Surface: OCR backend ",StringComparison.OrdinalIgnoreCase)||
            text.StartsWith("Surface: no OCR engine",StringComparison.OrdinalIgnoreCase)||
            text.Contains("events accepted into private Tree",StringComparison.OrdinalIgnoreCase)||
            text.Contains("activity intervals committed.",StringComparison.OrdinalIgnoreCase)||
            text.Contains("safety-query-unavailable",StringComparison.OrdinalIgnoreCase)&&text.Contains("OCR",StringComparison.OrdinalIgnoreCase);
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
        // UIA and OCR observations emitted by one foreground read share this
        // stable screen identity, even when wall-clock timestamps collide.
        if (!activity) Add("screen", $"{parent}:screen:{StartMono}");
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
