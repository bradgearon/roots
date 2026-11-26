using System.Security.Cryptography;
using System.Diagnostics;
using Wds.Roots.Host.Runtime;
using System.Net.WebSockets;
using System.IO;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Http;
using Wds.Roots.Api.Translation;

namespace Wds.Roots.Host;

/// <summary>Paired-device authenticated encryption for automatic LAN routing.
/// The network address is a locator, not the identity. No token in HTTP.
/// Direction-separated keys; response bound to request nonce; short replay window.
/// </summary>
internal sealed class SecureComputeRoute
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    private static readonly byte[] RequestAad = Encoding.ASCII.GetBytes("roots.compute.v1.request");
    private readonly byte[] _requestKey, _responseKey;
    private readonly PLaMoLocalTranslationService _translator;
    private readonly Dictionary<string,long> _seen = new();
    private readonly object _gate = new();
    public SecureComputeRoute(string secret, PLaMoLocalTranslationService translator)
    {
        byte[] key = Convert.FromHexString(secret);
        _requestKey = HMACSHA256.HashData(key, Encoding.ASCII.GetBytes("roots.compute.v1.request"));
        _responseKey = HMACSHA256.HashData(key, Encoding.ASCII.GetBytes("roots.compute.v1.response"));
        CryptographicOperations.ZeroMemory(key);
        _translator = translator;
    }

    private static void Trace(string message)
    {
        Console.WriteLine("[Roots compute] " + message);
        RootsDailyLog.Write("compute", message);
    }

    public async Task<IResult> ExchangeAsync(HttpContext context)
    {
        Envelope? wire;
        try { wire = await context.Request.ReadFromJsonAsync<Envelope>(cancellationToken: context.RequestAborted); }
        catch (OperationCanceledException) { return Results.StatusCode(499); }
        catch { return Results.BadRequest(); }
        var (code, reply) = await ExchangeCoreAsync(wire, context.RequestAborted);
        return reply == null ? Results.StatusCode(code) : Results.Json(reply, Json);
    }

    // One encrypted envelope per WebSocket text message. No plaintext bearer
    // key, no unauthenticated capabilities and no arbitrary model endpoint.
    public async Task RunSocketAsync(HttpContext context)
    {
        if (!context.WebSockets.IsWebSocketRequest || !string.IsNullOrEmpty(context.Request.Headers["Origin"]))
        { Trace("upgrade rejected: websocket=" + context.WebSockets.IsWebSocketRequest + " origin_present=" + !string.IsNullOrEmpty(context.Request.Headers["Origin"])); context.Response.StatusCode = 400; return; }
        string session = Guid.NewGuid().ToString("N")[..12];
        Trace("session=" + session + " upgrade requested");
        WebSocket accepted;
        try { accepted = await context.WebSockets.AcceptWebSocketAsync(new WebSocketAcceptContext { KeepAliveInterval = TimeSpan.Zero }); }
        catch (Exception e) { Trace("session=" + session + " upgrade failed: " + e.GetType().Name); throw; }
        using var socket = accepted;
        Trace("session=" + session + " upgrade accepted");
        using var lifetime = CancellationTokenSource.CreateLinkedTokenSource(context.RequestAborted);
        var token = lifetime.Token;
        try
        {
            bool verified = false;
            while (socket.State == WebSocketState.Open && !token.IsCancellationRequested)
            {
                using var readDeadline = CancellationTokenSource.CreateLinkedTokenSource(token);
                if (!verified) readDeadline.CancelAfter(TimeSpan.FromSeconds(5));
                var buffer = new byte[4096];
                using var message = new MemoryStream();
                WebSocketReceiveResult part;
                do
                {
                    part = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), readDeadline.Token);
                    if (part.MessageType == WebSocketMessageType.Close) { Trace("session=" + session + " peer closed"); return; }
                    if (part.MessageType != WebSocketMessageType.Text || message.Length + part.Count > 32768) { Trace("session=" + session + " invalid frame type or size"); return; }
                    message.Write(buffer, 0, part.Count);
                } while (!part.EndOfMessage);

                Trace("session=" + session + " frame received bytes=" + message.Length);
                var wire = JsonSerializer.Deserialize<Envelope>(message.ToArray(), Json);
                var (code, reply) = await ExchangeCoreAsync(wire, token);
                if (code != 200 || reply == null)
                {
                    Trace("session=" + session + " request rejected status=" + code);
                    // Report only a coarse failure category; never emit request data,
                    // key material or crypto internals.
                    using var closeDeadline = CancellationTokenSource.CreateLinkedTokenSource(token);
                    closeDeadline.CancelAfter(TimeSpan.FromSeconds(1));
                    var closeStatus = code == 429 ? (WebSocketCloseStatus)1013
                        : code >= 500 ? WebSocketCloseStatus.InternalServerError
                        : WebSocketCloseStatus.PolicyViolation;
                    await socket.CloseOutputAsync(closeStatus, "Compute request failed", closeDeadline.Token);
                    return;
                }
                verified = true;
                byte[] bytes = JsonSerializer.SerializeToUtf8Bytes(reply, Json);
                await socket.SendAsync(new ArraySegment<byte>(bytes), WebSocketMessageType.Text, true, token);
                Trace("session=" + session + " encrypted response sent");
            }
        }
        catch (OperationCanceledException) { Trace("session=" + session + " canceled or first-frame timeout"); }
        catch (WebSocketException e) { Trace("session=" + session + " websocket error=" + e.WebSocketErrorCode); }
        catch (JsonException) { Trace("session=" + session + " invalid envelope JSON"); }
        catch (Exception e) { Trace("session=" + session + " unexpected error=" + e.GetType().Name); }
        finally { socket.Abort(); Trace("session=" + session + " ended"); }
    }

    private async Task<(int Code, Envelope? Reply)> ExchangeCoreAsync(
        Envelope? wire, CancellationToken cancellationToken)
    {
        byte[] nonce;
        string requestId = "unknown", stage = "decode_envelope";
        var timer = Stopwatch.StartNew();
        JsonDocument request;
        try
        {
            if (wire == null) return (400, null);
            nonce = Convert.FromBase64String(wire.Nonce);
            byte[] sealedBytes = Convert.FromBase64String(wire.Data);
            if (nonce.Length != 12 || sealedBytes.Length < 16 || sealedBytes.Length > 16000) return (400, null);
            requestId = Convert.ToHexString(SHA256.HashData(nonce))[..12].ToLowerInvariant();
            Trace("request=" + requestId + " encrypted envelope received");
            stage = "decrypt";
            byte[] plain = new byte[sealedBytes.Length - 16];
            try
            {
                using var aes = new AesGcm(_requestKey, 16);
                aes.Decrypt(nonce, sealedBytes.AsSpan(0,plain.Length), sealedBytes.AsSpan(plain.Length), plain, RequestAad);
                stage = "parse_plaintext";
                request = JsonDocument.Parse(plain);
                // JsonDocument owns its parsed backing memory until disposal.
            }
            catch { CryptographicOperations.ZeroMemory(plain); throw; }
        }
        catch (OperationCanceledException) { return (499, null); }
        catch (Exception e) { Trace("request=" + requestId + " rejected stage=" + stage + " error=" + e.GetType().Name); return (401, null); }

        using (request)
        {
            try
            {
                stage = "timestamp";
                var root = request.RootElement;
                long at = root.GetProperty("at").GetInt64();
                long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                if (at < now - 120000 || at > now + 120000) { Trace("request=" + requestId + " rejected stage=timestamp clock_skew_ms=" + (now-at)); return (401, null); }
                string nonceKey = Convert.ToHexString(nonce);
                lock (_gate)
                {
                    foreach (var expired in _seen.Where(p => p.Value < now).Select(p => p.Key).ToArray())
                        _seen.Remove(expired);
                    if (_seen.ContainsKey(nonceKey)) { Trace("request=" + requestId + " rejected stage=replay"); return (401, null); }
                    if (_seen.Count >= 8192) { Trace("request=" + requestId + " rejected stage=capacity"); return (429, null); }
                    _seen.Add(nonceKey, at + 120001);
                }

                stage = "request_validation";
                Trace("request=" + requestId + " authentication accepted");
                string? kind = root.GetProperty("kind").GetString();
                object response;
                if (kind == "probe")
                    response = new { ok = true, capability = "translate", model = PLaMoLocalTranslationService.ModelName, protocol = 1 };
                else if (kind == "translate")
                {
                    var input = root.GetProperty("request").Deserialize<TranslationRequest>(Json);
                    if (input == null || input.Text.Length > 1200) return (400, null);
                    stage = "model_inference";
                    Trace("request=" + requestId + " model inference started input_chars=" + input.Text.Length);
                    response = await _translator.TranslateAsync(input, cancellationToken);
                    Trace("request=" + requestId + " model inference completed elapsed_ms=" + timer.ElapsedMilliseconds);
                }
                else return (400, null);

                stage = "encrypt_reply";
                byte[] body = JsonSerializer.SerializeToUtf8Bytes(response, Json);
                byte[] replyNonce = RandomNumberGenerator.GetBytes(12);
                byte[] sealedReply = new byte[body.Length + 16];
                using (var aes = new AesGcm(_responseKey, 16))
                    aes.Encrypt(replyNonce, body, sealedReply.AsSpan(0,body.Length), sealedReply.AsSpan(body.Length), nonce);
                CryptographicOperations.ZeroMemory(body);
                return (200, new Envelope(Convert.ToBase64String(replyNonce), Convert.ToBase64String(sealedReply)));
            }
            catch (OperationCanceledException) { Trace("request=" + requestId + " canceled stage=" + stage); return (499, null); }
            catch (ArgumentException e) { Trace("request=" + requestId + " invalid stage=" + stage + " error=" + e.GetType().Name); return (400, null); }
            catch (Exception e) { Trace("request=" + requestId + " failed stage=" + stage + " error=" + e.GetType().Name); return (503, null); }
        }
    }

    private sealed record Envelope(string Nonce,string Data);
}
