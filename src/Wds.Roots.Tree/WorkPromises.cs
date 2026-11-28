namespace Wds.Roots.Tree;

/// Event-driven projection. Call Refresh on a completion notification and
/// RefreshAll after reconnect. Disposing does not cancel durable work.
public sealed class WorkPromises : IDisposable
{
    private readonly NativeTree _tree;
    private readonly object _gate=new();
    private readonly Dictionary<long,TaskCompletionSource<NativeTree.Work>> _waiting=new();
    private bool _closed;
    public WorkPromises(NativeTree tree)=>_tree=tree;
    public sealed record Promise(long Id,Task<NativeTree.Work> Completion);
    public Promise Request(long fragment,string operation,string profile,long priority,long deadline=0)
    {
        lock(_gate){ObjectDisposedException.ThrowIf(_closed,this);return Attach(_tree.RequestWork(fragment,operation,profile,priority,deadline));}
    }
    public Promise Attach(long id)
    {
        TaskCompletionSource<NativeTree.Work> source;
        lock(_gate){
            ObjectDisposedException.ThrowIf(_closed,this);
            if(!_waiting.TryGetValue(id,out var existing)){source=new(TaskCreationOptions.RunContinuationsAsynchronously);_waiting.Add(id,source);}
            else source=existing;
        }
        Refresh(id);return new(id,source.Task);
    }
    public void Refresh(long id)
    {
        TaskCompletionSource<NativeTree.Work> source;NativeTree.Work? job;
        lock(_gate){
            if(!_waiting.TryGetValue(id,out var existing))return;
            source=existing;job=_tree.ReadWork(id);
            if(job?.State is NativeTree.WorkState.Pending or NativeTree.WorkState.Running)return;
            _waiting.Remove(id);
        }
        if(job==null)source.TrySetException(new InvalidOperationException("Work removed"));else source.TrySetResult(job);
    }
    public void RefreshAll(){long[] ids;lock(_gate)ids=_waiting.Keys.ToArray();foreach(var id in ids)Refresh(id);}
    public void Dispose(){
        TaskCompletionSource<NativeTree.Work>[] waiting;
        lock(_gate){_closed=true;waiting=_waiting.Values.ToArray();_waiting.Clear();}
        foreach(var source in waiting)source.TrySetCanceled();
    }
}
