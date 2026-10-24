param([string]$Serial = 'emulator-5556')
$ErrorActionPreference = 'Stop'
$adb = Join-Path (Split-Path $PSScriptRoot -Parent) '.tools\android-sdk\platform-tools\adb.exe'
Add-Type -AssemblyName UIAutomationClient

function Get-ResumedActivity {
    $activities = (& $adb -s $Serial shell dumpsys activity activities) -join "`n"
    if ($activities -match 'mResumedActivity:[^\r\n]*?u0\s+(\S+)') { return $Matches[1] }
    throw 'Cannot determine the resumed Android activity.'
}

# Exercise the actual Qt toolbar, NOT adb input (which bypasses the faulty input path).
# This opens Android Settings and returns to the TV launcher; it does not erase data.
foreach ($control in @('back', 'home')) {
    & $adb -s $Serial shell am start -W -a android.settings.SETTINGS | Out-Null
    if ($LASTEXITCODE -ne 0 -or (Get-ResumedActivity) -notlike 'com.android.tv.settings/*') {
        throw 'Android TV Settings did not open.'
    }
    $condition = New-Object System.Windows.Automation.PropertyCondition `
        ([System.Windows.Automation.AutomationElement]::AutomationIdProperty), "ToolControls.${control}_button"
    $buttons = [System.Windows.Automation.AutomationElement]::RootElement.FindAll(
        [System.Windows.Automation.TreeScope]::Descendants, $condition)
    if ($buttons.Count -ne 1) { throw 'Expected exactly one standalone emulator toolbar. Close other emulator windows.' }
    $buttons[0].GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
    $deadline = [DateTime]::UtcNow.AddSeconds(5)
    do {
        Start-Sleep -Milliseconds 150
        $activity = Get-ResumedActivity
    } while ($activity -like 'com.android.tv.settings/*' -and [DateTime]::UtcNow -lt $deadline)
    if ($activity -like 'com.android.tv.settings/*' -or
            ($control -eq 'home' -and $activity -notlike 'com.google.android.tvlauncher/*')) {
        throw "FAIL: toolbar $control had no expected effect (current: $activity)."
    }
    Write-Output "PASS: actual emulator toolbar $control left Android Settings (current: $activity)."
}
