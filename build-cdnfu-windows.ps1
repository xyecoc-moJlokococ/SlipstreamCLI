# Build cdnfu.exe (CDN / XHTTP packet-up client) for the Smugly Windows client.
#
# Native MSVC cargo build. cdnfu's client stack pulls BoringSSL via wreq; that is compiled
# from source, so CMake has to be on PATH. No extra DLL install for the user — BoringSSL is
# linked in.
#
# Usage (from SlipstreamCLI):
#   .\build-cdnfu-windows.ps1

[CmdletBinding()]
param(
    [string]$CdnfuRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\cdn-fuckup")).Path,
    [string]$CMakeBin = (Join-Path $env:USERPROFILE "Documents\tools\cmake\bin")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ((Test-Path $CMakeBin) -and ($env:PATH -notlike "*$CMakeBin*")) {
    $env:PATH = "$CMakeBin;$env:PATH"
}
if ($env:PATH -notlike "*\.cargo\bin*") {
    $env:PATH = "$env:USERPROFILE\.cargo\bin;$env:PATH"
}

$vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
$vs = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
$vcvars = Join-Path $vs "VC\Auxiliary\Build\vcvars64.bat"
if (!(Test-Path $vcvars)) { throw "vcvars64.bat not found at $vcvars" }
$envLines = & cmd.exe /c "`"$vcvars`" >nul && set"
foreach ($line in $envLines) {
    if ($line -match '^(.*?)=(.*)$') {
        Set-Item -Path "Env:$($Matches[1])" -Value $Matches[2]
    }
}
if (!(Get-Command link.exe -ErrorAction SilentlyContinue)) {
    throw "link.exe still missing after vcvars64; is the Windows SDK installed?"
}

# BoringSSL's CMake requires NASM. vcpkg's openssl port downloads one we can reuse.
$nasmCandidates = @(
    (Join-Path $env:USERPROFILE "Documents\vcpkg\downloads\tools\nasm\nasm-3.01"),
    (Join-Path $env:USERPROFILE "Documents\tools\nasm")
)
foreach ($dir in $nasmCandidates) {
    if (Test-Path (Join-Path $dir "nasm.exe")) {
        $env:PATH = "$dir;$env:PATH"
        $env:CMAKE_ASM_NASM_COMPILER = Join-Path $dir "nasm.exe"
        break
    }
}
if (!(Get-Command nasm.exe -ErrorAction SilentlyContinue)) {
    throw "nasm.exe not found (BoringSSL needs it). Install NASM or let vcpkg fetch it via openssl."
}

$llvmBin = "C:\Program Files\LLVM\bin"
if (Test-Path (Join-Path $llvmBin "libclang.dll")) {
    $env:PATH = "$llvmBin;$env:PATH"
    $env:LIBCLANG_PATH = $llvmBin
}
if ([string]::IsNullOrWhiteSpace($env:LIBCLANG_PATH) -or !(Test-Path (Join-Path $env:LIBCLANG_PATH "libclang.dll"))) {
    throw "libclang.dll not found. Install LLVM (winget install LLVM.LLVM) so bindgen can wrap BoringSSL."
}

if (!(Get-Command cmake -ErrorAction SilentlyContinue)) {
    throw "cmake not on PATH (needed to build BoringSSL for wreq). Expected $CMakeBin"
}
if (!(Get-Command cargo -ErrorAction SilentlyContinue)) {
    throw "cargo not on PATH."
}
if (!(Test-Path (Join-Path $CdnfuRoot "Cargo.toml"))) {
    throw "cdn-fuckup not found at $CdnfuRoot"
}

Push-Location $CdnfuRoot
try {
    rustup target add x86_64-pc-windows-msvc | Out-Null
    Write-Host "Building cdnfu (x86_64-pc-windows-msvc, client feature)..."
    cargo build --release --target x86_64-pc-windows-msvc --bin cdnfu --features client --no-default-features
    if ($LASTEXITCODE -ne 0) { throw "cargo build cdnfu failed ($LASTEXITCODE)" }
} finally {
    Pop-Location
}

$built = Join-Path $CdnfuRoot "target\x86_64-pc-windows-msvc\release\cdnfu.exe"
if (!(Test-Path $built)) { throw "expected $built" }

$engines = Join-Path $PSScriptRoot "engines"
New-Item -ItemType Directory -Force -Path $engines | Out-Null
Copy-Item $built (Join-Path $engines "cdnfu.exe") -Force

Write-Host ""
Write-Host "CDNFU_READY=$(Join-Path $engines 'cdnfu.exe')"
Get-Item (Join-Path $engines "cdnfu.exe") | Select-Object FullName, Length, LastWriteTime
