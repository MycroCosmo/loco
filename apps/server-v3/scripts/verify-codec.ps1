param([string]$LinuxImage = 'loco-vips-codec:8.15.3')
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
    if ($windowsExit -ne 0) { throw 'Windows 필수 형식 검증 실패: 변환기와 네이티브 구성을 확인하세요.' }
    $probe = (Resolve-Path build/codec-probe).Path
    $reports = (Resolve-Path build/reports/codec).Path
    New-Item -ItemType Directory -Force "$reports/linux" | Out-Null
    docker build --target codec-runtime -f ../../infra/docker/server-v3.Dockerfile -t $LinuxImage ../..
    if ($LASTEXITCODE -ne 0) { throw 'Linux libvips 검증 이미지 준비 실패' }
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
        imagePreparationIncluded = $false
    } | ConvertTo-Json | Set-Content "$reports/timing.json"
    if ($linuxExit -ne 0) { throw 'Linux 필수 형식 검증 실패: 변환기와 네이티브 구성을 확인하세요.' }
    Get-Content "$reports/windows/summary.md"
    Get-Content "$reports/linux/summary.md"
    Get-Content "$reports/timing.json"
} finally { Pop-Location }
