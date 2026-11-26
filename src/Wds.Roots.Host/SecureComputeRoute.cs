using System.Security.Cryptography;
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
        byte[] nonce;
        JsonDocument request;
        try
        {
            var wire = await context.Request.ReadFromJsonAsync<Envelope>(cancellationToken: context.RequestAborted);
            if (wire == null) return Results.BadRequest();
            nonce = Convert.FromBase64String(wire.Nonce);
            byte[] sealedBytes = Convert.FromBase64String(wire.Data);
            if (nonce.Length != 12 || sealedBytes.Length < 16 || sealedBytes.Length > 16000) return Results.BadRequest();
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
        catch (OperationCanceledException) { return Results.StatusCode(499); }
        catch (Exception) { return Results.Unauthorized(); }

        using (request)
        {
            try
            {
                var root = request.RootElement;
                long at = root.GetProperty("at").GetInt64();
                long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                if (at < now - 120000 || at > now + 120000) return Results.Unauthorized();
                string nonceKey = Convert.ToHexString(nonce);
                lock (_gate)
                {
                    foreach (var expired in _seen.Where(p => p.Value < now).Select(p => p.Key).ToArray())
                        _seen.Remove(expired);
                    if (_seen.ContainsKey(nonceKey)) return Results.Unauthorized();
                    if (_seen.Count >= 8192) return Results.StatusCode(429);
                    _seen.Add(nonceKey, at + 120001);
                }

                string? kind = root.GetProperty("kind").GetString();
                object response;
                if (kind == "probe")
                    response = new { ok = true, capability = "translate", model = PLaMoLocalTranslationService.ModelName, protocol = 1 };
                else if (kind == "translate")
                {
                    var input = root.GetProperty("request").Deserialize<TranslationRequest>(Json);
                    if (input == null || input.Text.Length > 1200) return Results.BadRequest();
                    response = await _translator.TranslateAsync(input, context.RequestAborted);
                }
                else return Results.BadRequest();

                byte[] body = JsonSerializer.SerializeToUtf8Bytes(response, Json);
                byte[] replyNonce = RandomNumberGenerator.GetBytes(12);
                byte[] sealedReply = new byte[body.Length + 16];
                using (var aes = new AesGcm(_responseKey, 16))
                    aes.Encrypt(replyNonce, body, sealedReply.AsSpan(0,body.Length), sealedReply.AsSpan(body.Length), nonce);
                CryptographicOperations.ZeroMemory(body);
                return Results.Json(new Envelope(Convert.ToBase64String(replyNonce), Convert.ToBase64String(sealedReply)), Json);
            }
            catch (OperationCanceledException) { return Results.StatusCode(499); }
            catch (ArgumentException) { return Results.BadRequest(); }
            catch (Exception) { return Results.StatusCode(503); }
        }
    }

    private sealed record Envelope(string Nonce,string Data);
}
