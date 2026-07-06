#!/usr/bin/env bash
# Downloads LWJGL 3.3.6 core + glfw + opengl (jars and linux x64 natives)
set -e
V=3.3.6
BASE=https://repo1.maven.org/maven2/org/lwjgl
LIB="$(dirname "$0")/lib"
mkdir -p "$LIB"

mods=(lwjgl lwjgl-glfw lwjgl-opengl)
for m in "${mods[@]}"; do
  jar="$LIB/$m-$V.jar"
  nat="$LIB/$m-$V-natives-linux.jar"
  [ -f "$jar" ] || curl -sSL -o "$jar" "$BASE/$m/$V/$m-$V.jar"
  [ -f "$nat" ] || curl -sSL -o "$nat" "$BASE/$m/$V/$m-$V-natives-linux.jar"
  echo "ok: $m"
done
# LuaJ: Lua interpreter for server plugins
LUAJ="$LIB/luaj-jse-3.0.1.jar"
[ -f "$LUAJ" ] || curl -sSL -o "$LUAJ" "https://repo1.maven.org/maven2/org/luaj/luaj-jse/3.0.1/luaj-jse-3.0.1.jar"
echo "ok: luaj"
echo "All deps in $LIB"
