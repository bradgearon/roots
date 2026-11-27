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
            if (args.Any(arg => arg is not ("--intelligence" or "--lan" or "--local-only")))
                throw new ArgumentException("Intelligence launch accepts --lan or --local-only. Translation has its own launch.cmd.");
            bool lan = args.Contains("--lan") && !args.Contains("--local-only");
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
            Task shutdown = Task.Delay(Timeout.Infinite, quit.Token);
            await Task.WhenAny(shutdown, engine.ExitTask);
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
}
