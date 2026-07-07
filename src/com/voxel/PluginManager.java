package com.voxel;

import org.luaj.vm2.*;
import org.luaj.vm2.lib.*;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * Lua plugin system for the dedicated server. With plugins=true the core
 * creates plugins/ and loads every .lua file in it (precompiled .luac
 * bytecode is rejected). Each plugin gets a global `pacmine` table:
 *
 *   pacmine.log(msg)                      print to the server console/log
 *   pacmine.on(event, fn)                 subscribe: "join"  fn(name, premium, licensed)
 *                                                    "leave" fn(name)
 *                                                    "block" fn(x, y, z, id)
 *                                                    "tick"  fn(seconds)
 *   pacmine.get_block(x, y, z)            block id at coords
 *   pacmine.set_block(x, y, z, id)        change a block and sync all clients
 *   pacmine.players()                     table of online player names
 *   pacmine.kick(name)                    disconnect a player, returns bool
 *   pacmine.seed()                        world seed
 *   pacmine.is_infinite()                 endless world?
 */
public class PluginManager {
    public interface Log { void log(String s); }

    private final World world;
    private final NetServer server;
    private final Log logger;
    private final Map<String, List<LuaValue>> handlers = new HashMap<>();
    private final List<String> loaded = new ArrayList<>();

    public PluginManager(World world, NetServer server, Log logger) {
        this.world = world; this.server = server; this.logger = logger;
    }

    public List<String> loadedPlugins() { return loaded; }

    /** Load every plugins/*.lua (creates the folder on first run). */
    public void loadAll() {
        File dir = new File("plugins");
        if (!dir.isDirectory()) {
            dir.mkdirs();
            writeReadme(dir);
            logger.log("Created plugins/ — drop .lua files there (see plugins/README.txt)");
        }
        File[] files = dir.listFiles((d, n) -> n.endsWith(".lua"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) {
            try {
                byte[] head = new byte[4];
                try (var in = Files.newInputStream(f.toPath())) { in.read(head); }
                if (head[0] == 0x1B && head[1] == 'L' && head[2] == 'u' && head[3] == 'a') {
                    logger.log("Plugin " + f.getName() + " SKIPPED: compiled .luac bytecode is not allowed");
                    continue;
                }
                Globals g = JsePlatform.standardGlobals();
                g.set("pacmine", api());
                g.loadfile(f.getPath()).call();
                loaded.add(f.getName());
                logger.log("Plugin loaded: " + f.getName());
            } catch (Exception e) {
                logger.log("Plugin " + f.getName() + " FAILED: " + e.getMessage());
            }
        }
        if (loaded.isEmpty()) logger.log("No plugins found in plugins/");
    }

    /** Fire an event into every subscribed plugin handler. */
    public synchronized void fire(String event, Object... args) {
        List<LuaValue> list = handlers.get(event);
        if (list == null) return;
        LuaValue[] la = new LuaValue[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof String)  la[i] = LuaValue.valueOf((String) a);
            else if (a instanceof Boolean) la[i] = LuaValue.valueOf((Boolean) a);
            else if (a instanceof Number)  la[i] = LuaValue.valueOf(((Number) a).doubleValue());
            else la[i] = LuaValue.NIL;
        }
        for (LuaValue fn : list) {
            try { fn.invoke(LuaValue.varargsOf(la)); }
            catch (LuaError e) { logger.log("Plugin handler '" + event + "' error: " + e.getMessage()); }
        }
    }

    private LuaTable api() {
        LuaTable t = new LuaTable();
        t.set("log", new OneArgFunction() {
            public LuaValue call(LuaValue msg) { logger.log("[plugin] " + msg.tojstring()); return NIL; }
        });
        t.set("on", new TwoArgFunction() {
            public LuaValue call(LuaValue ev, LuaValue fn) {
                synchronized (PluginManager.this) {
                    handlers.computeIfAbsent(ev.tojstring(), k -> new ArrayList<>()).add(fn);
                }
                return NIL;
            }
        });
        t.set("get_block", new ThreeArgFunction() {
            public LuaValue call(LuaValue x, LuaValue y, LuaValue z) {
                return valueOf(world.get(x.toint(), y.toint(), z.toint()));
            }
        });
        t.set("set_block", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                server.applyBlock(a.toint(1), a.toint(2), a.toint(3), (byte) a.toint(4));
                return NIL;
            }
        });
        t.set("players", new ZeroArgFunction() {
            public LuaValue call() {
                LuaTable list = new LuaTable();
                int i = 1;
                for (String n : server.onlineNames()) list.set(i++, valueOf(n));
                return list;
            }
        });
        t.set("kick", new OneArgFunction() {
            public LuaValue call(LuaValue name) { return valueOf(server.kick(name.tojstring())); }
        });
        t.set("say", new TwoArgFunction() {
            public LuaValue call(LuaValue from, LuaValue text) {
                server.hostChat(from.tojstring(), text.tojstring());
                logger.log("<" + from.tojstring() + "> " + text.tojstring());
                return NIL;
            }
        });
        t.set("spawn_npc", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return valueOf(server.spawnNpc(a.tojstring(1), a.todouble(2), a.todouble(3), a.todouble(4)));
            }
        });
        t.set("move_npc", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                float yaw = a.narg() >= 5 ? (float) a.todouble(5) : 0;
                return valueOf(server.moveNpc(a.toint(1), a.todouble(2), a.todouble(3), a.todouble(4), yaw));
            }
        });
        t.set("remove_npc", new OneArgFunction() {
            public LuaValue call(LuaValue id) { return valueOf(server.removeNpc(id.toint())); }
        });
        t.set("player_pos", new OneArgFunction() {
            public LuaValue call(LuaValue name) {
                double[] p = server.positionOf(name.tojstring());
                if (p == null) return NIL;
                LuaTable pos = new LuaTable();
                pos.set("x", p[0]); pos.set("y", p[1]); pos.set("z", p[2]);
                return pos;
            }
        });
        t.set("surface_y", new TwoArgFunction() {
            public LuaValue call(LuaValue x, LuaValue z) {
                int bx = x.toint(), bz = z.toint(), y = World.SY - 1;
                while (y > 0 && !world.isSolid(bx, y, bz)) y--;
                return valueOf(y + 1);
            }
        });
        t.set("seed", new ZeroArgFunction() {
            public LuaValue call() { return valueOf(world.seed()); }
        });
        t.set("is_infinite", new ZeroArgFunction() {
            public LuaValue call() { return valueOf(world.infinite); }
        });
        return t;
    }

    private void writeReadme(File dir) {
        try {
            Files.writeString(new File(dir, "README.txt").toPath(), """
                PACMine plugins: put .lua files here (plain Lua source; compiled .luac is rejected).
                Every plugin gets the global `pacmine` table:

                  pacmine.log(msg)                -- print to console/server.log
                  pacmine.on("join",  function(name, premium, licensed) ... end)
                  pacmine.on("leave", function(name) ... end)
                  pacmine.on("block", function(x, y, z, id) ... end)   -- block changed by a player
                  pacmine.on("chat",  function(name, text) ... end)    -- player chat message
                  pacmine.on("tick",  function(seconds) ... end)       -- once per second
                  pacmine.say(from, text)         -- send a chat line to all players
                  pacmine.get_block(x, y, z)      -- block id
                  pacmine.set_block(x, y, z, id)  -- change block, synced to all players
                  pacmine.players()               -- table of online names
                  pacmine.kick(name)              -- disconnect a player
                  pacmine.seed(), pacmine.is_infinite()

                NPCs (rendered by clients like normal players, with a name tag):
                  pacmine.spawn_npc(name, x, y, z)      -- returns npc id
                  pacmine.move_npc(id, x, y, z [, yaw]) -- move it (true/false)
                  pacmine.remove_npc(id)                -- despawn (true/false)
                  pacmine.player_pos(name)              -- {x=,y=,z=} or nil
                  pacmine.surface_y(x, z)               -- ground level at column

                Block ids: 0 air, 1 grass, 2 dirt, 3 stone, 4 wood, 5 leaves, 6 sand,
                           7 bedrock, 8 coal, 9 iron, 10 water, 11 lava.

                Example (welcome.lua):
                  pacmine.on("join", function(name, premium)
                      pacmine.log("Welcome, " .. name .. (premium and " *" or ""))
                  end)
                """);
        } catch (Exception ignored) {}
    }
}
