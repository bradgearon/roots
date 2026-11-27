using System.Text;

namespace Wds.Roots.Host.Runtime;

/// <summary>An OS-held file lease, released even after a launcher crash. A file
/// left on disk is not treated as a running process. No thread-affine mutex is
/// held across the host's asynchronous startup and shutdown.</summary>
internal sealed class HostInstanceLease : IDisposable
{
    private readonly FileStream _stream;

    private HostInstanceLease(FileStream stream) => _stream = stream;

    public static HostInstanceLease Acquire(string lockPath)
    {
        string path = Path.GetFullPath(lockPath);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        FileStream stream;
        try { stream = new FileStream(path, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None); }
        catch (IOException error)
        {
            throw new InvalidOperationException(
                "Could not acquire the Roots desktop host lease. Another Roots launcher may already be running. " +
                "Check its console and the listeners on ports 8081, 8223 and 8224 before launching again. " +
                $"Lease file: {path}. System error: {error.Message}", error);
        }

        try
        {
            byte[] owner = Encoding.UTF8.GetBytes($"Roots host PID {Environment.ProcessId}\nStarted {DateTimeOffset.Now:O}\n");
            stream.SetLength(0);
            stream.Write(owner);
            stream.Flush();
            return new HostInstanceLease(stream);
        }
        catch { stream.Dispose(); throw; }
    }

    public void Dispose() => _stream.Dispose();
}
