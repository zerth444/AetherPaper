param(
    [string]$Python = 'python',
    [string]$ClientJar = '',
    [string]$ServerJar = '',
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Gradle = '',
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$previousPythonPath = $env:PYTHONPATH
$previousClient = $env:AETHER_CLIENT_JAR
$previousServer = $env:AETHER_SERVER_JAR
try {
    $localDeps = Join-Path $projectRoot 'build\python-deps'
    if (Test-Path $localDeps) { $env:PYTHONPATH = "$localDeps;$previousPythonPath" }
    if ($ClientJar) { $env:AETHER_CLIENT_JAR = (Resolve-Path -LiteralPath $ClientJar).Path }
    if ($ServerJar) { $env:AETHER_SERVER_JAR = (Resolve-Path -LiteralPath $ServerJar).Path }
    & $Python (Join-Path $PSScriptRoot 'build_all.py')
    if ($LASTEXITCODE -ne 0) { throw "Data build failed: $LASTEXITCODE" }
    & (Join-Path $PSScriptRoot 'build_plugin.ps1') -JavaHome $JavaHome -Gradle $Gradle -Offline:$Offline
    & $Python (Join-Path $PSScriptRoot 'write_release_manifest.py')
    if ($LASTEXITCODE -ne 0) { throw 'Release manifest failed' }
} finally {
    $env:PYTHONPATH = $previousPythonPath
    $env:AETHER_CLIENT_JAR = $previousClient
    $env:AETHER_SERVER_JAR = $previousServer
}
