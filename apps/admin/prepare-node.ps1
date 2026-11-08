$ErrorActionPreference = 'Stop'
$version = '24.22.2'
$tools = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../server-v3/build/admin-node'))
$nodeHome = Join-Path $tools "node-v$version-win-x64"
if (-not (Test-Path "$nodeHome/node.exe")) {
    New-Item -ItemType Directory -Force -Path $tools | Out-Null
    $archive = "node-v$version-win-x64.zip"
    Invoke-WebRequest "https://nodejs.org/dist/v$version/$archive" -OutFile "$tools/node.zip"
    $sums = (Invoke-WebRequest "https://nodejs.org/dist/v$version/SHASUMS256.txt").Content
    $expected = (($sums -split "`n" | Where-Object { $_.Trim().EndsWith(" $archive") }) -split '\s+')[0]
    if ((Get-FileHash "$tools/node.zip" -Algorithm SHA256).Hash.ToLower() -ne $expected) {
        throw 'Node archive checksum mismatch'
    }
    Expand-Archive "$tools/node.zip" -DestinationPath $tools -Force
}
$env:ADMIN_NODE_HOME = $nodeHome
$env:PATH = "$nodeHome;$env:PATH"
if ((& "$nodeHome/node.exe" --version) -ne "v$version") { throw 'Incorrect Node version' }
if ((& "$nodeHome/npm.cmd" --version) -ne '11.19.1') { throw 'Incorrect npm version' }
