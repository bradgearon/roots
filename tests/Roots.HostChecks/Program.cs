using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Text;
using Wds.Roots.Host.Runtime;
using Wds.Roots.Host;
using System.Text.Json;
using System.IO.Compression;

// Child fixtures re-launch this executable. They neither load models nor touch
// user processes, and use only temporary files or ephemeral loopback ports.
if (args.Length > 0 && args[0].StartsWith("--fixture-", StringComparison.Ordinal))
{
    switch (args[0])
    {
        case "--fixture-exit":
            Console.Out.WriteLine("fixture standard output");
            for (int i = 0; i < 200; i++) Console.Error.WriteLine($"fixture loading detail {i}");
            Console.Error.WriteLine("fixture final startup error");
            return 23;
        case "--fixture-sleep":
            await Task.Delay(Timeout.InfiniteTimeSpan);
            return 0;
        case "--fixture-ready":
            PublishMarker(args[1], Environment.ProcessId.ToString());
            await Task.Delay(Timeout.InfiniteTimeSpan);
            return 0;
        case "--fixture-lease":
            using (HostInstanceLease.Acquire(args[1]))
            {
                PublishMarker(args[2], "lease held");
                await Task.Delay(Timeout.InfiniteTimeSpan);
            }
            return 0;
        case "--fixture-job-host":
            using (var job = ChildProcessJob.Create())
            using (var worker = Process.Start(Self("--fixture-sleep")) ?? throw new Exception("Could not start owned job fixture."))
            {
                try
                {
                    job.Attach(worker);
                    PublishMarker(args[1], worker.Id.ToString());
                    await Task.Delay(Timeout.InfiniteTimeSpan);
                }
                finally { await Stop(worker); }
            }
            return 0;
        default:
            throw new ArgumentException("Unknown child fixture.");
    }
}

string scratch = Path.Combine(Path.GetTempPath(), "Roots.HostChecks-" + Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(scratch);
int passed = 0;
try
{
    await Run("Compressed public Axon receipts survive camelCase and legacy PascalCase recovery", () =>
    {
        using var item=JsonDocument.Parse("{\"id\":\"fixture\",\"text\":\"Retained knowledge\"}");
        var receipt=new DesktopPublicAxonReceipt(item.RootElement.Clone(),new string('a',64),123456,true);
        // Reproduce the actual old archive writer (Web/camelCase), and legacy
        // individual receipt writer (default/PascalCase).
        foreach(var options in new[]{new JsonSerializerOptions(JsonSerializerDefaults.Web),new JsonSerializerOptions()})
        {
            using var bytes=new MemoryStream();
            using(var zip=new GZipStream(bytes,CompressionLevel.Fastest,true))
                JsonSerializer.Serialize(zip,new[]{receipt,receipt with{Promoted=false}},options);
            bytes.Position=0;
            using var unzip=new GZipStream(bytes,CompressionMode.Decompress);
            var recovered=JsonSerializer.Deserialize<DesktopPublicAxonReceipt[]>(unzip,DesktopPublicAxonReceipt.Options);
            Require(recovered is {Length:2},"Receipt count changed.");
            Require(recovered![0].Item.ValueKind==JsonValueKind.Object,"Receipt item was not read.");
            Require(recovered[0].Item.GetProperty("text").GetString()=="Retained knowledge","Receipt text changed.");
            Require(recovered[0].Peer==receipt.Peer&&recovered[0].At==receipt.At,"Receipt provenance changed.");
            Require(recovered[0].Promoted&&!recovered[1].Promoted,"Pending and promoted states changed.");
        }
        bool rejected=false;
        try { JsonSerializer.Deserialize<DesktopPublicAxonReceipt>("{\"peer\":\"fixture\",\"at\":1,\"promoted\":false}",DesktopPublicAxonReceipt.Options); }
        catch(JsonException){rejected=true;}
        Require(rejected,"An archive receipt missing its item was accepted.");
        return Task.CompletedTask;
    });

    await Run("Early exit drains output and reports the actual exit code", async () =>
    {
        var logged = new ConcurrentQueue<string>();
        await using var child = ManagedModelProcess.Start(Self("--fixture-exit"), logged.Enqueue);
        await Until(() => child.HasExited, "The exit fixture did not finish.");
        Exception error = await Fails(() => child.ThrowIfExitedAsync());
        Require(error.Message.Contains("23", StringComparison.Ordinal), "Missing decimal exit code.");
        Require(error.Message.Contains("0x00000017", StringComparison.OrdinalIgnoreCase), "Missing hexadecimal exit code.");
        Require(error.Message.Contains("fixture final startup error", StringComparison.Ordinal), "The final stderr line was lost before formatting the startup failure.");
        Require(logged.Any(line => line.Contains("fixture standard output", StringComparison.Ordinal)), "Standard output was not drained to the log.");
    });

    await Run("A healthy probe cannot hide an already exited owned child", async () =>
    {
        await using var child = ManagedModelProcess.Start(Self("--fixture-exit"));
        await Until(() => child.HasExited, "The exit fixture did not finish.");
        Exception error = await Fails(() => child.WaitUntilReadyAsync(_ => Task.FromResult(true), TimeSpan.FromSeconds(3), CancellationToken.None));
        Require(error.Message.Contains("23", StringComparison.Ordinal), "Readiness did not report the owned child's exit.");
    });

    await Run("Readiness succeeds while the owned child is alive", async () =>
    {
        string ready = Path.Combine(scratch, "ready.txt");
        await using var child = ManagedModelProcess.Start(Self("--fixture-ready", ready));
        await child.WaitUntilReadyAsync(_ => Task.FromResult(File.Exists(ready)), TimeSpan.FromSeconds(8), CancellationToken.None);
        Require(!child.HasExited, "Readiness returned after the child exited.");
        Require(File.ReadAllText(ready) == child.ProcessId.ToString(), "Readiness came from a different process.");
    });

    await Run("Readiness has a finite timeout and disposal cleans up", async () =>
    {
        var child = ManagedModelProcess.Start(Self("--fixture-sleep"));
        int pid = child.ProcessId;
        try
        {
            Exception error = await Fails(() => child.WaitUntilReadyAsync(_ => Task.FromResult(false), TimeSpan.FromMilliseconds(300), CancellationToken.None));
            Require(error is TimeoutException, "Readiness did not report a timeout.");
        }
        finally { await child.DisposeAsync(); }
        Require(!Running(pid), "The timed-out owned process survived disposal.");
    });

    await Run("Cancellation interrupts readiness and cleans up the child", async () =>
    {
        var child = ManagedModelProcess.Start(Self("--fixture-sleep"));
        int pid = child.ProcessId;
        using var cancel = new CancellationTokenSource(TimeSpan.FromMilliseconds(200));
        try
        {
            Exception error = await Fails(() => child.WaitUntilReadyAsync(_ => Task.FromResult(false), TimeSpan.FromSeconds(8), cancel.Token));
            Require(error is OperationCanceledException, "Readiness did not preserve cancellation.");
        }
        finally { await child.DisposeAsync(); }
        Require(!Running(pid), "The canceled owned process survived disposal.");
    });

    await Run("Disposal stops only the owned process and is repeatable", async () =>
    {
        using var unrelated = Process.Start(Self("--fixture-sleep")) ?? throw new Exception("Could not start unrelated fixture.");
        try
        {
            var owned = ManagedModelProcess.Start(Self("--fixture-sleep"));
            int ownedPid = owned.ProcessId;
            await owned.DisposeAsync();
            await owned.DisposeAsync();
            Require(!Running(ownedPid), "The owned fixture is still running.");
            Require(!unrelated.HasExited, "Disposal stopped an unrelated process.");
        }
        finally { await Stop(unrelated); }
    });

    await Run("An occupied port reports the conflict and leaves its owner alive", async () =>
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        int port = ((IPEndPoint)listener.LocalEndpoint).Port;
        listener.Stop();
        // Check reuse before accepting a connection: Windows can retain an
        // accepted socket in TIME_WAIT after the listener has been closed.
        RuntimePort.EnsureAvailable(port);
        listener = new TcpListener(IPAddress.Loopback, port);
        listener.Start();
        try
        {
            Exception error = FailsSync(() => RuntimePort.EnsureAvailable(port));
            Require(error.Message.Contains(port.ToString(), StringComparison.Ordinal), "Conflict diagnostics omitted the port number.");
            if (OperatingSystem.IsWindows())
                Require(error.Message.Contains($"PID {Environment.ProcessId}", StringComparison.Ordinal), "The Windows port ownership report did not identify this fixture's listener.");
            using var probe = new TcpClient();
            await probe.ConnectAsync(IPAddress.Loopback, port);
            using var accepted = await listener.AcceptTcpClientAsync();
            Require(probe.Connected && accepted.Connected, "The port owner was disturbed by preflight.");
        }
        finally { listener.Stop(); }
    });

    if (Socket.OSSupportsIPv6)
    {
        var listener = new TcpListener(IPAddress.IPv6Loopback, 0);
        listener.Server.DualMode = false;
        listener.Start();
        try
        {
            await Run("Localhost preflight checks IPv6 when requested", async () =>
            {
                int port = ((IPEndPoint)listener.LocalEndpoint).Port;
                RuntimePort.EnsureAvailable(port); // The model's IPv4-only bind is free.
                Exception error = FailsSync(() => RuntimePort.EnsureAvailable(port, includeIPv6: true));
                Require(error.Message.Contains(port.ToString(), StringComparison.Ordinal), "IPv6 conflict diagnostics omitted the port number.");
                if (OperatingSystem.IsWindows())
                    Require(error.Message.Contains($"PID {Environment.ProcessId}", StringComparison.Ordinal), "The Windows ownership report omitted the IPv6 listener's PID.");
                using var probe = new TcpClient(AddressFamily.InterNetworkV6);
                await probe.ConnectAsync(IPAddress.IPv6Loopback, port);
                using var accepted = await listener.AcceptTcpClientAsync();
                Require(probe.Connected && accepted.Connected, "The IPv6 listener was disturbed by preflight.");
            });
        }
        finally { listener.Stop(); }
    }
    else Console.WriteLine("SKIP: IPv6 localhost preflight requires IPv6 support.");

    await Run("The host lease excludes a second launcher and can be reacquired", () =>
    {
        string path = Path.Combine(scratch, "host.lock");
        using (HostInstanceLease.Acquire(path))
            FailsSync(() => { using var second = HostInstanceLease.Acquire(path); });
        using var reopened = HostInstanceLease.Acquire(path);
        return Task.CompletedTask;
    });

    await Run("A hard-killed host releases its lease without deleting the file", async () =>
    {
        string path = Path.Combine(scratch, "crashed-host.lock");
        string ready = Path.Combine(scratch, "lease-ready.txt");
        using var child = Process.Start(Self("--fixture-lease", path, ready)) ?? throw new Exception("Could not start lease fixture.");
        try
        {
            await Until(() => File.Exists(ready) || child.HasExited, "Lease fixture did not become ready.");
            Require(!child.HasExited, "Lease fixture exited before acquiring the file.");
            FailsSync(() => { using var second = HostInstanceLease.Acquire(path); });
            await Stop(child);
            Require(File.Exists(path), "The fixture did not leave its lease file for recovery.");
            using var recovered = HostInstanceLease.Acquire(path);
        }
        finally { await Stop(child); }
    });

    if (OperatingSystem.IsWindows())
    {
        await Run("Windows closes the owned process job when the launcher is hard-killed", async () =>
        {
            string pidFile = Path.Combine(scratch, "job-worker.txt");
            using var unrelated = Process.Start(Self("--fixture-sleep")) ?? throw new Exception("Could not start unrelated fixture.");
            using var host = Process.Start(Self("--fixture-job-host", pidFile)) ?? throw new Exception("Could not start job host fixture.");
            Process? worker = null;
            try
            {
                await Until(() => File.Exists(pidFile) || host.HasExited, "Job host fixture did not become ready.");
                Require(!host.HasExited, "Job host exited before assigning its worker.");
                worker = Process.GetProcessById(int.Parse(File.ReadAllText(pidFile)));
                _ = worker.SafeHandle; // Hold this exact process identity for cleanup; never target a reused PID.
                Require(!worker.HasExited, "The worker was not running before its host was killed.");
                host.Kill(entireProcessTree: false); // A tree kill would hide a broken Windows Job Object.
                using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(8));
                await host.WaitForExitAsync(deadline.Token);
                await worker.WaitForExitAsync(deadline.Token);
                Require(!unrelated.HasExited, "Job cleanup stopped an unrelated process.");
            }
            finally
            {
                await Stop(host);
                if (worker != null) { await Stop(worker); worker.Dispose(); }
                await Stop(unrelated);
            }
        });
    }

    await Run("Loading health is retried until the expected model is ready", async () =>
    {
        int healthCalls = 0, modelCalls = 0;
        using var client = new HttpClient(new FixtureHttpHandler((request, _) =>
        {
            if (request.RequestUri!.AbsolutePath == "/health")
                return Task.FromResult(++healthCalls == 1 ? Reply(HttpStatusCode.ServiceUnavailable, "{\"status\":\"loading model\"}") : Reply(HttpStatusCode.OK, "{\"status\":\"ok\"}"));
            Require(request.RequestUri.AbsolutePath == "/v1/models", "Unexpected readiness endpoint.");
            modelCalls++;
            return Task.FromResult(Reply(HttpStatusCode.OK, ModelList("plamo-3-translate")));
        }));
        Require(!await PLaMoReadiness.CheckAsync(client, CancellationToken.None), "Loading health was treated as ready.");
        Require(modelCalls == 0, "The model list was requested before health was ready.");
        Require(await PLaMoReadiness.CheckAsync(client, CancellationToken.None), "A ready compatible model was rejected.");
        Require(healthCalls == 2 && modelCalls == 1, "Readiness did not inspect the expected endpoints.");
    });

    await Run("Readiness accepts the expected primary model ID", async () =>
    {
        using var client = ModelClient(ModelList("plamo-3-translate"));
        Require(await PLaMoReadiness.CheckAsync(client, CancellationToken.None), "The expected primary model ID was rejected.");
    });

    await Run("Readiness accepts a declared alternate model alias", async () =>
    {
        using var client = ModelClient("{\"data\":[{\"object\":\"model\",\"id\":\"model-file-name\",\"aliases\":[\"plamo-3-translate\"]}]}");
        Require(await PLaMoReadiness.CheckAsync(client, CancellationToken.None), "The expected alternate alias was rejected.");
    });

    await Run("Healthy HTTP from the wrong model is rejected", async () =>
    {
        using var client = ModelClient(ModelList("another-model"));
        Exception error = await Fails(async () => { await PLaMoReadiness.CheckAsync(client, CancellationToken.None); });
        Require(error is InvalidDataException, "A healthy wrong model did not produce a compatibility error.");
        Require(error.Message.Contains("plamo-3-translate", StringComparison.Ordinal), "The compatibility error did not name the expected alias.");
    });

    await Run("Malformed or incompatible health JSON is rejected", async () =>
    {
        foreach (string health in new[] { "{", "[]", "{\"status\":\"loading\"}", "{\"status\":true}" })
        {
            using var client = ModelClient(ModelList("plamo-3-translate"), health);
            Exception error = await Fails(async () => { await PLaMoReadiness.CheckAsync(client, CancellationToken.None); });
            Require(error is InvalidDataException, "Malformed health was accepted or misclassified.");
        }
    });

    await Run("A denied health endpoint fails with its HTTP status", async () =>
    {
        using var client = new HttpClient(new FixtureHttpHandler((_, _) => Task.FromResult(Reply(HttpStatusCode.Unauthorized, "{}"))));
        Exception error = await Fails(async () => { await PLaMoReadiness.CheckAsync(client, CancellationToken.None); });
        Require(error is InvalidDataException && error.Message.Contains("401", StringComparison.Ordinal), "An unauthorized endpoint did not preserve its status.");
    });

    await Run("Malformed model lists cannot establish model compatibility", async () =>
    {
        foreach (string models in new[] { "{", "{\"data\":\"plamo-3-translate\"}", "{\"data\":[{\"id\":\"plamo-3-translate\"}]}" })
        {
            using var client = ModelClient(models);
            Exception error = await Fails(async () => { await PLaMoReadiness.CheckAsync(client, CancellationToken.None); });
            Require(error is InvalidDataException, "An invalid model list was accepted or misclassified.");
        }
    });

    await Run("External readiness stops at its deadline", async () =>
    {
        using var client = new HttpClient(new FixtureHttpHandler((_, _) => Task.FromResult(Reply(HttpStatusCode.ServiceUnavailable, "{}"))));
        var timer = Stopwatch.StartNew();
        Exception error = await Fails(() => PLaMoReadiness.WaitForExternalAsync(client, TimeSpan.FromMilliseconds(300), CancellationToken.None));
        Require(error is TimeoutException, "External startup did not report its timeout.");
        Require(timer.Elapsed < TimeSpan.FromSeconds(5), "External startup exceeded the bounded wait.");
    });

    await Run("External readiness preserves caller cancellation", async () =>
    {
        using var client = new HttpClient(new FixtureHttpHandler(async (_, token) =>
        {
            await Task.Delay(Timeout.InfiniteTimeSpan, token);
            return Reply(HttpStatusCode.OK, "{\"status\":\"ok\"}");
        }));
        using var cancel = new CancellationTokenSource(TimeSpan.FromMilliseconds(200));
        Exception error = await Fails(() => PLaMoReadiness.WaitForExternalAsync(client, TimeSpan.FromSeconds(8), cancel.Token));
        Require(error is OperationCanceledException, "Caller cancellation was converted into a model timeout.");
    });

    Console.WriteLine($"Roots host checks passed: {passed}.");
    if (!OperatingSystem.IsWindows())
        Console.WriteLine("SKIP: Windows Job Object hard-kill cleanup requires Windows; the process/port/lease checks passed.");
    return 0;
}
finally
{
    try { Directory.Delete(scratch, recursive: true); } catch (IOException) { }
}

async Task Run(string name, Func<Task> test)
{
    await test();
    passed++;
    Console.WriteLine("PASS: " + name);
}

static ProcessStartInfo Self(params string[] args)
{
    string executable = Environment.ProcessPath ?? throw new InvalidOperationException("Current process path is unavailable.");
    var start = new ProcessStartInfo(executable) { UseShellExecute = false, CreateNoWindow = true };
    if (Path.GetFileNameWithoutExtension(executable).Equals("dotnet", StringComparison.OrdinalIgnoreCase))
        start.ArgumentList.Add(Assembly.GetExecutingAssembly().Location);
    foreach (string arg in args) start.ArgumentList.Add(arg);
    return start;
}

static void PublishMarker(string path, string value)
{
    string temporary = path + ".writing";
    File.WriteAllText(temporary, value);
    File.Move(temporary, path);
}

static HttpResponseMessage Reply(HttpStatusCode status, string body) => new(status)
{
    Content = new StringContent(body, Encoding.UTF8, "application/json")
};

static string ModelList(string id) => "{\"data\":[{\"object\":\"model\",\"id\":\"" + id + "\"}]}";

static HttpClient ModelClient(string models, string health = "{\"status\":\"ok\"}") =>
    new(new FixtureHttpHandler((request, _) => Task.FromResult(Reply(HttpStatusCode.OK,
        request.RequestUri!.AbsolutePath == "/health" ? health : models))));

static void Require(bool condition, string message)
{
    if (!condition) throw new InvalidOperationException(message);
}

static async Task<Exception> Fails(Func<Task> action)
{
    try { await action(); }
    catch (Exception error) { return error; }
    throw new InvalidOperationException("Expected an exception.");
}

static Exception FailsSync(Action action)
{
    try { action(); }
    catch (Exception error) { return error; }
    throw new InvalidOperationException("Expected an exception.");
}

static async Task Until(Func<bool> ready, string failure)
{
    using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(8));
    try { while (!ready()) await Task.Delay(25, deadline.Token); }
    catch (OperationCanceledException) { throw new TimeoutException(failure); }
}

static bool Running(int pid)
{
    try { using var process = Process.GetProcessById(pid); return !process.HasExited; }
    catch (ArgumentException) { return false; }
}

static async Task Stop(Process process)
{
    if (!process.HasExited) process.Kill(entireProcessTree: true);
    using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(8));
    await process.WaitForExitAsync(deadline.Token);
}

sealed class FixtureHttpHandler(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> response) : HttpMessageHandler
{
    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) => response(request, cancellationToken);
}
