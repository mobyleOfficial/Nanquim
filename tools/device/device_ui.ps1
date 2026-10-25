# Helpers to drive the Nanquim debug UI on a device through adb + uiautomator (Windows
param([string]$Device = $env:NANQUIM_DEVICE)

$script:Adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
$script:Device = $Device
if (-not $script:Device) { throw "Pass -Device <adb serial> or set NANQUIM_DEVICE" }

function Get-UiXml {
    & $script:Adb -s $script:Device shell uiautomator dump /sdcard/ui.xml | Out-Null
    return (& $script:Adb -s $script:Device shell cat /sdcard/ui.xml) -join ''
}

function Get-Center([System.Text.RegularExpressions.Match]$match) {
    $x = ([int]$match.Groups[1].Value + [int]$match.Groups[3].Value) / 2
    $y = ([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2
    return @($x, $y)
}

function Invoke-TapText([string]$label) {
    $xml = Get-UiXml
    $match = [regex]::Match($xml, 'text="' + [regex]::Escape($label) + '"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $match.Success) { Write-Output "NOT FOUND: $label"; return $false }
    $center = Get-Center $match
    & $script:Adb -s $script:Device shell input tap $center[0] $center[1]
    Start-Sleep -Milliseconds 700
    return $true
}

function Invoke-TapSwitch([int]$index) {
    $xml = Get-UiXml
    $matches = [regex]::Matches($xml, 'class="android.widget.Switch"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($matches.Count -le $index) { Write-Output "SWITCH $index NOT FOUND ($($matches.Count) switches)"; return $false }
    $center = Get-Center $matches[$index]
    & $script:Adb -s $script:Device shell input tap $center[0] $center[1]
    Start-Sleep -Milliseconds 500
    return $true
}

function Invoke-TapLeftOfText([string]$label, [int]$offset = 80) {
    $xml = Get-UiXml
    $match = [regex]::Match($xml, 'text="' + [regex]::Escape($label) + '"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $match.Success) { Write-Output "NOT FOUND: $label"; return $false }
    $x = [int]$match.Groups[1].Value - $offset
    $y = ([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2
    & $script:Adb -s $script:Device shell input tap $x $y
    Start-Sleep -Milliseconds 500
    return $true
}

function Save-Screenshot([string]$path) {
    & $script:Adb -s $script:Device shell screencap -p /sdcard/nanquim-shot.png
    & $script:Adb -s $script:Device pull /sdcard/nanquim-shot.png $path | Out-Null
}

function Send-Back {
    & $script:Adb -s $script:Device shell input keyevent KEYCODE_BACK
    Start-Sleep -Milliseconds 500
}
