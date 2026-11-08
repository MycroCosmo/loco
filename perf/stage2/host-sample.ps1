param([Parameter(Mandatory)][string]$OutputFile, [int]$Seconds = 600)
$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public class PerfHostCounters {
    [DllImport("kernel32.dll")] public static extern bool GetSystemTimes(out long idle, out long kernel, out long user);
    [StructLayout(LayoutKind.Sequential)] public struct Memory {
        public uint length, load;
        public ulong totalPhysical, availablePhysical, totalPage, availablePage, totalVirtual, availableVirtual, extendedVirtual;
    }
    [DllImport("kernel32.dll")] public static extern bool GlobalMemoryStatusEx(ref Memory memory);
}
'@
$writer = [IO.StreamWriter]::new($OutputFile, $false, [Text.UTF8Encoding]::new($false))
$writer.AutoFlush = $true
$clock = [Diagnostics.Stopwatch]::StartNew()
$oldIdle = 0L; $oldTotal = 0L
try {
    while ($clock.Elapsed.TotalSeconds -lt $Seconds) {
        $idle = 0L; $kernel = 0L; $user = 0L
        if (-not [PerfHostCounters]::GetSystemTimes([ref]$idle, [ref]$kernel, [ref]$user)) { throw 'Host CPU counters unavailable' }
        $memory = New-Object PerfHostCounters+Memory
        $memory.length = 64
        if (-not [PerfHostCounters]::GlobalMemoryStatusEx([ref]$memory)) { throw 'Host memory counters unavailable' }
        $total = $kernel + $user
        $busy = if ($oldTotal -ne 0 -and $total -gt $oldTotal) { 100 * (1 - ($idle - $oldIdle) / ($total - $oldTotal)) } else { $null }
        $writer.WriteLine((@{at=[DateTimeOffset]::UtcNow.ToString('o');cpuPercent=$busy;availablePhysicalBytes=$memory.availablePhysical;memoryLoadPercent=$memory.load} | ConvertTo-Json -Compress))
        $oldIdle = $idle; $oldTotal = $total
        Start-Sleep -Milliseconds 1000
    }
} finally { $writer.Dispose() }
