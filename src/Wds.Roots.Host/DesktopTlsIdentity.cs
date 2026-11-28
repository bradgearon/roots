using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace Wds.Roots.Host;

/// <summary>Persistent local TLS identity; share the public certificate fingerprint
/// together with the existing token, never the private key.</summary>
internal static class DesktopTlsIdentity
{
    public static X509Certificate2 Load(string directory)
    {
        Directory.CreateDirectory(directory);
        string path = Path.Combine(directory, "compute-tls.pfx");
        if (!File.Exists(path))
        {
            using var rsa = RSA.Create(3072);
            var request = new CertificateRequest("CN=Roots Desktop", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
            request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
            request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, true));
            request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new OidCollection { new Oid("1.3.6.1.5.5.7.3.1") }, false));
            using var certificate = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(5));
            using var stream = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None);
            stream.Write(certificate.Export(X509ContentType.Pfx));
        }
        // Windows Schannel cannot reliably acquire credentials for ephemeral
        // private keys. Import into the current user's key storage and retain
        // the certificate for the lifetime of Kestrel. Do not use PersistKeySet:
        // the imported key can be cleaned up on disposal; the PFX is our identity.
        var storage = OperatingSystem.IsWindows()
            ? X509KeyStorageFlags.UserKeySet
            : X509KeyStorageFlags.EphemeralKeySet;
        var identity = X509CertificateLoader.LoadPkcs12FromFile(path, null, storage);
        if (!identity.HasPrivateKey || identity.NotAfter.ToUniversalTime() <= DateTime.UtcNow)
        { identity.Dispose(); throw new InvalidOperationException("Roots TLS identity is invalid or expired."); }
        File.WriteAllText(Path.Combine(directory, "compute-tls-sha256.txt"), Convert.ToHexString(identity.GetCertHash(HashAlgorithmName.SHA256)).ToLowerInvariant());
        return identity;
    }
}
