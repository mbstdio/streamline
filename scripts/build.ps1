param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    if (Test-Path '.tools\android-sdk') { $env:ANDROID_HOME = Join-Path $root '.tools\android-sdk' }
    $tasks = @('assembleRelease')
    if (-not $SkipTests) { $tasks += @('testReleaseUnitTest', 'lintRelease') }
    & '.\gradlew.bat' @tasks '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw 'Build or checks failed.' }
    $output = 'app\build\outputs\apk\release'
    $metadata = Get-Content "$output\output-metadata.json" -Raw | ConvertFrom-Json
    if ($metadata.elements.Count -ne 1) { throw 'Expected one release APK.' }
    $version = $metadata.elements[0].versionName
    $manifest = Get-Content 'update.json' -Raw | ConvertFrom-Json
    $release = $manifest.versions.PSObject.Properties[$version]
    if (-not $release) { throw "Version $version is missing from update.json." }
    New-Item -ItemType Directory -Force 'artifacts' | Out-Null
    $name = "streamline-$version.apk"
    Copy-Item (Join-Path $output $metadata.elements[0].outputFile) "artifacts\$name" -Force
    $hash = (Get-FileHash "artifacts\$name" -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $name" | Set-Content "artifacts\$name.sha256" -Encoding ascii
    $release.Value | Add-Member -NotePropertyName sha256 -NotePropertyValue $hash -Force
    $manifest | ConvertTo-Json -Depth 20 | Set-Content 'artifacts\update.json' -Encoding utf8
    Write-Output "APK : $root\artifacts\$name"
    Write-Output "Manifest : $root\artifacts\update.json"
} finally { Pop-Location }
