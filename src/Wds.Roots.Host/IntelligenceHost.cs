using System.Diagnostics;
using System.Security.Cryptography;
using System.Text.Json;
using Wds.Roots.Host.Runtime;

namespace Wds.Roots.Host;

internal static class IntelligenceHost
{
    internal static async Task<int> RunAsync(string[] args)
    {
        using var quit = new CancellationTokenSource();
        ConsoleCancelEventHandler cancel = (_, e) => { e.Cancel = true; quit.Cancel(); };
        Console.CancelKeyPress += cancel;
        ManagedModelProcess? engine = null;
        HostInstanceLease? lease = null;
        try
        {
            if (args.Any(arg => arg is not ("--intelligence" or "--lan" or "--local-only" or "--whirlwind")))
                throw new ArgumentException("Intelligence launch accepts --lan, --local-only or --whirlwind. Translation has its own launch.cmd.");
            bool remote = args.Contains("--whirlwind");
            bool lan = args.Contains("--lan") && !args.Contains("--local-only") && !remote;
            string root = Directory.GetCurrentDirectory(), aiRoot = AiRuntimeRoot.Resolve();
            string data = IntelligenceProtocol.DataDirectory();
            lease = HostInstanceLease.Acquire(Path.Combine(data, "intelligence-host.lock"), "Roots intelligence host", "8082 and 8221");
            RuntimePort.EnsureAvailable(IntelligenceProtocol.BackendPort);
            RuntimePort.EnsureAvailable(IntelligenceProtocol.Port, allAddresses: lan);

            const string profile = "config/intelligence.runtime.json";
            var manifest = JsonSerializer.Deserialize(await File.ReadAllTextAsync(Path.Combine(root, profile), quit.Token), RuntimeJson.Default.RuntimeConfig)
                ?? throw new InvalidDataException("Invalid intelligence runtime manifest.");
            var models = manifest.Models.Where(model => model.Enabled).ToArray();
            if (models.Length != 1 || models[0].Id != IntelligenceProtocol.ModelId ||
                !string.Equals(models[0].Sha256, IntelligenceProtocol.ModelSha, StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException("The intelligence profile must enable the pinned Qwen3.5 0.8B Q4_0 model advertised by this API.");
            using var provisionHttp = new HttpClient { Timeout = Timeout.InfiniteTimeSpan };
            using var supervisor = new ServiceSupervisor(root, aiRoot, provisionHttp, manifestRelativePath: profile);
            await supervisor.StartAsync(quit.Token);
            string modelPath = EnginePackInstaller.Under(aiRoot, models[0].Path);
            string directory = AiRuntimeRoot.ResolveEngineDirectory("llm", "engines/llm/llama-cpp-dynamic-win-x64");
            string executable = Path.Combine(directory, "llama-server.exe");
            if (!File.Exists(executable)) throw new FileNotFoundException("Intelligence llama-server not found", executable);
            if (!File.Exists(modelPath)) throw new FileNotFoundException("Intelligence model not found", modelPath);
            // The API advertises this exact weight identity even when a user
            // explicitly disabled provisioning and supplied an existing stack.
            Console.WriteLine("Verifying the intelligence model identity…");
            await using (var modelFile = File.OpenRead(modelPath))
            {
                string digest = Convert.ToHexString(await SHA256.HashDataAsync(modelFile, quit.Token));
                if (!digest.Equals(IntelligenceProtocol.ModelSha, StringComparison.OrdinalIgnoreCase))
                    throw new InvalidDataException("The intelligence model file does not match the selected Qwen weights. Restore the verified model before launching.");
            }
            RuntimePort.EnsureAvailable(IntelligenceProtocol.BackendPort);
            var start = new ProcessStartInfo(executable) { WorkingDirectory = directory };
            foreach (string argument in new[] { "-m", modelPath, "--host", "127.0.0.1", "--port", IntelligenceProtocol.BackendPort.ToString(),
                "--ctx-size", "4096", "--jinja", "--alias", IntelligenceProtocol.ModelAlias, "-ngl", supervisor.SelectedBackend == "cpu" ? "0" : "99" })
                start.ArgumentList.Add(argument);
            start.Environment[AiRuntimeRoot.EnvironmentVariable] = aiRoot;
            Console.WriteLine("Starting desktop intelligence: " + modelPath);
            Console.WriteLine("Intelligence log: " + RootsDailyLog.CurrentPath);
            engine = ManagedModelProcess.Start(start, line =>
            {
                Console.WriteLine("[Intelligence] " + line);
                RootsDailyLog.Write("Intelligence", line);
            }, "Intelligence");
            using var backend = new IntelligenceBackend();
            await engine.WaitUntilReadyAsync(backend.ReadyAsync, TimeSpan.FromMinutes(3), quit.Token);
            await using var gateway = new IntelligenceGateway(backend, lan);
            await gateway.StartAsync(quit.Token);
            await using var whirlwind = remote ? StartWhirlwind(root, data) : null;
            Task shutdown = Task.Delay(Timeout.Infinite, quit.Token);
            if (whirlwind != null) await Task.WhenAny(shutdown, engine.ExitTask, whirlwind.ExitTask);
            else await Task.WhenAny(shutdown, engine.ExitTask);
            if (!quit.IsCancellationRequested && whirlwind != null) await whirlwind.ThrowIfExitedAsync();
            if (!quit.IsCancellationRequested) await engine.ThrowIfExitedAsync();
            await shutdown;
            return 0;
        }
        catch (OperationCanceledException) when (quit.IsCancellationRequested) { return 0; }
        catch (Exception error)
        {
            Console.Error.WriteLine("Roots intelligence startup/runtime failed: " + error.Message);
            RootsDailyLog.Write("Intelligence", "Startup/runtime failed", error);
            Console.Error.WriteLine("Details: " + RootsDailyLog.CurrentPath);
            return 1;
        }
        finally
        {
            try { if (engine != null) await engine.DisposeAsync(); }
            finally { lease?.Dispose(); Console.CancelKeyPress -= cancel; }
        }
    }
    private static ManagedModelProcess StartWhirlwind(string root, string data)
    {
        string executable = Path.Combine(root, "native", "whirlwind", "target", "release", "roots-whirlwind.exe");
        if (!File.Exists(executable)) throw new FileNotFoundException("Build the Whirlwind host using build-whirlwind.cmd desktop.", executable);
        string internalDir = Path.Combine(data, "internal-mesh");
        Directory.CreateDirectory(internalDir);
        string grants = Path.Combine(internalDir, "allowed-devices.txt");
        if (!File.Exists(grants)) File.WriteAllText(grants, "");
        var start = new ProcessStartInfo(executable) { WorkingDirectory = internalDir };
        foreach (string argument in new[] { "serve", Path.Combine(internalDir, "identity.key"), grants }) start.ArgumentList.Add(argument);
        string? relay = Environment.GetEnvironmentVariable("ROOTS_INTERNAL_LIBP2P_RELAY");
        if (!string.IsNullOrWhiteSpace(relay)) start.ArgumentList.Add(relay.Trim());
        Console.WriteLine("Internal Mesh compute only · allow devices with allow-internal-device.cmd.");
        return ManagedModelProcess.Start(start, line => Console.WriteLine("[Whirlwind] " + line), "Whirlwind");
    }
}
