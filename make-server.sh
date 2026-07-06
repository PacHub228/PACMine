#!/usr/bin/env bash
# Builds the dedicated server core: PACMine-Server.jar (headless, no LWJGL).
set -e
D="$(cd "$(dirname "$0")" && pwd)"
OUT="$D/out-server"
rm -rf "$OUT"
mkdir -p "$OUT"
javac -d "$OUT" \
  "$D"/src/com/voxel/ServerMain.java \
  "$D"/src/com/voxel/NetServer.java \
  "$D"/src/com/voxel/NetEvent.java \
  "$D"/src/com/voxel/World.java \
  "$D"/src/com/voxel/Noise.java \
  "$D"/src/com/voxel/Player.java \
  "$D"/src/com/voxel/SaveGame.java \
  "$D"/src/com/voxel/PMCrypt.java \
  "$D"/src/com/voxel/AuthClient.java
jar cfe "$D/PACMine-Server.jar" com.voxel.ServerMain -C "$OUT" .
echo "Server core -> $D/PACMine-Server.jar"
