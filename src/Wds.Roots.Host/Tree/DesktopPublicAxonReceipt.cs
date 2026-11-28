using System.Text.Encodings.Web;
using System.Text.Json;

namespace Wds.Roots.Host;

// Local disk envelope, not a network protocol change. Older individual
// receipts used PascalCase; the archive writer uses camelCase.
internal sealed record DesktopPublicAxonReceipt(JsonElement Item,string Peer,long At,bool Promoted)
{
    internal static readonly JsonSerializerOptions Options=new(JsonSerializerDefaults.Web)
    {
        Encoder=JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        PropertyNameCaseInsensitive=true,
        RespectRequiredConstructorParameters=true
    };
}
