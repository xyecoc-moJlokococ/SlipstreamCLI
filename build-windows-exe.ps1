# Build a real, double-clickable Smugly.exe.
#
# Uses jpackage --type app-image: a self-contained folder with the launcher, a trimmed JRE and all
# jars. No installer, no admin rights, nothing registered with Windows — the folder can be copied
# or deleted freely. (An .msi would additionally need the WiX toolset installed.)
#
# The launchers enable AppCDS (-XX:+AutoCreateSharedArchive): the first run writes smugly.jsa next
# to the jars, every later run memory-maps the already-parsed classes instead of loading them from
# scratch. That is what makes the first click on a menu item — which pulls in a whole screen's worth
# of Compose classes — stop being noticeably slower than the rest.
#
# skiko.renderApi is deliberately NOT passed here. DesktopMain picks DIRECT3D on Windows (GPU /
# display refresh). Leave unset so that default applies and stays overridable:
#   set JAVA_TOOL_OPTIONS=-Dskiko.renderApi=SOFTWARE
# Measured trade-off — SOFTWARE ~130 MB WS but ~24 FPS feel; DIRECT3D ~220 MB WS, smooth on 100 Hz.
#
# Memory flags are sized from what the app actually uses, not guessed. Measured with
# `jcmd <pid> GC.heap_info` on a 16 GB machine: the JVM had committed a 254 MB heap (the default
# initial size is 1/16 of RAM, and the max 1/4) while only ~23 MB was live. Capping the heap and
# using SerialGC — G1 alone runs ~10 refinement/concurrent threads for a heap this small — is where
# the footprint drop comes from. 256 MB still leaves large headroom: the proxy's worst case is
# maxActiveClients x 128 KB of relay buffers.
#
# `--generate-cds-archive` in the jlink options is what makes that possible at all: jpackage's
# default runtime has no base CDS archive, and a dynamic archive cannot be built without one
# ("-XX:ArchiveClassesAtExit is unsupported when base CDS archive is not loaded"). Dropping
# --strip-native-commands from the defaults also keeps runtime\bin\java.exe, which is what any
# future -Xlog:cds troubleshooting needs.
#
# Run _wsl_build_desktop_windows.sh first: the jars it stages are the input here.

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$lib  = Join-Path $root 'desktop\build\windows-runtime\lib'
$dest = Join-Path $root 'dist'
$image = Join-Path $dest 'Smugly'
$engines = Join-Path $root 'engines'

if (-not (Test-Path $lib)) {
    throw "Runtime not staged. Run first (in WSL): bash _wsl_build_desktop_windows.sh"
}

# jpackage ships with any modern JDK; prefer one on PATH, else the known Adoptium install.
$jpackage = $null
if (Get-Command jpackage -ErrorAction SilentlyContinue) {
    $jpackage = (Get-Command jpackage).Source
} else {
    $jdk = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue |
           Where-Object { Test-Path (Join-Path $_.FullName 'bin\jpackage.exe') } |
           Select-Object -First 1
    if ($jdk) { $jpackage = Join-Path $jdk.FullName 'bin\jpackage.exe' }
}
if (-not $jpackage) { throw 'jpackage not found (needs a JDK 17+).' }
Write-Host "jpackage: $jpackage"

if (Test-Path $image) { Remove-Item $image -Recurse -Force }
New-Item -ItemType Directory -Force -Path $dest | Out-Null

# A second, console-attached launcher so the recovery/diagnostic flags can actually print.
# The main launcher stays windowless — a VPN client should not flash a console on every start.
#
# It deliberately gets its own java-options with **no** shared archive: AutoCreateSharedArchive
# writes the archive from whatever the first clean exit happened to load, and a short CLI run loads
# almost none of the UI. Letting it win would leave the GUI with a near-useless archive.
$cliProps = Join-Path $env:TEMP 'smugly-cli-launcher.properties'
@(
    'win-console=true',
    'main-class=app.smugly.desktop.MainKt',
    'main-jar=desktop.jar',
    'java-options=-Dfile.encoding=UTF-8'
) | Set-Content -Path $cliProps -Encoding ascii

& $jpackage `
    --type app-image `
    --name Smugly `
    --app-version 1.0.0 `
    --vendor Smugly `
    --description 'Smugly multi-protocol client' `
    --input $lib `
    --main-jar desktop.jar `
    --main-class app.smugly.desktop.MainKt `
    --dest $dest `
    --jlink-options '--strip-debug --no-man-pages --no-header-files --generate-cds-archive' `
    --java-options '-Dskiko.vsync.enabled=true' `
    --java-options '-Dsun.java2d.d3d=true' `
    --java-options '-Dsun.awt.noerasebackground=true' `
    --java-options '-Dsun.awt.erasebackgroundonresize=false' `
    --java-options '-Dfile.encoding=UTF-8' `
    --java-options '-XX:+AutoCreateSharedArchive' `
    --java-options '-XX:SharedArchiveFile=$APPDIR\smugly.jsa' `
    --java-options '-Xms32m' `
    --java-options '-Xmx256m' `
    --java-options '-Xlog:cds=error' `
    --java-options '-XX:+UseSerialGC' `
    --java-options '-XX:MaxMetaspaceSize=192m' `
    --java-options '-XX:ReservedCodeCacheSize=96m' `
    --add-launcher "Smugly-cli=$cliProps"

if ($LASTEXITCODE -ne 0) { throw "jpackage failed ($LASTEXITCODE)" }

# The engines are looked up next to the jars (EngineBinaries.appDir()), so they ride along inside
# the image rather than being a separate thing the user has to place.
if (Test-Path $engines) {
    $target = Join-Path $image 'app\engines'
    New-Item -ItemType Directory -Force -Path $target | Out-Null
    Copy-Item (Join-Path $engines '*.exe') $target -Force
    # Xray routing (geoip:/geosite:) needs these next to xray.exe (working dir = engines/).
    foreach ($dat in @('geoip.dat', 'geosite.dat')) {
        $src = Join-Path $engines $dat
        if (-not (Test-Path $src)) {
            $fallback = Join-Path $root "xray-mobile\assets\$dat"
            if (Test-Path $fallback) { $src = $fallback }
        }
        if (Test-Path $src) {
            Copy-Item $src $target -Force
        } else {
            Write-Warning "missing $dat - Xray geoip/geosite routing will fail to start"
        }
    }
    $names = (Get-ChildItem $target -File | ForEach-Object { $_.Name }) -join ', '
    Write-Host "engines copied: $names"
} else {
    Write-Warning "no engines\ folder - the app will start but cannot connect until engines are built"
}

Write-Host ''
Write-Host "BUILT: $(Join-Path $image 'Smugly.exe')"
Write-Host '  GUI : Smugly.exe'
Write-Host '  CLI : Smugly-cli.exe --engines | --show-system-proxy | --restore-system-proxy | --connect'
