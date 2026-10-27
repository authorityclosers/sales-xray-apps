# Code-signs one Windows file for Sales X-Ray with SSL.com eSigner (cloud key, no USB token).
# Tauri calls it for the app and each installer through bundle.windows.signCommand, which
# desktop.yml sets only on publish runs with the secrets present:
#   SX_WINDOWS_SIGN_USERNAME, SX_WINDOWS_SIGN_PASSWORD, SX_WINDOWS_SIGN_TOTP_SECRET (secrets),
#   SX_WINDOWS_SIGN_CREDENTIAL_ID (variable; needed only when the account holds several certificates).
# Azure Artifact Signing would be cheaper, but it doesn't onboard companies or people in India
# (Microsoft's quickstart, Oct 2026). Every signature counts against the eSigner plan's monthly quota.
param([Parameter(Mandatory)] [string] $File)
$ErrorActionPreference = "Stop"

foreach ($name in "SX_WINDOWS_SIGN_USERNAME", "SX_WINDOWS_SIGN_PASSWORD", "SX_WINDOWS_SIGN_TOTP_SECRET") {
  if (-not [Environment]::GetEnvironmentVariable($name)) { throw "$name is not set; nothing was signed" }
}

# SSL.com's CodeSignTool for Windows (it brings its own Java), fetched once per runner.
$tools = Join-Path $env:RUNNER_TEMP "codesigntool"
if (-not (Test-Path $tools)) {
  $zip = Join-Path $env:RUNNER_TEMP "codesigntool.zip"
  Invoke-WebRequest -Uri "https://ssl.com/download/codesigntool-for-windows/" -OutFile $zip
  Expand-Archive -Path $zip -DestinationPath $tools
  Remove-Item $zip
}
$tool = Get-ChildItem -Path $tools -Recurse -Filter CodeSignTool.bat | Select-Object -First 1
if (-not $tool) { throw "CodeSignTool.bat isn't in SSL.com's download" }

$signed = Join-Path $env:RUNNER_TEMP "signed-$([guid]::NewGuid())"
New-Item -ItemType Directory -Path $signed | Out-Null
$arguments = @(
  "sign",
  "-username=$env:SX_WINDOWS_SIGN_USERNAME",
  "-password=$env:SX_WINDOWS_SIGN_PASSWORD",
  "-totp_secret=$env:SX_WINDOWS_SIGN_TOTP_SECRET",
  "-input_file_path=$File",
  "-output_dir_path=$signed"
)
if ($env:SX_WINDOWS_SIGN_CREDENTIAL_ID) { $arguments += "-credential_id=$env:SX_WINDOWS_SIGN_CREDENTIAL_ID" }
Push-Location $tool.DirectoryName
try { & $tool.FullName @arguments } finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw "CodeSignTool failed with exit code $LASTEXITCODE for $File" }
Move-Item -Force (Join-Path $signed (Split-Path -Leaf $File)) $File
Remove-Item -Recurse -Force $signed

$signature = Get-AuthenticodeSignature $File
if ($signature.Status -ne "Valid") { throw "$File is not validly signed after signing: $($signature.Status)" }
Write-Output "Signed $File ($($signature.SignerCertificate.Subject))"
