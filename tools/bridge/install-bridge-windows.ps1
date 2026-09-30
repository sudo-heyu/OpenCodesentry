#Requires -Version 5.1
<#
.SYNOPSIS
    Windows: install (or remove) a Scheduled Task that keeps the OpenCode
    bridge running — started at logon, restarted if it ever exits.

.DESCRIPTION
    The task runs the bridge with pythonw.exe so no console window appears.
    The first time it listens, Windows may ask whether to allow Python through
    the firewall — allow it on the Tailscale network.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\tools\bridge\install-bridge-windows.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\tools\bridge\install-bridge-windows.ps1 -Uninstall
#>
[CmdletBinding()]
param(
    [switch]$Uninstall,
    [int]$Port = 4096
)

$ErrorActionPreference = "Stop"
$TaskName = "OpenCodeBridge"
$Repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$Bridge = Join-Path $Repo "tools\bridge\opencode-bridge.py"

if ($Uninstall) {
    if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
        Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    }
    Write-Host "removed scheduled task $TaskName"
    exit 0
}

if (-not (Test-Path $Bridge)) {
    throw "bridge script not found: $Bridge"
}

# Prefer pythonw.exe so nothing flashes on screen; fall back to python.exe.
$pythonw = (Get-Command pythonw.exe -ErrorAction SilentlyContinue).Source
if (-not $pythonw) {
    $python = (Get-Command python.exe -ErrorAction SilentlyContinue).Source
    if (-not $python) {
        throw "Python not found on PATH. Install Python 3 and re-run this script."
    }
    $candidate = Join-Path (Split-Path $python) "pythonw.exe"
    $pythonw = if (Test-Path $candidate) { $candidate } else { $python }
}

$action = New-ScheduledTaskAction -Execute $pythonw -Argument "`"$Bridge`" --port $Port"
$trigger = New-ScheduledTaskTrigger -AtLogOn
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -RestartCount 999 `
    -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -StartWhenAvailable

Register-ScheduledTask `
    -TaskName $TaskName `
    -Action $action `
    -Trigger $trigger `
    -Settings $settings `
    -RunLevel Limited `
    -Force | Out-Null

Start-ScheduledTask -TaskName $TaskName

Write-Host "installed scheduled task: $TaskName"
Write-Host "python: $pythonw"
Write-Host "bridge: $Bridge"
Write-Host ""
Write-Host "--- task state ---"
Get-ScheduledTask -TaskName $TaskName | Select-Object TaskName, State | Format-Table -AutoSize
Write-Host "--- listening on port $Port ---"
Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
    Select-Object LocalAddress, LocalPort | Format-Table -AutoSize
Write-Host ""
Write-Host "To watch the bridge's output, stop the task and run it by hand:"
Write-Host "  Stop-ScheduledTask -TaskName $TaskName"
Write-Host "  & `"$pythonw`" `"$Bridge`" --port $Port"
