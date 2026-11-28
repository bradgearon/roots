using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Wds.Roots.Tree;

/// <summary>
/// Shared local capture policy. The setting survives launcher/host restarts.
/// All Experience intake and policy changes share a short cross-process gate.
/// An epoch fences observations acquired before a pause/resume transition.
/// </summary>
public sealed class ExperienceCaptureControl
{
    public sealed record Setting(bool Enabled,string Epoch);
    public sealed record Worker(string Run,string[] Excluded,string Epoch,bool Active,int ControlVersion=1);
    public sealed record CaptureStatus(bool Enabled,bool WatcherRunning,bool WorkerActive,bool SupportsPause,bool CurrentEpoch);

    private readonly string directory, mutexName;
    private string PolicyPath=>Path.Combine(directory,"capture.control.json");
    private string WorkerPath=>Path.Combine(directory,"capture.session.json");

    public ExperienceCaptureControl(string directory)
    {
        this.directory=Path.GetFullPath(directory);
        string identity=OperatingSystem.IsWindows()?this.directory.ToUpperInvariant():this.directory;
        mutexName=(OperatingSystem.IsWindows()?@"Local\":"")+"Roots.ExperienceCapture."+Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(identity)));
    }

    private T Locked<T>(Func<T> operation)
    {
        using var mutex=new Mutex(false,mutexName);
        bool entered=false;
        try{
            try{entered=mutex.WaitOne(TimeSpan.FromSeconds(15));}
            catch(AbandonedMutexException){entered=true;}
            if(!entered)throw new TimeoutException("Experience capture control is busy.");
            return operation();
        }finally{if(entered)mutex.ReleaseMutex();}
    }

    private static void WriteAtomic<T>(string path,T value)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        string temporary=path+"."+Guid.NewGuid().ToString("N")+".tmp";
        try{
            using(var stream=new FileStream(temporary,FileMode.CreateNew,FileAccess.Write,FileShare.None)){
                JsonSerializer.Serialize(stream,value);stream.Flush(true);
            }
            File.Move(temporary,path,true);
        }finally{if(File.Exists(temporary))File.Delete(temporary);}
    }

    private Setting ReadUnlocked()
    {
        // Preserve the preexisting behavior on first launch. Only an explicit
        // resume can recover an invalid policy; never silently enable it.
        try{
            var value=JsonSerializer.Deserialize<Setting>(File.ReadAllText(PolicyPath));
            return value!=null&&Guid.TryParseExact(value.Epoch,"N",out _)?value:new(false,"");
        }catch(FileNotFoundException){return new(true,Guid.Empty.ToString("N"));}
        catch(DirectoryNotFoundException){return new(true,Guid.Empty.ToString("N"));}
        catch(IOException){return new(false,"");}
        catch(UnauthorizedAccessException){return new(false,"");}
        catch(JsonException){return new(false,"");}
    }

    public Setting Read()=>Locked(ReadUnlocked);

    public Setting SetEnabled(bool enabled)=>Locked(()=>{
        var old=ReadUnlocked();
        if(old.Enabled==enabled&&old.Epoch!="")return old;
        var next=new Setting(enabled,Guid.NewGuid().ToString("N"));
        WriteAtomic(PolicyPath,next);
        return next;
    });

    // The gate is held only during a synchronous, bounded write. Once a pause
    // returns successfully, no old-epoch writer can commit afterward.
    public bool TryCommit(string epoch,Action commit)=>Locked(()=>{
        var state=ReadUnlocked();
        if(!state.Enabled||state.Epoch!=epoch)return false;
        commit();return true;
    });

    private bool WatcherRunning()
    {
        try{
            using var unused=new FileStream(Path.Combine(directory,"capture.lock"),FileMode.Open,FileAccess.ReadWrite,FileShare.None);
            return false;
        }catch(FileNotFoundException){return false;}
        catch(DirectoryNotFoundException){return false;}
        catch(IOException e) when((e.HResult&0xffff) is 32 or 33){return true;}
    }

    private Worker? ReadWorker()
    {
        try{
            var worker=JsonSerializer.Deserialize<Worker>(File.ReadAllText(WorkerPath));
            if(worker is {ControlVersion:1,Excluded.Length:<=64}&&Guid.TryParseExact(worker.Run,"N",out _)&&Guid.TryParseExact(worker.Epoch,"N",out _))
                return worker;
        }catch(IOException){}
        catch(UnauthorizedAccessException){}
        catch(JsonException){}
        return null;
    }

    // Called by the lease-owning receiver, never by the extension.
    public void PublishWorker(Worker worker)=>Locked(()=>{
        WriteAtomic(WorkerPath,worker);return true;
    });

    public Worker? ActiveSession()=>Locked(()=>{
        var state=ReadUnlocked();
        if(!state.Enabled||!WatcherRunning())return null;
        var worker=ReadWorker();
        return worker is {Active:true}&&worker.Epoch==state.Epoch?worker:null;
    });

    public CaptureStatus Status()=>Locked(()=>{
        var state=ReadUnlocked();bool running=WatcherRunning();
        var worker=running?ReadWorker():null;
        return new CaptureStatus(state.Enabled,running,worker?.Active==true,!running||worker!=null,worker!=null&&worker.Epoch==state.Epoch);
    });
}
