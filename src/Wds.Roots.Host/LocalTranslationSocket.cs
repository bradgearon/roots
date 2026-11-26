using System.Collections.Concurrent;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Http;
using Wds.Roots.Api.Translation;
using Wds.Roots.Api.LanguageDetection;

namespace Wds.Roots.Host;

/// <summary>
/// One persistent, authenticated, loopback-only browser extension connection.
/// Each message carries an ID, allowing bounded concurrent requests, cancellation
/// and out-of-order results without reconnecting or exposing the token to X.
/// </summary>
internal static class LocalTranslationSocket
{
    private const int MaxMessageBytes = 64 * 1024;
    private const int MaxInFlight = 8;
    private const int AuthDeadlineSeconds = 5;
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    public static async Task RunAsync(
        HttpContext context, ITranslationService translator, string secret,
        ILanguageDetectionService detector, bool lan = false)
    {
        if (!context.WebSockets.IsWebSocketRequest)
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            return;
        }

        // WebSocket connections are not protected by CORS. Allow Chrome extension
        // workers only; an arbitrary website must never initiate this protocol.
        bool mobile = lan && context.Request.IsHttps && string.IsNullOrEmpty(context.Request.Headers["Origin"].ToString()) && LocalTranslationGateway.IsAuthorized(context.Request.Headers["X-SixStars-Token"].ToString(), secret);
        if (lan && !mobile) { context.Response.StatusCode = 401; Trace("mobile upgrade rejected"); return; }
        var remote = context.Connection.RemoteIpAddress;
        if (remote?.IsIPv4MappedToIPv6 == true) remote = remote.MapToIPv4();
        if (!mobile && (!System.Net.IPAddress.IsLoopback(remote ?? System.Net.IPAddress.None) || !IsChromeExtensionOrigin(context.Request.Headers["Origin"].ToString())))
        {
            context.Response.StatusCode = StatusCodes.Status403Forbidden;
            return;
        }

        using WebSocket socket = await context.WebSockets.AcceptWebSocketAsync(new WebSocketAcceptContext { KeepAliveInterval = mobile ? TimeSpan.Zero : TimeSpan.FromSeconds(20) });
        if (mobile) Trace("mobile TLS socket authenticated");
        using var lifetime = CancellationTokenSource.CreateLinkedTokenSource(context.RequestAborted);
        using var sendGate = new SemaphoreSlim(1, 1);
        var active = new ConcurrentDictionary<string, CancellationTokenSource>(StringComparer.Ordinal);
        var tasks = new ConcurrentBag<Task>();

        try
        {
            if (!mobile) using (var authDeadline = CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token))
            {
                authDeadline.CancelAfter(TimeSpan.FromSeconds(AuthDeadlineSeconds));
                string? first = await ReceiveTextAsync(socket, authDeadline.Token);
                if (first == null || !IsAuthenticated(first, secret))
                {
                    await TrySendAsync(socket, sendGate,
                        new { type = "auth", ok = false }, lifetime.Token);
                    await TryCloseAsync(socket, WebSocketCloseStatus.PolicyViolation, "Unauthorized");
                    return;
                }
            }

            await TrySendAsync(socket, sendGate,
                new { type = "auth", ok = true, transport = "websocket" }, lifetime.Token);

            while (socket.State == WebSocketState.Open && !lifetime.IsCancellationRequested)
            {
                string? message = await ReceiveTextAsync(socket, lifetime.Token);
                if (message == null) break;

                JsonDocument document;
                try { document = JsonDocument.Parse(message); }
                catch (JsonException)
                {
                    await TrySendAsync(socket, sendGate,
                        new { type = "error", error = "invalid_json" }, lifetime.Token);
                    continue;
                }

                using (document)
                {
                    JsonElement root = document.RootElement;
                    if (root.ValueKind != JsonValueKind.Object) continue;
                    string? type = GetString(root, "type");
                    if (type == "ping")
                    {
                        await TrySendAsync(socket, sendGate,
                            new { type = "pong" }, lifetime.Token);
                        continue;
                    }

                    string? id = GetString(root, "id");
                    if (!ValidId(id)) continue;
                    if (type == "cancel")
                    {
                        if (active.TryGetValue(id!, out var running)) { try { running.Cancel(); } catch (ObjectDisposedException) { } }
                        continue;
                    }
                    if (type != "translate" && type != "detect") continue;

                    if (active.Count >= MaxInFlight)
                    {
                        await TrySendAsync(socket, sendGate,
                            new { type = "result", id, ok = false, error = "too_many_requests" },
                            lifetime.Token);
                        continue;
                    }

                    TranslationRequest? request;
                    try { request = JsonSerializer.Deserialize<TranslationRequest>(message, JsonOptions); }
                    catch (JsonException) { request = null; }
                    if (request == null || string.IsNullOrWhiteSpace(request.Text)
                        || request.Text.Length > 12000)
                    {
                        await TrySendAsync(socket, sendGate,
                            new { type = "result", id, ok = false, error = "invalid_request" },
                            lifetime.Token);
                        continue;
                    }

                    var requestCancellation =
                        CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token);
                    if (!active.TryAdd(id!, requestCancellation))
                    {
                        requestCancellation.Dispose();
                        await TrySendAsync(socket, sendGate,
                            new { type = "result", id, ok = false, error = "duplicate_id" },
                            lifetime.Token);
                        continue;
                    }

                    // Do not block the receive loop: it must remain available to
                    // process cancellation and further translation requests.
                    tasks.Add(TranslateAndRespondAsync(id!, request,
                        translator, detector, type == "detect", socket, sendGate, active,
                        requestCancellation, lifetime.Token));
                }
            }
        }
        catch (OperationCanceledException) { }
        catch (WebSocketException) { }
        catch (InvalidDataException) { }
        catch (JsonException) { }
        finally
        {
            lifetime.Cancel();
            foreach (var request in active.Values)
            {
                try { request.Cancel(); } catch (ObjectDisposedException) { }
            }

            try { await Task.WhenAll(tasks).WaitAsync(TimeSpan.FromSeconds(3)); }
            catch (Exception) { /* Requests are cancelled; do not block shutdown. */ }
            await TryCloseAsync(socket, WebSocketCloseStatus.NormalClosure, "Closed");
        }
    }

    private static void Trace(string message){ Console.WriteLine("[Roots compute] " + message); Wds.Roots.Host.Runtime.RootsDailyLog.Write("compute", message); }

    private static async Task TranslateAndRespondAsync(
        string id, TranslationRequest request, ITranslationService translator,
        ILanguageDetectionService detector, bool detectOnly, WebSocket socket, SemaphoreSlim sendGate,
        ConcurrentDictionary<string, CancellationTokenSource> active,
        CancellationTokenSource requestCancellation, CancellationToken lifetime)
    {
        var timer = System.Diagnostics.Stopwatch.StartNew();
        // IDs are supplied by clients; never log arbitrary client-controlled strings.
        Trace("request started operation=" + (detectOnly ? "detect" : "translate") + " chars=" + request.Text.Length);
        try
        {
            if (detectOnly)
            {
                var detected = await detector.DetectAsync(request.Text, requestCancellation.Token);
                if (!requestCancellation.IsCancellationRequested)
                    await TrySendAsync(socket, sendGate, new
                    {
                        type = "result", id, ok = true, operation = "detect",
                        language = detected.Language, confidence = detected.Confidence,
                        reliable = detected.Reliable, model = detected.Model, elapsedMs = detected.ElapsedMs
                    }, lifetime);
                return;
            }
            // The model remains loaded in the independent llama.cpp process.
            TranslationResponse answer = await translator.TranslateAsync(
                request, requestCancellation.Token);
            Trace("translation completed elapsed_ms=" + timer.ElapsedMilliseconds);
            if (!requestCancellation.IsCancellationRequested)
            {
                await TrySendAsync(socket, sendGate, new
                {
                    type = "result", id, ok = true,
                    translation = answer.Translation, sourceLang = answer.SourceLang,
                    targetLang = answer.TargetLang, model = answer.Model,
                    elapsedMs = answer.ElapsedMs
                }, lifetime);
            }
        }
        catch (OperationCanceledException) { /* Client cancelled or disconnected. */ }
        catch (ArgumentException)
        {
            await TrySendAsync(socket, sendGate,
                new { type = "result", id, ok = false, error = "invalid_request" }, lifetime);
        }
        catch (Exception e)
        {
            Trace("request failed error=" + e.GetType().Name);
            // Never log, echo or include source text or secret credentials.
            await TrySendAsync(socket, sendGate,
                new { type = "result", id, ok = false, error = detectOnly ? "detection_unavailable" : "translation_unavailable" },
                lifetime);
        }
        finally
        {
            active.TryRemove(id, out _);
            requestCancellation.Dispose();
        }
    }

    private static bool IsChromeExtensionOrigin(string value)
    {
        const string prefix = "chrome-extension://";
        if (!value.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) return false;
        string id = value.Substring(prefix.Length).TrimEnd('/');
        return id.Length == 32 && id.All(c => c is >= 'a' and <= 'p');
    }

    private static bool IsAuthenticated(string json, string secret)
    {
        try
        {
            using JsonDocument doc = JsonDocument.Parse(json);
            JsonElement root = doc.RootElement;
            return root.ValueKind == JsonValueKind.Object
                && GetString(root, "type") == "auth"
                && LocalTranslationGateway.IsAuthorized(GetString(root, "token") ?? "", secret);
        }
        catch (JsonException) { return false; }
    }

    private static string? GetString(JsonElement root, string name)
    {
        return root.TryGetProperty(name, out JsonElement value)
            && value.ValueKind == JsonValueKind.String ? value.GetString() : null;
    }

    private static bool ValidId(string? id)
    {
        return id is { Length: > 0 and <= 80 }
            && id.All(c => (c is >= 'a' and <= 'z') ||
                (c is >= 'A' and <= 'Z') || (c is >= '0' and <= '9') ||
                c is '-' or '_' or '.');
    }

    private static async Task<string?> ReceiveTextAsync(WebSocket socket, CancellationToken token)
    {
        using var data = new MemoryStream();
        byte[] buffer = new byte[8 * 1024];
        while (true)
        {
            WebSocketReceiveResult part = await socket.ReceiveAsync(
                new ArraySegment<byte>(buffer), token);
            if (part.MessageType == WebSocketMessageType.Close) return null;
            if (part.MessageType != WebSocketMessageType.Text)
                throw new InvalidDataException("Only text messages are allowed.");
            if (data.Length + part.Count > MaxMessageBytes)
                throw new InvalidDataException("Translation frame exceeds size limit.");
            data.Write(buffer, 0, part.Count);
            if (part.EndOfMessage) break;
        }
        return Encoding.UTF8.GetString(data.ToArray());
    }

    private static async Task TrySendAsync(
        WebSocket socket, SemaphoreSlim sendGate, object payload, CancellationToken token)
    {
        try
        {
            byte[] utf8 = JsonSerializer.SerializeToUtf8Bytes(payload, JsonOptions);
            await sendGate.WaitAsync(token);
            try
            {
                if (socket.State == WebSocketState.Open)
                    await socket.SendAsync(new ArraySegment<byte>(utf8),
                        WebSocketMessageType.Text, endOfMessage: true, cancellationToken: token);
            }
            finally { sendGate.Release(); }
        }
        catch (OperationCanceledException) { }
        catch (WebSocketException) { }
        catch (ObjectDisposedException) { }
        catch (InvalidOperationException) { }
    }

    private static async Task TryCloseAsync(
        WebSocket socket, WebSocketCloseStatus status, string reason)
    {
        try
        {
            if (socket.State is WebSocketState.Open or WebSocketState.CloseReceived)
                await socket.CloseAsync(status, reason, CancellationToken.None);
        }
        catch (Exception) { }
    }
}
