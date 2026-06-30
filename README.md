# PACMine

A small voxel sandbox game written in Java with [LWJGL](https://www.lwjgl.org/) (OpenGL + GLFW).

> 🇷🇺 Версия на русском: [README.ru.md](README.ru.md)

## Features
- Procedurally generated terrain (value-noise hills, trees, sand near water level)
- First-person movement with gravity, jumping and AABB collision
- Place / break blocks via raycasting (6 block types + unbreakable bedrock)
- Textured blocks loaded from `assets/` into a runtime texture atlas
- Chop 3 trees to craft a **sword**, shown first-person in your hand
- **Zombies** that chase the player; kill them with the sword
- Start menu (PLAY / QUIT) with a tiny built-in bitmap font
- Invisible walls at the world edges

## Controls
| Action | Key |
|---|---|
| Move | `W` `A` `S` `D` |
| Jump | `Space` |
| Look | mouse |
| Break block / hit zombie | Left click |
| Place block | Right click |
| Select block | `1`–`6` |
| Back to menu | `Esc` |

## Build & run
Requires a JDK (21+). LWJGL natives are fetched for your OS.

**Linux / macOS:**
```bash
bash get-deps.sh   # download LWJGL jars into lib/
bash build.sh      # compile to out/
bash run.sh        # launch the game
```

**Windows:**
```bat
get-deps.bat
build.bat
run.bat
```

> ⚠️ **The Windows build is unverified and may have issues.** All versions are
> tested on **Linux Fedora 44 Workstation**.

## Launcher
A small Swing launcher downloads, builds and runs the latest version from GitHub:
- Linux/macOS: `cd launcher && ./launcher.sh`
- Windows: `cd launcher && launcher.bat`

## Project layout
- `src/com/voxel/` — game source
  - `Main.java` — window, game loop, input, menu, HUD, combat
  - `World.java` / `Noise.java` — terrain generation and block storage
  - `ChunkRenderer.java` — per-chunk meshing (display lists)
  - `TextureAtlas.java` — texture loading / atlas
  - `Player.java` — movement & collision
  - `Zombie.java` — mob entity & AI
  - `Font5x7.java` — bitmap font for menu text
- `assets/` — PNG textures (16×16)

## License
[MIT](LICENSE)
