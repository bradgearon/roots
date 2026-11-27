using System.Diagnostics;
using System.Text.Json;
using Wds.Roots.Host;
using Wds.Roots.Host.Runtime;

if (args.Contains("--intelligence")) return await IntelligenceHost.RunAsync(args);

var root = Directory.GetCurrentDirectory();
using var quit = new CancellationTokenSource();
Console.CancelKeyPress += (_, e) => { e.Cancel = true; quit.Cancel(); };
using var http = new HttpClient { Timeout = Timeout.InfiniteTimeSpan };
using var readiness = new HttpClient(new HttpClientHandler { AllowAutoRedirect = false, UseProxy = false })
{
    Timeout = TimeSpan.FromSeconds(3), MaxResponseContentBufferSize = 64 * 1024
};
bool external = args.Contains("--external-model");
bool lan = args.Contains("--lan") && !args.Contains("--local-only");
string aiRoot = AiRuntimeRoot.Resolve();
using var supervisor = new ServiceSupervisor(root, aiRoot, http);
ManagedModelProcess? engine = null;
HostInstanceLease? lease = null;
int exitCode = 0;

try
{
    string local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
    if (string.IsNullOrWhiteSpace(local)) local = Path.GetTempPath();
    lease = HostInstanceLease.Acquire(Path.Combine(local, "Roots", "desktop-host.lock"));

    // Reject existing listeners before provisioning or allocating another model.
    // Never adopt or kill another application merely because it uses this port.
    if (!external) RuntimePort.EnsureAvailable(8081);
    RuntimePort.EnsureAvailable(8224, includeIPv6: true);
    if (lan) RuntimePort.EnsureAvailable(8223, allAddresses: true);

    if (!external)
    {
        await supervisor.StartAsync(quit.Token);
        var manifest = JsonSerializer.Deserialize(
            await File.ReadAllTextAsync(Path.Combine(root, "config", "runtime.json"), quit.Token),
            RuntimeJson.Default.RuntimeConfig) ?? throw new InvalidDataException("Invalid runtime manifest.");
        var enabledModels = manifest.Models.Where(m => m.Enabled && m.Path.StartsWith("models/llm/", StringComparison.OrdinalIgnoreCase)).ToArray();
        if (enabledModels.Length != 1) throw new InvalidDataException("Enable exactly one LLM model in config/runtime.json.");
        string model = EnginePackInstaller.Under(aiRoot, enabledModels[0].Path);
        // Use the existing Six Stars runtime directly; a shared .active pointer
        // can otherwise select a separately provisioned, incompatible engine.
        string dir = EnginePackInstaller.Under(aiRoot, "engines/llm/llama-cpp-dynamic-win-x64");
        string exe = Path.Combine(dir, "llama-server.exe");
        if (!File.Exists(exe)) throw new FileNotFoundException("llama-server not found", exe);
        if (!File.Exists(model)) throw new FileNotFoundException("PLaMo model not found", model);
        // Optional configured services, or another application, may have bound
        // the model port during provisioning. Check again before spawning.
        RuntimePort.EnsureAvailable(8081);
        var start = new ProcessStartInfo(exe) { WorkingDirectory = dir };
        foreach (var arg in new[] { "-m", model, "--host", "127.0.0.1", "--port", "8081", "--ctx-size", "8192", "--jinja", "--no-prefill-assistant", "--alias", PLaMoReadiness.ModelAlias, "-ngl", supervisor.SelectedBackend == "cpu" ? "0" : "99" })
            start.ArgumentList.Add(arg);
        start.Environment[AiRuntimeRoot.EnvironmentVariable] = aiRoot;
        Console.WriteLine($"Starting PLaMo: {exe}");
        Console.WriteLine($"PLaMo model: {model}");
        Console.WriteLine($"PLaMo log: {RootsDailyLog.CurrentPath}");
        engine = ManagedModelProcess.Start(start, line =>
        {
            Console.WriteLine("[PLaMo] " + line);
            RootsDailyLog.Write("PLaMo", line);
        });
        Console.WriteLine($"PLaMo PID {engine.ProcessId}; waiting for {PLaMoReadiness.BaseUrl}…");
        // This is the server we launched with the configured model and alias.
        // Restore the original /health readiness contract for the owned process;
        // /v1/models verification remains required for an externally managed server.
        await engine.WaitUntilReadyAsync(async token =>
        {
            try
            {
                using var response = await readiness.GetAsync(PLaMoReadiness.BaseUrl + "/health", token);
                return response.IsSuccessStatusCode;
            }
            catch (HttpRequestException) { return false; }
            catch (TaskCanceledException) when (!token.IsCancellationRequested) { return false; }
        }, TimeSpan.FromMinutes(3), quit.Token);
    }
    else
    {
        Console.WriteLine($"Checking the external PLaMo server at {PLaMoReadiness.BaseUrl}; its process remains externally managed.");
        await PLaMoReadiness.WaitForExternalAsync(readiness, TimeSpan.FromMinutes(3), quit.Token);
    }

    Console.WriteLine(external ? "PLaMo ready; model alias verified." : "PLaMo ready.");
    await using var gateway = new LocalTranslationGateway(8223, PLaMoReadiness.BaseUrl + "/v1/completions", lan);
    await gateway.StartAsync(quit.Token);
    Task shutdown = Task.Delay(Timeout.Infinite, quit.Token);
    if (engine != null)
    {
        // A later model crash must close the gateway rather than leave a healthy
        // looking desktop bridge whose model process has already disappeared.
        await Task.WhenAny(shutdown, engine.ExitTask);
        if (!quit.IsCancellationRequested) await engine.ThrowIfExitedAsync();
    }
    await shutdown;
}
catch (OperationCanceledException) when (quit.IsCancellationRequested) { }
catch (Exception error)
{
    exitCode = 1;
    Console.Error.WriteLine("Roots startup/runtime failed: " + error.Message);
    RootsDailyLog.Write("Host", "Startup/runtime failed", error);
    Console.Error.WriteLine("Details: " + RootsDailyLog.CurrentPath);
}
finally
{
    try { if (engine != null) await engine.DisposeAsync(); }
    finally { lease?.Dispose(); }
}
return exitCode;
