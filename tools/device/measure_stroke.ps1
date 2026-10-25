# Stroke frame timing (5 s stroke with 30 layers, no frame over 16.7 ms): opens a
param([string]$Spec = "4096x4096x30", [string]$Prefix = "stroke", [int]$SettleSeconds = 40,
      [string]$Device = $env:NANQUIM_DEVICE, [string]$OutputDirectory)

$OutputDirectory = if ($OutputDirectory) { $OutputDirectory } else { Join-Path $PSScriptRoot "..\..\build\device-measurements" }
New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
. "$PSScriptRoot\device_ui.ps1" -Device $Device
Add-Type -AssemblyName System.Drawing
function Save-Hud([string]$name) {
    $full = "$OutputDirectory\$Prefix-$name.png"
    Save-Screenshot $full | Out-Null
    $image = [System.Drawing.Image]::FromFile($full)
    $crop = New-Object System.Drawing.Bitmap 1200, 330
    $graphics = [System.Drawing.Graphics]::FromImage($crop)
    $graphics.DrawImage($image, (New-Object System.Drawing.Rectangle 0, 0, 1200, 330), (New-Object System.Drawing.Rectangle 0, 330, 1200, 330), [System.Drawing.GraphicsUnit]::Pixel)
    $crop.Save("$OutputDirectory\$Prefix-$name-hud.png")
    $graphics.Dispose(); $crop.Dispose(); $image.Dispose()
}
& $Adb -s $Device shell am force-stop com.mobyle.nanquim
& $Adb -s $Device shell am start -W -n com.mobyle.nanquim/com.mobyle.nanquim.MainActivity --es com.mobyle.nanquim.extra.TEST_DOCUMENT $Spec --ei com.mobyle.nanquim.extra.TEST_FILL -1 | Out-Null
Start-Sleep -Seconds $SettleSeconds
$opened = Invoke-TapText "Debug"
Write-Output ("reset: " + (Invoke-TapText "Zerar estatísticas de frames"))
if ($opened) { Send-Back }
Start-Sleep -Seconds 1
& $Adb -s $Device shell input stylus swipe 300 600 1300 1900 5000
Start-Sleep -Seconds 2
$opened = Invoke-TapText "Debug"
Invoke-TapLeftOfText "HUD de memória e frames" | Out-Null
if ($opened) { Send-Back }
Start-Sleep -Seconds 1
Save-Hud "after"
& $Adb -s $Device logcat -d -s NanquimEngine:E AndroidRuntime:E | Select-Object -Last 3
