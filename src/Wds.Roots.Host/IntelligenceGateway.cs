using System.Net;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace Wds.Roots.Host;

/// <summary>Explicitly paired general intelligence on its own TLS port.
/// No translation endpoints or raw llama.cpp routes are exposed here.</summary>
internal sealed class IntelligenceGateway(IntelligenceBackend backend, bool lan,
    int port = IntelligenceProtocol.Port, string? directory = null) : IAsyncDisposable
{
    private WebApplication? _app;
    private X509Certificate2? _identity;
    internal Uri? Address { get; private set; }

    internal async Task StartAsync(CancellationToken token = default)
    {
        string data = directory ?? IntelligenceProtocol.DataDirectory();
        string secret = IntelligenceProtocol.ReadOrCreateToken(data);
        _identity = DesktopTlsIdentity.Load(data);
        var builder = WebApplication.CreateBuilder(new WebApplicationOptions { Args = [] });
        // Request bodies and authentication values are never written to logs.
        builder.Logging.SetMinimumLevel(LogLevel.Warning);
        builder.WebHost.ConfigureKestrel(options =>
        {
            if (lan) options.ListenAnyIP(port, listener => listener.UseHttps(_identity));
            else options.Listen(IPAddress.Loopback, port, listener => listener.UseHttps(_identity));
            options.Limits.MaxRequestBodySize = 32 * 1024;
            options.Limits.RequestHeadersTimeout = TimeSpan.FromSeconds(10);
            options.AddServerHeader = false;
        });
        var app = builder.Build();
        app.Use(async (context, next) =>
        {
            if (!IntelligenceProtocol.Authorized(context.Request.Headers.Authorization.ToString(), secret))
            { await Error(context, 401, "unauthorized"); return; }
            if (context.Request.Headers.ContainsKey("Origin"))
            { await Error(context, 403, "browser_origin_not_allowed"); return; }
            await next(context);
        });
        app.MapGet(IntelligenceProtocol.HealthPath, async (HttpContext context) =>
        {
            try
            {
                if (!await backend.ReadyAsync(context.RequestAborted)) return Results.Json(new { error = "intelligence_unavailable" }, statusCode: 503);
                return Results.Json(new { version = IntelligenceProtocol.Version, ok = true, capability = "intelligence", model = IntelligenceProtocol.Model, modelSha = IntelligenceProtocol.ModelSha });
            }
            catch (IntelligenceFailure error) { return Results.Json(new { error = error.Code }, statusCode: error.Status); }
            catch (OperationCanceledException) { return Results.StatusCode(499); }
        });
        app.MapPost(IntelligenceProtocol.GeneratePath, async (HttpContext context) =>
        {
            try
            {
                var request = await context.Request.ReadFromJsonAsync<IntelligenceRequest>(cancellationToken: context.RequestAborted);
                IntelligenceProtocol.Validate(request);
                return Results.Json(await backend.GenerateAsync(request!, context.RequestAborted));
            }
            catch (IntelligenceFailure error) { return Results.Json(new { error = error.Code }, statusCode: error.Status); }
            catch (BadHttpRequestException error) { return Results.Json(new { error = "invalid_request" }, statusCode: error.StatusCode); }
            catch (JsonException) { return Results.Json(new { error = "invalid_request" }, statusCode: 400); }
            catch (InvalidOperationException) { return Results.Json(new { error = "invalid_request" }, statusCode: 400); }
            catch (OperationCanceledException) { return Results.StatusCode(499); }
        });
        try
        {
            await app.StartAsync(token);
            _app = app;
            string? address = app.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()?.Addresses.FirstOrDefault();
            if (address != null) Address = new Uri(address.Replace("[::]", "127.0.0.1"));
        }
        catch { await app.DisposeAsync(); _identity.Dispose(); _identity = null; throw; }
        Console.WriteLine($"DESKTOP INTELLIGENCE: HTTPS port {port}; model {IntelligenceProtocol.Model} (Qwen3.5 0.8B Q4_0).");
        if (lan)
            foreach (var nic in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up || nic.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Loopback) continue;
                foreach (var ip in nic.GetIPProperties().UnicastAddresses)
                    if (ip.Address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork && !IPAddress.IsLoopback(ip.Address))
                        Console.WriteLine($"  https://{ip.Address}:{port}");
            }
        else Console.WriteLine($"  https://127.0.0.1:{port} (local only)");
        Console.WriteLine("Run get-intelligence-key.cmd to copy the separate intelligence pairing key.");
    }

    private static Task Error(HttpContext context, int status, string error)
    {
        context.Response.StatusCode = status;
        return context.Response.WriteAsJsonAsync(new { error }, context.RequestAborted);
    }

    public async ValueTask DisposeAsync()
    {
        if (_app != null)
        {
            try { await _app.StopAsync(); } finally { await _app.DisposeAsync(); _app = null; }
        }
        _identity?.Dispose(); _identity = null;
    }
}
