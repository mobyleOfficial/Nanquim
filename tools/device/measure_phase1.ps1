# Layer toggle and camera tour measurements: opens a test document (e.g. 4096x4096x20), turns the HUD on,
param([string]$Spec, [string]$Prefix, [int]$SettleSeconds, [int]$ToggleSeconds = 4,
      [string]$Device = $env:NANQUIM_DEVICE, [string]$OutputDirectory)


$OutputDirectory = if ($OutputDirectory) { $OutputDirectory } else { Join-Path $PSScriptRoot "..\..\build\device-measurements" }
New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
. "$PSScriptRoot\device_ui.ps1" -Device $Device
Add-Type -AssemblyName System.Drawing

function Save-Hud([string]$name) {
    $full = "$OutputDirectory\$Prefix-$name.png"
    Save-Screenshot $full
    $image = [System.Drawing.Image]::FromFile($full)
    $crop = New-Object System.Drawing.Bitmap 1200, 280
    $graphics = [System.Drawing.Graphics]::FromImage($crop)
    $graphics.DrawImage($image, (New-Object System.Drawing.Rectangle 0, 0, 1200, 280), (New-Object System.Drawing.Rectangle 0, 190, 1200, 280), [System.Drawing.GraphicsUnit]::Pixel)
    $crop.Save("$OutputDirectory\$Prefix-$name-hud.png")
    $graphics.Dispose(); $crop.Dispose(); $image.Dispose()
}

function Invoke-DebugAction([string]$label) {
    $opened = Invoke-TapText "Debug"
    $tapped = Invoke-TapText $label
    if ($opened) { Send-Back }
    Write-Output "action '$label': debug=$opened tapped=$tapped"
}

& $Adb -s $Device shell am force-stop com.mobyle.nanquim
& $Adb -s $Device shell am start -W -n com.mobyle.nanquim/com.mobyle.nanquim.MainActivity --es com.mobyle.nanquim.extra.TEST_DOCUMENT $Spec --ei com.mobyle.nanquim.extra.TEST_FILL -1 | Out-Null
Start-Sleep -Seconds $SettleSeconds
Write-Output ("hud: " + (Invoke-TapText "Debug") + " " + (Invoke-TapLeftOfText "HUD de memória e frames"))
Send-Back
Start-Sleep -Seconds 2
Save-Hud "settled"

foreach ($step in @("Ocultar camada de baixo", "Mostrar camada de baixo", "Ocultar camada do topo", "Mostrar camada do topo")) {
    Invoke-DebugAction $step
    Start-Sleep -Seconds $ToggleSeconds
    Save-Hud ("toggle-" + ($step -replace '\s', '-'))
}

$opened = Invoke-TapText "Debug"
Invoke-TapLeftOfText "HUD de memória e frames" | Out-Null
Invoke-TapText "Tour de câmera (10 s)" | Out-Null
if ($opened) { Send-Back }
Start-Sleep -Seconds 11
$opened = Invoke-TapText "Debug"
Invoke-TapLeftOfText "HUD de memória e frames" | Out-Null
if ($opened) { Send-Back }
Start-Sleep -Seconds 1
Save-Hud "tour"
& $Adb -s $Device shell cat /proc/meminfo | Select-String -Pattern '^(MemAvailable|SwapFree):'
& $Adb -s $Device logcat -d -s NanquimEngine:E AndroidRuntime:E libc:F | Select-Object -Last 3
