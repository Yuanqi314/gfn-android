$ErrorActionPreference = "Stop"
$Version = "9.5.0"
$ExpectedSha256 = "553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746"
$Base = Join-Path $env:USERPROFILE ".gradle\gfn-bootstrap"
$Dist = Join-Path $Base "gradle-$Version"
$Zip = Join-Path $Base "gradle-$Version-bin.zip"
$Url = "https://services.gradle.org/distributions/gradle-$Version-bin.zip"
$Gradle = Join-Path $Dist "bin\gradle.bat"

if (-not (Test-Path $Gradle)) {
    New-Item -ItemType Directory -Force -Path $Base | Out-Null
    Write-Host "正在下载 Gradle $Version：$Url"
    Invoke-WebRequest -Uri $Url -OutFile $Zip
    $Actual = (Get-FileHash $Zip -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($ExpectedSha256 -ne $Actual) { throw "Gradle 分发包 SHA-256 校验失败" }
    if (Test-Path $Dist) { Remove-Item -Recurse -Force $Dist }
    Expand-Archive -Path $Zip -DestinationPath $Base -Force
}

& $Gradle @args
exit $LASTEXITCODE
