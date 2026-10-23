# Changelog

## Unreleased — graphics, performance and fixes

### Graphics
- **Ambient occlusion** baked into chunk meshes: corners, cliffs and cave mouths
  now shade naturally instead of every face having one flat brightness.
- **Distance fog** tinted with the sky colour, so far terrain fades into the
  horizon instead of ending abruptly. The night overlay still darkens the world.
- **Cloud layer**: deterministic blocky clouds high above the world, dimmed at
  night, drawn without writing depth.
- **Debug overlay** now reports how many chunks were drawn and culled.

### Performance
- **Frustum culling**: chunks behind the camera or off to the side are skipped
  before any OpenGL call, and dirty chunks outside the view are not rebuilt
  until they come back. On the 4x4 test world this culls 6 of 16 chunks
  immediately; the gain grows with world size and while looking at terrain.
- **Joining an infinite world no longer deep-clones the entire chunk map**.
  `World.chunkData(...)` returns the live array, so a join costs O(edited
  chunks) instead of O(all loaded chunks) — on a pregenerated 250x250 server
  that was roughly 750 MB of copying per player.
- Loaded chunks are streamed to joiners one by one instead of building a full
  `exportChunks()` snapshot first.

### Fixes
- Trees growing near a chunk border in infinite worlds keep their canopy: the
  neighbour chunk now replants trees rooted up to 2 blocks outside it, so
  crowns are no longer cut in half at chunk seams.
- `TextureAtlas` no longer probes the same PNG twice for non-wood tiles.
- `launcher.bat` always recompiles the launcher instead of silently running a
  stale class file.
- `.gitignore` covers the dedicated server's `players.dat` and `plugins/`.

### Tooling
- **`SelfTest`** — 35 headless checks (world generation, chunk math, save
  format, encryption, crafting tables, frustum maths). Run it with
  `java -cp out com.voxel.SelfTest`; it exits non-zero on failure, so it can be
  wired into CI.
- **`--smoke`** mode renders a fixed number of frames, prints the GL
  renderer/timing and optionally writes a screenshot:
  `java -cp "out;lib/*" com.voxel.Main --smoke 60 shot.png [yaw] [pitch]`.
- `Main --server.*` now delegates to `ServerMain` instead of running a second,
  divergent server implementation (no more world-less 8x8 legacy server).
