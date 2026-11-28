using System.Net;
using System.Net.Sockets;
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
var listener=new TcpListener(IPAddress.Loopback,0);listener.Start();
try
{
    int port=((IPEndPoint)listener.LocalEndpoint).Port;
    var server=Task.Run(async ()=>{
        using var socket=await listener.AcceptTcpClientAsync(ct);
        using var channel=await DesktopRepChannel.OpenAsync(socket.GetStream(),b,false,aid,ct);
        Check(channel.PeerId==aid,"Authenticated client");
        var request=await channel.ReadAsync(ct);
        Check(request["op"]?.GetValue<string>()=="bind","Encrypted request");
        await channel.SendAsync(new JsonObject{["op"]="bound",["root_graph"]=true},ct);
    },ct);
    using var client=new TcpClient();await client.ConnectAsync(IPAddress.Loopback,port,ct);
    using var channel=await DesktopRepChannel.OpenAsync(client.GetStream(),a,true,bid,ct);
    Check(channel.PeerId==bid,"Authenticated server");
    await channel.SendAsync(new JsonObject{["op"]="bind"},ct);
    Check((await channel.ReadAsync(ct))["op"]?.GetValue<string>()=="bound","Encrypted reply");
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
