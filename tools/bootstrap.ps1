[CmdletBinding()]
param(
    [string]$ToolRoot = '',
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$ReviewSdkLicenses,
    [switch]$InstallSdk
)

$ErrorActionPreference = 'Stop'
if (-not $ToolRoot) { $ToolRoot = Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) '.tooling' }
$ToolRoot = [IO.Path]::GetFullPath($ToolRoot)
$Downloads = Join-Path $ToolRoot 'downloads'
$SdkRoot = Join-Path $ToolRoot 'android-sdk'
New-Item -ItemType Directory -Path $ToolRoot, $Downloads, $SdkRoot -Force | Out-Null

function Assert-ToolPath([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path)
    if (-not $full.StartsWith($ToolRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing an extraction or move outside the tooling directory: $full"
    }
    return $full
}

function Get-VerifiedDownload([string]$Name, [string]$Url, [string]$Algorithm, [string]$Expected) {
    $archive = Assert-ToolPath (Join-Path $Downloads $Name)
    if (Test-Path -LiteralPath $archive) {
        $actual = (Get-FileHash -LiteralPath $archive -Algorithm $Algorithm).Hash
        if ($actual -ine $Expected) { throw "Existing archive has the wrong $Algorithm checksum: $archive" }
    } else {
        $partial = "$archive.partial"
        Write-Host "Downloading $Url"
        & curl.exe --fail --location --retry 2 --connect-timeout 20 --max-time 1200 --output $partial $Url
        if ($LASTEXITCODE -ne 0) { throw "Download failed ($LASTEXITCODE): $Url. Partial file was retained at $partial." }
        $actual = (Get-FileHash -LiteralPath $partial -Algorithm $Algorithm).Hash
        if ($actual -ine $Expected) { throw "$Algorithm mismatch for $partial; archive was not extracted." }
        Move-Item -LiteralPath (Assert-ToolPath $partial) -Destination $archive
    }
    $receipt = [ordered]@{
        url = $Url
        file = $Name
        verifiedAlgorithm = $Algorithm
        expectedChecksum = $Expected.ToLowerInvariant()
        sha256 = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
        bytes = (Get-Item -LiteralPath $archive).Length
    }
    $receiptPath = "$archive.receipt.json"
    if (-not (Test-Path -LiteralPath $receiptPath)) {
        $receipt | ConvertTo-Json | Set-Content -LiteralPath "$receiptPath.tmp" -Encoding UTF8
        Move-Item -LiteralPath "$receiptPath.tmp" -Destination $receiptPath
    }
    return $archive
}

$gradleZip = Get-VerifiedDownload 'gradle-8.9-bin.zip' 'https://downloads.gradle.org/distributions/gradle-8.9-bin.zip' 'SHA256' 'd725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab'
# The SHA-1 below is the checksum published in Google's SDK repository metadata.
# A SHA-256 receipt is also recorded for every downloaded archive.
$commandZip = Get-VerifiedDownload 'commandlinetools-win-13114758_latest.zip' 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' 'SHA1' '54a582f3bf73e04253602f2d1c80bd5868aac115'

Add-Type -AssemblyName System.IO.Compression.FileSystem
if (-not (Test-Path -LiteralPath (Join-Path $ToolRoot 'gradle-8.9\bin\gradle.bat'))) {
    $staging = Assert-ToolPath (Join-Path $ToolRoot ('extract-gradle-' + [guid]::NewGuid().ToString('N')))
    [IO.Compression.ZipFile]::ExtractToDirectory($gradleZip, $staging)
    Move-Item -LiteralPath (Assert-ToolPath (Join-Path $staging 'gradle-8.9')) -Destination (Assert-ToolPath (Join-Path $ToolRoot 'gradle-8.9'))
    Remove-Item -LiteralPath $staging # Empty directory only; no recursive deletion.
}
$sdkManager = Join-Path $SdkRoot 'cmdline-tools\19.0\bin\sdkmanager.bat'
if (-not (Test-Path -LiteralPath $sdkManager)) {
    $staging = Assert-ToolPath (Join-Path $ToolRoot ('extract-sdk-tools-' + [guid]::NewGuid().ToString('N')))
    [IO.Compression.ZipFile]::ExtractToDirectory($commandZip, $staging)
    New-Item -ItemType Directory -Path (Join-Path $SdkRoot 'cmdline-tools') -Force | Out-Null
    Move-Item -LiteralPath (Assert-ToolPath (Join-Path $staging 'cmdline-tools')) -Destination (Assert-ToolPath (Join-Path $SdkRoot 'cmdline-tools\19.0'))
    Remove-Item -LiteralPath $staging
}

if (-not $JavaHome) { $JavaHome = 'C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot' }
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) { throw 'JDK 17 or newer is required. Pass -JavaHome or set JAVA_HOME.' }
$oldJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JavaHome
    if ($ReviewSdkLicenses) {
        Write-Host 'Read the displayed Android SDK licenses and answer personally. This script does not supply acceptance responses.'
        & $sdkManager "--sdk_root=$SdkRoot" --licenses
        if ($LASTEXITCODE -ne 0) { throw "License review exited with code $LASTEXITCODE." }
    }
    if ($InstallSdk) {
        $license = Join-Path $SdkRoot 'licenses\android-sdk-license'
        if (-not (Test-Path -LiteralPath $license) -or (Get-Item -LiteralPath $license).Length -eq 0) {
            throw 'No Android SDK license acceptance was found. Run this script with -ReviewSdkLicenses -InstallSdk and personally review the terms.'
        }
        & $sdkManager "--sdk_root=$SdkRoot" --install 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
        if ($LASTEXITCODE -ne 0) { throw "SDK installation exited with code $LASTEXITCODE." }
    }
    & (Join-Path $ToolRoot 'gradle-8.9\bin\gradle.bat') --version
    if ($LASTEXITCODE -ne 0) { throw 'Gradle could not start.' }
} finally {
    $env:JAVA_HOME = $oldJava
}
Write-Host "Tooling prepared: $ToolRoot"
Write-Host "Android SDK: $SdkRoot"
if (-not $InstallSdk) { Write-Host 'Next: .\tools\bootstrap.ps1 -ReviewSdkLicenses -InstallSdk (license acceptance is interactive).' }
