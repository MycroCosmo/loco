param([string]$LinuxImage = 'eclipse-temurin:21-jre-jammy@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269')
$ErrorActionPreference = 'Stop'
if (-not $IsWindows) { throw 'Windows PowerShell 7에서 실행하세요. Linux 검증은 이 스크립트가 Docker로 실행합니다.' }
Push-Location (Resolve-Path "$PSScriptRoot/..")
try {
    foreach ($name in @('summary.json', 'summary.md')) {
        $previous = Join-Path $PWD "build/reports/codec/linux/$name"
        if (Test-Path -LiteralPath $previous) { Remove-Item -LiteralPath $previous }
    }
    $timer = [Diagnostics.Stopwatch]::StartNew()
    & ./gradlew.bat codecTest --rerun prepareCodecProbe --console=plain
    $windowsExit = $LASTEXITCODE
    $timer.Stop()
    $windowsSeconds = $timer.Elapsed.TotalSeconds
    if ($windowsExit -ne 0) { throw 'Windows 필수 형식 검증 실패: API 이관을 중단하고 B안을 검토하세요.' }
    $probe = (Resolve-Path build/codec-probe).Path
    $reports = (Resolve-Path build/reports/codec).Path
    New-Item -ItemType Directory -Force "$reports/linux" | Out-Null
    docker image inspect $LinuxImage *> $null
    if ($LASTEXITCODE -ne 0) {
        docker pull $LinuxImage
        if ($LASTEXITCODE -ne 0) { throw 'Linux 검증 이미지 준비 실패' }
    }
    $timer.Restart()
    docker run --rm --network none --user 1000:1000 --cpus 2 --memory 1536m `
        --mount "type=bind,source=$probe,target=/probe,readonly" `
        --mount "type=bind,source=$reports,target=/reports" `
        $LinuxImage java '-Djava.awt.headless=true' -cp '/probe/classes:/probe/lib/*' `
        com.loco.images.codec.CodecProbe /reports/linux
    $linuxExit = $LASTEXITCODE
    $timer.Stop()
    [pscustomobject]@{
        windowsSeconds = $windowsSeconds
        linuxSeconds = $timer.Elapsed.TotalSeconds
        linuxImage = $LinuxImage
        linuxExitCode = $linuxExit
        imagePullIncluded = $false
    } | ConvertTo-Json | Set-Content "$reports/timing.json"
    if ($linuxExit -ne 0) { throw 'Linux 필수 형식 검증 실패: API 이관을 중단하고 B안을 검토하세요.' }
    Get-Content "$reports/windows/summary.md"
    Get-Content "$reports/linux/summary.md"
    Get-Content "$reports/timing.json"
} finally { Pop-Location }
