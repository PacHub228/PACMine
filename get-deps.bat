@echo off
rem Downloads LWJGL 3.3.6 core + glfw + opengl (jars and Windows x64 natives)
setlocal enabledelayedexpansion
cd /d "%~dp0"
if not exist lib mkdir lib
set V=3.3.6
set BASE=https://repo1.maven.org/maven2/org/lwjgl
for %%m in (lwjgl lwjgl-glfw lwjgl-opengl) do (
  if not exist "lib\%%m-%V%.jar" powershell -Command "Invoke-WebRequest -Uri '%BASE%/%%m/%V%/%%m-%V%.jar' -OutFile 'lib\%%m-%V%.jar'"
  if not exist "lib\%%m-%V%-natives-windows.jar" powershell -Command "Invoke-WebRequest -Uri '%BASE%/%%m/%V%/%%m-%V%-natives-windows.jar' -OutFile 'lib\%%m-%V%-natives-windows.jar'"
  echo ok: %%m
)
rem LuaJ: Lua interpreter for server plugins
if not exist "lib\luaj-jse-3.0.1.jar" powershell -Command "Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/org/luaj/luaj-jse/3.0.1/luaj-jse-3.0.1.jar' -OutFile 'lib\luaj-jse-3.0.1.jar'"
echo ok: luaj
echo All deps in lib\
