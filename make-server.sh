#!/usr/bin/env bash
# Builds the dedicated server core: PACMine-Server.jar (headless, no LWJGL).
set -e
D="$(cd "$(dirname "$0")" && pwd)"
OUT="$D/out-server"
rm -rf "$OUT"
mkdir -p "$OUT"
# LuaJ for plugins (bundled into the fat jar)
LUAJ="$D/lib/luaj-jse-3.0.1.jar"
[ -f "$LUAJ" ] || { mkdir -p "$D/lib"; curl -sSL -o "$LUAJ" "https://repo1.maven.org/maven2/org/luaj/luaj-jse/3.0.1/luaj-jse-3.0.1.jar"; }
javac -cp "$LUAJ" -d "$OUT" \
  "$D"/src/com/voxel/ServerMain.java \
  "$D"/src/com/voxel/NetServer.java \
  "$D"/src/com/voxel/NetEvent.java \
  "$D"/src/com/voxel/World.java \
  "$D"/src/com/voxel/Noise.java \
  "$D"/src/com/voxel/Player.java \
  "$D"/src/com/voxel/SaveGame.java \
  "$D"/src/com/voxel/PMCrypt.java \
  "$D"/src/com/voxel/AuthClient.java \
  "$D"/src/com/voxel/PluginManager.java \
  "$D"/src/com/voxel/LiquidSim.java
(cd "$OUT" && jar xf "$LUAJ" org lua)          # merge LuaJ classes into the fat jar
jar cfe "$D/PACMine-Server.jar" com.voxel.ServerMain -C "$OUT" .
echo "Server core -> $D/PACMine-Server.jar"
