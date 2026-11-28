using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Wds.Roots.Host;

int passed = 0;
await Run("HTTPS rejects absent or wrong intelligence credentials before querying the model", async () =>
{
    await using var fixture = await Fixture.Start();
    using var missing = await fixture.Send(HttpMethod.Get, IntelligenceProtocol.HealthPath, authorized: false);
    Require(missing.StatusCode == HttpStatusCode.Unauthorized, "Missing credentials were accepted.");
    using var request = fixture.Request(HttpMethod.Get, IntelligenceProtocol.HealthPath);
    request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", new string('0', 64));
    using var wrong = await fixture.Client.SendAsync(request);
    Require(wrong.StatusCode == HttpStatusCode.Unauthorized && fixture.Calls == 0, "Wrong credentials reached the backend.");
});
await Run("Authenticated health advertises the exact general-intelligence model", async () =>
{
    await using var fixture = await Fixture.Start();
    using var response = await fixture.Send(HttpMethod.Get, IntelligenceProtocol.HealthPath);
    Require(response.StatusCode == HttpStatusCode.OK, "Health did not succeed.");
    using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
    Require(json.RootElement.GetProperty("capability").GetString() == "intelligence" &&
        json.RootElement.GetProperty("model").GetString() == IntelligenceProtocol.Model &&
        json.RootElement.GetProperty("modelSha").GetString() == IntelligenceProtocol.ModelSha, "Wrong capability/model advertised.");
});
await Run("Generation forwards a bounded non-thinking chat request and returns matching identity", async () =>
{
    string? sent = null;
    await using var fixture = await Fixture.Start(async (request, token) =>
    {
        if (request.RequestUri!.AbsolutePath == "/v1/chat/completions") sent = await request.Content!.ReadAsStringAsync(token);
        return Fixture.Default(request);
    });
    using var response = await fixture.Generate(Fixture.Valid("Explain the measured connection failure."));
    Require(response.StatusCode == HttpStatusCode.OK, "Generation did not succeed.");
    using var result = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
    Require(result.RootElement.GetProperty("text").GetString() == "Measured connection result." &&
        result.RootElement.GetProperty("modelSha").GetString() == IntelligenceProtocol.ModelSha, "Missing output or model identity.");
    using var body = JsonDocument.Parse(sent ?? throw new Exception("No backend request."));
    Require(body.RootElement.GetProperty("model").GetString() == IntelligenceProtocol.ModelAlias &&
        body.RootElement.GetProperty("max_tokens").GetInt32() == 384 &&
        !body.RootElement.GetProperty("chat_template_kwargs").GetProperty("enable_thinking").GetBoolean() &&
        body.RootElement.GetProperty("messages")[0].GetProperty("content").GetString() == "Explain the measured connection failure.", "Backend request changed model, prompt, budget or reasoning mode.");
});
await Run("Wrong selected model or weight hash is rejected before any model work", async () =>
{
    await using var fixture = await Fixture.Start();
    using var wrongModel = await fixture.Generate(Fixture.Valid() with { Model = "ALTERNATE" });
    using var wrongHash = await fixture.Generate(Fixture.Valid() with { ModelSha = new string('f', 64) });
    Require((int)wrongModel.StatusCode == 409 && (int)wrongHash.StatusCode == 409 && fixture.Calls == 0, "Model mismatch was silently substituted.");
});
await Run("Version, empty input, prompt and token budgets are enforced without inference", async () =>
{
    await using var fixture = await Fixture.Start();
    foreach (var request in new[] { Fixture.Valid() with { Version = 2 }, Fixture.Valid(" "), Fixture.Valid(new string('x', 3001)),
        Fixture.Valid() with { MaxTokens = 0 }, Fixture.Valid() with { MaxTokens = 385 } })
    {
        using var response = await fixture.Generate(request);
        Require(response.StatusCode == HttpStatusCode.BadRequest, "An invalid request was accepted.");
    }
    Require(fixture.Calls == 0, "Invalid input reached the model.");
});
await Run("Malformed JSON and oversized bodies fail safely", async () =>
{
    await using var fixture = await Fixture.Start();
    using var invalid = fixture.Request(HttpMethod.Post, IntelligenceProtocol.GeneratePath);
    invalid.Content = new StringContent("{broken", Encoding.UTF8, "application/json");
    using var response = await fixture.Client.SendAsync(invalid);
    Require(response.StatusCode == HttpStatusCode.BadRequest, "Malformed JSON was accepted.");
    using var large = fixture.Request(HttpMethod.Post, IntelligenceProtocol.GeneratePath);
    large.Content = new StringContent(JsonSerializer.Serialize(new { padding = new string('x', 40000) }), Encoding.UTF8, "application/json");
    using var oversized = await fixture.Client.SendAsync(large);
    Require((int)oversized.StatusCode == 413 && fixture.Calls == 0, "Body bound did not prevent backend use.");
});
await Run("A healthy different backend model is rejected before generation", async () =>
{
    int generations = 0;
    await using var fixture = await Fixture.Start((request, token) =>
    {
        if (request.RequestUri!.AbsolutePath == "/v1/chat/completions") generations++;
        return Task.FromResult(request.RequestUri.AbsolutePath == "/v1/models"
            ? Fixture.Json(new { data = new[] { new { id = "plamo-3-translate", @object = "model" } } }) : Fixture.Default(request));
    });
    using var response = await fixture.Generate(Fixture.Valid());
    Require((int)response.StatusCode == 503 && generations == 0, "A translation backend was used for intelligence.");
    Require((await response.Content.ReadAsStringAsync()).Contains("backend_model_mismatch"), "Wrong model diagnosis omitted.");
});
await Run("A changed model in the completion response cannot be reported as selected Qwen", async () =>
{
    await using var fixture = await Fixture.Start((request, token) => Task.FromResult(request.RequestUri!.AbsolutePath == "/v1/chat/completions"
        ? Fixture.Json(new { model = "different-model", choices = new[] { new { message = new { content = "wrong output" } } } }) : Fixture.Default(request)));
    using var response = await fixture.Generate(Fixture.Valid());
    Require((int)response.StatusCode == 503 && !(await response.Content.ReadAsStringAsync()).Contains("wrong output"), "Mismatched completion escaped validation.");
});
await Run("Loading backend is reported unavailable without attempting generation", async () =>
{
    await using var fixture = await Fixture.Start((request, token) => Task.FromResult(new HttpResponseMessage(HttpStatusCode.ServiceUnavailable)));
    using var response = await fixture.Generate(Fixture.Valid());
    Require((int)response.StatusCode == 503 && fixture.Calls == 1, "Loading backend triggered generation or a retry loop.");
});
await Run("Generation timeout cancels backend work and releases the slot", async () =>
{
    bool block = true;
    var cancelled = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
    await using var fixture = await Fixture.Start(async (request, token) =>
    {
        if (block && request.RequestUri!.AbsolutePath == "/v1/chat/completions")
            try { await Task.Delay(Timeout.Infinite, token); } catch (OperationCanceledException) { cancelled.TrySetResult(); throw; }
        return Fixture.Default(request);
    }, TimeSpan.FromMilliseconds(150));
    using var response = await fixture.Generate(Fixture.Valid());
    Require((int)response.StatusCode == 504, "Generation did not stop at its deadline.");
    await cancelled.Task.WaitAsync(TimeSpan.FromSeconds(3));
    block = false;
    using var retry = await fixture.Generate(Fixture.Valid());
    Require(retry.StatusCode == HttpStatusCode.OK, "Timed-out request retained the only inference slot.");
});
await Run("Client cancellation reaches the model and concurrent generation is bounded", async () =>
{
    var started = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
    var cancelled = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
    await using var fixture = await Fixture.Start(async (request, token) =>
    {
        if (request.RequestUri!.AbsolutePath == "/v1/chat/completions")
        {
            started.TrySetResult();
            try { await Task.Delay(Timeout.Infinite, token); } catch (OperationCanceledException) { cancelled.TrySetResult(); throw; }
        }
        return Fixture.Default(request);
    });
    using var abort = new CancellationTokenSource();
    Task<HttpResponseMessage> first = fixture.Generate(Fixture.Valid(), abort.Token);
    await started.Task.WaitAsync(TimeSpan.FromSeconds(5));
    using var busy = await fixture.Generate(Fixture.Valid());
    Require((int)busy.StatusCode == 429, "Concurrent requests escaped the generation bound.");
    abort.Cancel();
    try { using var unexpected = await first; throw new Exception("Client cancellation did not propagate."); } catch (OperationCanceledException) { }
    await cancelled.Task.WaitAsync(TimeSpan.FromSeconds(5));
});
await Run("Browser-origin requests cannot use paired mobile intelligence", async () =>
{
    await using var fixture = await Fixture.Start();
    using var request = fixture.Request(HttpMethod.Get, IntelligenceProtocol.HealthPath);
    request.Headers.Add("Origin", "https://example.invalid");
    using var response = await fixture.Client.SendAsync(request);
    Require(response.StatusCode == HttpStatusCode.Forbidden && fixture.Calls == 0, "Browser origin reached intelligence.");
});
Console.WriteLine($"Roots intelligence checks passed: {passed}.");

async Task Run(string name, Func<Task> action)
{
    await action(); passed++; Console.WriteLine("PASS: " + name);
}
static void Require(bool condition, string message) { if (!condition) throw new Exception(message); }

sealed class Fixture : IAsyncDisposable
{
    private readonly string _directory;
    private readonly IntelligenceBackend _backend;
    private readonly IntelligenceGateway _gateway;
    private readonly HttpClient _backendClient;
    private readonly Handler _handler;
    private readonly string _token;
    internal HttpClient Client { get; }
    internal int Calls => _handler.Calls;
    private Fixture(string directory, IntelligenceBackend backend, IntelligenceGateway gateway, HttpClient backendClient, Handler handler, HttpClient client)
    { _directory = directory; _backend = backend; _gateway = gateway; _backendClient = backendClient; _handler = handler; Client = client; _token = File.ReadAllText(Path.Combine(directory, "intelligence-token.txt")).Trim(); }

    internal static IntelligenceRequest Valid(string prompt = "Diagnose this connection.") =>
        new(1, IntelligenceProtocol.Model, IntelligenceProtocol.ModelSha, prompt, 384);

    internal static async Task<Fixture> Start(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>>? action = null, TimeSpan? timeout = null)
    {
        string directory = Path.Combine(Path.GetTempPath(), "RootsIntelligenceChecks", Guid.NewGuid().ToString("N"));
        var handler = new Handler(action ?? ((request, token) => Task.FromResult(Default(request))));
        var backendClient = new HttpClient(handler) { Timeout = Timeout.InfiniteTimeSpan };
        var backend = new IntelligenceBackend(backendClient, timeout);
        var gateway = new IntelligenceGateway(backend, false, 0, directory);
        try
        {
            await gateway.StartAsync();
            string pin = File.ReadAllText(Path.Combine(directory, "compute-tls-sha256.txt")).Trim();
            var client = new HttpClient(new HttpClientHandler
            {
                AllowAutoRedirect = false, UseProxy = false,
                ServerCertificateCustomValidationCallback = (_, certificate, _, _) => certificate != null &&
                    Convert.ToHexString(certificate.GetCertHash(HashAlgorithmName.SHA256)).Equals(pin, StringComparison.OrdinalIgnoreCase)
            }) { BaseAddress = gateway.Address, Timeout = TimeSpan.FromSeconds(10) };
            return new Fixture(directory, backend, gateway, backendClient, handler, client);
        }
        catch { await gateway.DisposeAsync(); backend.Dispose(); backendClient.Dispose(); Directory.Delete(directory, true); throw; }
    }

    internal HttpRequestMessage Request(HttpMethod method, string path, bool authorized = true)
    {
        var request = new HttpRequestMessage(method, path);
        if (authorized) request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _token);
        return request;
    }
    internal async Task<HttpResponseMessage> Send(HttpMethod method, string path, bool authorized = true)
    { using var request = Request(method, path, authorized); return await Client.SendAsync(request); }
    internal async Task<HttpResponseMessage> Generate(IntelligenceRequest value, CancellationToken token = default)
    { using var request = Request(HttpMethod.Post, IntelligenceProtocol.GeneratePath); request.Content = JsonContent.Create(value); return await Client.SendAsync(request, token); }

    internal static HttpResponseMessage Default(HttpRequestMessage request) => request.RequestUri!.AbsolutePath switch
    {
        "/health" => Json(new { status = "ok" }),
        "/v1/models" => Json(new { data = new[] { new { id = IntelligenceProtocol.ModelAlias, @object = "model" } } }),
        "/v1/chat/completions" => Json(new { model = IntelligenceProtocol.ModelAlias, choices = new[] { new { message = new { content = "Measured connection result." } } } }),
        _ => new HttpResponseMessage(HttpStatusCode.NotFound)
    };
    internal static HttpResponseMessage Json(object value) => new(HttpStatusCode.OK) { Content = JsonContent.Create(value) };
    public async ValueTask DisposeAsync()
    { Client.Dispose(); await _gateway.DisposeAsync(); _backend.Dispose(); _backendClient.Dispose(); Directory.Delete(_directory, true); }

    private sealed class Handler(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> action) : HttpMessageHandler
    {
        internal int Calls;
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token)
        { Interlocked.Increment(ref Calls); return action(request, token); }
    }
}
