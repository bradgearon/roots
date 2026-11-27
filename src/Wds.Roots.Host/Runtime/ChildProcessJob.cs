using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace Wds.Roots.Host.Runtime;

/// <summary>
/// Lifetime boundary for children started by this launcher. Windows closes this
/// non-inherited, unnamed handle if the host is forcibly terminated, which also
/// terminates the attached processes. The normal owned-process cleanup remains
/// necessary on every platform. Never attach an existing external runtime.
/// </summary>
internal sealed class ChildProcessJob : IDisposable
{
    private readonly SafeJobHandle? _job;
    private int _disposed;

    private ChildProcessJob(SafeJobHandle? job) => _job = job;

    public static ChildProcessJob Create()
    {
        if (!OperatingSystem.IsWindows()) return new(null);

        // A null SECURITY_ATTRIBUTES pointer makes this handle non-inheritable;
        // a child must not keep the job alive after the launcher disappears.
        SafeJobHandle job = CreateJobObjectW(nint.Zero, null);
        try
        {
            if (job.IsInvalid)
                throw new Win32Exception(Marshal.GetLastWin32Error(), "Could not create the Windows process cleanup job.");

            var limits = new JobExtendedLimitInformation
            {
                BasicLimitInformation = new JobBasicLimitInformation { LimitFlags = 0x00002000 }
            };
            if (!SetInformationJobObject(job, 9, ref limits, (uint)Marshal.SizeOf<JobExtendedLimitInformation>()))
                throw new Win32Exception(Marshal.GetLastWin32Error(), "Could not enable Windows cleanup of launcher-owned processes.");
            return new(job);
        }
        catch
        {
            job.Dispose();
            throw;
        }
    }

    /// <summary>Call immediately after successfully starting an owned child.
    /// The caller must stop its new child if assignment fails.</summary>
    public void Attach(Process process)
    {
        ArgumentNullException.ThrowIfNull(process);
        ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed) != 0, this);
        if (_job == null || process.HasExited) return;

        if (!AssignProcessToJobObject(_job, process.SafeHandle))
        {
            int error = Marshal.GetLastWin32Error();
            // A fast startup failure must retain its real exit code and output,
            // rather than being hidden behind a job-assignment error.
            if (process.HasExited) return;
            throw new Win32Exception(error,
                $"Could not attach process {process.Id} to the Windows cleanup job. The new child must be stopped before retrying.");
        }
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) == 0) _job?.Dispose();
    }

    // Layouts follow JOBOBJECT_BASIC_LIMIT_INFORMATION and
    // JOBOBJECT_EXTENDED_LIMIT_INFORMATION in winnt.h; SIZE_T/ULONG_PTR vary
    // with architecture. Only KILL_ON_JOB_CLOSE is enabled, with no UI limits.
    [StructLayout(LayoutKind.Sequential)]
    private struct JobBasicLimitInformation
    {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public uint LimitFlags;
        public nuint MinimumWorkingSetSize;
        public nuint MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public nuint Affinity;
        public uint PriorityClass;
        public uint SchedulingClass;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct IoCounters
    {
        public ulong ReadOperationCount;
        public ulong WriteOperationCount;
        public ulong OtherOperationCount;
        public ulong ReadTransferCount;
        public ulong WriteTransferCount;
        public ulong OtherTransferCount;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct JobExtendedLimitInformation
    {
        public JobBasicLimitInformation BasicLimitInformation;
        public IoCounters IoInfo;
        public nuint ProcessMemoryLimit;
        public nuint JobMemoryLimit;
        public nuint PeakProcessMemoryUsed;
        public nuint PeakJobMemoryUsed;
    }

    private sealed class SafeJobHandle : SafeHandleZeroOrMinusOneIsInvalid
    {
        public SafeJobHandle() : base(ownsHandle: true) { }
        protected override bool ReleaseHandle() => CloseHandle(handle);
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)]
    private static extern SafeJobHandle CreateJobObjectW(nint attributes, string? name);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetInformationJobObject(SafeJobHandle job, int informationClass,
        ref JobExtendedLimitInformation information, uint informationLength);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool AssignProcessToJobObject(SafeJobHandle job, SafeProcessHandle process);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CloseHandle(nint handle);
}
