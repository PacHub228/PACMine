#!/usr/bin/env bash
# Compiles (if needed) and runs the PACMine launcher.
set -e
D="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$D/out"
if [ ! -f "$D/out/LauncherMain.class" ] || [ "$D/LauncherMain.java" -nt "$D/out/LauncherMain.class" ]; then
  echo "Compiling launcher..."
  javac -d "$D/out" "$D/LauncherMain.java"
fi
java -cp "$D/out" LauncherMain
