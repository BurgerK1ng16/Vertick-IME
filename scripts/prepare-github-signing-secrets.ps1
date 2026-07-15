param(
    [string]$Keystore = (Join-Path $PSScriptRoot "..\weike-release.jks"),
    [string]$Alias = "weike-release"
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $Keystore)) { throw "Keystore not found: $Keystore" }

$encoded = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path $Keystore)))
Set-Clipboard -Value $encoded

Write-Host "ANDROID_KEYSTORE_BASE64 has been copied to the clipboard."
Write-Host "In GitHub, add these Actions secrets:"
Write-Host "  ANDROID_KEYSTORE_BASE64  (paste clipboard content)"
Write-Host "  ANDROID_STORE_PASSWORD  (the keystore password)"
Write-Host "  ANDROID_KEY_ALIAS       ($Alias)"
Write-Host "  ANDROID_KEY_PASSWORD    (the key password)"
