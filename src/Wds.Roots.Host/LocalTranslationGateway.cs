using System.Security.Cryptography;
using Wds.Roots.Api.Forge;
using Wds.Roots.Api.Forge.Contracts;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Wds.Roots.Api.Translation;
using Wds.Roots.Api.LanguageDetection;

namespace Wds.Roots.Host;

/// <summary>
/// Authenticated local browser API; it calls the dedicated always-hot PLaMo server and never
/// exposes the raw model endpoint or the LAN mobile bridge to arbitrary web pages.
/// </summary>
internal sealed class LocalTranslationGateway : IAsyncDisposable
{
    private readonly int _port;
    private readonly bool _lan;
    private readonly PLaMoLocalTranslationService _translator;
    private WebApplication? _app;

    public LocalTranslationGateway(int port, string llamaChatEndpoint, bool lan = false)
    {
        _port = port;
        _lan = lan;
        _translator = new PLaMoLocalTranslationService(llamaChatEndpoint);
    }

    public async Task StartAsync(CancellationToken cancellationToken = default, bool issuePairing = false)
    {
        string secret = ReadOrCreateToken();
        var builder = WebApplication.CreateBuilder(new WebApplicationOptions { Args = Array.Empty<string>() });
        builder.WebHost.ConfigureKestrel(options =>
        {
            if (_lan) options.ListenAnyIP(_port); else options.ListenLocalhost(_port);
            options.Limits.MaxRequestBodySize = 32 * 1024;
            options.AddServerHeader = false;
        });
        var app = builder.Build();
        // A single authenticated extension WebSocket remains open across many
        // translations. HTTP stays available for old extension versions/tests.
        app.UseWebSockets(new WebSocketOptions
        {
            KeepAliveInterval = TimeSpan.FromSeconds(20)
        });

        app.MapGet("/api/translation/v1/socket", (HttpContext context) =>
            LocalTranslationSocket.RunAsync(context, _translator, secret, FastTextLanguageDetectionService.Shared, _lan));


        app.MapPost("/api/forge/v1/invoke", async (HttpContext context) =>
        {
            if (!IsAuthorized(context.Request.Headers["X-SixStars-Token"].ToString(), secret)) return Results.Unauthorized();
            try {
                var request = await context.Request.ReadFromJsonAsync<ForgeServiceRequest>(cancellationToken: context.RequestAborted);
                if (request == null) return Results.BadRequest(new { error = "request_required" });
                return Results.Json(await new TranslationForge(_translator, FastTextLanguageDetectionService.Shared).InvokeAsync(request, context.RequestAborted));
            } catch (ArgumentException) { return Results.BadRequest(new { error = "invalid_forge_request" }); }
              catch (JsonException) { return Results.BadRequest(new { error = "invalid_json" }); }
              catch (OperationCanceledException) { return Results.StatusCode(499); }
              catch (Exception) { return Results.StatusCode(503); }
        });

        app.MapGet("/api/translation/v1/health", () => Results.Json(new
        {
            ok = true, capability = "translate", languageDetection = FastTextLanguageDetectionService.ModelName, model = PLaMoLocalTranslationService.ModelName,
            backend = "dedicated PLaMo llama.cpp server on 8081", authentication = "required-for-inference"
        }));

        // Automatic compute routing never sends the legacy bearer token or
        // plaintext social content. A successful encrypted probe proves possession
        // of the paired key; encrypted requests are replay-checked.
        var pairing = new ComputePairing(Path.Combine(Path.GetDirectoryName(TokenPath())!, "compute-clients"));
        var secureRoute = new SecureComputeRoute(secret, _translator, pairing);
        app.MapPost("/api/compute/v1/exchange", secureRoute.ExchangeAsync);
        app.MapGet("/api/compute/v1/socket", secureRoute.RunSocketAsync);
        if (issuePairing && _lan)
        {
            string invitation = pairing.Issue();
            var pairingRoute = new SecureComputeRoute(invitation, _translator, pairing, invitation: true);
            app.MapGet("/api/compute/v1/pair", pairingRoute.RunSocketAsync);
            Console.WriteLine("ROOTS ONE-TIME PAIRING CODE (10 minutes): " + invitation);
        }

        app.MapPost("/api/translation/v1/detect", async (HttpContext context) =>
        {
            if (!IsAuthorized(context.Request.Headers["X-SixStars-Token"].ToString(), secret))
                return Results.Unauthorized();
            try
            {
                var request = await context.Request.ReadFromJsonAsync<TranslationRequest>(cancellationToken: context.RequestAborted);
                if (request == null) return Results.BadRequest(new { error = "request_required" });
                return Results.Json(await FastTextLanguageDetectionService.Shared.DetectAsync(request.Text, context.RequestAborted));
            }
            catch (JsonException) { return Results.BadRequest(new { error = "invalid_json" }); }
            catch (ArgumentException) { return Results.BadRequest(new { error = "invalid_request" }); }
            catch (OperationCanceledException) { return Results.StatusCode(499); }
            catch (Exception) { return Results.Json(new { error = "detection_unavailable" }, statusCode: 503); }
        });

        app.MapPost("/api/translation/v1/translate", async (HttpContext context) =>
        {
            // No CORS or ambient credentials: a Chrome extension worker supplies a local
            // random token. The content script and the visited website never see it.
            if (!IsAuthorized(context.Request.Headers["X-SixStars-Token"].ToString(), secret))
                return Results.Unauthorized();

            TranslationRequest? request;
            try
            {
                request = await context.Request.ReadFromJsonAsync<TranslationRequest>(
                    cancellationToken: context.RequestAborted);
            }
            catch (JsonException)
            {
                return Results.BadRequest(new { error = "invalid_json" });
            }
            if (request == null) return Results.BadRequest(new { error = "request_required" });

            try
            {
                var result = await _translator.TranslateAsync(request, context.RequestAborted);
                return Results.Json(result, new JsonSerializerOptions(JsonSerializerDefaults.Web));
            }
            catch (ArgumentException ex)
            {
                return Results.BadRequest(new { error = "invalid_translation_request", message = ex.Message });
            }
            catch (OperationCanceledException)
            {
                return Results.StatusCode(499);
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("Translation failed: " + ex.GetType().Name);
                return Results.Json(new { error = "translation_unavailable" }, statusCode: 503);
            }
        });

        await app.StartAsync(cancellationToken);
        _app = app;
        Console.WriteLine("LOCAL TRANSLATION WebSocket ws://127.0.0.1:" + _port + "/api/translation/v1/socket");
        Console.WriteLine("LOCAL TRANSLATION HTTP fallback http://127.0.0.1:" + _port + "/api/translation/v1/translate");
        Console.WriteLine("LOCAL TRANSLATION token file: " + TokenPath());
    }

    internal static bool IsAuthorized(string provided, string secret)
    {
        if (provided.Length != 64 || provided.Length != secret.Length) return false;
        return CryptographicOperations.FixedTimeEquals(
            Encoding.ASCII.GetBytes(provided), Encoding.ASCII.GetBytes(secret));
    }

    private static string TokenPath()
    {
        string parent = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        if (string.IsNullOrWhiteSpace(parent)) parent = AppContext.BaseDirectory;
        return Path.Combine(parent, "Roots", "translation-token.txt");
    }

    private static string ReadOrCreateToken()
    {
        string path = TokenPath();
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        if (File.Exists(path))
        {
            string value = File.ReadAllText(path).Trim();
            if (value.Length == 64 && value.All(Uri.IsHexDigit)) return value;
            throw new InvalidOperationException("Translation token file is invalid: " + path);
        }
        string token = Convert.ToHexString(RandomNumberGenerator.GetBytes(32)).ToLowerInvariant();
        using (var stream = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None))
        using (var writer = new StreamWriter(stream, new UTF8Encoding(false)))
            writer.Write(token);
        return token;
    }

    public async ValueTask DisposeAsync()
    {
        if (_app != null)
        {
            try { await _app.StopAsync(); } catch { }
            await _app.DisposeAsync();
            _app = null;
        }
        _translator.Dispose();
    }
}
