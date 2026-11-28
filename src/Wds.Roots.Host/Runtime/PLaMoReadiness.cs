using System.Net;
using System.Text.Json;

namespace Wds.Roots.Host.Runtime;

/// <summary>Checks the pinned llama.cpp API contract and expected model alias.
/// This establishes endpoint compatibility, not ownership or model provenance.</summary>
internal static class PLaMoReadiness
{
    internal const string ModelAlias = "plamo-3-translate";
    internal const string BaseUrl = "http://127.0.0.1:8081";

    public static async Task<bool> CheckAsync(HttpClient client, CancellationToken token)
    {
        try
        {
            using var health = await client.GetAsync(BaseUrl + "/health", token).ConfigureAwait(false);
            if (health.StatusCode == HttpStatusCode.ServiceUnavailable) return false;
            if (health.StatusCode != HttpStatusCode.OK)
                throw new InvalidDataException($"The model endpoint on port 8081 returned HTTP {(int)health.StatusCode} for /health. Expected the local PLaMo llama-server.");
            using var status = JsonDocument.Parse(await health.Content.ReadAsStringAsync(token).ConfigureAwait(false));
            if (status.RootElement.ValueKind != JsonValueKind.Object ||
                !status.RootElement.TryGetProperty("status", out var state) || state.ValueKind != JsonValueKind.String || state.GetString() != "ok")
                throw new InvalidDataException("Port 8081 answered /health but did not return the expected llama.cpp ready status.");

            using var models = await client.GetAsync(BaseUrl + "/v1/models", token).ConfigureAwait(false);
            if (models.StatusCode == HttpStatusCode.ServiceUnavailable) return false;
            if (models.StatusCode != HttpStatusCode.OK)
                throw new InvalidDataException($"Port 8081 returned HTTP {(int)models.StatusCode} for /v1/models; the PLaMo model alias could not be verified.");
            using var document = JsonDocument.Parse(await models.Content.ReadAsStringAsync(token).ConfigureAwait(false));
            if (document.RootElement.ValueKind == JsonValueKind.Object &&
                document.RootElement.TryGetProperty("data", out var data) && data.ValueKind == JsonValueKind.Array)
            {
                foreach (var model in data.EnumerateArray())
                {
                    if (model.ValueKind != JsonValueKind.Object ||
                        !model.TryGetProperty("object", out var kind) || kind.ValueKind != JsonValueKind.String || kind.GetString() != "model") continue;
                    if (model.TryGetProperty("id", out var id) && id.ValueKind == JsonValueKind.String && id.GetString() == ModelAlias) return true;
                    if (model.TryGetProperty("aliases", out var aliases) && aliases.ValueKind == JsonValueKind.Array &&
                        aliases.EnumerateArray().Any(alias => alias.ValueKind == JsonValueKind.String && alias.GetString() == ModelAlias)) return true;
                }
            }
            throw new InvalidDataException($"Port 8081 is healthy but does not advertise the expected '{ModelAlias}' model. Use the intended PLaMo server with --alias {ModelAlias}.");
        }
        catch (HttpRequestException) { return false; }
        catch (TaskCanceledException) when (!token.IsCancellationRequested) { return false; }
        catch (JsonException error) { throw new InvalidDataException("Port 8081 returned malformed model health or model-list JSON. Check which process owns this port.", error); }
    }

    public static async Task WaitForExternalAsync(HttpClient client, TimeSpan timeout, CancellationToken token)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(token);
        deadline.CancelAfter(timeout);
        try
        {
            while (!await CheckAsync(client, deadline.Token).ConfigureAwait(false))
                await Task.Delay(250, deadline.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (!token.IsCancellationRequested)
        {
            throw new TimeoutException($"No compatible external PLaMo server became ready at {BaseUrl} within {timeout.TotalSeconds:0} seconds.");
        }
    }
}
