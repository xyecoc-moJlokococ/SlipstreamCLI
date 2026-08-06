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
if exist "%JSA%" (
  powershell -NoProfile -Command ^
    "if ((Get-Item -LiteralPath '%LIB%\shared-desktop.jar').LastWriteTime -gt (Get-Item -LiteralPath '%JSA%').LastWriteTime) { Remove-Item -LiteralPath '%JSA%' -Force }"
)

rem Rendering and AWT background properties are deliberately NOT set here. They used to be, and
rem they silently overrode what DesktopMain decides — including the dark erase brush that keeps a
rem live resize from exposing white. To force a renderer for one run:
rem   set JAVA_TOOL_OPTIONS=-Dskiko.renderApi=DIRECT3D
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
