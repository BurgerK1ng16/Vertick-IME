param(
    [ValidateSet("rime-ice-base", "rime-ice-extended", "rime-ice-english", "rime-ice-emoji", "wanxiang-grammar")]
    [string]$PackId = "rime-ice-base",
    [string]$Version = "2026.07.20",
    [string]$OutputDirectory = "dictionaries/dist"
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$source = Join-Path $root "app/src/main/assets/rime/prebuilt"
$work = Join-Path $root "build/dictionary-pack/$PackId-$Version"
$output = Join-Path $root $OutputDirectory
Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path (Join-Path $work "build") -Force | Out-Null
New-Item -ItemType Directory -Path $output -Force | Out-Null

# A pack is a self-contained, host-built Rime runtime bundle. Android only
# extracts this archive and opens shared/build; it never invokes maintenance.
foreach ($name in @("default.yaml", "weike_pinyin.schema.yaml", "weike_t9.schema.yaml")) {
    Copy-Item (Join-Path $source $name) (Join-Path $work $name)
    Copy-Item (Join-Path $source $name) (Join-Path $work "build/$name")
}
foreach ($name in @("weike_pinyin.prism.bin", "weike_t9.prism.bin", "weike_pinyin.reverse.bin")) {
    Copy-Item (Join-Path $source $name) (Join-Path $work "build/$name")
}
$tableZip = Join-Path $source "weike_pinyin.table.bin.zip"
Expand-Archive -LiteralPath $tableZip -DestinationPath (Join-Path $work "build") -Force
if (-not (Test-Path (Join-Path $work "build/weike_pinyin.table.bin"))) {
    throw "Prebuilt table archive did not contain weike_pinyin.table.bin"
}

$archive = Join-Path $output "$PackId-$Version.zip"
Remove-Item -LiteralPath $archive -Force -ErrorAction SilentlyContinue
Compress-Archive -Path (Join-Path $work "*") -DestinationPath $archive -CompressionLevel Optimal
$hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
$size = (Get-Item -LiteralPath $archive).Length
$manifest = [ordered]@{
    schemaVersion = 1
    generatedAt = [DateTime]::UtcNow.ToString("o")
    packs = @([ordered]@{
        id = $PackId
        displayName = "Rime-Ice base offline dictionary"
        version = $Version
        sizeBytes = $size
        sha256 = $hash
        urls = @()
        requires = @()
        license = "GPL-3.0-or-later"
        enabledByDefault = $true
    })
}
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $output "manifest.json") -Encoding utf8
Write-Host "Created $archive ($size bytes, sha256=$hash)"
