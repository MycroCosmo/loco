param([Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]+$')][string]$Label,
      [ValidateSet('each-case','quota')][string]$RestartMode='quota')
$ErrorActionPreference = 'Stop'
$app = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$destination = Join-Path $app "build/performance/$Label"
New-Item -ItemType Directory -Path $destination -Force | Out-Null
Remove-Item -LiteralPath (Join-Path $destination 'result.json') -Force -ErrorAction SilentlyContinue
Push-Location $app
try {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    & ./gradlew.bat spotlessApply test --rerun comparisonTest --rerun build "-PharnessRestart=$RestartMode" --console=plain *> (Join-Path $destination 'gradle.log')
    $code = $LASTEXITCODE
    $timer.Stop()
    if ($code -ne 0) { Get-Content (Join-Path $destination 'gradle.log') -Tail 45; throw "Gradle failed ($code), elapsed $($timer.Elapsed.TotalSeconds)s" }
    $totals = @{total=0;passed=0;failed=0;skipped=0}
    $timing = @{restart=0.0;preparation=0.0;request=0.0;image=0.0}
    $tasks = @{}
    $manifest = @()
    $groups = @(
        @{name='test'; directory='build/test-results/test'; report='fast'},
        @{name='comparisonTest'; directory='build/test-results/comparisonTest'; report='compatibility'},
        @{name='cms-core:test'; directory='modules/cms-core/build/test-results/test'; report=$null}
    )
    foreach ($group in $groups) {
        $task = $group.name
        $counts = @{total=0;failed=0;skipped=0}
        $results = @(Get-ChildItem (Join-Path $group.directory 'TEST-*.xml'))
        if ($results.Count -eq 0) { throw "No test results for $task" }
        $results | ForEach-Object {
            [xml]$xml = Get-Content $_.FullName -Raw
            $counts.total += [int]$xml.testsuite.tests
            $counts.failed += [int]$xml.testsuite.failures + [int]$xml.testsuite.errors
            $counts.skipped += [int]$xml.testsuite.skipped
            foreach ($case in $xml.testsuite.testcase) { $manifest += "$task|$($case.classname)|$($case.name)" }
        }
        $counts.passed = $counts.total - $counts.failed - $counts.skipped
        $tasks[$task] = $counts
        foreach($key in @('total','passed','failed','skipped')) { $totals[$key] += $counts[$key] }
        $report = $group.report
        if (!$report) { continue }
        foreach($file in @('summary','timing')) { Copy-Item "build/reports/$report/$file.json" (Join-Path $destination "$task-$file.json") }
        $observed = Get-Content "build/reports/$report/timing.json" -Raw | ConvertFrom-Json
        $timing.restart += $observed.restartMillis / 1000
        $timing.preparation += $observed.preparationMillis / 1000
        $timing.request += $observed.requestExcludingImageMillis / 1000
        $timing.image += $observed.imageMillis / 1000
    }
    $timing.other = $timer.Elapsed.TotalSeconds - $timing.restart - $timing.preparation - $timing.request - $timing.image
    $summary = Get-Content (Join-Path $destination 'comparisonTest-summary.json') -Raw | ConvertFrom-Json
    $result = @{label=$Label;exitCode=$code;seconds=$timer.Elapsed.TotalSeconds;categoriesSeconds=$timing;tests=$totals;tasks=$tasks;v2Restarts=$summary.v2Restarts;knownDifferences=$summary.knownDifferences.Count}
    if (Test-Path 'build/reports/compatibility/quota.json') { Copy-Item 'build/reports/compatibility/quota.json' (Join-Path $destination 'quota.json') }
    $manifest | Sort-Object | Set-Content (Join-Path $destination 'test-manifest.txt')
    $result | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $destination 'result.json')
    $result | ConvertTo-Json -Depth 8
    exit $code
} finally { Pop-Location }
