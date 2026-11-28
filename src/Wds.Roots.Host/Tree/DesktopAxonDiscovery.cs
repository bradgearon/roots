using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Wds.Roots.Tree;

namespace Wds.Roots.Host;

internal sealed record PublicAxonRoute(string Id,IPAddress Address,string Via);
internal static class DesktopAxonDiscovery
{
    public const int Port=8235;
    const string Prefix="ROOTS-AXON/1 ";
    internal static bool Private(IPAddress address)
    {
        byte[] a=address.GetAddressBytes();
        return a.Length==4&&(a[0]==10||a[0]==172&&a[1]>=16&&a[1]<=31||a[0]==192&&a[1]==168);
    }
    static bool Usable(IPAddress address)=>Private(address)&&DesktopMeshNetwork.SameSubnet(address);
    static IEnumerable<(IPAddress Local,IPAddress Broadcast)> Interfaces()
    {
        foreach(var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if(nic.OperationalStatus!=OperationalStatus.Up||nic.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel)continue;
            foreach(var a in nic.GetIPProperties().UnicastAddresses)
            {
                if(!Private(a.Address)||a.PrefixLength is <8 or >30)continue;
                uint ip=System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(a.Address.GetAddressBytes());
                uint broadcast=ip|(uint.MaxValue>>a.PrefixLength);
                byte[] bytes=new byte[4];System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(bytes,broadcast);
                yield return (a.Address,new IPAddress(bytes));
            }
        }
    }
    public static async Task<List<PublicAxonRoute>> LanAsync(CancellationToken ct)
    {
        var tasks=Interfaces().Distinct().Take(8).Select(async iface=>{
            var found=new Dictionary<string,PublicAxonRoute>();
            try
            {
                using var udp=new UdpClient(new IPEndPoint(iface.Local,0));udp.EnableBroadcast=true;
                string nonce=Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();
                byte[] query=Encoding.ASCII.GetBytes(Prefix+nonce);
                await udp.SendAsync(query,new IPEndPoint(iface.Broadcast,Port),ct);
                using var deadline=CancellationTokenSource.CreateLinkedTokenSource(ct);deadline.CancelAfter(2500);
                while(found.Count<32)
                {
                    var reply=await udp.ReceiveAsync(deadline.Token);
                    if(reply.Buffer.Length>160||reply.RemoteEndPoint.Port!=Port||!Usable(reply.RemoteEndPoint.Address)||
                        !DesktopMeshNetwork.SameSubnet(reply.RemoteEndPoint.Address,iface.Local))continue;
                    string value=Encoding.ASCII.GetString(reply.Buffer);
                    string start=Prefix+nonce+" ";
                    if(!value.StartsWith(start,StringComparison.Ordinal))continue;
                    string[] fields=value[start.Length..].Split(' ');
                    if(fields.Length==2&&MeshGraphItem.IsHash(fields[0])&&fields[1]=="8235")
                        found.TryAdd(fields[0],new(fields[0],reply.RemoteEndPoint.Address,"IP"));
                }
            }catch(OperationCanceledException) when(!ct.IsCancellationRequested){}
            catch(SocketException){}
            return found.Values.ToList();
        }).ToArray();
        var result=await Task.WhenAll(tasks);
        ct.ThrowIfCancellationRequested();return result.SelectMany(x=>x).DistinctBy(x=>x.Id).ToList();
    }
    public static async Task<List<PublicAxonRoute>> BleAsync(string repositoryRoot,CancellationToken ct)
    {
        var found=new List<PublicAxonRoute>();
        if(!OperatingSystem.IsWindows())return found;
        string path=Path.Combine(repositoryRoot,"src","Wds.Roots.BleDiscovery","bin","Release","net9.0-windows10.0.19041.0","Wds.Roots.BleDiscovery.dll");
        if(!File.Exists(path))return found;
        using var p=new Process{StartInfo=new ProcessStartInfo("dotnet"){
            UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true}};
        p.StartInfo.ArgumentList.Add(path);
        try
        {
            if(!p.Start())return found;
            using var deadline=CancellationTokenSource.CreateLinkedTokenSource(ct);deadline.CancelAfter(TimeSpan.FromSeconds(22));
            var output=p.StandardOutput.ReadToEndAsync(deadline.Token);
            var error=p.StandardError.ReadToEndAsync(deadline.Token);
            await p.WaitForExitAsync(deadline.Token);
            string lines=await output;await error;
            if(lines.Length>32768)return found;
            foreach(string line in lines.Split('\n').Take(64))
            {
                try
                {
                    using var doc=JsonDocument.Parse(line);var e=doc.RootElement;
                    string? id=e.GetProperty("id").GetString();
                    if(MeshGraphItem.IsHash(id)&&e.GetProperty("port").GetInt32()==Port&&
                        IPAddress.TryParse(e.GetProperty("host").GetString(),out var ip)&&Usable(ip))
                        found.Add(new(id!,ip,"BLE"));
                }catch(JsonException){}catch(InvalidOperationException){}catch(KeyNotFoundException){}
            }
        }
        catch(OperationCanceledException) when(!ct.IsCancellationRequested){}
        catch(Exception) when(!ct.IsCancellationRequested){}
        finally{try{if(!p.HasExited){p.Kill(true);await p.WaitForExitAsync(CancellationToken.None).WaitAsync(TimeSpan.FromSeconds(2));}}catch{}}
        return found.DistinctBy(x=>x.Id).ToList();
    }
}
