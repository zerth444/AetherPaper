param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Gradle = '',
    [switch]$Offline,
    [switch]$CopyToServer
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$previousJavaHome = $env:JAVA_HOME
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
    $unixTemp = Join-Path $projectRoot 'build\tmp'
    New-Item -ItemType Directory -Force -Path $unixTemp | Out-Null
    $env:JAVA_TOOL_OPTIONS = "$previousJavaOptions -Djdk.net.unixdomain.tmpdir=`"$unixTemp`""
    if (-not $Gradle) { $Gradle = Join-Path $projectRoot 'plugin\gradlew.bat' }
    $gradleArgs = @('-p', (Join-Path $projectRoot 'plugin'), 'build', '--no-daemon', '--console=plain')
    if ($Offline) { $gradleArgs += '--offline' }
    if ($CopyToServer) { $gradleArgs += 'copyToServer' }
    & $Gradle @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
    New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot 'dist') | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectRoot 'plugin\build\libs\Aether-1.0.0.jar') -Destination (Join-Path $projectRoot 'dist\Aether-1.0.0.jar')
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:JAVA_TOOL_OPTIONS = $previousJavaOptions
}
