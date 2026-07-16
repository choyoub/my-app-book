param(
    [switch]$SelfContained
)

$ErrorActionPreference = "Stop"
$project = Join-Path $PSScriptRoot "windows\Durumari.Remote\Durumari.Remote.csproj"
$output = Join-Path $PSScriptRoot "windows\publish"

$arguments = @(
    "publish",
    $project,
    "-c", "Release",
    "-o", $output,
    "-p:PublishSingleFile=true"
)

if ($SelfContained) {
    $arguments += @("-r", "win-x64", "--self-contained", "true")
} else {
    $arguments += @("--self-contained", "false")
}

& dotnet @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Windows 리모콘 빌드에 실패했습니다."
}

Write-Host "Windows 리모콘: $output\Durumari.Remote.exe"
