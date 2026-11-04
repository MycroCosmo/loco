$ErrorActionPreference = 'Stop'
$directory = Join-Path $PSScriptRoot '../build/vips'
$executable = Join-Path $directory 'vips-dev-8.15/bin/vips.exe'
if (Test-Path -LiteralPath $executable) {
    $version = & $executable --version
    if ($LASTEXITCODE -eq 0 -and $version -eq 'vips-8.15.3') { return }
    throw 'Existing libvips has an unexpected version; inspect build/vips before replacing it.'
}
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$archive = Join-Path $directory 'windows.zip'
$expected = 'AF6886ECC4C86084B99544BB47950797D3686ABAF4462FF10446BA00ACD73BFD'
if (!(Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest -Uri 'https://github.com/libvips/build-win64-mxe/releases/download/v8.15.3/vips-dev-w64-web-8.15.3.zip' -OutFile $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expected) { throw 'libvips archive checksum mismatch' }
Expand-Archive -LiteralPath $archive -DestinationPath $directory -Force
if ((& $executable --version) -ne 'vips-8.15.3') { throw 'libvips version check failed' }
