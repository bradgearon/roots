using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using static Wds.Roots.Host.DesktopPublicFootprintCodec;

namespace Wds.Roots.Host;

internal sealed partial class DesktopPublicAxonService
{
    const int MaxFootprints=4096;
    readonly SortedDictionary<string,DesktopPublicFootprintCodec.Footprint> _footprints=new(StringComparer.Ordinal);
    bool _footprintsLoaded;
    string FootprintDirectory=>Path.Combine(_directory,"public-footprints-v1");

    void LoadFootprints()
    {
        if(_footprintsLoaded)return;
        if(Directory.Exists(FootprintDirectory))
        {
            long now=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            foreach(string path in Directory.EnumerateFiles(FootprintDirectory,"*.json").OrderBy(p=>p,StringComparer.Ordinal))
            {
                if(_footprints.Count>=MaxFootprints)break;
                try
                {
                    if(new FileInfo(path).Length>MaxFootprintBytes)continue;
                    var card=VerifyFootprint(File.ReadAllText(path),now);
                    if(card.Id==Path.GetFileNameWithoutExtension(path)&&card.Id!=_id)_footprints[card.Id]=card;
                }
                catch(Exception){/* skip damaged public cache entries */}
            }
        }
        _footprintsLoaded=true;
    }
    void RetainFootprint(Footprint card)
    {
        if(_footprints.TryGetValue(card.Id,out var old)&&old.Revision>=card.Revision)return;
        if(old==null&&_footprints.Count>=MaxFootprints)return;
        Directory.CreateDirectory(FootprintDirectory);
        Atomic(Path.Combine(FootprintDirectory,card.Id+".json"),card.Wire);
        _footprints[card.Id]=card;
    }
    JsonArray FootprintOffer(ECDsa key,string peer)
    {
        LoadFootprints();
        string[] links;
        lock(_gate)links=_peers.Values.Where(p=>!p.Blocked).OrderByDescending(p=>p.LastSeen).ThenBy(p=>p.Id,StringComparer.Ordinal).Select(p=>p.Id).ToArray();
        var own=SignFootprint(key,links,DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        var page=new JsonArray(JsonNode.Parse(own.Wire));
        foreach(var card in _footprints.Values.Where(c=>c.Id!=peer&&c.Id!=_id&&!Blocked(c.Id))
            .OrderBy(_=>Random.Shared.Next()).Take(MaxFootprintPage-1))
            page.Add(JsonNode.Parse(card.Wire));
        return page;
    }
    void ReceiveFootprints(JsonObject response,string peer)
    {
        if(response["footprints_v1"] is not JsonArray page||page.Count<1||page.Count>MaxFootprintPage||Blocked(peer))return;
        long now=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        Footprint direct;
        try{direct=VerifyFootprint(page[0]?.ToJsonString()??"",now);}catch(Exception){return;}
        if(direct.Id!=peer||direct.Id==_id)return;
        LoadFootprints();RetainFootprint(direct);
        foreach(var entry in page.Skip(1))
        {
            Footprint card;
            try{card=VerifyFootprint(entry?.ToJsonString()??"",now);}catch(Exception){continue;}
            if(card.Id!=_id&&!Blocked(card.Id))RetainFootprint(card);
        }
    }
}
