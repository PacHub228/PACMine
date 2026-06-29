@echo off
cd /d "%~dp0"
if not exist out mkdir out
if not exist "out\LauncherMain.class" goto compile
rem recompile if source is newer is hard in batch; just always compile if class missing
goto run
:compile
echo Compiling launcher...
javac -d out LauncherMain.java
:run
java -cp out LauncherMain
