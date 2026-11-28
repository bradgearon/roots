using System.Net;
using System.Text.Json;
using Microsoft.AspNetCore.Http.Features;

namespace Wds.Roots.Host;

/// <summary>Desktop lifecycle and local extension control plane. Mesh has
/// its own pinned TLS port, not access to browser or inference endpoints.</summary>
internal sealed class DesktopRootsHost : IAsyncDisposable
{
    readonly DesktopTreeService _tree;
    readonly DesktopMeshService? _mesh;
    readonly string _meshError;
    DesktopRootsHost(DesktopTreeService tree,DesktopMeshService? mesh,string meshError)
    {_tree=tree;_mesh=mesh;_meshError=meshError;}
    public static DesktopRootsHost? TryOpen(string repositoryRoot,bool lan)
    {
        if(Environment.GetEnvironmentVariable("ROOTS_TREE_DISABLED")=="1")return null;
        DesktopTreeService? tree=null;
        try
        {
            if(string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("ROOTS_TREE_LIBRARY")))
            {
                string library=OperatingSystem.IsWindows()?"roots_tree.dll":"libroots_tree.so";
                foreach(string libraryDirectory in new[]{"build/desktop-tree/Release","build/desktop-tree","build/tree-core/Release","build/tree-core"})
                {
                    string candidate=Path.GetFullPath(Path.Combine(repositoryRoot,libraryDirectory,library));
                    if(File.Exists(candidate)){Environment.SetEnvironmentVariable("ROOTS_TREE_LIBRARY",candidate);break;}
                }
            }
            string local=Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            if(string.IsNullOrWhiteSpace(local))throw new InvalidOperationException("Missing user-local data directory.");
            string directory=Path.Combine(local,"Roots","Tree");
            Directory.CreateDirectory(directory);
            if(!OperatingSystem.IsWindows())File.SetUnixFileMode(directory,UnixFileMode.UserRead|UnixFileMode.UserWrite|UnixFileMode.UserExecute);
            tree=new DesktopTreeService(directory);
            DesktopMeshService? mesh=null;string error="";
            try{mesh=new DesktopMeshService(tree,Path.Combine(directory,"Mesh"),lan,repositoryRoot);}
            catch(Exception e){error=e.GetType().Name;Console.Error.WriteLine("Roots Mesh unavailable: "+error);}
            Console.WriteLine("ROOTS TREE: "+directory+" · "+tree.Count+" fragments · capture "+(tree.Capture?"on":"off"));
            if(mesh!=null)Console.WriteLine("ROOTS MESH: persistent peer identity; manage listening and bindings in the browser extension.");
            return new(tree,mesh,error);
        }
        catch(Exception e)
        {
            tree?.Dispose();
            Console.Error.WriteLine("Roots Tree unavailable ("+e.GetType().Name+"). Translation remains available. Run build-desktop-tree.cmd.");
            return null;
        }
    }
    DesktopMeshService Mesh()=>_mesh??throw new InvalidOperationException("Desktop Mesh unavailable: "+_meshError);
    record CaptureSetting(bool Enabled);
    record BindRequest(string Invitation);
    record UnbindRequest(string Pin);
    record PublicBlockRequest(string Pin,bool Blocked);
    record BranchRequest(string Name,string Term);
    record CompileRequest(string Language);

    public static void Map(WebApplication app,string token,int port,DesktopRootsHost? host)
    {
        // All local Tree/peer administration requires the existing extension
        // token and a true loopback connection. No LAN admin endpoints or CORS.
        var group=app.MapGroup("/api/tree/v1");
        group.AddEndpointFilter(async (invocation,next)=>{
            var ctx=invocation.HttpContext;
            ctx.Response.Headers.CacheControl="no-store";
            if(ctx.Connection.LocalPort!=port||ctx.Connection.RemoteIpAddress==null||
                !IPAddress.IsLoopback(ctx.Connection.RemoteIpAddress)||
                !LocalTranslationGateway.IsAuthorized(ctx.Request.Headers["X-SixStars-Token"].ToString(),token))
                return Results.Unauthorized();
            if(host==null)return Results.Json(new {ok=false,error="tree_unavailable"},statusCode:503);
            try{return await next(invocation);}
            catch(OperationCanceledException){return Results.StatusCode(499);}
            catch(ArgumentException){return Results.BadRequest(new {ok=false,error="invalid_request"});}
            catch(Exception e)
            {
                // Never echo requests, invitation tokens, or user content.
                return Results.Json(new {ok=false,error="tree_operation_failed",detail=e.GetType().Name},statusCode:503);
            }
        });
        group.MapGet("/status",()=>Results.Json(new {ok=true,capture=host!._tree.Capture,
            count=host._tree.Count,revision=host._tree.Revision,mesh=host._mesh?.Status(),meshError=host._meshError}));
        group.MapPost("/capture",(CaptureSetting setting)=>{
            host!._tree.SetCapture(setting.Enabled);return Results.Json(new {ok=true,capture=host._tree.Capture});
        });
        group.MapPost("/observe",async (HttpContext ctx)=>{
            // The translation endpoint retains its existing 32 KiB bound.
            var limit=ctx.Features.Get<IHttpMaxRequestBodySizeFeature>();
            if(limit!=null&&!limit.IsReadOnly)limit.MaxRequestBodySize=96*1024;
            if(!host!._tree.Capture)return Results.Json(new {ok=false,error="capture_off"},statusCode:409);
            var post=await ctx.Request.ReadFromJsonAsync<DesktopTreeService.BrowserPost>(cancellationToken:ctx.RequestAborted);
            if(post==null)return Results.BadRequest(new {ok=false,error="invalid_post"});
            ctx.RequestAborted.ThrowIfCancellationRequested();
            return Results.Json(new {ok=true,inserted=host._tree.Observe(post)});
        });
        group.MapGet("/feed",(string? term,long? before,int? limit)=>{
            var page=host!._tree.Feed(term??"",Math.Max(0,before??0),(uint)Math.Clamp(limit??30,1,100));
            return Results.Json(new {ok=true,items=page,nextBefore=page.Count==0?0:page[^1].Sequence});
        });
        group.MapGet("/records",(string? term,long? before,int? limit)=>{
            var rows=host!._tree.Records(term??"",Math.Max(0,before??0),(uint)Math.Clamp(limit??30,1,100));
            var records=rows.GroupBy(r=>r.MasterSequence).Select(g=>new {sequence=g.Key,key=g.First().Fragment.ExternalId,
                members=g.Select(r=>r.Fragment).ToArray()}).ToArray();
            return Results.Json(new {ok=true,items=records,nextBefore=records.Length==0?0:records[^1].sequence});
        });
        // These routes are local administration only, under the same loopback/token
        // filter. Experience never enters the Mesh or public social catalog.
        group.MapGet("/view/{source}/status",(string source)=>{
            if(source=="experience"){var state=DesktopExperienceTree.Status();return Results.Json(new {ok=true,count=state.Count,revision=state.Revision});}
            if(source=="knowledge")return Results.Json(new {ok=true,count=host!._tree.Count,revision=host._tree.Revision});
            return Results.BadRequest(new {ok=false,error="invalid_source"});
        });
        group.MapGet("/view/{source}/records",(string source,string? term,long? beforeMs,long? beforeSequence,int? limit,long? agent)=>{
            if(source!="experience"&&source!="knowledge")return Results.BadRequest(new {ok=false,error="invalid_source"});
            long ms=Math.Max(0,beforeMs??0),seq=Math.Max(0,beforeSequence??0),branch=Math.Max(0,agent??0);
            uint count=(uint)Math.Clamp(limit??30,1,100);
            var rows=source=="experience"?DesktopExperienceTree.Records(term??"",ms,seq,count,branch)
                :host!._tree.RecordsByTime(term??"",ms,seq,count,branch);
            var records=rows.GroupBy(r=>r.MasterSequence).Select(g=>new {sequence=g.Key,
                observedMs=g.Min(r=>r.Fragment.ObservedMs),key=g.First().Fragment.ExternalId,members=g.Select(r=>r.Fragment).ToArray()}).ToArray();
            var last=records.LastOrDefault();
            return Results.Json(new {ok=true,items=records,nextBeforeMs=last?.observedMs??0,nextBeforeSequence=last?.sequence??0});
        });
        group.MapGet("/view/{source}/weights/{sequence:long}",(string source,long sequence)=>{
            if(sequence<=0||source!="experience"&&source!="knowledge")return Results.BadRequest(new {ok=false,error="invalid_request"});
            var weights=source=="experience"?DesktopExperienceTree.Weights(sequence):host!._tree.FragmentWeights(sequence);
            return Results.Json(new {ok=true,profile="roots.lexical.byte-trigram.fnv1a.l2.v1",weights});
        });
        group.MapGet("/view/{source}/branches",(string source)=>{
            if(source!="experience"&&source!="knowledge")return Results.BadRequest(new {ok=false,error="invalid_source"});
            return Results.Json(new {ok=true,items=source=="experience"?DesktopExperienceTree.Branches():host!._tree.Branches()});
        });
        group.MapPost("/view/{source}/branches",(string source,BranchRequest request)=>{
            if(source!="experience"&&source!="knowledge")return Results.BadRequest(new {ok=false,error="invalid_source"});
            return Results.Json(new {ok=true,id=source=="experience"?DesktopExperienceTree.AddBranch(request.Name,request.Term):host!._tree.AddBranch(request.Name,request.Term)});
        });
        group.MapDelete("/view/{source}/branches/{id:long}",(string source,long id)=>{
            if(source!="experience"&&source!="knowledge"||id<=0)return Results.BadRequest(new {ok=false,error="invalid_request"});
            if(source=="experience")DesktopExperienceTree.RemoveBranch(id);else host!._tree.RemoveBranch(id);
            return Results.Json(new {ok=true});
        });
        group.MapGet("/branches",()=>Results.Json(new {ok=true,items=host!._tree.Branches()}));
        group.MapPost("/branches",(BranchRequest request)=>Results.Json(new {ok=true,id=host!._tree.AddBranch(request.Name,request.Term)}));
        group.MapDelete("/branches/{id:long}",(long id)=>{host!._tree.RemoveBranch(id);return Results.Json(new {ok=true});});
        group.MapPost("/compile",(CompileRequest request)=>Results.Json(host!._tree.CompileLocalized(request.Language)));
        group.MapPost("/mesh/listen",(CaptureSetting setting)=>{
            host!.Mesh().Enable(setting.Enabled);return Results.Json(new {ok=true,mesh=host.Mesh().Status()});
        });
        group.MapGet("/mesh/invitations",()=>Results.Json(new {ok=true,items=host!.Mesh().Invitations()}));
        group.MapPost("/mesh/bind",(BindRequest request)=>{
            host!.Mesh().Bind(request.Invitation);return Results.Json(new {ok=true,mesh=host.Mesh().Status()});
        });
        group.MapPost("/mesh/unbind",(UnbindRequest request)=>{
            host!.Mesh().Unbind(request.Pin);return Results.Json(new {ok=true,mesh=host.Mesh().Status()});
        });
        group.MapPost("/mesh/public-block",(PublicBlockRequest request)=>{
            host!.Mesh().BlockPublic(request.Pin,request.Blocked);return Results.Json(new {ok=true,mesh=host.Mesh().Status()});
        });
        group.MapPost("/mesh/receive",()=>Results.Json(host!.Mesh().StartReceive()));
    }
    public async ValueTask DisposeAsync()
    {
        try{if(_mesh!=null)await _mesh.DisposeAsync();}finally{_tree.Dispose();}
    }
}
