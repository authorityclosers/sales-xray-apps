# Packs the Tauri release build of Sales X-Ray into an MSIX for the Microsoft Store.
# Tauri bundles only .exe and .msi, so this lays out the package by hand and runs the
# Windows SDK's MakeAppx. It does not sign: the Store signs Store uploads, and
# desktop.yml signs its sideload test copy with a throwaway certificate.
#
#   ./make-msix.ps1 -Exe sales-xray-desktop.exe -Icons <tauri icon output> -Version 0.1.7 `
#     -IdentityName AuthorityClosers.SalesXRay -Publisher "CN=..." -PublisherDisplayName "Authority Closers" `
#     -Out dist/sales-xray.msix
param(
  [Parameter(Mandatory)] [string] $Exe,
  [Parameter(Mandatory)] [string] $Icons,
  [Parameter(Mandatory)] [string] $Version,
  [Parameter(Mandatory)] [string] $IdentityName,
  [Parameter(Mandatory)] [string] $Publisher,
  [Parameter(Mandatory)] [string] $PublisherDisplayName,
  [string] $DisplayName = "Sales X-Ray",
  [Parameter(Mandatory)] [string] $Out
)
$ErrorActionPreference = "Stop"

# MSIX versions have four parts, and the Store wants the last one to be 0.
$parts = $Version.Split(".")
if ($parts.Count -ne 3 -or ($parts | Where-Object { $_ -notmatch '^\d+$' })) { throw "Version must be X.Y.Z, got '$Version'" }
$msixVersion = "$Version.0"

$layout = Join-Path ([System.IO.Path]::GetTempPath()) "sales-xray-msix-$([guid]::NewGuid())"
New-Item -ItemType Directory -Path "$layout/Assets" | Out-Null
Copy-Item $Exe "$layout/SalesXray.exe"
foreach ($logo in "StoreLogo", "Square44x44Logo", "Square71x71Logo", "Square150x150Logo") {
  Copy-Item (Join-Path $Icons "$logo.png") "$layout/Assets/$logo.png"
}

function Escape-Xml([string] $value) { [System.Security.SecurityElement]::Escape($value) }
$manifest = Get-Content -Raw (Join-Path $PSScriptRoot "AppxManifest.xml")
$manifest = $manifest.Replace("{{IDENTITY_NAME}}", (Escape-Xml $IdentityName)).
  Replace("{{PUBLISHER}}", (Escape-Xml $Publisher)).
  Replace("{{PUBLISHER_DISPLAY_NAME}}", (Escape-Xml $PublisherDisplayName)).
  Replace("{{DISPLAY_NAME}}", (Escape-Xml $DisplayName)).
  Replace("{{VERSION}}", $msixVersion)
if ($manifest -match '\{\{') { throw "AppxManifest.xml still has an unfilled {{value}}" }
[xml] $manifest | Out-Null  # fails here on a malformed manifest, before MakeAppx
Set-Content -Path "$layout/AppxManifest.xml" -Value $manifest -Encoding utf8

$makeappx = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin\*\x64\makeappx.exe" |
  Sort-Object { [version] $_.Directory.Parent.Name } -Descending | Select-Object -First 1
if (-not $makeappx) { throw "MakeAppx isn't on this machine (Windows SDK)" }
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Out) | Out-Null
& $makeappx.FullName pack /o /h SHA256 /d $layout /p $Out
if ($LASTEXITCODE -ne 0) { throw "MakeAppx failed with exit code $LASTEXITCODE" }
Remove-Item -Recurse -Force $layout
Write-Output "Packed $Out ($IdentityName $msixVersion, $Publisher)"
