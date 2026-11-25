using System.Net;
using System.Text;
using System.Text.Json;
using Wds.Roots.Api.Forge;
using Wds.Roots.Api.Forge.Contracts;
using Wds.Roots.Api.LanguageDetection;
using Wds.Roots.Api.Translation;

using var http = new HttpClient(new FakeModel());
using var service = new PLaMoLocalTranslationService("http://localhost:8081/v1/completions", http);
var forge = new TranslationForge(service, new FakeDetector());
var request = new ForgeServiceRequest { RequestId = "test-1", Operation = ForgeOperations.LanguageTranslate, Input = new() { ["text"] = "こんにちは。", ["sourceLang"] = "ja", ["targetLang"] = "en" } };
var result = await forge.InvokeAsync(request, CancellationToken.None);
if (result.CorrelationId != "test-1" || result.Content["translation"] != "Hello.") throw new Exception("Forge translation contract failed.");
request.Operation = ForgeOperations.LanguageDetect;
result = await forge.InvokeAsync(request, CancellationToken.None);
if (result.Content["language"] != "ja") throw new Exception("Detection contract failed.");
Console.WriteLine("Roots contract checks passed.");

sealed class FakeModel : HttpMessageHandler {
    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token) {
        if (request.RequestUri?.AbsolutePath != "/v1/completions") throw new Exception("Invalid endpoint");
        using var doc = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(token));
        var prompt = doc.RootElement.GetProperty("prompt").GetString()!;
        if (!prompt.Contains("<|plamo:key|>lang<|plamo:val|>ja") || !prompt.EndsWith("<|plamo:key|>lang<|plamo:val|>en<|plamo:msg|>")) throw new Exception("Invalid PFML3 prompt.");
        return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("{\"choices\":[{\"text\":\"Hello.\"}]}", Encoding.UTF8, "application/json") };
    }
}
sealed class FakeDetector : ILanguageDetectionService {
    public Task<LanguageDetectionResult> DetectAsync(string text, CancellationToken token = default) => Task.FromResult(new LanguageDetectionResult("ja", .99, true, "fake", 1));
}
