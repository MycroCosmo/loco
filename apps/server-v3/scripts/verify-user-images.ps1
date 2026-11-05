$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$project = 'loco-images-' + [Guid]::NewGuid().ToString('N').Substring(0, 10)
$directory = Join-Path $repository "apps/server-v3/build/$project"
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$envFile = Join-Path $directory '.env'
@(
    'DB_USERNAME=loco_smoke'
    ('DB_PASSWORD=' + [Guid]::NewGuid().ToString('N'))
    ('JWT_SECRET=' + [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N'))
    'SMTP_HOST=mailpit'
    'SMTP_PORT=1025'
) | Set-Content $envFile
$override = Join-Path $directory 'ports.yaml'
@'
services:
  v2:
    ports: !override ["127.0.0.1::3000"]
  v3:
    ports: !override ["127.0.0.1::8081"]
  mailpit:
    ports: !override []
'@ | Set-Content $override
$compose = @('compose', '--env-file', $envFile, '-p', $project, '-f', (Join-Path $repository 'compose.yaml'), '-f', $override)
function DockerCommand([string[]] $Arguments) {
    $output = & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed: $($Arguments[0])" }
    return $output
}
function UploadImage([string] $Base, [string] $File, [string] $Mime, [string] $Token, [string] $ApiPath='/api/users/me/profile-image') {
    $response = & curl.exe --silent --show-error --fail-with-body -H "Authorization: Bearer $Token" -F "file=@$File;type=$Mime" ($Base + $ApiPath)
    if ($LASTEXITCODE -ne 0) { throw 'Image upload failed' }
    return $response | ConvertFrom-Json
}
function VerifySharedFile([string] $Url) {
    $hashes = @()
    foreach ($base in @($script:v2, $script:v3)) {
        $download = Join-Path $directory ([Guid]::NewGuid().ToString('N') + '.webp')
        $null = Invoke-WebRequest -Uri ($base + $Url) -OutFile $download
        $hashes += (Get-FileHash $download -Algorithm SHA256).Hash
    }
    if ($hashes[0] -ne $hashes[1]) { throw 'Shared image bytes differ' }
}
$timer = [Diagnostics.Stopwatch]::StartNew()
$result = @{ project=$project; passed=$false; checks=@() }
try {
    DockerCommand ($compose + @('up', '--build', '-d', '--wait', '--wait-timeout', '180')) | Out-Null
    $script:v2 = 'http://' + (DockerCommand ($compose + @('port', 'v2', '3000'))).Trim()
    $script:v3 = 'http://' + (DockerCommand ($compose + @('port', 'v3', '8081'))).Trim()
    foreach ($service in @('v2','v3')) {
        $identity = (DockerCommand ($compose + @('exec','-T',$service,'id','-u'))).Trim()
        $group = (DockerCommand ($compose + @('exec','-T',$service,'id','-g'))).Trim()
        if ($identity -ne '1000' -or $group -ne '1000') { throw 'Unexpected UID/GID' }
    }
    $result.checks += 'v2/v3 UID:GID 1000:1000'
    $register = @{email='images@example.test';password=[Guid]::NewGuid().ToString('N');nickname='images'} | ConvertTo-Json
    $account = Invoke-RestMethod -Method Post -Uri "$v2/api/auth/register" -ContentType 'application/json' -Body $register
    DockerCommand ($compose + @('exec','-T','v2','node','-e',"const fs=require('fs');fs.mkdirSync('/app/uploads/profiles',{recursive:true});fs.writeFileSync('/app/uploads/profiles/preserved.txt','preserved')")) | Out-Null
    $previous = $null
    foreach ($extension in @('jpeg','png','webp','avif','animated-webp')) {
        $name = if ($extension -eq 'animated-webp') { 'animated.webp' } else { "quadrants.$extension" }
        $mime = if ($extension -eq 'animated-webp') { 'image/webp' } else { "image/$extension" }
        $file = Join-Path $repository "apps/server-v3/src/test/resources/codec/$name"
        $uploaded = UploadImage $v3 $file $mime $account.accessToken
        VerifySharedFile $uploaded.profileImageUrl
        if ($previous) {
            foreach ($base in @($v2,$v3)) {
                $old = Invoke-WebRequest -Uri ($base + $previous) -SkipHttpErrorCheck
                if ($old.StatusCode -ne 404) { throw 'Previous image was not removed' }
            }
        }
        $previous = $uploaded.profileImageUrl
        $result.checks += "Linux v3 $extension conversion + v2/v3 shared GET"
    }
    $heic = Join-Path $repository 'apps/server-v3/src/test/resources/codec/quadrants.heic'
    $errorFile = Join-Path $directory 'heic-response.json'
    $status = & curl.exe --silent --show-error -o $errorFile -w '%{http_code}' -H "Authorization: Bearer $($account.accessToken)" -F "file=@$heic;type=image/heic" "$v3/api/users/me/profile-image"
    $errorBody = Get-Content $errorFile -Raw | ConvertFrom-Json
    if ($status -ne '500' -or $errorBody.message -ne 'Internal server error') { throw 'HEIC did not retain v2 error contract' }
    $profile = Invoke-RestMethod -Uri "$v3/api/users/me" -Headers @{Authorization="Bearer $($account.accessToken)"}
    if ($profile.profileImageUrl -ne $previous) { throw 'HEIC changed the stored profile' }
    $emptyFiles = (DockerCommand ($compose + @('exec','-T','v2','node','-e',"const fs=require('fs'),p='/app/uploads/profiles/';console.log(fs.readdirSync(p).filter(n=>n.endsWith('.webp')&&fs.statSync(p+n).size===0).length)"))).Trim()
    if ($emptyFiles -ne '1') { throw 'HEIC residual file differs from v2' }
    $result.checks += 'Linux HEIC keeps v2 500 + DB unchanged + empty output file'
    $uploaded = UploadImage $v2 (Join-Path $repository 'apps/server-v3/src/test/resources/codec/quadrants.png') 'image/png' $account.accessToken
    VerifySharedFile $uploaded.profileImageUrl
    if ((Invoke-WebRequest -Uri ($v3 + $previous) -SkipHttpErrorCheck).StatusCode -ne 404) { throw 'v2 did not remove v3 image' }
    if ((Invoke-WebRequest -Uri "$v3/uploads/profiles/preserved.txt").Content -ne 'preserved') { throw 'Unrelated volume data changed' }
    $result.checks += 'v2 replacement + v3 GET + unrelated file preserved'

    $room = Invoke-RestMethod -Method Post -Uri "$v3/api/maps" -Headers @{Authorization="Bearer $($account.accessToken)"} -ContentType 'application/json' -Body '{"title":"cover smoke","visibility":"PUBLIC"}'
    $coverPath = "/api/maps/$($room.id)/cover"
    DockerCommand ($compose + @('exec','-T','v2','node','-e',"const fs=require('fs');fs.mkdirSync('/app/uploads/maps',{recursive:true});fs.writeFileSync('/app/uploads/maps/preserved.txt','preserved')")) | Out-Null
    $previous = $null
    foreach ($extension in @('jpeg','png','webp','avif','animated-webp')) {
        $name = if ($extension -eq 'animated-webp') { 'animated.webp' } else { "quadrants.$extension" }
        $mime = if ($extension -eq 'animated-webp') { 'image/webp' } else { "image/$extension" }
        $uploaded = UploadImage $v3 (Join-Path $repository "apps/server-v3/src/test/resources/codec/$name") $mime $account.accessToken $coverPath
        VerifySharedFile $uploaded.coverImageUrl
        if ($previous -and (Invoke-WebRequest -Uri ($v2 + $previous) -SkipHttpErrorCheck).StatusCode -ne 404) { throw 'Previous cover was not removed' }
        $previous = $uploaded.coverImageUrl
        $result.checks += "Linux v3 cover $extension conversion + v2/v3 shared GET"
    }
    $large = (DockerCommand ($compose + @('exec','-T','v2','node','-e',"require('sharp')({create:{width:1024,height:768,channels:3,background:'#336699'}}).png().toBuffer().then(b=>console.log(b.toString('base64')))"))).Trim()
    $largePath = Join-Path $directory 'cover-large.png'
    [IO.File]::WriteAllBytes($largePath, [Convert]::FromBase64String($large))
    $uploaded = UploadImage $v3 $largePath 'image/png' $account.accessToken $coverPath
    VerifySharedFile $uploaded.coverImageUrl
    $metadata = (DockerCommand ($compose + @('exec','-T','v2','node','-e',"require('sharp')('/app'+process.argv[1]).metadata().then(m=>console.log(JSON.stringify({width:m.width,height:m.height,format:m.format})))",$uploaded.coverImageUrl))) | ConvertFrom-Json
    if ($metadata.width -ne 800 -or $metadata.height -ne 400 -or $metadata.format -ne 'webp') { throw 'Linux cover dimensions differ' }
    $previous = $uploaded.coverImageUrl
    $result.checks += 'Linux cover 1024x768 -> 800x400 WebP'
    $status = & curl.exe --silent --show-error -o $errorFile -w '%{http_code}' -H "Authorization: Bearer $($account.accessToken)" -F "file=@$heic;type=image/heic" ($v3 + $coverPath)
    $errorBody = Get-Content $errorFile -Raw | ConvertFrom-Json
    if ($status -ne '500' -or $errorBody.message -ne 'Internal server error') { throw 'Cover HEIC error differs' }
    $after = Invoke-RestMethod -Uri "$v3/api/maps/$($room.id)" -Headers @{Authorization="Bearer $($account.accessToken)"}
    if ($after.coverImageUrl -ne $previous) { throw 'HEIC changed the stored cover' }
    $emptyFiles = (DockerCommand ($compose + @('exec','-T','v2','node','-e',"const fs=require('fs'),p='/app/uploads/maps/';console.log(fs.readdirSync(p).filter(n=>n.endsWith('.webp')&&fs.statSync(p+n).size===0).length)"))).Trim()
    if ($emptyFiles -ne '1') { throw 'HEIC cover residual file differs' }
    $result.checks += 'Linux cover HEIC 500 + DB unchanged + empty output file'
    $uploaded = UploadImage $v2 $largePath 'image/png' $account.accessToken $coverPath
    VerifySharedFile $uploaded.coverImageUrl
    if ((Invoke-WebRequest -Uri ($v3 + $previous) -SkipHttpErrorCheck).StatusCode -ne 404) { throw 'v2 did not remove v3 cover' }
    foreach ($folder in @('maps','profiles')) {
        if ((Invoke-WebRequest -Uri "$v3/uploads/$folder/preserved.txt").Content -ne 'preserved') { throw 'Unrelated volume data changed' }
    }
    $result.checks += 'v2 cover replacement + v3 GET + both folders preserved'
    $result.passed = $true
} finally {
    $timer.Stop(); $result.seconds = $timer.Elapsed.TotalSeconds
    $result | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $repository 'apps/server-v3/build/user-images-compose-result.json')
    # 생성한 임시 프로젝트 이름을 확인한 뒤 이 프로젝트의 컨테이너·볼륨만 제거한다.
    if ($project -notmatch '^loco-images-[0-9a-f]{10}$') { throw 'Unsafe cleanup project' }
    & docker @compose down --volumes --remove-orphans | Out-Null
    Remove-Item -LiteralPath $envFile -Force
}
$result | ConvertTo-Json -Depth 5
