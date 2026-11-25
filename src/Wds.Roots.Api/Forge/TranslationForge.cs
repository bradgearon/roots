using System.Globalization;
using Wds.Roots.Api.Forge.Contracts;
using Wds.Roots.Api.Translation;
using Wds.Roots.Api.LanguageDetection;
namespace Wds.Roots.Api.Forge;

public interface IForgeService
{
    string ForgeId { get; }
    Task<ForgeServicePacket> InvokeAsync(ForgeServiceRequest request, CancellationToken cancellationToken);
}

public sealed class TranslationForge(ITranslationService translator, ILanguageDetectionService detector) : IForgeService
{
    public string ForgeId => "roots";
    public async Task<ForgeServicePacket> InvokeAsync(ForgeServiceRequest request, CancellationToken token)
    {
        if (request.SchemaVersion != 1 || request.Input is null || request.Actor is null || !request.Input.TryGetValue("text", out var text))
            throw new ArgumentException("Invalid Forge request.");
        var packet = new ForgeServicePacket { CorrelationId = request.RequestId, Actor = request.Actor, Kind = request.Operation };
        if (request.Operation == ForgeOperations.LanguageDetect)
        {
            var result = await detector.DetectAsync(text, token);
            packet.Content["language"] = result.Language;
            packet.Content["confidence"] = result.Confidence.ToString(CultureInfo.InvariantCulture);
            packet.Content["reliable"] = result.Reliable.ToString();
            packet.Content["model"] = result.Model;
        }
        else if (request.Operation == ForgeOperations.LanguageTranslate)
        {
            var result = await translator.TranslateAsync(new TranslationRequest { Text = text, SourceLang = request.Input.GetValueOrDefault("sourceLang"), TargetLang = request.Input.GetValueOrDefault("targetLang") ?? "en" }, token);
            packet.Content["translation"] = result.Translation;
            packet.Content["sourceLang"] = result.SourceLang;
            packet.Content["targetLang"] = result.TargetLang;
            packet.Content["model"] = result.Model;
        }
        else throw new ArgumentException("Unsupported Forge operation.");
        return packet;
    }
}
