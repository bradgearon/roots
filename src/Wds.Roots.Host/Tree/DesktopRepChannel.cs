using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Wds.Roots.Host;

/// <summary>REP/1 interoperable with Android EncounterChannel. The public
/// identity is independent of the private Mesh certificate and GPU grants.</summary>
internal sealed class DesktopRepChannel : IDisposable
{
    const int Magic=0x52455031, MaxFrame=65536;
    static readonly byte[] Label=Encoding.ASCII.GetBytes("Roots REP/1 handshake");
    readonly Stream _stream;
    readonly byte[] _send,_receive;
    long _sent,_received;
    public string PeerId {get;}
    DesktopRepChannel(Stream stream,string peer,byte[] send,byte[] receive)
    {_stream=stream;PeerId=peer;_send=send;_receive=receive;}
    static void Blob(Stream stream,byte[] value)
    {
        Span<byte> header=stackalloc byte[4];BinaryPrimitives.WriteInt32BigEndian(header,value.Length);
        stream.Write(header);stream.Write(value);
    }
    static byte[] ReadBlob(Stream stream,int max)
    {
        Span<byte> header=stackalloc byte[4];stream.ReadExactly(header);
        int n=BinaryPrimitives.ReadInt32BigEndian(header);
        if(n<1||n>max)throw new InvalidDataException("Invalid REP field size.");
        byte[] value=new byte[n];stream.ReadExactly(value);return value;
    }
    static async Task<byte[]> ReadBlobAsync(Stream stream,int max,CancellationToken ct)
    {
        byte[] header=new byte[4];await stream.ReadExactlyAsync(header,ct);
        int n=BinaryPrimitives.ReadInt32BigEndian(header);
        if(n<1||n>max)throw new InvalidDataException("Invalid REP field size.");
        byte[] value=new byte[n];await stream.ReadExactlyAsync(value,ct);return value;
    }
    static async Task BlobAsync(Stream stream,byte[] value,CancellationToken ct)
    {
        byte[] header=new byte[4];BinaryPrimitives.WriteInt32BigEndian(header,value.Length);
        await stream.WriteAsync(header,ct);await stream.WriteAsync(value,ct);await stream.FlushAsync(ct);
    }
    static byte[] Hello(ECDsa identity,ECDiffieHellman ephemeral)
    {
        using var ms=new MemoryStream();
        byte[] magic=new byte[4];BinaryPrimitives.WriteInt32BigEndian(magic,Magic);ms.Write(magic);
        Blob(ms,identity.ExportSubjectPublicKeyInfo());Blob(ms,ephemeral.ExportSubjectPublicKeyInfo());
        ms.Write(RandomNumberGenerator.GetBytes(32));return ms.ToArray();
    }
    static (byte[] Identity,byte[] Ephemeral) ParseHello(byte[] hello)
    {
        using var ms=new MemoryStream(hello,false);Span<byte> magic=stackalloc byte[4];ms.ReadExactly(magic);
        if(BinaryPrimitives.ReadInt32BigEndian(magic)!=Magic)throw new InvalidDataException("Unsupported REP version.");
        byte[] identity=ReadBlob(ms,256),ephemeral=ReadBlob(ms,256);
        Span<byte> nonce=stackalloc byte[32];ms.ReadExactly(nonce);
        if(ms.Position!=ms.Length)throw new InvalidDataException("Trailing REP hello.");
        return (identity,ephemeral);
    }
    static void RequireP256(ECParameters p)
    {
        if(p.Curve.Oid.Value!="1.2.840.10045.3.1.7"||p.Q.X?.Length!=32||p.Q.Y?.Length!=32)
            throw new CryptographicException("Expected P-256.");
    }
    static byte[] Proof(byte[] transcript,bool client)=>[(byte)(client?1:2),..transcript];
    static byte[] Expand(byte[] prk,string label)=>HMACSHA256.HashData(prk,[..Encoding.ASCII.GetBytes(label),1]);
    public static string Fingerprint(ECDsa key)=>Convert.ToHexString(SHA256.HashData(key.ExportSubjectPublicKeyInfo())).ToLowerInvariant();
    public static async Task<DesktopRepChannel> OpenAsync(Stream stream,ECDsa identity,bool client,string expectedPeer,CancellationToken ct)
    {
        using var ephemeral=ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256);
        byte[] local=Hello(identity,ephemeral),remote;
        if(client){await BlobAsync(stream,local,ct);remote=await ReadBlobAsync(stream,1024,ct);}
        else{remote=await ReadBlobAsync(stream,1024,ct);await BlobAsync(stream,local,ct);}
        var peer=ParseHello(remote);
        using var verify=ECDsa.Create();verify.ImportSubjectPublicKeyInfo(peer.Identity,out int read);
        if(read!=peer.Identity.Length)throw new CryptographicException("Invalid identity key.");
        RequireP256(verify.ExportParameters(false));
        string id=Convert.ToHexString(SHA256.HashData(peer.Identity)).ToLowerInvariant();
        if(id==Fingerprint(identity)||id!=expectedPeer)throw new CryptographicException("REP identity mismatch.");
        using var bytes=new MemoryStream();bytes.Write(Label);Blob(bytes,client?local:remote);Blob(bytes,client?remote:local);
        byte[] transcript=SHA256.HashData(bytes.ToArray());
        byte[] signature=identity.SignData(Proof(transcript,client),HashAlgorithmName.SHA256,DSASignatureFormat.Rfc3279DerSequence);
        byte[] other;
        if(client){await BlobAsync(stream,signature,ct);other=await ReadBlobAsync(stream,256,ct);}
        else{other=await ReadBlobAsync(stream,256,ct);await BlobAsync(stream,signature,ct);}
        if(!verify.VerifyData(Proof(transcript,!client),other,HashAlgorithmName.SHA256,DSASignatureFormat.Rfc3279DerSequence))
            throw new CryptographicException("Invalid REP proof.");
        using var remoteKey=ECDiffieHellman.Create();remoteKey.ImportSubjectPublicKeyInfo(peer.Ephemeral,out read);
        if(read!=peer.Ephemeral.Length)throw new CryptographicException("Invalid ephemeral key.");
        RequireP256(remoteKey.ExportParameters(false));
        byte[] secret=ephemeral.DeriveRawSecretAgreement(remoteKey.PublicKey);
        byte[] prk=HMACSHA256.HashData(transcript,secret);
        try
        {
            byte[] c2s=Expand(prk,"Roots REP/1 client to server"),s2c=Expand(prk,"Roots REP/1 server to client");
            return new DesktopRepChannel(stream,id,client?c2s:s2c,client?s2c:c2s);
        }
        finally{CryptographicOperations.ZeroMemory(secret);CryptographicOperations.ZeroMemory(prk);}
    }
    static byte[] Nonce(long seq)
    {
        if(seq<0||seq==long.MaxValue)throw new CryptographicException("REP sequence exhausted.");
        byte[] nonce=new byte[12];BinaryPrimitives.WriteInt64BigEndian(nonce.AsSpan(4),seq);return nonce;
    }
    public async Task SendAsync(JsonObject value,CancellationToken ct)
    {
        byte[] data=JsonSerializer.SerializeToUtf8Bytes(value,DesktopPublicItem.WireOptions);
        if(data.Length<1||data.Length>MaxFrame)throw new InvalidDataException("REP payload too large.");
        byte[] nonce=Nonce(_sent),encrypted=new byte[data.Length+16];
        using var aes=new AesGcm(_send,16);
        aes.Encrypt(nonce,data,encrypted.AsSpan(0,data.Length),encrypted.AsSpan(data.Length,16),[..Label,..nonce]);
        await BlobAsync(_stream,encrypted,ct);_sent++;
    }
    public async Task<JsonObject> ReadAsync(CancellationToken ct)
    {
        byte[] encrypted=await ReadBlobAsync(_stream,MaxFrame+16,ct);
        if(encrypted.Length<17)throw new InvalidDataException("Invalid encrypted REP frame.");
        byte[] nonce=Nonce(_received),data=new byte[encrypted.Length-16];
        using var aes=new AesGcm(_receive,16);
        aes.Decrypt(nonce,encrypted.AsSpan(0,data.Length),encrypted.AsSpan(data.Length),data,[..Label,..nonce]);_received++;
        return JsonNode.Parse(data,documentOptions:new JsonDocumentOptions{MaxDepth=24}) as JsonObject??throw new InvalidDataException("Invalid REP message.");
    }
    public void Dispose(){CryptographicOperations.ZeroMemory(_send);CryptographicOperations.ZeroMemory(_receive);}
}
