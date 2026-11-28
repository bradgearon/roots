using System.Security.Cryptography;
using System.Text;

namespace Wds.Roots.Host;

internal static class IntelligenceProtocol
{
    internal const int Port = 8221, BackendPort = 8082, Version = 1;
    internal const int MaxPromptCharacters = 3000, MaxOutputTokens = 384;
    internal const string Model = "BALANCED";
    internal const string ModelId = "qwen3.5-0.8b-q4_0";
    internal const string ModelSha = "57d1997790d1744fba5b40a7317df71ea5e2acee28c47e78f0cce39c0703f8cf";
    internal const string ModelAlias = "roots-intelligence-qwen3.5-0.8b";
    internal const string BackendUrl = "http://127.0.0.1:8082";
    internal const string HealthPath = "/api/intelligence/v1/health";
    internal const string GeneratePath = "/api/intelligence/v1/generate";

    internal static bool Authorized(string? authorization, string secret)
    {
        const string prefix = "Bearer ";
        if (authorization == null || authorization.Length != prefix.Length + 64 ||
            !authorization.StartsWith(prefix, StringComparison.OrdinalIgnoreCase) || secret.Length != 64) return false;
        return CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(authorization[prefix.Length..]), Encoding.ASCII.GetBytes(secret));
    }

    internal static void Validate(IntelligenceRequest? request)
    {
        if (request == null || request.Version != Version || string.IsNullOrWhiteSpace(request.Prompt) ||
            request.Prompt.Length > MaxPromptCharacters || request.MaxTokens < 1 || request.MaxTokens > MaxOutputTokens ||
            string.IsNullOrEmpty(request.Model) || string.IsNullOrEmpty(request.ModelSha))
            throw new IntelligenceFailure(400, "invalid_request");
        if (request.Model != Model || request.ModelSha != ModelSha)
            throw new IntelligenceFailure(409, "model_mismatch");
    }

    internal static string DataDirectory()
    {
        string local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        if (string.IsNullOrWhiteSpace(local)) local = AppContext.BaseDirectory;
        return Path.Combine(local, "Roots", "intelligence");
    }

    internal static string ReadOrCreateToken(string directory)
    {
        Directory.CreateDirectory(directory);
        string path = Path.Combine(directory, "intelligence-token.txt");
        if (File.Exists(path))
        {
            string value = File.ReadAllText(path).Trim();
            if (value.Length == 64 && value.All(Uri.IsHexDigit)) return value;
            throw new InvalidDataException("The saved desktop intelligence token is invalid: " + path);
        }
        string token = Convert.ToHexString(RandomNumberGenerator.GetBytes(32)).ToLowerInvariant();
        using var stream = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None);
        using var writer = new StreamWriter(stream, new UTF8Encoding(false));
        writer.Write(token);
        return token;
    }
}

internal sealed record IntelligenceRequest(int Version, string Model, string ModelSha, string Prompt, int MaxTokens);
internal sealed record IntelligenceResponse(int Version, string Model, string ModelSha, string Text, long ElapsedMs);

internal sealed class IntelligenceFailure(int status, string code) : Exception(code)
{
    internal int Status { get; } = status;
    internal string Code { get; } = code;
}
