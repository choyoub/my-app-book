$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$localApkDir = Join-Path $projectRoot "apk"
$buildFile = Join-Path $projectRoot "app\build.gradle.kts"
$versionMatch = Select-String -LiteralPath $buildFile -Pattern 'versionName\s*=\s*"([^"]+)"' | Select-Object -First 1
if (-not $versionMatch) {
    throw "versionName was not found in $buildFile"
}
$versionName = $versionMatch.Matches[0].Groups[1].Value
$outputName = "durumari-v$versionName-release.apk"

Set-Location $projectRoot

& (Join-Path $projectRoot "gradlew.bat") assembleRelease

$releaseApk = Join-Path $projectRoot "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path -LiteralPath $releaseApk)) {
    throw "Release APK was not found: $releaseApk"
}

New-Item -ItemType Directory -Force -Path $localApkDir | Out-Null
$localOutput = Join-Path $localApkDir $outputName
Copy-Item -LiteralPath $releaseApk -Destination $localOutput -Force

Write-Host "Release APK: $localOutput"
