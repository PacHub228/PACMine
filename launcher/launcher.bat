@echo off
rem Always recompile: javac is fast, and a stale class file after editing
rem LauncherMain.java is a much worse surprise than one extra second at start.
cd /d "%~dp0"
if not exist out mkdir out
echo Compiling launcher...
javac -d out LauncherMain.java
if errorlevel 1 (
  echo LAUNCHER BUILD FAILED
  pause
  exit /b 1
)
java -cp out LauncherMain
