@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"
set CP=
for %%f in (lib\*.jar) do set CP=!CP!;%%f
if not exist out mkdir out
echo Compiling...
dir /s /b src\*.java > sources.txt
javac -cp "!CP!" -d out @sources.txt
del sources.txt
echo Build OK -^> out\
