using System.Buffers.Binary;
using System.Net.Security;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Wds.Roots.Host;

/// <summary>REP/2 identity binding and ordinary length-prefixed messages over
/// TLS. No additional encryption or MAC of knowledge, archives or messages.
/// The existing Axon signing identity remains independent of the TLS certificate.</summary>
internal sealed class DesktopRepChannel : IDisposable
{
    const int Magic=0x52455032, ControlMax=1024*1024;
    static readonly byte[] Label=Encoding.ASCII.GetBytes("Roots REP/2 TLS identity");
    readonly SslStream _stream;
    public string PeerId {get;}
    DesktopRepChannel(SslStream stream,string peer){_stream=stream;PeerId=peer;}
    static void Blob(Stream stream,byte[] value)
    {
        Span<byte> header=stackalloc byte[4];BinaryPrimitives.WriteInt32BigEndian(header,value.Length);
        stream.Write(header);stream.Write(value);
    }
    static byte[] ReadBlob(Stream stream,int max)
    {
        Span<byte> header=stackalloc byte[4];stream.ReadExactly(header);
        int n=BinaryPrimitives.ReadInt32BigEndian(header);
        if(n<1||n>max)throw new InvalidDataException("Invalid Axon field length.");
        byte[] value=new byte[n];stream.ReadExactly(value);return value;
    }
    static async Task<byte[]> ReadBlobAsync(Stream stream,int max,CancellationToken ct,int expected=-1)
    {
        byte[] header=new byte[4];await stream.ReadExactlyAsync(header,ct);
        int n=BinaryPrimitives.ReadInt32BigEndian(header);
        if(n<1||n>max||(expected>=0&&n!=expected))throw new InvalidDataException("Invalid Axon field length.");
        byte[] value=new byte[n];await stream.ReadExactlyAsync(value,ct);return value;
    }
    static async Task BlobAsync(Stream stream,byte[] value,CancellationToken ct)
    {
        byte[] header=new byte[4];BinaryPrimitives.WriteInt32BigEndian(header,value.Length);
        await stream.WriteAsync(header,ct);await stream.WriteAsync(value,ct);await stream.FlushAsync(ct);
    }
    static byte[] Hello(ECDsa identity)
    {
        using var ms=new MemoryStream();byte[] magic=new byte[4];BinaryPrimitives.WriteInt32BigEndian(magic,Magic);
        ms.Write(magic);Blob(ms,identity.ExportSubjectPublicKeyInfo());
        ms.Write(RandomNumberGenerator.GetBytes(32));return ms.ToArray();
    }
    static byte[] ParseHello(byte[] hello)
    {
        using var ms=new MemoryStream(hello,false);Span<byte> magic=stackalloc byte[4];ms.ReadExactly(magic);
        if(BinaryPrimitives.ReadInt32BigEndian(magic)!=Magic)throw new InvalidDataException("Unsupported TLS Axon protocol.");
        byte[] identity=ReadBlob(ms,256);Span<byte> nonce=stackalloc byte[32];ms.ReadExactly(nonce);
        if(ms.Position!=ms.Length)throw new InvalidDataException("Trailing Axon hello.");
        return identity;
    }
    static void RequireP256(ECParameters p)
    {
        if(p.Curve.Oid.Value!="1.2.840.10045.3.1.7"||p.Q.X?.Length!=32||p.Q.Y?.Length!=32)
            throw new CryptographicException("Expected P-256.");
    }
    static byte[] Proof(byte[] transcript,bool client)=>[(byte)(client?1:2),..transcript];
    public static string Fingerprint(ECDsa key)=>Convert.ToHexString(SHA256.HashData(key.ExportSubjectPublicKeyInfo())).ToLowerInvariant();
    public static async Task<DesktopRepChannel> OpenOverTlsAsync(SslStream stream,ECDsa identity,bool client,string expectedPeer,CancellationToken ct)
    {
        if(!stream.IsAuthenticated||!stream.IsEncrypted)throw new AuthenticationException("Axon requires TLS.");
        var cert=client?stream.RemoteCertificate:stream.LocalCertificate;
        if(cert==null)throw new CryptographicException("Missing TLS certificate.");
        if(client&&!Wds.Roots.Tree.MeshGraphItem.IsHash(expectedPeer))throw new CryptographicException("Missing expected Axon identity.");
        byte[] certHash=SHA256.HashData(cert.GetRawCertData());
        byte[] local=Hello(identity),remote;
        if(client){await BlobAsync(stream,local,ct);remote=await ReadBlobAsync(stream,1024,ct);}
        else{remote=await ReadBlobAsync(stream,1024,ct);await BlobAsync(stream,local,ct);}
        byte[] peer=ParseHello(remote);
        using var verify=ECDsa.Create();verify.ImportSubjectPublicKeyInfo(peer,out int read);
        if(read!=peer.Length)throw new CryptographicException("Invalid identity key.");
        RequireP256(verify.ExportParameters(false));
        string id=Convert.ToHexString(SHA256.HashData(peer)).ToLowerInvariant();
        if(id==Fingerprint(identity)||(expectedPeer!=""&&id!=expectedPeer))throw new CryptographicException("TLS Axon identity mismatch.");
        using var bytes=new MemoryStream();bytes.Write(Label);bytes.Write(certHash);
        Blob(bytes,client?local:remote);Blob(bytes,client?remote:local);
        byte[] transcript=SHA256.HashData(bytes.ToArray());
        byte[] signature=identity.SignData(Proof(transcript,client),HashAlgorithmName.SHA256,DSASignatureFormat.Rfc3279DerSequence);
        if(client)
        {
            byte[] other=await ReadBlobAsync(stream,256,ct);
            if(!verify.VerifyData(Proof(transcript,false),other,HashAlgorithmName.SHA256,DSASignatureFormat.Rfc3279DerSequence))
                throw new CryptographicException("Invalid TLS Axon server proof.");
            await BlobAsync(stream,signature,ct);
        }
        else
        {
            await BlobAsync(stream,signature,ct);byte[] other=await ReadBlobAsync(stream,256,ct);
            if(!verify.VerifyData(Proof(transcript,true),other,HashAlgorithmName.SHA256,DSASignatureFormat.Rfc3279DerSequence))
                throw new CryptographicException("Invalid TLS Axon client proof.");
        }
        return new DesktopRepChannel(stream,id);
    }
    public Task SendAsync(JsonObject value,CancellationToken ct)=>
        SendPublicBytesAsync(JsonSerializer.SerializeToUtf8Bytes(value,DesktopPublicItem.WireOptions),ControlMax,ct);
    public async Task<JsonObject> ReadAsync(CancellationToken ct)=>
        JsonNode.Parse(await ReadBlobAsync(_stream,ControlMax,ct),documentOptions:new JsonDocumentOptions{MaxDepth=24}) as JsonObject
        ??throw new InvalidDataException("Invalid Axon message.");
    public Task SendPublicBytesAsync(byte[] data,int limit,CancellationToken ct)
    {
        if(data.Length<1||data.Length>limit)throw new InvalidDataException("Invalid Axon payload length.");
        return BlobAsync(_stream,data,ct);
    }
    public Task<byte[]> ReadPublicBytesAsync(int expected,int limit,CancellationToken ct)=>ReadBlobAsync(_stream,limit,ct,expected);
    public void Dispose(){} // TLS stream is owned by the connection.
}
