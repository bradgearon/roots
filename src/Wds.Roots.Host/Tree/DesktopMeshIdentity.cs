using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.AspNetCore.DataProtection;
using Wds.Roots.Tree;

namespace Wds.Roots.Host;

internal sealed record MeshInvitation(string Host,string Pin,string Token)
{
    public string Uri => $"roots://mesh/v1?host={Host}&port=8233&pin={Pin}&token={Token}";
    public static MeshInvitation Parse(string value)
    {
        if(value.Length>1024)throw new ArgumentException("Invalid Mesh invitation.");
        var match=Regex.Match(value.Trim(),@"^roots://mesh/v1\?([^#]+)$");
        if(!match.Success)throw new ArgumentException("Use a Roots Mesh invitation.");
        var fields=new Dictionary<string,string>(StringComparer.Ordinal);
        foreach(string field in match.Groups[1].Value.Split('&'))
        {
            var pair=field.Split('=',2);
            if(pair.Length!=2||!fields.TryAdd(pair[0],System.Uri.UnescapeDataString(pair[1])))
                throw new ArgumentException("Invalid invitation fields.");
        }
        if(fields.Count!=4||!fields.TryGetValue("host",out var host)||!fields.TryGetValue("port",out var port)||
            !fields.TryGetValue("pin",out var pin)||!fields.TryGetValue("token",out var token)||port!="8233"||
            !Regex.IsMatch(host,@"^\d{1,3}(\.\d{1,3}){3}$")||!MeshGraphItem.IsHash(pin)||!MeshGraphItem.IsHash(token))
            throw new ArgumentException("Invalid Mesh invitation.");
        var octets=host.Split('.').Select(s=>int.Parse(s,System.Globalization.CultureInfo.InvariantCulture)).ToArray();
        if(octets.Any(n=>n>255))throw new ArgumentException("Invalid IPv4 address.");
        return new(string.Join(".",octets),pin,token);
    }
    public override string ToString() => $"Mesh peer {Host} (credentials omitted)";
}

internal sealed class DesktopMeshIdentity : IDisposable
{
    readonly IDataProtector _protector;
    readonly string _directory;
    readonly byte[] _root;
    public X509Certificate2 Certificate { get; }
    public string Pin { get; }
    sealed record IdentityData(string Pfx,string Root);
    public DesktopMeshIdentity(string directory)
    {
        _directory=directory;Directory.CreateDirectory(directory);
        if(!OperatingSystem.IsWindows())File.SetUnixFileMode(directory,UnixFileMode.UserRead|UnixFileMode.UserWrite|UnixFileMode.UserExecute);
        var keys=Directory.CreateDirectory(Path.Combine(directory,"keys"));
        if(!OperatingSystem.IsWindows())File.SetUnixFileMode(keys.FullName,UnixFileMode.UserRead|UnixFileMode.UserWrite|UnixFileMode.UserExecute);
        var protection=DataProtectionProvider.Create(keys,options=>
        {
            options.SetApplicationName("Wds.Roots.Mesh");
            if(OperatingSystem.IsWindows())options.ProtectKeysWithDpapi();
        });
        _protector=protection.CreateProtector("roots.mesh.desktop.v1");
        string identity=Path.Combine(directory,"identity.protected"),marker=Path.Combine(directory,"identity.pin");
        if(!File.Exists(identity))
        {
            if(File.Exists(marker))throw new InvalidOperationException("Saved Mesh identity missing. Not rotating automatically.");
            using var ec=ECDsa.Create(ECCurve.NamedCurves.nistP256);
            var request=new CertificateRequest("CN=Roots Desktop Mesh",ec,HashAlgorithmName.SHA256);
            request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false,false,0,true));
            request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature,true));
            request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new OidCollection {new Oid("1.3.6.1.5.5.7.3.1")},false));
            using var cert=request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1),DateTimeOffset.UtcNow.AddYears(10));
            var value=new IdentityData(Convert.ToBase64String(cert.Export(X509ContentType.Pfx)),Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)));
            SaveProtected(identity,JsonSerializer.Serialize(value));
        }
        var saved=JsonSerializer.Deserialize<IdentityData>(ReadProtected(identity))??throw new InvalidDataException("Invalid Mesh identity.");
        _root=Convert.FromBase64String(saved.Root);
        if(_root.Length!=32)throw new InvalidDataException("Invalid Mesh authority.");
        Certificate=X509CertificateLoader.LoadPkcs12(Convert.FromBase64String(saved.Pfx),null,
            OperatingSystem.IsWindows()?X509KeyStorageFlags.UserKeySet:X509KeyStorageFlags.EphemeralKeySet);
        using var privateKey=Certificate.GetECDsaPrivateKey();
        if(privateKey==null||!Current(Certificate))throw new InvalidOperationException("Invalid or expired Mesh identity.");
        Pin=Fingerprint(Certificate);
        if(File.Exists(marker)&&File.ReadAllText(marker).Trim()!=Pin)throw new InvalidOperationException("Saved Mesh certificate changed.");
        if(!File.Exists(marker))File.WriteAllText(marker,Pin);
    }
    public static bool Current(X509Certificate2 cert)=>cert.NotBefore.ToUniversalTime()<=DateTime.UtcNow&&cert.NotAfter.ToUniversalTime()>DateTime.UtcNow;
    public static string Fingerprint(X509Certificate2 cert)=>Convert.ToHexString(cert.GetCertHash(HashAlgorithmName.SHA256)).ToLowerInvariant();
    public static bool Equal(string expected,string? actual)=>MeshGraphItem.IsHash(expected)&&MeshGraphItem.IsHash(actual)&&
        CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(expected),Encoding.ASCII.GetBytes(actual!));
    string Derive(string purpose,string member)=>Convert.ToHexString(HMACSHA256.HashData(_root,
        Encoding.ASCII.GetBytes("roots.mesh.network.v1\n"+purpose+"\n"+Pin+"\n"+member))).ToLowerInvariant();
    public string Enrollment=>Derive("enrollment","");
    public string Grant(string member)=>Derive("member",member);
    public static string Nonce()=>Convert.ToHexString(RandomNumberGenerator.GetBytes(32)).ToLowerInvariant();
    static byte[] Challenge(string issuer,string member,string nonce)
    {
        if(!MeshGraphItem.IsHash(issuer)||!MeshGraphItem.IsHash(member)||!MeshGraphItem.IsHash(nonce))
            throw new ArgumentException("Invalid member challenge.");
        return Encoding.ASCII.GetBytes("roots.mesh.member-proof.v1\n"+issuer+"\n"+member+"\n"+nonce);
    }
    public string Proof(string issuer,string nonce)
    {
        using var key=Certificate.GetECDsaPrivateKey()??throw new CryptographicException("Missing member key.");
        return Convert.ToBase64String(key.SignData(Challenge(issuer,Pin,nonce),HashAlgorithmName.SHA256,DSASignatureFormat.Rfc3279DerSequence));
    }
    public static bool Verify(string certificate,string proof,string issuer,string member,string nonce)
    {
        if(certificate.Length>8192||proof.Length>512)return false;
        try
        {
            using var cert=X509CertificateLoader.LoadCertificate(Convert.FromBase64String(certificate));
            using var key=cert.GetECDsaPublicKey();
            return key!=null&&Current(cert)&&Equal(member,Fingerprint(cert))&&key.VerifyData(
                Challenge(issuer,member,nonce),Convert.FromBase64String(proof),HashAlgorithmName.SHA256,DSASignatureFormat.Rfc3279DerSequence);
        }
        catch{return false;}
    }
    public string ReadProtected(string path)=>_protector.Unprotect(File.ReadAllText(path));
    public void SaveProtected(string path,string value)
    {
        string temp=path+"."+Guid.NewGuid().ToString("N")+".tmp";
        try
        {
            File.WriteAllText(temp,_protector.Protect(value));
            if(!OperatingSystem.IsWindows())File.SetUnixFileMode(temp,UnixFileMode.UserRead|UnixFileMode.UserWrite);
            File.Move(temp,path,true);
        }
        finally{File.Delete(temp);}
    }
    public void Dispose(){Certificate.Dispose();CryptographicOperations.ZeroMemory(_root);}
}

internal static class DesktopMeshNetwork
{
    // Actual IPv4 interface prefixes, not an assumed /24 or an arbitrary
    // hostname. Exclude loopback, tunnels, link-local and inactive adapters.
    static IEnumerable<UnicastIPAddressInformation> Addresses()
    {
        foreach(var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if(nic.OperationalStatus!=OperationalStatus.Up||nic.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel)continue;
            foreach(var item in nic.GetIPProperties().UnicastAddresses)
            {
                if(item.Address.AddressFamily!=AddressFamily.InterNetwork||IPAddress.IsLoopback(item.Address)||item.PrefixLength is <1 or >30)continue;
                var bytes=item.Address.GetAddressBytes();
                if(bytes[0]==169&&bytes[1]==254)continue;
                yield return item;
            }
        }
    }
    public static string[] Hosts()=>Addresses().Select(a=>a.Address.ToString()).Distinct().ToArray();
    public static bool SameSubnet(IPAddress address,IPAddress? local=null)
    {
        if(address.AddressFamily!=AddressFamily.InterNetwork||IPAddress.IsLoopback(address))return false;
        uint remote=System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(address.GetAddressBytes());
        foreach(var a in Addresses())
        {
            if(local!=null&&!a.Address.Equals(local))continue;
            if(a.Address.Equals(address))return false;
            uint own=System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(a.Address.GetAddressBytes());
            uint mask=uint.MaxValue<<(32-a.PrefixLength);
            if((own&mask)==(remote&mask))return true;
        }
        return false;
    }
}
