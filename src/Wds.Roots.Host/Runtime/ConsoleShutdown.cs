using System.Diagnostics;

namespace Wds.Roots.Host.Runtime;

/// <summary>
/// Owns Ctrl+C for a foreground Roots entry point. Request orderly cancellation
/// first; independently terminate this process if native IO, a worker, or disposal
/// does not complete. Windows closes all process handles and owned cleanup jobs
/// on termination. Never enumerates or kills another Roots session.
/// </summary>
internal sealed class ConsoleShutdown : IDisposable
{
    private readonly CancellationTokenSource _quit;
    private readonly TimeSpan _deadline;
    private readonly string _role;
    private int _stopping;

    public ConsoleShutdown(CancellationTokenSource quit, string role, TimeSpan deadline)
    {
        _quit = quit;
        _role = role;
        _deadline = deadline;
        Console.CancelKeyPress += Cancel;
    }

    private void Cancel(object? sender, ConsoleCancelEventArgs e)
    {
        e.Cancel = true;
        if (Interlocked.Exchange(ref _stopping, 1) != 0)
        {
            ForceExit();
            return;
        }

        // A dedicated thread does not depend on the thread pool, which may be
        // blocked by native reads or disposal. Keep it armed until process exit.
        var watchdog = new Thread(() =>
        {
            Thread.Sleep(_deadline);
            ForceExit();
        }) { IsBackground = true, Name = "Roots shutdown deadline" };
        watchdog.Start();

        // Do not run cancellation callbacks or pipe shutdown synchronously
        // on the console control handler.
        _ = _quit.CancelAsync();
        Console.WriteLine($"Stopping {_role} (PID {Environment.ProcessId})...");
    }

    private static void ForceExit()
    {
        // Kill only ourselves. Existing non-inherited job handles guarantee
        // cleanup of attached children even if managed finally blocks stall.
        using var self = Process.GetCurrentProcess();
        self.Kill();
    }

    public void Dispose() => Console.CancelKeyPress -= Cancel;
}
