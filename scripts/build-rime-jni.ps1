param(
    [string]$NdkRoot = "$env:LOCALAPPDATA\Android\Sdk\ndk\26.1.10909125",
    [string]$CmakeRoot = "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1",
    [string]$Abi = "arm64-v8a",
    [string]$Api = "34"
)

$ErrorActionPreference = "Stop"

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$sourceRoot = Join-Path $projectRoot "third_party\librime\trime\app\src\main\jni"
$buildRoot = Join-Path $projectRoot "app\build\rime-jni\$Abi"
$toolchain = Join-Path $NdkRoot "build\cmake\android.toolchain.cmake"
$cmake = Join-Path $CmakeRoot "bin\cmake.exe"
$ninja = Join-Path $CmakeRoot "bin\ninja.exe"

if (-not (Test-Path $sourceRoot)) { throw "Missing locked Trime source tree: $sourceRoot" }
if (-not (Test-Path $toolchain)) { throw "Android NDK toolchain not found: $toolchain" }
if (-not (Test-Path $cmake)) { throw "Android SDK CMake not found: $cmake" }
if (-not (Test-Path $ninja)) { throw "Android SDK Ninja not found: $ninja" }

Push-Location $sourceRoot
try {
& $cmake -S . -B $buildRoot -G Ninja `
    "-DCMAKE_TOOLCHAIN_FILE=$toolchain" `
    "-DCMAKE_MAKE_PROGRAM=$ninja" `
    "-DANDROID_ABI=$Abi" `
    "-DANDROID_PLATFORM=android-$Api" `
    -DCMAKE_BUILD_TYPE=Release
if ($LASTEXITCODE -ne 0) { throw "CMake configuration failed" }

& $cmake --build $buildRoot --target rime_jni --parallel
if ($LASTEXITCODE -ne 0) { throw "librime_jni build failed" }
} finally {
    Pop-Location
}

$library = Get-ChildItem $buildRoot -Filter "librime_jni.so" -Recurse | Select-Object -First 1
if ($null -eq $library) { throw "librime_jni.so was not produced" }

$destination = Join-Path $projectRoot "app\src\main\jniLibs\$Abi\librime_jni.so"
New-Item -ItemType Directory -Path (Split-Path $destination) -Force | Out-Null
Copy-Item -LiteralPath $library.FullName -Destination $destination -Force
Write-Host "Copied $($library.FullName) to $destination"

Write-Host "Built $($library.FullName)"
