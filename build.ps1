[CmdletBinding()]
param(
    [string[]]$Tasks = @('assembleDebug'),
    [string]$SdkRoot = '',
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = $PSScriptRoot
$ToolRoot = Join-Path (Split-Path $ProjectRoot -Parent) '.tooling'
$Gradle = Join-Path $ToolRoot 'gradle-8.9\bin\gradle.bat'
if (-not $JavaHome) { $JavaHome = 'C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot' }
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    throw 'JDK 17 or later is required. Set JAVA_HOME or pass -JavaHome.'
}
if (-not $SdkRoot) {
    $SdkRoot = $env:ANDROID_SDK_ROOT
    if (-not $SdkRoot) { $SdkRoot = $env:ANDROID_HOME }
    if (-not $SdkRoot) { $SdkRoot = Join-Path $ToolRoot 'android-sdk' }
}
$SdkRoot = [IO.Path]::GetFullPath($SdkRoot)
if (-not (Test-Path -LiteralPath $Gradle)) {
    throw 'Gradle is not prepared. Run .\tools\bootstrap.ps1 first.'
}
# Merely having SDK files must never silently create an acceptance record.
if (-not (Test-Path -LiteralPath (Join-Path $SdkRoot 'licenses\android-sdk-license'))) {
    throw 'Android SDK license acceptance is missing. Read and accept it yourself with .\tools\bootstrap.ps1 -ReviewSdkLicenses -InstallSdk.'
}
foreach ($file in @('platforms\android-35\android.jar', 'build-tools\35.0.0\aapt2.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $SdkRoot $file))) {
        throw "SDK component is missing: $file. Run .\tools\bootstrap.ps1 -InstallSdk after accepting the SDK licenses."
    }
}

$original = @{}
foreach ($key in @('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'GRADLE_USER_HOME')) {
    $original[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
try {
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $SdkRoot
    $env:ANDROID_SDK_ROOT = $SdkRoot
    $env:GRADLE_USER_HOME = Join-Path $ToolRoot 'gradle-user-home'
    $argsList = @('-p', $ProjectRoot, '--no-daemon', '--console=plain', '--stacktrace') + $Tasks
    if ($Offline) { $argsList += '--offline' }
    & $Gradle @argsList
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE." }
    if ($Tasks -contains 'assembleDebug' -or $Tasks -contains ':app:assembleDebug') {
        $apk = Join-Path $ProjectRoot 'app\build\outputs\apk\debug\app-debug.apk'
        if (-not (Test-Path -LiteralPath $apk)) { throw 'Gradle reported success but the debug APK is missing.' }
        Write-Host "APK: $apk"
        Get-FileHash -LiteralPath $apk -Algorithm SHA256 | Format-List
    }
} finally {
    foreach ($key in $original.Keys) { [Environment]::SetEnvironmentVariable($key, $original[$key], 'Process') }
}
