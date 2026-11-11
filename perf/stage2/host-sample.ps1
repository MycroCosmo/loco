param([Parameter(Mandatory)][string]$OutputFile, [int]$Seconds = 600)
$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public class PerfHostCounters {
    [DllImport("powrprof.dll")] public static extern uint PowerGetEffectiveOverlayScheme(out Guid g);
    [StructLayout(LayoutKind.Sequential)] public struct Power { public byte ACLineStatus,BatteryFlag,BatteryLifePercent,SystemStatusFlag; public uint BatteryLifeTime,BatteryFullLifeTime; }
    [DllImport("kernel32.dll")] public static extern bool GetSystemPowerStatus(out Power p);
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
$previousProcesses = @{}; $previousAt = 0.0
try {
    while ($clock.Elapsed.TotalSeconds -lt $Seconds) {
        $idle = 0L; $kernel = 0L; $user = 0L
        if (-not [PerfHostCounters]::GetSystemTimes([ref]$idle, [ref]$kernel, [ref]$user)) { throw 'Host CPU counters unavailable' }
        $memory = New-Object PerfHostCounters+Memory
        $memory.length = 64
        if (-not [PerfHostCounters]::GlobalMemoryStatusEx([ref]$memory)) { throw 'Host memory counters unavailable' }
        $total = $kernel + $user
        $busy = if ($oldTotal -ne 0 -and $total -gt $oldTotal) { 100 * (1 - ($idle - $oldIdle) / ($total - $oldTotal)) } else { $null }
        $now = $clock.Elapsed.TotalSeconds; $processes = @(); $nextProcesses = @{}
        foreach ($process in Get-Process) {
            if ($process.Id -eq 0) { continue }
            try {
                $cpu = $process.TotalProcessorTime.TotalSeconds
                $cores = if ($previousProcesses.ContainsKey($process.Id) -and $now -gt $previousAt) { [Math]::Max(0.0, ($cpu - $previousProcesses[$process.Id]) / ($now - $previousAt)) } else { 0.0 }
                $nextProcesses[$process.Id] = $cpu
                $workload = $process.ProcessName -in @('vmmemWSL','vmmem','com.docker.backend') -or $process.Id -eq $PID
                $processes += @{name=$process.ProcessName;pid=$process.Id;cpuCores=$cores;workingSetBytes=$process.WorkingSet64;expectedWorkload=$workload}
            } catch { }
        }
        $backgroundCores = ($processes | Where-Object {-not $_.expectedWorkload} | Measure-Object cpuCores -Sum).Sum
        $overlay = [guid]::Empty; $power = New-Object PerfHostCounters+Power
        if ([PerfHostCounters]::PowerGetEffectiveOverlayScheme([ref]$overlay) -ne 0 -or -not [PerfHostCounters]::GetSystemPowerStatus([ref]$power)) { throw 'Power counters unavailable' }
        $writer.WriteLine((@{at=[DateTimeOffset]::UtcNow.ToString('o');cpuPercent=$busy;availablePhysicalBytes=$memory.availablePhysical;memoryLoadPercent=$memory.load;processCount=$processes.Count;nonWorkloadCpuCores=$backgroundCores;topCpu=@($processes | Sort-Object { $_.cpuCores } -Descending | Select-Object -First 10);topMemory=@($processes | Sort-Object { $_.workingSetBytes } -Descending | Select-Object -First 10);ac=$power.ACLineStatus;overlay=$overlay.ToString()} | ConvertTo-Json -Depth 5 -Compress))
        $previousProcesses = $nextProcesses; $previousAt = $now
        $oldIdle = $idle; $oldTotal = $total
        Start-Sleep -Milliseconds 5000
    }
} finally { $writer.Dispose() }
