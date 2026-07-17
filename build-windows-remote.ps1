param(
    [ValidatePattern("^\d+\.\d+\.\d+$")]
    [string]$Version = "0.1.4"
)

$ErrorActionPreference = "Stop"
$project = Join-Path $PSScriptRoot "windows\Durumari.Remote.Wpf\Durumari.Remote.Wpf.csproj"
$installerScript = Join-Path $PSScriptRoot "windows\Durumari.Remote.Wpf\Installer\Durumari.Remote.nsi"
$appIcon = Join-Path $PSScriptRoot "windows\Durumari.Remote.Wpf\Assets\durumari.ico"
$appIconPng = Join-Path $PSScriptRoot "windows\Durumari.Remote.Wpf\Assets\durumari-icon.png"
$publishRoot = Join-Path $PSScriptRoot "windows\publish"
$appOutput = Join-Path $publishRoot "app"
$installerAssets = Join-Path $publishRoot "installer-assets"
$welcomeBitmap = Join-Path $installerAssets "welcome.bmp"
$headerBitmap = Join-Path $installerAssets "header.bmp"
$setupOutput = Join-Path $publishRoot "Durumari-Remote-Setup-$Version-x64.exe"

$resolvedRepository = [System.IO.Path]::GetFullPath($PSScriptRoot)
$resolvedPublishRoot = [System.IO.Path]::GetFullPath($publishRoot)
if (-not $resolvedPublishRoot.StartsWith(
    $resolvedRepository + [System.IO.Path]::DirectorySeparatorChar,
    [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "게시 폴더가 저장소 밖을 가리키고 있습니다: $resolvedPublishRoot"
}

if (Test-Path -LiteralPath $publishRoot) {
    Remove-Item -LiteralPath $publishRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $appOutput | Out-Null
New-Item -ItemType Directory -Force -Path $installerAssets | Out-Null

$arguments = @(
    "publish",
    $project,
    "-c", "Release",
    "-r", "win-x64",
    "--self-contained", "true",
    "-o", $appOutput,
    "-p:Version=$Version",
    "-p:PublishSingleFile=true",
    "-p:IncludeNativeLibrariesForSelfExtract=true",
    "-p:EnableCompressionInSingleFile=true",
    "-p:DebugType=None",
    "-p:DebugSymbols=false"
)

& dotnet @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Windows 리모콘 게시에 실패했습니다."
}

$appExecutable = Join-Path $appOutput "Durumari.Remote.exe"
if (-not (Test-Path -LiteralPath $appExecutable)) {
    throw "게시된 실행 파일을 찾을 수 없습니다: $appExecutable"
}

$makeNsis = Get-Command "makensis.exe" -ErrorAction SilentlyContinue
if ($null -eq $makeNsis) {
    $knownNsisPaths = @(
        (Join-Path ${env:ProgramFiles(x86)} "NSIS\makensis.exe"),
        (Join-Path $env:ProgramFiles "NSIS\makensis.exe")
    )
    $makeNsisPath = $knownNsisPaths |
        Where-Object { $_ -and (Test-Path -LiteralPath $_) } |
        Select-Object -First 1
} else {
    $makeNsisPath = $makeNsis.Source
}

if (-not $makeNsisPath) {
    throw "NSIS 3.x를 찾을 수 없습니다. NSIS를 설치한 뒤 다시 실행하세요."
}

Add-Type -AssemblyName System.Drawing
$sourceImage = [System.Drawing.Image]::FromFile($appIconPng)
try {
    $welcome = [System.Drawing.Bitmap]::new(164, 314)
    try {
        $graphics = [System.Drawing.Graphics]::FromImage($welcome)
        try {
            $graphics.Clear([System.Drawing.Color]::White)
            $graphics.InterpolationMode =
                [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $graphics.SmoothingMode =
                [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
            $graphics.DrawImage($sourceImage, 18, 82, 128, 128)
        } finally {
            $graphics.Dispose()
        }
        $welcome.Save($welcomeBitmap, [System.Drawing.Imaging.ImageFormat]::Bmp)
    } finally {
        $welcome.Dispose()
    }

    $header = [System.Drawing.Bitmap]::new(150, 57)
    try {
        $graphics = [System.Drawing.Graphics]::FromImage($header)
        try {
            $graphics.Clear([System.Drawing.Color]::White)
            $graphics.InterpolationMode =
                [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $graphics.SmoothingMode =
                [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
            $graphics.DrawImage($sourceImage, 98, 4, 48, 48)
        } finally {
            $graphics.Dispose()
        }
        $header.Save($headerBitmap, [System.Drawing.Imaging.ImageFormat]::Bmp)
    } finally {
        $header.Dispose()
    }
} finally {
    $sourceImage.Dispose()
}

& $makeNsisPath `
    "/INPUTCHARSET" "UTF8" `
    "/DAPP_VERSION=$Version" `
    "/DPUBLISH_DIR=$appOutput" `
    "/DAPP_ICON=$appIcon" `
    "/DWELCOME_BITMAP=$welcomeBitmap" `
    "/DHEADER_BITMAP=$headerBitmap" `
    "/DOUTPUT_FILE=$setupOutput" `
    $installerScript
if ($LASTEXITCODE -ne 0) {
    throw "NSIS 인스톨러 빌드에 실패했습니다."
}

if (-not (Test-Path -LiteralPath $setupOutput)) {
    throw "완성된 인스톨러를 찾을 수 없습니다: $setupOutput"
}

Write-Host "휴대용 실행 파일: $appExecutable"
Write-Host "NSIS 인스톨러: $setupOutput"
