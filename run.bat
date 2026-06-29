@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"
set CP=out
for %%f in (lib\*.jar) do set CP=!CP!;%%f
java --enable-native-access=ALL-UNNAMED -cp "!CP!" com.voxel.Main
