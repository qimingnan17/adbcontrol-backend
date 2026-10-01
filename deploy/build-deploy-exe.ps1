# Compile oneclick-deploy.ps1 into a standalone AdbControlDeploy.exe (double-click = one-click deploy).
# Depends on the ps2exe module (auto-installed for current user on first run).
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File deploy\build-deploy-exe.ps1
# Output: deploy\AdbControlDeploy.exe - keep it inside adbcontrol-backend\deploy\ (repo root is found from its location).
# NOTE: keep this file ASCII-only (PowerShell 5.1 reads BOM-less files as ANSI).
param(
    [string]$OutFile = ""
)
$ErrorActionPreference = 'Stop'
if (-not $OutFile) { $OutFile = Join-Path $PSScriptRoot "AdbControlDeploy.exe" }

# Self-heal module path: machines with Store PowerShell 7 pollute PSModulePath, which makes
# PS 5.1 load PS7's PowerShellGet (broken). Pin a clean path including the user scope.
$docsModules = Join-Path ([Environment]::GetFolderPath("MyDocuments")) "WindowsPowerShell\Modules"
$env:PSModulePath = "$docsModules;$env:ProgramFiles\WindowsPowerShell\Modules;$env:SystemRoot\System32\WindowsPowerShell\v1.0\Modules"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

if (-not (Get-Module -ListAvailable -Name ps2exe)) {
    Write-Output "[1/2] installing ps2exe module (current user scope)..."
    Install-Module ps2exe -Scope CurrentUser -Force
} else {
    Write-Output "[1/2] ps2exe module ready"
}
Import-Module ps2exe

Write-Output "[2/2] compiling $OutFile ..."
Invoke-ps2exe -inputFile (Join-Path $PSScriptRoot "oneclick-deploy.ps1") `
              -outputFile $OutFile `
              -title "AdbControl One-Click Deploy" `
              -version "1.0.0.0"
if (-not (Test-Path $OutFile)) { throw "compile failed: $OutFile not created" }

Write-Output ""
Write-Output "DONE: $OutFile"
Write-Output "Usage: double-click to deploy (default administrator@100.91.103.13)."
Write-Output "Note: some AV products may flag ps2exe binaries - whitelist if needed; keep exe inside deploy\."
