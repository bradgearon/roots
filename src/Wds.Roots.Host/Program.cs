using System.Diagnostics;
using System.Text.Json;
using Wds.Roots.Host;
using Wds.Roots.Host.Runtime;
var root = Directory.GetCurrentDirectory();
using var quit = new CancellationTokenSource();
Console.CancelKeyPress += (_, e) => { e.Cancel = true; quit.Cancel(); };
using var http = new HttpClient { Timeout = Timeout.InfiniteTimeSpan };
bool external = args.Contains("--external-model");
using var supervisor = new ServiceSupervisor(root, AiRuntimeRoot.Resolve(), http);
Process? engine = null;
try {
    if (!external) {
        await supervisor.StartAsync(quit.Token);
        var manifest = JsonSerializer.Deserialize(await File.ReadAllTextAsync(Path.Combine(root, "config", "runtime.json"), quit.Token), RuntimeJson.Default.RuntimeConfig) ?? throw new InvalidDataException("Invalid runtime manifest.");
        var selected = manifest.Models.Single(m => m.Enabled && m.Path.StartsWith("models/llm/", StringComparison.OrdinalIgnoreCase));
        string model = EnginePackInstaller.Under(AiRuntimeRoot.Resolve(), selected.Path);
        string dir = AiRuntimeRoot.ResolveEngineDirectory("llm", "engines/llm/llama-cpp-dynamic-win-x64");
        string exe = Path.Combine(dir, "llama-server.exe");
        if (!File.Exists(exe)) throw new FileNotFoundException("llama-server not found", exe);
        var start = new ProcessStartInfo(exe) { UseShellExecute = false, WorkingDirectory = dir, CreateNoWindow = true };
        foreach (var arg in new[]{"-m",model,"--host","127.0.0.1","--port","8081","--ctx-size","8192","--jinja","--no-prefill-assistant","--alias","plamo-3-translate","-ngl",supervisor.SelectedBackend=="cpu"?"0":"99"}) start.ArgumentList.Add(arg);
        engine = Process.Start(start) ?? throw new InvalidOperationException("Could not start PLaMo.");
    }
    using var readiness = new HttpClient { Timeout = TimeSpan.FromSeconds(3) };
    var deadline = DateTime.UtcNow.AddMinutes(3);
    while (true) {
        quit.Token.ThrowIfCancellationRequested();
        if (engine?.HasExited == true) throw new InvalidOperationException("PLaMo exited during startup.");
        try { using var response = await readiness.GetAsync("http://127.0.0.1:8081/health", quit.Token); if (response.IsSuccessStatusCode) break; }
        catch (HttpRequestException) { } catch (TaskCanceledException) when (!quit.IsCancellationRequested) { }
        if (DateTime.UtcNow > deadline) throw new TimeoutException("PLaMo readiness timed out.");
        await Task.Delay(500, quit.Token);
    }
    await using var gateway = new LocalTranslationGateway(8223, "http://127.0.0.1:8081/v1/completions", args.Contains("--lan") && !args.Contains("--local-only"));
    await gateway.StartAsync(quit.Token);
    await Task.Delay(Timeout.Infinite, quit.Token);
} catch (OperationCanceledException) when (quit.IsCancellationRequested) { }
finally { if (engine != null) { try { if (!engine.HasExited) engine.Kill(true); } finally { engine.Dispose(); } } }
