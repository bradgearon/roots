using System.Threading;
using System.Threading.Tasks;

namespace Wds.Roots.Api.Translation
{
    /// <summary>Stable model-agnostic translation boundary.</summary>
    public interface ITranslationService
    {
        Task<TranslationResponse> TranslateAsync(TranslationRequest request, CancellationToken cancellationToken);
    }

    public sealed class TranslationRequest
    {
        public string Text { get; set; } = string.Empty;
        public string? SourceLang { get; set; }
        public string TargetLang { get; set; } = "en";
        public int MaxTokens { get; set; } = 512;
    }

    public sealed class TranslationResponse
    {
        public string Translation { get; set; } = string.Empty;
        public string SourceLang { get; set; } = "auto";
        public string TargetLang { get; set; } = "en";
        public string Model { get; set; } = "plamo-3-translate";
        public long ElapsedMs { get; set; }
    }
}