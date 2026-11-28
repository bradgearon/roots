using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Net.Http;
using System.Text;
using System.Text.Json;
 using System.Threading;
using System.Threading.Tasks;

namespace Wds.Roots.Api.Translation
{
    /// <summary>
    /// Reusable PLaMo 3 Translate client. Uses a dedicated localhost llama.cpp
    /// server kept resident independently of the integration clients.
    /// </summary>
    public sealed class PLaMoLocalTranslationService : ITranslationService, IDisposable
    {
        public const string ModelName = "plamo-3-translate";
        private readonly HttpClient _http;
        private readonly string _endpoint;
        private readonly bool _ownsClient;

        public PLaMoLocalTranslationService(
            string completionEndpoint = "http://127.0.0.1:8081/v1/completions",
            HttpClient? client = null)
        {
            _endpoint = completionEndpoint;
            _ownsClient = client == null;
            _http = client ?? new HttpClient(new HttpClientHandler { UseProxy = false })
            {
                Timeout = TimeSpan.FromSeconds(90) // Allows for long translation inputs.
            };
        }

        public async Task<TranslationResponse> TranslateAsync(TranslationRequest request, CancellationToken cancellationToken)
        {
            if (request == null) throw new ArgumentNullException(nameof(request));
            if (string.IsNullOrWhiteSpace(request.Text)) throw new ArgumentException("Text is required.", nameof(request));
            if (request.Text.Length > 12000) throw new ArgumentException("Text is too long (12000 characters max).", nameof(request));
            string target = NormalizeLanguage(request.TargetLang, required: true)!;
            string? source = NormalizeLanguage(request.SourceLang, required: false);
            int maxTokens = request.MaxTokens <= 0 ? 512 : Math.Min(request.MaxTokens, 2048);

            if (request.Text.Contains("<|plamo:", StringComparison.Ordinal)) throw new ArgumentException("Text contains a reserved model delimiter.");
            string prompt = "<|plamo:tag|>dataset<|plamo:msg|>translation<|plamo:tag|>input"
                + (source == null ? "" : "<|plamo:key|>lang<|plamo:val|>" + source)
                + "<|plamo:msg|>" + request.Text + "<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>" + target + "<|plamo:msg|>";
            var payload = new
            {
                model = ModelName,
                prompt,
                temperature = 0,
                max_tokens = maxTokens,
                stream = false,
                stop = new[] { "<|plamo:tag|>", "<|plamo:bos|>" }
            };

            var timer = Stopwatch.StartNew();
            using var outbound = new HttpRequestMessage(HttpMethod.Post, _endpoint)
            {
                Content = new StringContent(JsonSerializer.Serialize(payload), Encoding.UTF8, "application/json")
            };
            using var result = await _http.SendAsync(outbound, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
                .ConfigureAwait(false);
            string responseText = await result.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            if (!result.IsSuccessStatusCode)
                throw new HttpRequestException("Local translation returned HTTP " + (int)result.StatusCode + ".");

            using var doc = JsonDocument.Parse(responseText);
            string? translation = doc.RootElement.GetProperty("choices")[0].GetProperty("text").GetString();
            if (string.IsNullOrWhiteSpace(translation))
                throw new InvalidOperationException("Translation model returned empty content.");
            timer.Stop();

            return new TranslationResponse
            {
                Translation = translation.Trim(),
                SourceLang = source ?? "auto",
                TargetLang = target,
                Model = ModelName,
                ElapsedMs = timer.ElapsedMilliseconds
            };
        }

        // Both desktop and Android use the published PLaMo 3 language vocabulary.
        // https://huggingface.co/pfnet/plamo-3-translate-2b#supported-languages
        private static readonly HashSet<string> ModelLanguages = new(StringComparer.Ordinal)
        {
            "ja", "en", "zh-hans", "zh-hant", "ko", "ar", "az", "be", "bg",
            "bn", "ca", "ceb", "de", "es", "et", "eu", "fa", "fr",
            "gu", "he", "hi", "hr", "hu", "hy", "id", "it", "jv",
            "ka", "kk", "km", "lt", "lv", "ml", "mr", "ms", "my",
            "ne", "nl", "pa", "pl", "ru", "si", "sk", "sl", "sr",
            "sw", "ta", "te", "th", "tl", "ur", "uz", "vi"
        };

        private static string? NormalizeLanguage(string? language, bool required)
        {
            if (string.IsNullOrWhiteSpace(language))
            {
                if (required) throw new ArgumentException("Target language is required.");
                return null;
            }
            string value = language.Trim().ToLowerInvariant();
            if (value == "auto")
            {
                if (required) throw new ArgumentException("A concrete target language is required.");
                return null;
            }
            // Preserve script-specific Chinese codes and accept legacy detector aliases.
            value = value.Replace('_', '-');
            value = value switch
            {
                "iw" => "he", "in" => "id", "fil" => "tl",
                "zh-cn" or "zh-sg" => "zh-hans",
                "zh-tw" or "zh-hk" or "zh-mo" => "zh-hant",
                "zh" when required => "zh-hans",
                _ => value
            };
            if (ModelLanguages.Contains(value)) return value;
            if (!required) return null; // Unknown source: let PLaMo infer it.
            throw new ArgumentException("Unsupported PLaMo target language.");
        }

        public void Dispose()
        {
            if (_ownsClient) _http.Dispose();
        }
    }
}