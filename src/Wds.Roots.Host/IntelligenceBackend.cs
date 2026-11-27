using System.Diagnostics;
using System.Net;
using System.Net.Http.Json;
using System.Text.Json;

namespace Wds.Roots.Host;

/// <summary>Bounded general-purpose Qwen inference. The raw model endpoint is
/// loopback-only; callers must pass the gateway's authentication first.</summary>
internal sealed class IntelligenceBackend : IDisposable
{
    private readonly HttpClient _http;
    private readonly bool _ownsHttp;
    private readonly TimeSpan _generationTimeout;
    private readonly SemaphoreSlim _generation = new(1, 1);

    internal IntelligenceBackend(HttpClient? http = null, TimeSpan? generationTimeout = null)
    {
        _ownsHttp = http == null;
        _http = http ?? new HttpClient(new HttpClientHandler { AllowAutoRedirect = false, UseProxy = false })
        { Timeout = Timeout.InfiniteTimeSpan, MaxResponseContentBufferSize = 256 * 1024 };
        _generationTimeout = generationTimeout ?? TimeSpan.FromSeconds(75);
    }

    internal async Task<bool> ReadyAsync(CancellationToken token)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(token);
        deadline.CancelAfter(TimeSpan.FromSeconds(3));
        try
        {
            using var health = await _http.GetAsync(IntelligenceProtocol.BackendUrl + "/health", deadline.Token).ConfigureAwait(false);
            if (health.StatusCode == HttpStatusCode.ServiceUnavailable) return false;
            if (health.StatusCode != HttpStatusCode.OK) throw new IntelligenceFailure(503, "intelligence_unavailable");
            using var status = JsonDocument.Parse(await health.Content.ReadAsStringAsync(deadline.Token).ConfigureAwait(false));
            if (status.RootElement.ValueKind != JsonValueKind.Object || !status.RootElement.TryGetProperty("status", out var state) ||
                state.ValueKind != JsonValueKind.String || state.GetString() != "ok") throw new IntelligenceFailure(503, "invalid_backend_health");

            using var models = await _http.GetAsync(IntelligenceProtocol.BackendUrl + "/v1/models", deadline.Token).ConfigureAwait(false);
            if (models.StatusCode == HttpStatusCode.ServiceUnavailable) return false;
            if (models.StatusCode != HttpStatusCode.OK) throw new IntelligenceFailure(503, "intelligence_unavailable");
            using var catalog = JsonDocument.Parse(await models.Content.ReadAsStringAsync(deadline.Token).ConfigureAwait(false));
            if (catalog.RootElement.ValueKind == JsonValueKind.Object && catalog.RootElement.TryGetProperty("data", out var data) && data.ValueKind == JsonValueKind.Array)
                foreach (var model in data.EnumerateArray())
                {
                    if (model.ValueKind != JsonValueKind.Object) continue;
                    if (model.TryGetProperty("id", out var id) && id.ValueKind == JsonValueKind.String && id.GetString() == IntelligenceProtocol.ModelAlias) return true;
                    if (model.TryGetProperty("aliases", out var aliases) && aliases.ValueKind == JsonValueKind.Array &&
                        aliases.EnumerateArray().Any(alias => alias.ValueKind == JsonValueKind.String && alias.GetString() == IntelligenceProtocol.ModelAlias)) return true;
                }
            throw new IntelligenceFailure(503, "backend_model_mismatch");
        }
        catch (HttpRequestException) { return false; }
        catch (OperationCanceledException) when (!token.IsCancellationRequested) { return false; }
        catch (JsonException) { throw new IntelligenceFailure(503, "invalid_backend_response"); }
    }

    internal async Task<IntelligenceResponse> GenerateAsync(IntelligenceRequest request, CancellationToken token)
    {
        IntelligenceProtocol.Validate(request);
        if (!await _generation.WaitAsync(0, token).ConfigureAwait(false)) throw new IntelligenceFailure(429, "busy");
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(token);
        deadline.CancelAfter(_generationTimeout);
        var timer = Stopwatch.StartNew();
        try
        {
            if (!await ReadyAsync(deadline.Token).ConfigureAwait(false)) throw new IntelligenceFailure(503, "intelligence_unavailable");
            using var response = await _http.PostAsJsonAsync(IntelligenceProtocol.BackendUrl + "/v1/chat/completions", new
            {
                model = IntelligenceProtocol.ModelAlias,
                messages = new[] { new { role = "user", content = request.Prompt } },
                max_tokens = request.MaxTokens, stream = false, temperature = 0.35,
                chat_template_kwargs = new { enable_thinking = false }
            }, deadline.Token).ConfigureAwait(false);
            if (response.StatusCode != HttpStatusCode.OK) throw new IntelligenceFailure(503, "intelligence_unavailable");
            using var document = JsonDocument.Parse(await response.Content.ReadAsStringAsync(deadline.Token).ConfigureAwait(false));
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object || !root.TryGetProperty("model", out var model) || model.ValueKind != JsonValueKind.String ||
                model.GetString() != IntelligenceProtocol.ModelAlias) throw new IntelligenceFailure(503, "backend_model_mismatch");
            if (!root.TryGetProperty("choices", out var choices) || choices.ValueKind != JsonValueKind.Array || choices.GetArrayLength() != 1 ||
                choices[0].ValueKind != JsonValueKind.Object || !choices[0].TryGetProperty("message", out var message) || message.ValueKind != JsonValueKind.Object ||
                !message.TryGetProperty("content", out var content) || content.ValueKind != JsonValueKind.String)
                throw new IntelligenceFailure(503, "invalid_backend_response");
            string text = content.GetString()?.Trim() ?? "";
            if (text.Length == 0 || text.Length > 16000) throw new IntelligenceFailure(503, "invalid_backend_response");
            return new IntelligenceResponse(IntelligenceProtocol.Version, IntelligenceProtocol.Model, IntelligenceProtocol.ModelSha, text, timer.ElapsedMilliseconds);
        }
        catch (OperationCanceledException) when (!token.IsCancellationRequested) { throw new IntelligenceFailure(504, "intelligence_timeout"); }
        catch (HttpRequestException) { throw new IntelligenceFailure(503, "intelligence_unavailable"); }
        catch (JsonException) { throw new IntelligenceFailure(503, "invalid_backend_response"); }
        finally { _generation.Release(); }
    }

    public void Dispose()
    {
        if (_ownsHttp) _http.Dispose();
        _generation.Dispose();
    }
}
