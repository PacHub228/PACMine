# Changelog

## Unreleased — Minecraft-style menu overhaul

### UI
- Rewritten menu system with a small immediate-mode widget toolkit
  (`Ui.java`): bevelled grey buttons, inset text fields, list rows, panels and
  labels with hard pixel shadows, all scaled to the window height.
- **Main menu**: big PACMINE logo, animated yellow splash, Одиночная игра /
  Сетевая игра / Настройки / Достижения / Выход.
- **Выбор мира**: world list with size, date and selection, plus Играть /
  Создать / Удалить / Назад.
- **Создание мира** with three tabs (Игра / Мир / Прочее): world name, game
  mode, mobs, world protection, seed, finite/infinite type, size, bonus chest.
  Seeds are accepted as numbers or hashed from text, like Minecraft.
- **Сетевая игра**: saved server list (`~/.pacmine/servers.txt`), connect,
  direct address, add/delete server, host game.
- **Настройки**: render distance (2–12 chunks), FOV, mouse sensitivity,
  clouds and fog toggles — persisted in `~/.pacmine/settings.properties` and
  actually wired into the renderer, camera and mouse look.
- Restyled pause menu; font now batches a whole string into one draw call and
  gained `%`, `°` and `—` glyphs.

### Fixes
- **Closing the window now saves the world** (Alt+F4 / X used to lose the
  session progress).
- The dedicated server installs a shutdown hook, so Ctrl+C / SIGTERM saves the
  world instead of dropping it.
- **No more display-list leak**: `ChunkRenderer.dispose()` is called whenever a
  world is replaced, and on shutdown.
- Infinite worlds respawn at a dry spawn instead of hard-coded (0,0), which
  could be open ocean.
- The launcher's downloads now have connect/read timeouts instead of hanging
  forever on a stalled mirror.

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
