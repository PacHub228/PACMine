#!/usr/bin/env bash
set -e
D="$(cd "$(dirname "$0")" && pwd)"
CP=$(ls "$D"/lib/*.jar | grep -v natives | paste -sd:)
mkdir -p "$D/out"
echo "Compiling..."
javac -cp "$CP" -d "$D/out" $(find "$D/src" -name '*.java')
echo "Build OK -> $D/out"
