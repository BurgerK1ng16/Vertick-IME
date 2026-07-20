param(
    [ValidateSet("base", "enhanced")]
    [string]$Pack = "base",
    [string]$OutputDirectory = "app/src/main/assets/prediction"
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$dictRoot = Join-Path $root "app/src/main/assets/rime/cn_dicts"
$tables = if ($Pack -eq "base") { @("8105.dict.yaml", "base.dict.yaml", "others.dict.yaml") } else { @("ext.dict.yaml", "tencent.dict.yaml") }
$edges = [System.Collections.Generic.Dictionary[string, int]]::new()
$accepted = 0
$sourceLimit = if ($Pack -eq "base") { 120000 } else { 100000 }

foreach ($table in $tables) {
    foreach ($line in [System.IO.File]::ReadLines((Join-Path $dictRoot $table), [System.Text.Encoding]::UTF8)) {
        if ($accepted -ge $sourceLimit) { break }
        $parts = $line -split "`t"
        $phrase = $parts[0].Trim()
        if ($phrase -notmatch '^[\u4E00-\u9FFF]{2,8}$') { continue }
        $accepted++
        $weight = 1
        if ($parts.Count -ge 3) { [void][int]::TryParse($parts[2].Trim(), [ref]$weight) }
        $weight = [Math]::Max(1, [Math]::Min($weight, 1000000))
        $maxPrefix = [Math]::Min(4, $phrase.Length - 1)
        for ($length = 1; $length -le $maxPrefix; $length++) {
            $context = $phrase.Substring(0, $length)
            $targetLength = [Math]::Min(4, $phrase.Length - $length)
            $target = $phrase.Substring($length, $targetLength)
            $key = "$context|$target"
            if (-not $edges.ContainsKey($key) -or $edges[$key] -lt $weight) { $edges[$key] = $weight }
        }
    }
}

$buckets = [System.Collections.Generic.Dictionary[string, System.Collections.Generic.List[object]]]::new()
foreach ($key in $edges.Keys) {
    $parts = $key -split '\|', 2
    if (-not $buckets.ContainsKey($parts[0])) { $buckets[$parts[0]] = [System.Collections.Generic.List[object]]::new() }
    $buckets[$parts[0]].Add([PSCustomObject]@{ Target = $parts[1]; Weight = [int]$edges[$key] })
}

function Write-Utf8([System.IO.BinaryWriter]$Writer, [string]$Value) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
    if ($bytes.Length -gt [UInt16]::MaxValue) { throw "Prediction token is too long" }
    $Writer.Write([UInt16]$bytes.Length)
    $Writer.Write($bytes)
}

$outputRoot = Join-Path $root $OutputDirectory
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$output = Join-Path $outputRoot "$Pack.bin"
$stream = [System.IO.File]::Open($output, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write)
$writer = [System.IO.BinaryWriter]::new($stream, [System.Text.Encoding]::UTF8, $false)
try {
    # Little-endian VTP1 header, followed by context -> up to eight weighted targets.
    $writer.Write([Int32]0x31505456)
    $contexts = @($buckets.Keys | Sort-Object)
    $writer.Write([Int32]$contexts.Count)
    foreach ($context in $contexts) {
        Write-Utf8 $writer $context
        $targets = @($buckets[$context] | Sort-Object -Property @{ Expression = "Weight"; Descending = $true }, @{ Expression = "Target"; Descending = $false } | Select-Object -First 8)
        $writer.Write([Byte]$targets.Count)
        foreach ($target in $targets) {
            Write-Utf8 $writer $target.Target
            $writer.Write([Int32]$target.Weight)
        }
    }
} finally {
    $writer.Dispose()
}
Write-Host "Generated $output from $($tables -join ', ')"
