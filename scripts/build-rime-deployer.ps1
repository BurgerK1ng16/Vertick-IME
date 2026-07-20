param(
    [string]$NdkRoot = "$env:LOCALAPPDATA\Android\Sdk\ndk\26.1.10909125",
    [string]$CmakeRoot = "$env:LOCALAPPDATA\Android\Sdk\cmake\3.22.1",
    [string]$Abi = "arm64-v8a",
    [string]$Api = "34"
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$source = Join-Path $root "third_party\librime\trime\app\src\main\jni"
$build = Join-Path $root "build\rime-deployer\$Abi"
$cmake = Join-Path $CmakeRoot "bin\cmake.exe"
$ninja = Join-Path $CmakeRoot "bin\ninja.exe"
$toolchain = Join-Path $NdkRoot "build\cmake\android.toolchain.cmake"

if (-not (Test-Path $toolchain)) { throw "Android NDK toolchain not found: $toolchain" }
& $cmake -S $source -B $build -G Ninja `
  "-DCMAKE_TOOLCHAIN_FILE=$toolchain" "-DCMAKE_MAKE_PROGRAM=$ninja" `
  "-DANDROID_ABI=$Abi" "-DANDROID_PLATFORM=android-$Api" `
  -DBUILD_RIME_DEPLOYER=ON -DCMAKE_BUILD_TYPE=Release
if ($LASTEXITCODE -ne 0) { throw "CMake configuration failed" }
& $cmake --build $build --target rime_deployer --parallel
if ($LASTEXITCODE -ne 0) { throw "rime_deployer build failed" }

$binary = Get-ChildItem $build -Filter "rime_deployer" -Recurse | Select-Object -First 1
if ($null -eq $binary) { throw "rime_deployer was not produced" }
Write-Output $binary.FullName
