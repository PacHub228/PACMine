package com.voxel;

import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * PACMine dedicated server core, Minecraft style: drop PACMine-Server.jar
 * into an empty folder and run it — everything it needs is created there:
 *
 *   server.properties   settings (port, world size, ...)
 *   saves/world.pms     the world, autosaved and saved on stop
 *   server.log          everything the console prints
 *
 * Run:  java -jar PACMine-Server.jar
 * Console commands: help, list, save, stop.
 *
 * Headless: no LWJGL required.
 */
public class ServerMain {
    static final Properties props = new Properties();
    static World world;
    static NetServer server;
    static SaveGame meta;
    static String worldName;
    static final Map<Integer, String> players = new ConcurrentHashMap<>();
    static PluginManager plugins;
    static volatile boolean running = true;
    static PrintWriter logFile;

    public static void main(String[] args) throws Exception {
        try { logFile = new PrintWriter(new FileWriter("server.log", true), true); } catch (IOException ignored) {}
        log("=== PACMine dedicated server ===");
        loadProps();
        int port = intProp("port", 25565);
        int chunks = Math.max(2, Math.min(24, intProp("world-size", 8)));
        boolean protection = Boolean.parseBoolean(props.getProperty("protection", "true"));
        int autosave = Math.max(15, intProp("autosave-seconds", 120));
        worldName = props.getProperty("world-name", "world");

        boolean infinite = Boolean.parseBoolean(props.getProperty("infinite", "false"));
        int pregen = Math.max(2, Math.min(250, intProp("pregen-chunks", 250)));

        // world: load if present, otherwise generate and persist
        if (new File(SaveGame.DIR, worldName + ".pms").exists()
                || new File(SaveGame.DIR, worldName + ".pmw").exists()) {
            meta = SaveGame.load(worldName);
            if (meta.infinite) {
                world = World.createInfinite(meta.seed, meta.protection);
                for (Map.Entry<Long, byte[]> e : meta.chunkMap.entrySet())
                    world.importChunk(World.chunkCX(e.getKey()), World.chunkCZ(e.getKey()), e.getValue());
                log("Loaded INFINITE world '" + worldName + "' (" + meta.chunkMap.size() + " chunks, seed " + meta.seed + ")");
            } else {
                world = new World(meta.chunks, meta.blocks);
                log("Loaded world '" + worldName + "' (" + meta.chunks + "x" + meta.chunks + " chunks)");
            }
        } else if (infinite) {
            long seed = System.nanoTime();
            world = World.createInfinite(seed, protection);
            pregenerate(pregen);
            meta = new SaveGame();
            meta.infinite = true; meta.seed = seed; meta.protection = protection; meta.hostileMobs = false;
            int sy = World.SY - 1;
            while (sy > 0 && !world.isSolid(0, sy, 0)) sy--;
            meta.px = 0.5; meta.py = sy + 1; meta.pz = 0.5;
            meta.health = Player.MAX_HEARTS;
            saveWorld();
            log("INFINITE server ready: " + pregen + "x" + pregen + " chunks pregenerated, "
                + "the rest of the endless world is generated as players explore it (seed " + seed + ")");
        } else {
            world = new World(System.nanoTime(), chunks, protection);
            meta = new SaveGame();
            meta.chunks = chunks; meta.protection = protection; meta.hostileMobs = false;
            int sx = world.sx / 2, sz = world.sz / 2, sy = World.SY - 1;
            while (sy > 0 && !world.isSolid(sx, sy, sz)) sy--;
            meta.px = sx + 0.5; meta.py = sy + 1; meta.pz = sz + 0.5;
            meta.health = Player.MAX_HEARTS;
            saveWorld();
            log("Generated world '" + worldName + "' (" + chunks + "x" + chunks + " chunks)");
        }

        Queue<NetEvent> queue = new ConcurrentLinkedQueue<>();
        server = new NetServer(world, protection, queue);
        server.setDedicated();
        loadPlayers();
        server.start(port);
        log("Listening on port " + port + ". Type 'help' for commands.");

        // Lua plugins
        if (Boolean.parseBoolean(props.getProperty("plugins", "false"))) {
            plugins = new PluginManager(world, server, ServerMain::log);
            plugins.loadAll();
            Thread ticker = new Thread(() -> {
                long start = System.currentTimeMillis();
                while (running) {
                    sleep(1000);
                    plugins.fire("tick", (System.currentTimeMillis() - start) / 1000);
                }
            }, "plugin-tick");
            ticker.setDaemon(true); ticker.start();
        }

        // drain events so we can log joins/leaves and answer `list`
        Thread drain = new Thread(() -> {
            while (running) {
                NetEvent e;
                while ((e = queue.poll()) != null) {
                    if (e.type == NetEvent.NAME && !players.containsValue(e.name)) {
                        players.put(e.id, e.name);
                        String badge = e.premium ? "license: PREMIUM" : e.licensed ? "license: yes" : "no license (guest)";
                        log("+ " + e.name + " joined [" + badge + "]");
                        if (plugins != null) plugins.fire("join", e.name, e.premium, e.licensed);
                    } else if (e.type == NetEvent.LEAVE) {
                        String n = players.remove(e.id);
                        if (n != null) {
                            log("- " + n + " left");
                            if (plugins != null) plugins.fire("leave", n);
                        }
                    } else if (e.type == NetEvent.BLOCK && plugins != null) {
                        plugins.fire("block", e.x, e.y, e.z, e.b);
                    } else if (e.type == NetEvent.CHAT) {
                        log("<" + e.name + "> " + e.text);
                        if (plugins != null) plugins.fire("chat", e.name, e.text);
                    }
                }
                sleep(200);
            }
        }, "server-drain");
        drain.setDaemon(true); drain.start();

        Thread saver = new Thread(() -> {
            while (running) {
                sleep(autosave * 1000L);
                if (!running) break;
                saveWorld();
                log("Autosaved '" + worldName + "'");
            }
        }, "server-autosave");
        saver.setDaemon(true); saver.start();

        // console
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while (running && (line = in.readLine()) != null) {
            if (line.trim().toLowerCase().startsWith("say ")) {
                String msg = line.trim().substring(4).trim();
                if (!msg.isEmpty()) { server.hostChat("[Server]", msg); log("<[Server]> " + msg); }
                continue;
            }
            switch (line.trim().toLowerCase()) {
                case "": break;
                case "help": log("Commands: list (online players), say <msg> (chat), plugins (loaded plugins), save (save world), stop (save and quit)"); break;
                case "plugins": log(plugins == null ? "Plugins disabled (plugins=false)"
                    : "Plugins (" + plugins.loadedPlugins().size() + "): " + String.join(", ", plugins.loadedPlugins())); break;
                case "list": log("Online (" + players.size() + "): " + String.join(", ", players.values())); break;
                case "save": saveWorld(); log("Saved '" + worldName + "'"); break;
                case "stop": running = false; break;
                default: log("Unknown command. Type 'help'.");
            }
        }
        log("Stopping...");
        server.stop();
        saveWorld();
        log("World saved. Bye.");
        System.exit(0);
    }

    /** Pregenerate an NxN chunk square centred on the origin, with progress logs. */
    static void pregenerate(int n) {
        long need = (long) n * n * World.CHUNK * World.CHUNK * World.SY;
        log("Pregenerating " + n + "x" + n + " chunks (~" + (need >> 20) + " MB of world)...");
        if (need > Runtime.getRuntime().maxMemory() / 2)
            log("WARNING: that is close to the JVM heap limit — consider java -Xmx"
                + ((need * 2) >> 20) + "m -jar PACMine-Server.jar, or lower pregen-chunks");
        int half = n / 2, done = 0, total = n * n, lastPct = -1;
        for (int cx = -half; cx < n - half; cx++)
            for (int cz = -half; cz < n - half; cz++) {
                world.ensureChunk(cx * World.CHUNK, cz * World.CHUNK);
                done++;
                int pct = done * 100 / total;
                if (pct / 10 > lastPct / 10) { log("  " + pct + "%"); lastPct = pct; }
            }
    }

    /** players.dat: last known position per player name (name:x,y,z). */
    static void loadPlayers() {
        Path f = Paths.get("players.dat");
        if (!Files.exists(f)) return;
        Map<String, double[]> m = new HashMap<>();
        try {
            for (String line : Files.readAllLines(f)) {
                int i = line.indexOf(':');
                if (i <= 0) continue;
                String[] p = line.substring(i + 1).split(",");
                if (p.length == 3)
                    m.put(line.substring(0, i), new double[]{
                        Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])});
            }
            server.importPositions(m);
            log("Loaded positions of " + m.size() + " players");
        } catch (Exception e) { log("players.dat load: " + e.getMessage()); }
    }

    static void savePlayers() {
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, double[]> e : server.exportPositions().entrySet()) {
                double[] p = e.getValue();
                sb.append(e.getKey()).append(':')
                  .append(String.format(Locale.US, "%.2f,%.2f,%.2f%n", p[0], p[1], p[2]));
            }
            Files.writeString(Paths.get("players.dat"), sb.toString());
        } catch (Exception e) { log("players.dat save: " + e.getMessage()); }
    }

    static synchronized void saveWorld() {
        if (server != null) savePlayers();   // world is first saved before the net server exists
        try {
            if (world.infinite) {
                meta.infinite = true;
                meta.seed = world.seed();
                meta.chunkMap = world.exportChunks();
            } else {
                meta.blocks = world.data();
                meta.chunks = world.cx;
            }
            meta.save(worldName);
        } catch (IOException e) {
            log("SAVE FAILED: " + e.getMessage());
        }
    }

    /** Read server.properties, writing a commented default file on first run. */
    static void loadProps() throws IOException {
        Path f = Paths.get("server.properties");
        if (!Files.exists(f)) {
            Files.writeString(f, """
                # PACMine server settings
                # port: TCP port players connect to
                port=25565
                # world-size: world size in chunks (2-24), used when generating a new world
                world-size=8
                # infinite: endless world — the server pregenerates pregen-chunks x pregen-chunks,
                # everything beyond that is generated as players explore
                infinite=false
                # pregen-chunks: pregenerated square for infinite worlds (2-250; 250 needs ~1.5 GB heap)
                pregen-chunks=250
                # protection: bedrock floor + invisible walls at the world edges
                protection=true
                # world-name: file name of the world inside saves/
                world-name=world
                # autosave-seconds: how often the world is written to disk
                autosave-seconds=120
                # plugins: load Lua plugins from the plugins/ folder (plain .lua only)
                plugins=false
                """);
            log("Created default server.properties");
        }
        try (InputStream in = Files.newInputStream(f)) { props.load(in); }
    }

    static int intProp(String key, int def) {
        try { return Integer.parseInt(props.getProperty(key, String.valueOf(def)).trim()); }
        catch (NumberFormatException e) { return def; }
    }

    static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss");
    static synchronized void log(String s) {
        String line = "[" + TS.format(new Date()) + "] " + s;
        System.out.println(line);
        if (logFile != null) logFile.println(line);
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }
}
