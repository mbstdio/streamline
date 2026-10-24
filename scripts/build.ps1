param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    if (Test-Path '.tools\android-sdk') { $env:ANDROID_HOME = Join-Path $root '.tools\android-sdk' }
    $tasks = @('assembleDebug')
    if (-not $SkipTests) { $tasks += @('testDebugUnitTest', 'lintDebug') }
    & '.\gradlew.bat' @tasks '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw 'Build or checks failed.' }
    New-Item -ItemType Directory -Force 'artifacts' | Out-Null
    Copy-Item 'app\build\outputs\apk\debug\app-debug.apk' 'artifacts\streamline-tv-0.2.0.apk' -Force
    $hash = (Get-FileHash 'artifacts\streamline-tv-0.2.0.apk' -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  streamline-tv-0.2.0.apk" | Set-Content 'artifacts\streamline-tv-0.2.0.apk.sha256' -Encoding ascii
    Write-Output "APK : $root\artifacts\streamline-tv-0.2.0.apk"
} finally { Pop-Location }
