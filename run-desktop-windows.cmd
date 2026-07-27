@echo off
setlocal
rem Launch the Windows desktop app from the staged runtime (_wsl_build_desktop_windows.sh).
rem Nothing is installed: it uses whatever JDK is already on the machine.

set "APPDIR=%~dp0"
set "LIB=%APPDIR%desktop\build\windows-runtime\lib"
set "VAYDNS_ENGINE_DIR=%APPDIR%engines"

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

"%JAVA_EXE%" ^
  -Dskiko.renderApi=DIRECT3D ^
  -Dskiko.vsync.enabled=true ^
  -Dsun.java2d.d3d=true ^
  -Dsun.awt.noerasebackground=true ^
  -Dsun.awt.erasebackgroundonresize=false ^
  -Dfile.encoding=UTF-8 ^
  -cp "%LIB%\*" app.vaydns.ui.DesktopMainKt %*
endlocal
