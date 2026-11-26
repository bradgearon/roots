using System.Security.Cryptography;
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
        { context.Response.StatusCode = 400; return; }
        using var socket = await context.WebSockets.AcceptWebSocketAsync();
        using var lifetime = CancellationTokenSource.CreateLinkedTokenSource(context.RequestAborted);
        var token = lifetime.Token;
        try
        {
            bool verified = false;
            while (socket.State == WebSocketState.Open && !token.IsCancellationRequested)
            {
                using var readDeadline = CancellationTokenSource.CreateLinkedTokenSource(token);
                readDeadline.CancelAfter(TimeSpan.FromSeconds(verified ? 75 : 5));
                var buffer = new byte[4096];
                using var message = new MemoryStream();
                WebSocketReceiveResult part;
                do
                {
                    part = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), readDeadline.Token);
                    if (part.MessageType == WebSocketMessageType.Close) return;
                    if (part.MessageType != WebSocketMessageType.Text || message.Length + part.Count > 32768) return;
                    message.Write(buffer, 0, part.Count);
                } while (!part.EndOfMessage);

                var wire = JsonSerializer.Deserialize<Envelope>(message.ToArray(), Json);
                var (code, reply) = await ExchangeCoreAsync(wire, token, requireProbe: !verified);
                if (code != 200 || reply == null) return;
                verified = true;
                byte[] bytes = JsonSerializer.SerializeToUtf8Bytes(reply, Json);
                await socket.SendAsync(new ArraySegment<byte>(bytes), WebSocketMessageType.Text, true, token);
            }
        }
        catch (OperationCanceledException) { }
        catch (WebSocketException) { }
        catch (JsonException) { }
        finally { socket.Abort(); }
    }

    private async Task<(int Code, Envelope? Reply)> ExchangeCoreAsync(
        Envelope? wire, CancellationToken cancellationToken, bool requireProbe = false)
    {
        byte[] nonce;
        JsonDocument request;
        try
        {
            if (wire == null) return (400, null);
            nonce = Convert.FromBase64String(wire.Nonce);
            byte[] sealedBytes = Convert.FromBase64String(wire.Data);
            if (nonce.Length != 12 || sealedBytes.Length < 16 || sealedBytes.Length > 16000) return (400, null);
            byte[] plain = new byte[sealedBytes.Length - 16];
            try
            {
                using var aes = new AesGcm(_requestKey, 16);
                aes.Decrypt(nonce, sealedBytes.AsSpan(0,plain.Length), sealedBytes.AsSpan(plain.Length), plain, RequestAad);
                request = JsonDocument.Parse(plain);
                // JsonDocument owns its parsed backing memory until disposal.
            }
            catch { CryptographicOperations.ZeroMemory(plain); throw; }
        }
        catch (OperationCanceledException) { return (499, null); }
        catch (Exception) { return (401, null); }

        using (request)
        {
            try
            {
                var root = request.RootElement;
                long at = root.GetProperty("at").GetInt64();
                long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                if (at < now - 120000 || at > now + 120000) return (401, null);
                string nonceKey = Convert.ToHexString(nonce);
                lock (_gate)
                {
                    foreach (var expired in _seen.Where(p => p.Value < now).Select(p => p.Key).ToArray())
                        _seen.Remove(expired);
                    if (_seen.ContainsKey(nonceKey)) return (401, null);
                    if (_seen.Count >= 8192) return (429, null);
                    _seen.Add(nonceKey, at + 120001);
                }

                string? kind = root.GetProperty("kind").GetString();
                if (requireProbe && kind != "probe") return (401, null);
                object response;
                if (kind == "probe")
                    response = new { ok = true, capability = "translate", model = PLaMoLocalTranslationService.ModelName, protocol = 1 };
                else if (kind == "translate")
                {
                    var input = root.GetProperty("request").Deserialize<TranslationRequest>(Json);
                    if (input == null || input.Text.Length > 1200) return (400, null);
                    response = await _translator.TranslateAsync(input, cancellationToken);
                }
                else return (400, null);

                byte[] body = JsonSerializer.SerializeToUtf8Bytes(response, Json);
                byte[] replyNonce = RandomNumberGenerator.GetBytes(12);
                byte[] sealedReply = new byte[body.Length + 16];
                using (var aes = new AesGcm(_responseKey, 16))
                    aes.Encrypt(replyNonce, body, sealedReply.AsSpan(0,body.Length), sealedReply.AsSpan(body.Length), nonce);
                CryptographicOperations.ZeroMemory(body);
                return (200, new Envelope(Convert.ToBase64String(replyNonce), Convert.ToBase64String(sealedReply)));
            }
            catch (OperationCanceledException) { return (499, null); }
            catch (ArgumentException) { return (400, null); }
            catch (Exception) { return (503, null); }
        }
    }

    private sealed record Envelope(string Nonce,string Data);
}
