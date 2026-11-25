using System;
using System.Diagnostics;
using System.Linq;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.IO;
using FastText.NetWrapper;

namespace Wds.Roots.Api.LanguageDetection;

public sealed record LanguageDetectionResult(string Language, double Confidence, bool Reliable, string Model, long ElapsedMs);
public interface ILanguageDetectionService
{
    Task<LanguageDetectionResult> DetectAsync(string text, CancellationToken cancellationToken = default);
}

// Independent of sockets, translation, conversation models and UI.
public sealed class FastTextLanguageDetectionService : ILanguageDetectionService, IDisposable
{
    public const string ModelName = "fasttext-lid.176.ftz";
    public static ILanguageDetectionService Shared { get; } = new FastTextLanguageDetectionService();
    private readonly object _gate = new();
    private FastTextWrapper? _detector;
    private readonly string _modelPath;

    public FastTextLanguageDetectionService(string? modelPath = null)
    {
        _modelPath = modelPath ?? Environment.GetEnvironmentVariable("ROOTS_LANGUAGE_MODEL") ??
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Roots", "models", "lid.176.ftz");
    }
    private bool _disposed;
    private static readonly Regex Noise = new(@"https?://\S+|www\.\S+|(?<!\w)@[\w_]+", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant, TimeSpan.FromMilliseconds(100));
    private static readonly Regex Whitespace = new(@"\s+", RegexOptions.CultureInvariant, TimeSpan.FromMilliseconds(100));

    public Task<LanguageDetectionResult> DetectAsync(string text, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(text) || text.Length > 12000)
            throw new ArgumentException("Text must contain 1 to 12000 characters.", nameof(text));
        return Task.Run(() => Detect(text, cancellationToken), cancellationToken);
    }

    private LanguageDetectionResult Detect(string text, CancellationToken token)
    {
        var timer = Stopwatch.StartNew();
        token.ThrowIfCancellationRequested();
        string clean = Whitespace.Replace(Noise.Replace(text, " "), " ").Trim();
        int letters = clean.Count(char.IsLetter);
        LanguageDetectionResult Unknown() => new("und", 0, false, ModelName, timer.ElapsedMilliseconds);
        if (letters < 3) return Unknown();
        lock (_gate)
        {
            token.ThrowIfCancellationRequested();
            ObjectDisposedException.ThrowIf(_disposed, this);
            if (_detector == null)
            {
                if (!File.Exists(_modelPath)) throw new FileNotFoundException("Language model missing; run Install-LanguageDetectorModel.ps1.", _modelPath);
                var detector = new FastTextWrapper();
                try { detector.LoadModel(File.ReadAllBytes(_modelPath)); }
                catch { detector.Dispose(); throw; }
                _detector = detector;
            }
            var candidates = _detector.PredictMultiple(clean, 2).OrderByDescending(x => x.Probability).ToArray();
            token.ThrowIfCancellationRequested();
            if (candidates.Length == 0) return Unknown();
            string language = candidates[0].Label.Replace("__label__", "").ToLowerInvariant();
            double confidence = candidates[0].Probability;
            double margin = confidence - (candidates.Length > 1 ? candidates[1].Probability : 0);
            bool reliable = letters >= 6 && confidence >= 0.70 && margin >= 0.15;
            return new(language, confidence, reliable, ModelName, timer.ElapsedMilliseconds);
        }
    }

    public void Dispose()
    {
        lock (_gate)
        {
            if (_disposed) return;
            _disposed = true;
            _detector?.Dispose();
            _detector = null;
        }
    }
}
