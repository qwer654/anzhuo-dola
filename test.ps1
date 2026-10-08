[CmdletBinding()]
param(
    [string]$SdkRoot = '',
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'build.ps1') -Tasks @('testDebugUnitTest', 'lintDebug', 'assembleDebug') -SdkRoot $SdkRoot -JavaHome $JavaHome -Offline:$Offline
if ($LASTEXITCODE -ne 0) { throw "Android verification failed with exit code $LASTEXITCODE." }
