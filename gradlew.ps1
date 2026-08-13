$ErrorActionPreference = "Stop"
$Version = "9.5.0"
$Base = Join-Path $env:USERPROFILE ".gradle\gfn-bootstrap"
$Dist = Join-Path $Base "gradle-$Version"
$Zip = Join-Path $Base "gradle-$Version-bin.zip"
$Sha = "$Zip.sha256"
$Url = "https://services.gradle.org/distributions/gradle-$Version-bin.zip"
$Gradle = Join-Path $Dist "bin\gradle.bat"

if (-not (Test-Path $Gradle)) {
    New-Item -ItemType Directory -Force -Path $Base | Out-Null
    Invoke-WebRequest -Uri $Url -OutFile $Zip
    Invoke-WebRequest -Uri "$Url.sha256" -OutFile $Sha
    $Expected = (Get-Content $Sha -Raw).Trim()
    $Actual = (Get-FileHash $Zip -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($Expected.ToLowerInvariant() -ne $Actual) { throw "Gradle distribution checksum mismatch" }
    if (Test-Path $Dist) { Remove-Item -Recurse -Force $Dist }
    Expand-Archive -Path $Zip -DestinationPath $Base -Force
}

& $Gradle @args
exit $LASTEXITCODE
