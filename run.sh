#!/usr/bin/env bash
set -e
D="$(cd "$(dirname "$0")" && pwd)"
CP="$D/out:$(ls "$D"/lib/*.jar | paste -sd:)"
java --enable-native-access=ALL-UNNAMED -cp "$CP" com.voxel.Main "$@"
