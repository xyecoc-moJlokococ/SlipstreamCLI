@echo off
setlocal
rem Launch the Windows desktop app from the staged runtime (_wsl_build_desktop_windows.sh).
rem Nothing is installed: it uses whatever JDK is already on the machine.

set "APPDIR=%~dp0"
set "LIB=%APPDIR%desktop\build\windows-runtime\lib"
set "SMUGLY_ENGINE_DIR=%APPDIR%engines"

if not exist "%LIB%" (
  echo Runtime not staged. Run this first, from WSL:
  echo   bash /mnt/c/Users/newbie/Documents/vphysics-compile/SlipstreamCLI/_wsl_build_desktop_windows.sh
  exit /b 1
)

rem Prefer a JDK on PATH; otherwise fall back to a known Adoptium install.
set "JAVA_EXE=java.exe"
where java.exe >nul 2>&1 || (
  for /d %%J in ("%ProgramFiles%\Eclipse Adoptium\jdk-*") do set "JAVA_EXE=%%J\bin\java.exe"
)
if not defined JAVA_EXE (
  echo No JDK found. Install one or put java.exe on PATH.
  exit /b 1
)

rem AppCDS, the same thing the packaged .exe gets. Without it every run loads every class from the
rem jars, and the first time a screen is opened — Settings, the editor — it stalls for a moment
rem while its classes are read and verified. The archive is written on the first **clean** exit and
rem memory-mapped by every run after that.
rem
rem After a rebuild the jar timestamps no longer match the archive; the JVM then prints a pile of
rem [cds] warnings and runs WITHOUT the archive (the freeze comes back). Drop a stale archive so
rem AutoCreateSharedArchive writes a fresh one on the next clean exit. -Xlog:cds=error keeps the
rem console clean if something still mismatches.
set "JSA=%APPDIR%desktop\build\windows-runtime\smugly-dev.jsa"
rem Drop AppCDS when any staged jar is newer than the archive. A stale/partial .jsa after rebuild
rem has been seen to produce NoClassDefFoundError on Compose synthetic classes
rem (WindowChromeKt$…$1$1$1) the moment the title bar receives mouse events.
if exist "%JSA%" (
  powershell -NoProfile -Command ^
    "$jsa = Get-Item -LiteralPath '%JSA%'; $newest = Get-ChildItem -LiteralPath '%LIB%' -Filter '*.jar' | Sort-Object LastWriteTime -Descending | Select-Object -First 1; if ($newest -and $newest.LastWriteTime -gt $jsa.LastWriteTime) { Remove-Item -LiteralPath '%JSA%' -Force; Write-Host 'AppCDS: dropped stale smugly-dev.jsa (jars newer)' }"
)

rem After a rebuild there is no .jsa yet. Headless-bake Home+Settings+Diagnostics into a fresh
rem archive so the first GUI launch is not a cold ClassLoader tour (tab freezes). SOFTWARE + no
rem window; takes ~1–3s once per rebuild. ArchiveClassesAtExit needs a clean exit(0).
if not exist "%JSA%" (
  echo [warmup] headless UI pass - writing AppCDS %JSA%
  "%JAVA_EXE%" ^
    -XX:+IgnoreUnrecognizedVMOptions ^
    -Xms32m -Xmx256m -XX:+UseSerialGC ^
    -XX:ArchiveClassesAtExit="%JSA%" ^
    -Dfile.encoding=UTF-8 ^
    -Dskiko.renderApi=SOFTWARE ^
    -Dskiko.vsync.enabled=false ^
    -cp "%LIB%\*" app.smugly.desktop.WarmupCdsKt
  if not exist "%JSA%" (
    echo [warmup] WARNING: AppCDS was not written - first tab switches may hitch
  ) else (
    echo [warmup] AppCDS ready
  )
)

rem Rendering and AWT background properties are deliberately NOT set here. They used to be, and
rem they silently overrode what DesktopMain decides — including the dark erase brush that keeps a
rem live resize from exposing white. To force a renderer for one run:
rem   set JAVA_TOOL_OPTIONS=-Dskiko.renderApi=SOFTWARE
rem Heap/GC match packaged Smugly.exe: without a cap a 16 GB machine hands the JVM a ~4 GB max
rem heap and G1's concurrent threads, which is why a quiet VPN session sat at ~350-400 MB RSS.
rem Xms 32m: a little headroom for the first Settings composition so SerialGC does not pause mid-tab.
"%JAVA_EXE%" ^
  -XX:+IgnoreUnrecognizedVMOptions ^
  -XX:+AutoCreateSharedArchive ^
  "-XX:SharedArchiveFile=%JSA%" ^
  -Xlog:cds=error ^
  -Dfile.encoding=UTF-8 ^
  -Xms32m ^
  -Xmx256m ^
  -XX:+UseSerialGC ^
  -XX:MaxMetaspaceSize=192m ^
  -XX:ReservedCodeCacheSize=96m ^
  -cp "%LIB%\*" app.smugly.ui.DesktopMainKt %*
endlocal
