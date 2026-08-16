# Build a standalone slipstream.exe for the Smugly Windows client.
#
# Why this exists: stock Slipstream on Windows used to need a separate OpenSSL (and often
# picoquic) install, then failed at runtime when those DLLs were missing. This script builds
# picoquic against vcpkg's static OpenSSL (x64-windows-static-md) and links slipstream-client
# the same way CI does, so the exe only depends on Windows + VC/UCRT.
#
# Prereqs (once):
#   - VS 2022 Build Tools with the C++ x64 toolchain
#   - CMake (portable is fine; this script looks in Documents\tools\cmake)
#   - vcpkg with openssl:x64-windows-static-md  (cloned to Documents\vcpkg by default)
#
# Usage (from SlipstreamCLI):
#   .\build-slipstream-windows.ps1
#   .\build-slipstream-windows.ps1 -SkipPicoquic   # reuse a previous picoquic stage

[CmdletBinding()]
param(
    [string]$SlipstreamRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\slipstream-rust")).Path,
    [string]$VcpkgRoot = (Join-Path $env:USERPROFILE "Documents\vcpkg"),
    [string]$CMakeBin = (Join-Path $env:USERPROFILE "Documents\tools\cmake\bin"),
    [switch]$SkipPicoquic
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Add-PathDir([string]$Dir) {
    if ((Test-Path $Dir) -and ($env:PATH -notlike "*$Dir*")) {
        $env:PATH = "$Dir;$env:PATH"
    }
}

function Import-VsDevEnv {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
    $vs = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    if ([string]::IsNullOrWhiteSpace($vs)) { throw "Visual Studio Build Tools with the C++ toolchain not found." }
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
}

Add-PathDir $CMakeBin
Add-PathDir (Join-Path $env:USERPROFILE ".cargo\bin")
Import-VsDevEnv

if (!(Get-Command cmake -ErrorAction SilentlyContinue)) {
    throw "cmake not on PATH. Install portable CMake to $CMakeBin or add it to PATH."
}
if (!(Get-Command cargo -ErrorAction SilentlyContinue)) {
    throw "cargo not on PATH."
}
if (!(Test-Path (Join-Path $SlipstreamRoot "Cargo.toml"))) {
    throw "slipstream-rust not found at $SlipstreamRoot"
}
if (!(Test-Path (Join-Path $SlipstreamRoot "vendor\picoquic\CMakeLists.txt"))) {
    throw "picoquic submodule missing. In slipstream-rust run: git submodule update --init --recursive vendor/picoquic"
}

$vcpkg = Join-Path $VcpkgRoot "vcpkg.exe"
if (!(Test-Path $vcpkg)) {
    throw "vcpkg.exe not found at $vcpkg. Clone vcpkg and run bootstrap-vcpkg.bat."
}

$triplet = "x64-windows-static-md"
$opensslRoot = Join-Path $VcpkgRoot "installed\$triplet"
if (!(Test-Path (Join-Path $opensslRoot "include\openssl\ssl.h"))) {
    Write-Host "Installing openssl:$triplet via vcpkg (first time takes a while)..."
    & $vcpkg install "openssl:$triplet" --disable-metrics
    if ($LASTEXITCODE -ne 0) { throw "vcpkg install openssl:$triplet failed ($LASTEXITCODE)" }
}

$env:VCPKG_ROOT = $VcpkgRoot
$env:OPENSSL_ROOT_DIR = $opensslRoot
$env:OPENSSL_INCLUDE_DIR = Join-Path $opensslRoot "include"
$env:OPENSSL_LIB_DIR = Join-Path $opensslRoot "lib"
$env:OPENSSL_STATIC = "1"
$env:OPENSSL_USE_STATIC_LIBS = "TRUE"

$pkgconf = @(
    (Join-Path $VcpkgRoot "installed\x64-windows\tools\pkgconf\pkgconf.exe"),
    (Join-Path $VcpkgRoot "installed\x64-windows-static-md\tools\pkgconf\pkgconf.exe"),
    (Join-Path $VcpkgRoot "installed\x64-windows\tools\pkgconf\pkg-config.exe")
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($pkgconf) {
    $env:PKG_CONFIG = $pkgconf
    $env:PKG_CONFIG_EXECUTABLE = $pkgconf
    $env:PKG_CONFIG_PATH = Join-Path $opensslRoot "lib\pkgconfig"
}

Push-Location $SlipstreamRoot
try {
    if (-not $SkipPicoquic) {
        Write-Host "Building picoquic (MSVC + static OpenSSL)..."
        . .\scripts\build_picoquic_windows.ps1
    } elseif ([string]::IsNullOrWhiteSpace($env:PICOQUIC_LIB_DIR)) {
        throw "-SkipPicoquic needs PICOQUIC_INCLUDE_DIR / PICOQUIC_LIB_DIR / PICOTLS_INCLUDE_DIR already set"
    }

    rustup target add x86_64-pc-windows-msvc | Out-Null
    Write-Host "Building slipstream-client (x86_64-pc-windows-msvc, openssl-static)..."
    cargo build -p slipstream-client --release --target x86_64-pc-windows-msvc --features openssl-static
    if ($LASTEXITCODE -ne 0) { throw "cargo build slipstream-client failed ($LASTEXITCODE)" }
} finally {
    Pop-Location
}

$built = Join-Path $SlipstreamRoot "target\x86_64-pc-windows-msvc\release\slipstream-client.exe"
if (!(Test-Path $built)) {
    throw "expected $built"
}

$engines = Join-Path $PSScriptRoot "engines"
New-Item -ItemType Directory -Force -Path $engines | Out-Null
Copy-Item $built (Join-Path $engines "slipstream.exe") -Force
# Keep the cargo name too so a raw drop-in still matches EngineBinaries.find("slipstream-client").
Copy-Item $built (Join-Path $engines "slipstream-client.exe") -Force

$verify = Join-Path $SlipstreamRoot "scripts\verify_windows_artifact_deps.ps1"
if (Test-Path $verify) {
    $checkDir = Join-Path $env:TEMP "smugly-slipstream-depcheck"
    if (Test-Path $checkDir) { Remove-Item $checkDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $checkDir | Out-Null
    Copy-Item (Join-Path $engines "slipstream.exe") $checkDir
    & $verify -DistDir $checkDir -TargetPlatform x64
}

Write-Host ""
Write-Host "SLIPSTREAM_READY=$(Join-Path $engines 'slipstream.exe')"
Get-Item (Join-Path $engines "slipstream.exe") | Select-Object FullName, Length, LastWriteTime
