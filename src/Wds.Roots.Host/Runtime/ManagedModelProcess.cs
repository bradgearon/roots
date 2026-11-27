using System.Diagnostics;

namespace Wds.Roots.Host.Runtime;

/// <summary>Owns exactly the child it starts. Captures both output streams before
/// checking readiness, preserves a bounded diagnostic tail, and closes its
/// Windows job plus the owned process tree on shutdown.</summary>
internal sealed class ManagedModelProcess : IAsyncDisposable
{
    private const int TailLines = 60, LineCharacters = 2048;
    private readonly Process _process;
    private readonly ChildProcessJob _job;
    private readonly Action<string>? _log;
    private readonly string _name;
    private readonly Queue<string> _tail = new();
    private readonly object _tailLock = new();
    private readonly Task _output;
    private readonly Task _exit;
    private int _disposed;

    private ManagedModelProcess(Process process, ChildProcessJob job, Action<string>? log, string name)
    {
        _process = process; _job = job; _log = log; _name = name;
        _output = Task.WhenAll(ReadOutputAsync(process.StandardOutput, "stdout"), ReadOutputAsync(process.StandardError, "stderr"));
        _exit = process.WaitForExitAsync();
    }

    public int ProcessId => _process.Id;
    public bool HasExited => _process.HasExited;
    public Task ExitTask => _exit;

    public static ManagedModelProcess Start(ProcessStartInfo start, Action<string>? log = null, string name = "PLaMo")
    {
        ArgumentNullException.ThrowIfNull(start);
        start.UseShellExecute = false;
        start.CreateNoWindow = true;
        start.RedirectStandardOutput = true;
        start.RedirectStandardError = true;
        var job = ChildProcessJob.Create();
        var process = new Process { StartInfo = start };
        bool started = false;
        try
        {
            started = process.Start();
            if (!started) throw new InvalidOperationException($"Could not start the {name} process.");
            job.Attach(process);
            return new ManagedModelProcess(process, job, log, name);
        }
        catch
        {
            // Only this newly created process can be stopped here. A failed
            // containment setup must never leave an untracked model server.
            try
            {
                if (started && !process.HasExited) process.Kill(entireProcessTree: true);
            }
            catch (InvalidOperationException) { }
            catch (System.ComponentModel.Win32Exception) { /* The job handle is still closed below. */ }
            finally { job.Dispose(); process.Dispose(); }
            throw;
        }
    }

    private async Task ReadOutputAsync(StreamReader reader, string stream)
    {
        try
        {
            while (await reader.ReadLineAsync().ConfigureAwait(false) is { } line)
            {
                if (line.Length > LineCharacters) line = line[..LineCharacters] + "…";
                string entry = $"[{stream}] {line}";
                lock (_tailLock)
                {
                    _tail.Enqueue(entry);
                    while (_tail.Count > TailLines) _tail.Dequeue();
                }
                Log(entry);
            }
        }
        catch (IOException) { /* A closed pipe during bounded shutdown. */ }
        catch (ObjectDisposedException) { }
    }

    private void Log(string message)
    {
        try { _log?.Invoke(message); }
        catch (Exception) { /* Logging cannot prevent draining a child pipe. */ }
    }

    private string Tail()
    {
        lock (_tailLock)
            return _tail.Count == 0 ? $"No {_name} stdout/stderr was captured." : string.Join(Environment.NewLine, _tail);
    }

    public async Task ThrowIfExitedAsync()
    {
        if (!_process.HasExited) return;
        // Short-lived DLL/tokenizer failures can exit before their last stderr
        // line is dispatched. Drain both streams before formatting the error.
        await DrainOutputAsync().ConfigureAwait(false);
        int code = _process.ExitCode;
        throw new InvalidOperationException(
            $"{_name} process {ProcessId} exited with code {code} (0x{unchecked((uint)code):X8})." + Environment.NewLine +
            $"Executable: {_process.StartInfo.FileName}" + Environment.NewLine + Tail());
    }

    public async Task WaitUntilReadyAsync(Func<CancellationToken, Task<bool>> probe, TimeSpan timeout, CancellationToken token)
    {
        ArgumentNullException.ThrowIfNull(probe);
        if (timeout <= TimeSpan.Zero) throw new ArgumentOutOfRangeException(nameof(timeout));
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(token);
        deadline.CancelAfter(timeout);
        try
        {
            while (true)
            {
                deadline.Token.ThrowIfCancellationRequested();
                await ThrowIfExitedAsync().ConfigureAwait(false);
                bool ready = await probe(deadline.Token).WaitAsync(deadline.Token).ConfigureAwait(false);
                // A response from a previous/unrelated server cannot hide an
                // already exited child of this launch.
                await ThrowIfExitedAsync().ConfigureAwait(false);
                if (ready) return;
                await Task.Delay(250, deadline.Token).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException) when (!token.IsCancellationRequested)
        {
            await ThrowIfExitedAsync().ConfigureAwait(false);
            throw new TimeoutException($"{_name} process {ProcessId} did not become ready within {timeout.TotalSeconds:0} seconds." +
                Environment.NewLine + Tail());
        }
    }

    private async Task DrainOutputAsync()
    {
        try { await _output.WaitAsync(TimeSpan.FromSeconds(2)).ConfigureAwait(false); }
        catch (TimeoutException) { /* A descendant may still hold an inherited pipe. */ }
    }

    public async ValueTask DisposeAsync()
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0) return;
        try
        {
            try { if (!_process.HasExited) _process.Kill(entireProcessTree: true); }
            catch (InvalidOperationException) { }
            catch (System.ComponentModel.Win32Exception error) { Log("Could not request process shutdown: " + error.Message); }
            finally { _job.Dispose(); }
            try { await _exit.WaitAsync(TimeSpan.FromSeconds(5)).ConfigureAwait(false); }
            catch (TimeoutException) { Log($"Process {ProcessId} has not confirmed exit. Inspect this PID before relaunching."); }
            await DrainOutputAsync().ConfigureAwait(false);
        }
        finally
        {
            _job.Dispose();
            _process.StandardOutput.Dispose();
            _process.StandardError.Dispose();
            _process.Dispose();
        }
    }
}
