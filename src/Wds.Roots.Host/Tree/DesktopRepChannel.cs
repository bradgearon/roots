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
    const int Magic=0x52455031, MaxFrame=65536, PublicControlMax=1024*1024;
    static readonly byte[] PublicLabel=Encoding.ASCII.GetBytes("Roots public plaintext v1");
    static readonly byte[] PublicKeyLabel=Encoding.ASCII.GetBytes("Roots REP public integrity key v1");
    static readonly byte[] Label=Encoding.ASCII.GetBytes("Roots REP/1 handshake");
    readonly Stream _stream;
    readonly byte[] _send,_receive;
    long _sent,_received;
    byte[]? _publicSend,_publicReceive;
    public bool PublicPlaintext=>_publicSend!=null;
    public void EnablePublicPlaintext()
    {
        if(PublicPlaintext)return;
        _publicSend=HMACSHA256.HashData(_send,PublicKeyLabel);
        _publicReceive=HMACSHA256.HashData(_receive,PublicKeyLabel);
    }
    static byte[] PublicTag(byte[] key,long sequence,byte[] bytes)
    {
        if(sequence<0||sequence==long.MaxValue)throw new CryptographicException("REP sequence exhausted.");
        using var mac=IncrementalHash.CreateHMAC(HashAlgorithmName.SHA256,key);
        byte[] prefix=new byte[12];BinaryPrimitives.WriteInt64BigEndian(prefix,sequence);
        BinaryPrimitives.WriteInt32BigEndian(prefix.AsSpan(8),bytes.Length);
        mac.AppendData(PublicLabel);mac.AppendData(prefix);mac.AppendData(bytes);
        return mac.GetHashAndReset();
    }
    async Task SendPlainAsync(byte[] data,int limit,CancellationToken ct)
    {
        if(_publicSend==null||data.Length<1||data.Length>limit)throw new InvalidDataException("Invalid public payload length.");
        byte[] header=new byte[4];BinaryPrimitives.WriteInt32BigEndian(header,data.Length);
        byte[] tag=PublicTag(_publicSend,_sent,data);
        await _stream.WriteAsync(header,ct);await _stream.WriteAsync(data,ct);await _stream.WriteAsync(tag,ct);
        await _stream.FlushAsync(ct);_sent++;
    }
    async Task<byte[]> ReadPlainAsync(int limit,int expected,CancellationToken ct)
    {
        if(_publicReceive==null)throw new InvalidDataException("Public plaintext not negotiated.");
        byte[] header=new byte[4];await _stream.ReadExactlyAsync(header,ct);
        int length=BinaryPrimitives.ReadInt32BigEndian(header);
        if(length<1||length>limit||(expected>=0&&length!=expected))throw new InvalidDataException("Invalid public payload length.");
        byte[] data=new byte[length],tag=new byte[32];
        await _stream.ReadExactlyAsync(data,ct);await _stream.ReadExactlyAsync(tag,ct);
        if(!CryptographicOperations.FixedTimeEquals(tag,PublicTag(_publicReceive,_received,data)))
            throw new CryptographicException("Public payload integrity failure.");
        _received++;return data;
    }
    public Task SendPublicBytesAsync(byte[] data,int limit,CancellationToken ct)=>SendPlainAsync(data,limit,ct);
    public Task<byte[]> ReadPublicBytesAsync(int expected,int limit,CancellationToken ct)=>ReadPlainAsync(limit,expected,ct);
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
    public Task SendAsync(JsonObject value,CancellationToken ct)=>
        SendFrameAsync(JsonSerializer.SerializeToUtf8Bytes(value,DesktopPublicItem.WireOptions),ct);
    public async Task SendFrameAsync(byte[] data,CancellationToken ct)
    {
        if(PublicPlaintext){await SendPlainAsync(data,PublicControlMax,ct);return;}
        if(data.Length<1||data.Length>MaxFrame)throw new InvalidDataException("REP payload too large.");
        byte[] nonce=Nonce(_sent),encrypted=new byte[data.Length+16];
        using var aes=new AesGcm(_send,16);
        aes.Encrypt(nonce,data,encrypted.AsSpan(0,data.Length),encrypted.AsSpan(data.Length,16),[..Label,..nonce]);
        await BlobAsync(_stream,encrypted,ct);_sent++;
    }
    public async Task<JsonObject> ReadAsync(CancellationToken ct)=>
        JsonNode.Parse(await ReadFrameAsync(ct),documentOptions:new JsonDocumentOptions{MaxDepth=24}) as JsonObject??throw new InvalidDataException("Invalid REP message.");
    public async Task<byte[]> ReadFrameAsync(CancellationToken ct)
    {
        if(PublicPlaintext)return await ReadPlainAsync(PublicControlMax,-1,ct);
        byte[] encrypted=await ReadBlobAsync(_stream,MaxFrame+16,ct);
        if(encrypted.Length<17)throw new InvalidDataException("Invalid encrypted REP frame.");
        byte[] nonce=Nonce(_received),data=new byte[encrypted.Length-16];
        using var aes=new AesGcm(_receive,16);
        aes.Decrypt(nonce,encrypted.AsSpan(0,data.Length),encrypted.AsSpan(data.Length),data,[..Label,..nonce]);_received++;
        return data;
    }
    public void Dispose()
    {
        CryptographicOperations.ZeroMemory(_send);CryptographicOperations.ZeroMemory(_receive);
        if(_publicSend!=null)CryptographicOperations.ZeroMemory(_publicSend);
        if(_publicReceive!=null)CryptographicOperations.ZeroMemory(_publicReceive);
    }
}
