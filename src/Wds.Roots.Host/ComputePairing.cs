using System.Security.Cryptography;

namespace Wds.Roots.Host;

/// <summary>One-time invitation authorizes a phone-generated, independent key.
/// Client keys are stored under the desktop user's local application directory.
/// The legacy translation token is never disclosed by pairing.</summary>
internal sealed class ComputePairing
{
    private readonly string _directory;
    private readonly object _gate = new();
    private DateTimeOffset _expiry;
    private bool _used = true;
    public ComputePairing(string directory) { _directory = directory; }
    public string Issue()
    {
        lock (_gate)
        {
            _expiry = DateTimeOffset.UtcNow.AddMinutes(10);
            _used = false;
            return Convert.ToHexString(RandomNumberGenerator.GetBytes(32)).ToLowerInvariant();
        }
    }
    public static string Id(string secret) =>
        Convert.ToHexString(SHA256.HashData(Convert.FromHexString(secret))).ToLowerInvariant();
    public bool Redeem(string secret)
    {
        if (secret.Length != 64 || !secret.All(Uri.IsHexDigit)) return false;
        lock (_gate)
        {
            if (_used || DateTimeOffset.UtcNow > _expiry) return false;
            Directory.CreateDirectory(_directory);
            string path = Path.Combine(_directory, Id(secret) + ".key");
            if (File.Exists(path)) return false;
            using (var file = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            using (var writer = new StreamWriter(file)) writer.Write(secret.ToLowerInvariant());
            _used = true;
            return true;
        }
    }
    public byte[]? Find(string id)
    {
        if (id.Length != 64 || !id.All(Uri.IsHexDigit)) return null;
        string path = Path.Combine(_directory, id.ToLowerInvariant() + ".key");
        if (!File.Exists(path)) return null;
        string secret = File.ReadAllText(path).Trim();
        if (secret.Length != 64 || !secret.All(Uri.IsHexDigit) || Id(secret) != id.ToLowerInvariant()) return null;
        return Convert.FromHexString(secret);
    }
}
