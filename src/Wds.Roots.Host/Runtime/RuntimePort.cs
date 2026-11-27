using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;

namespace Wds.Roots.Host.Runtime;

/// <summary>Local bind preflight only; it never connects to, adopts, or stops
/// another service. The runtime must still handle the race after this probe.</summary>
internal static class RuntimePort
{
    public static void EnsureAvailable(int port, bool allAddresses = false, bool includeIPv6 = false)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(port, 1);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(port, 65535);
        Probe(allAddresses ? IPAddress.Any : IPAddress.Loopback, port);
        if ((allAddresses || includeIPv6) && Socket.OSSupportsIPv6)
            Probe(allAddresses ? IPAddress.IPv6Any : IPAddress.IPv6Loopback, port, dualMode: allAddresses);
    }

    private static void Probe(IPAddress address, int port, bool dualMode = false)
    {
        try
        {
            using var socket = new Socket(address.AddressFamily, SocketType.Stream, ProtocolType.Tcp)
            {
                ExclusiveAddressUse = true
            };
            // Match Kestrel: IPv6Any also covers IPv4, whereas ::1 is a
            // separate localhost listener beside 127.0.0.1.
            if (address.AddressFamily == AddressFamily.InterNetworkV6) socket.DualMode = dualMode;
            socket.Bind(new IPEndPoint(address, port));
            socket.Listen(1);
        }
        catch (SocketException error) when (address.AddressFamily == AddressFamily.InterNetworkV6 &&
            error.SocketErrorCode is SocketError.AddressFamilyNotSupported or SocketError.AddressNotAvailable or SocketError.ProtocolNotSupported)
        {
            // Kestrel can use IPv4 on machines with IPv6 disabled or unavailable.
        }
        catch (SocketException error)
        {
            string owners = DescribeListeners(port);
            string inspect = OperatingSystem.IsWindows()
                ? $" Inspect in PowerShell: Get-NetTCPConnection -State Listen -LocalPort {port} | Select-Object LocalAddress,LocalPort,OwningProcess."
                : $" Inspect local TCP listeners for port {port}.";
            throw new InvalidOperationException(
                $"Cannot bind local TCP port {port} at {address} ({error.SocketErrorCode}). " +
                owners + " Close the identified service only if you intend to replace it, then launch again." + inspect, error);
        }
    }

    private static string DescribeListeners(int port)
    {
        if (!OperatingSystem.IsWindows()) return "The port is already in use or unavailable to this process.";
        try
        {
            var listeners = new List<Listener>();
            ReadListeners(2, port, listeners);  // AF_INET
            ReadListeners(23, port, listeners); // AF_INET6
            if (listeners.Count == 0)
                return "No active listener could be identified; a port reservation or access restriction can also prevent binding.";

            string details = string.Join("; ", listeners.Distinct().OrderBy(x => x.Pid).Take(8)
                .Select(x => $"{x.Endpoint}, {DescribeProcess(x.Pid)}"));
            return "Local listeners on this port: " + details + ".";
        }
        catch (Exception)
        {
            // Ownership metadata is best effort and must never mask the bind error.
            return "Listener ownership could not be read.";
        }
    }

    private static string DescribeProcess(uint pid)
    {
        try
        {
            using Process process = Process.GetProcessById(checked((int)pid));
            string name = process.ProcessName;
            string? path = null;
            try { path = process.MainModule?.FileName; } catch (Exception) { }
            return $"PID {pid} ({name}" + (string.IsNullOrEmpty(path) ? ")" : $", {path})");
        }
        catch (Exception) { return $"PID {pid} (process details unavailable)"; }
    }

    private sealed record Listener(uint Pid, string Endpoint);

    private static void ReadListeners(int family, int port, List<Listener> listeners)
    {
        const uint InsufficientBuffer = 122;
        const int MaxTableBytes = 2 * 1024 * 1024;
        int size = 0;
        uint status = GetExtendedTcpTable(nint.Zero, ref size, false, family, 3, 0);
        if (status != InsufficientBuffer || size < sizeof(uint) || size > MaxTableBytes) return;

        // Local endpoints can change between calls. Retry allocation at most
        // twice; diagnostics are bounded and cannot delay startup indefinitely.
        for (int attempt = 0; attempt < 2; attempt++)
        {
            int allocated = size;
            nint buffer = Marshal.AllocHGlobal(allocated);
            try
            {
                status = GetExtendedTcpTable(buffer, ref size, false, family, 3, 0);
                if (status == InsufficientBuffer)
                {
                    if (size < sizeof(uint) || size > MaxTableBytes) return;
                    continue;
                }
                if (status != 0 || size < sizeof(uint) || size > allocated) return;
                int count = Marshal.ReadInt32(buffer);
                int rowSize = family == 2 ? Marshal.SizeOf<Tcp4Row>() : Marshal.SizeOf<Tcp6Row>();
                if (count < 0 || count > (size - sizeof(uint)) / rowSize) return;
                for (int i = 0; i < count; i++)
                {
                    nint entry = nint.Add(buffer, sizeof(uint) + i * rowSize);
                    if (family == 2)
                    {
                        Tcp4Row row = Marshal.PtrToStructure<Tcp4Row>(entry);
                        if (HostPort(row.LocalPort) != port) continue;
                        string address = new IPAddress(BitConverter.GetBytes(row.LocalAddress)).ToString();
                        listeners.Add(new(row.OwningPid, $"{address}:{port}"));
                    }
                    else
                    {
                        Tcp6Row row = Marshal.PtrToStructure<Tcp6Row>(entry);
                        if (HostPort(row.LocalPort) != port) continue;
                        string address = new IPAddress(row.LocalAddress).ToString();
                        listeners.Add(new(row.OwningPid, $"[{address}]:{port}"));
                    }
                }
                return;
            }
            finally { Marshal.FreeHGlobal(buffer); }
        }
    }

    private static int HostPort(uint port) => unchecked((ushort)IPAddress.NetworkToHostOrder((short)port));

    // TCP_TABLE_OWNER_PID_LISTENER returns rows of these layouts. Ports are in
    // network byte order; only the low 16 bits carry the port number.
    [StructLayout(LayoutKind.Sequential)]
    private struct Tcp4Row
    {
        public uint State, LocalAddress, LocalPort, RemoteAddress, RemotePort, OwningPid;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Tcp6Row
    {
        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 16)] public byte[] LocalAddress;
        public uint LocalScopeId, LocalPort;
        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 16)] public byte[] RemoteAddress;
        public uint RemoteScopeId, RemotePort, State, OwningPid;
    }

    [DllImport("iphlpapi.dll", ExactSpelling = true)]
    private static extern uint GetExtendedTcpTable(nint table, ref int size,
        [MarshalAs(UnmanagedType.Bool)] bool order, int addressFamily, int tableClass, uint reserved);
}
