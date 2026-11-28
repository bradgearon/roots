using System.Net;
using System.Net.Sockets;
using System.Net.Security;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using Wds.Roots.Host;
using Wds.Roots.Tree;

static void Check(bool condition,string message){if(!condition)throw new Exception(message);}
using var cancellation=new CancellationTokenSource(TimeSpan.FromSeconds(15));
var ct=cancellation.Token;
using var a=ECDsa.Create(ECCurve.NamedCurves.nistP256);
using var b=ECDsa.Create(ECCurve.NamedCurves.nistP256);
string aid=DesktopRepChannel.Fingerprint(a),bid=DesktopRepChannel.Fingerprint(b);
Check(aid!=bid,"Separate identities");
byte[] publicArchive=new byte[2*1024*1024];new Random(42).NextBytes(publicArchive);
using var tlsKey=ECDsa.Create(ECCurve.NamedCurves.nistP256);
var certificateRequest=new CertificateRequest("CN=Roots Axon test",tlsKey,HashAlgorithmName.SHA256);
using var cert=certificateRequest.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1),DateTimeOffset.UtcNow.AddDays(1));
var listener=new TcpListener(IPAddress.Loopback,0);listener.Start();
try
{
    int port=((IPEndPoint)listener.LocalEndpoint).Port;
    var server=Task.Run(async ()=>{
        using var socket=await listener.AcceptTcpClientAsync(ct);
        using var tls=new SslStream(socket.GetStream(),false);
        await tls.AuthenticateAsServerAsync(new SslServerAuthenticationOptions{
            ServerCertificate=cert,EnabledSslProtocols=SslProtocols.Tls12|SslProtocols.Tls13},ct);
        using var channel=await DesktopRepChannel.OpenOverTlsAsync(tls,b,false,aid,ct);
        Check(channel.PeerId==aid,"Authenticated client");
        var request=await channel.ReadAsync(ct);
        Check(request["op"]?.GetValue<string>()=="bind","Encrypted request");
        await channel.SendAsync(new JsonObject{["op"]="bound",["root_graph"]=true},ct);
        Check((await channel.ReadPublicBytesAsync(publicArchive.Length,DesktopKnowledgeArchive.MaxWire,ct)).SequenceEqual(publicArchive),"Large archive through TLS without additional payload encryption");
        await channel.SendAsync(new JsonObject{["op"]="archive_ack"},ct);
    },ct);
    using var client=new TcpClient();await client.ConnectAsync(IPAddress.Loopback,port,ct);
    using var tls=new SslStream(client.GetStream(),false,(_,presented,_,_)=>presented!=null&&presented.GetRawCertData().SequenceEqual(cert.RawData));
    await tls.AuthenticateAsClientAsync(new SslClientAuthenticationOptions{
        TargetHost="Roots Axon test",EnabledSslProtocols=SslProtocols.Tls12|SslProtocols.Tls13,
        CertificateRevocationCheckMode=X509RevocationMode.NoCheck},ct);
    using var channel=await DesktopRepChannel.OpenOverTlsAsync(tls,a,true,bid,ct);
    Check(channel.PeerId==bid,"Authenticated server");
    await channel.SendAsync(new JsonObject{["op"]="bind"},ct);
    Check((await channel.ReadAsync(ct))["op"]?.GetValue<string>()=="bound","Encrypted reply");
    await channel.SendPublicBytesAsync(publicArchive,DesktopKnowledgeArchive.MaxWire,ct);
    Check((await channel.ReadAsync(ct))["op"]?.GetValue<string>()=="archive_ack","TLS archive receipt");
    await server;
}
finally{listener.Stop();}
var graph=MeshGraphItem.Create("fragment","record.x.body","local:x:abc","en",1000,"Unlinked original");
using var doc=JsonDocument.Parse(JsonSerializer.Serialize(graph.Wire()));
var item=DesktopPublicItem.Parse(doc.RootElement);
var rows=item.Rows(aid,1001);
Check(item.Id==graph.Id&&rows.Any(r=>r.Source=="record.mesh.body"&&r.Body=="Unlinked original"),"Unlinked graph receipt");
Check(rows.All(r=>r.ExternalId.StartsWith("local:mesh:graph:")),"External key isolation");
Check(rows.Any(r=>r.Source=="record.mesh.external-peer"&&r.Body==aid),"Peer provenance");
var source=MeshGraphItem.Create("fragment","record.x.link","local:x:abc","",1000,"https://x.com/test/status/123");
using var linkDoc=JsonDocument.Parse(JsonSerializer.Serialize(source.Wire()));
Check(DesktopPublicItem.Parse(linkDoc.RootElement).Rows(aid,1001)[0].ExternalId==rows[0].ExternalId,"Original grouping");
Console.WriteLine("Public Axon protocol and Tree projection checks passed.");

Check(new DesktopArchiveProfile().Budget().Records==4096,"Initial 4096 record target");
Check(new DesktopArchiveProfile(InFlight:4096).Recover().Budget().Records==2048,"Interrupted archive reduces target");

const string footprintVector = """{"id":"dfbe12e2d2664cbf6a3c217d455c72908c33bca4fdd3b2f068b6bd538cb7128c","revision":1000,"links":["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"],"key":"MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZ4DF/HAnXixwYaDnh3uxdN6t65iHAn8/qDZUFYun9Qw8uow0vDXSDoH3MKwce9bWYalC+QxqnKVcUS+eSgASZg==","sig":"MEQCIANwWze2MZ4bdv/5RI0fXVsbxlM5iClxvbxg/HNvcQUVAiBrsjbyN07soEGCkZsZuMdmVIEZ/iS55aod+mawpx6aFg=="}""";
var verified=DesktopPublicFootprintCodec.VerifyFootprint(footprintVector,1001);
Check(verified.Id=="dfbe12e2d2664cbf6a3c217d455c72908c33bca4fdd3b2f068b6bd538cb7128c"&&verified.Links.SequenceEqual(new[]{new string('a',64),new string('b',64)}),"Independent P-256 footprint vector");
var ownFootprint=DesktopPublicFootprintCodec.SignFootprint(a,new[]{bid,aid,bid},400000);
Check(ownFootprint.Id==aid&&ownFootprint.Links.SequenceEqual(new[]{bid}),"Signed footprint excludes self and duplicate links");
void RejectFootprint(string wire,long now)
{
    bool rejected=false;
    try{DesktopPublicFootprintCodec.VerifyFootprint(wire,now);}catch(Exception){rejected=true;}
    Check(rejected,"Malformed footprint was accepted");
}
var changed=JsonNode.Parse(footprintVector)!.AsObject();changed["revision"]=1002;
RejectFootprint(changed.ToJsonString(),1003);
changed=JsonNode.Parse(footprintVector)!.AsObject();changed["id"]=new string('c',64);
RejectFootprint(changed.ToJsonString(),1001);
RejectFootprint(ownFootprint.Wire,1000);
Console.WriteLine("Public footprint signature and interoperability checks passed.");
