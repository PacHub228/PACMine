package com.voxel;

import java.io.*;
import java.net.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Queue;

/**
 * Authoritative game server run by the host. Holds the world, accepts clients,
 * relays player moves and block edits between everyone, and feeds remote
 * actions back to the host via a shared event queue.
 */
public class NetServer {
    static final byte T_ASSIGN = 0, T_WORLD = 1, T_MOVE = 2, T_BLOCK = 3, T_LEAVE = 4, T_NAME = 5, T_WORLD_INF = 6, T_SPAWN = 7, T_CHAT = 8;
    public static final int PORT = 25565;

    private final World world;
    private final boolean protection;
    private final Queue<NetEvent> hostQueue;       // events delivered to the host thread
    private final List<Conn> clients = new CopyOnWriteArrayList<>();
    // chunks touched by edits (infinite worlds): only these are sent to joiners,
    // untouched terrain regenerates identically client-side from the seed
    private final java.util.Set<Long> editedChunks = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // last known position per player name: reconnect where you left off
    private final java.util.Map<String, double[]> playerPos = new java.util.concurrent.ConcurrentHashMap<>();

    public java.util.Map<String, double[]> exportPositions() { return playerPos; }
    public void importPositions(java.util.Map<String, double[]> m) { playerPos.putAll(m); }
    private final AtomicInteger nextId = new AtomicInteger(1); // host is 0
    private ServerSocket ss;
    private volatile boolean running;
    private volatile boolean hasHost = true;       // dedicated server has no local host player
    private volatile String hostName = "Host";
    private volatile boolean hostPremium = false;

    public NetServer(World world, boolean protection, Queue<NetEvent> hostQueue) {
        this.world = world; this.protection = protection; this.hostQueue = hostQueue;
    }

    public void setDedicated() { hasHost = false; }
    public void setHostInfo(String name, boolean premium) { hostName = name; hostPremium = premium; }

    public void start() throws IOException { start(PORT); }

    public void start(int port) throws IOException {
        ss = new ServerSocket(port);
        running = true;
        Thread t = new Thread(this::acceptLoop, "net-accept");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try { if (ss != null) ss.close(); } catch (IOException ignored) {}
        for (Conn c : clients) c.close();
        clients.clear();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = ss.accept();
                Conn c = new Conn(s, nextId.getAndIncrement());
                clients.add(c);
                c.sendAssignAndWorld();
                Thread t = new Thread(() -> read(c), "net-client-" + c.id);
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) System.err.println("accept: " + e.getMessage());
            }
        }
    }

    private void read(Conn c) {
        try {
            while (running) {
                byte type = c.in.readByte();
                if (type == T_MOVE) {
                    double x = c.in.readDouble(), y = c.in.readDouble(), z = c.in.readDouble();
                    float yaw = c.in.readFloat(), pitch = c.in.readFloat();
                    if (c.name != null) playerPos.put(c.name.toLowerCase(), new double[]{x, y, z});
                    enqueueMove(c.id, x, y, z, yaw, pitch);
                    for (Conn o : clients) if (o != c) o.sendMove(c.id, x, y, z, yaw, pitch);
                } else if (type == T_BLOCK) {
                    int x = c.in.readInt(), y = c.in.readInt(), z = c.in.readInt();
                    byte b = c.in.readByte();
                    world.set(x, y, z, b);
                    if (world.infinite) editedChunks.add(World.chunkKeyFor(x, z));
                    enqueueBlock(x, y, z, b);
                    for (Conn o : clients) if (o != c) o.sendBlock(x, y, z, b);
                } else if (type == T_CHAT) {
                    String msg = c.in.readUTF();
                    if (msg.length() > 100) msg = msg.substring(0, 100);
                    if (!msg.isBlank()) {
                        String from = c.name == null ? "Player" : c.name;
                        for (Conn o : clients) o.sendChat(from, msg);   // echo to everyone incl. sender
                        enqueueChat(from, msg);
                    }
                } else if (type == T_NAME) {
                    c.in.readInt();                          // id placeholder the client sends
                    String provided = c.in.readUTF();
                    String token = c.in.readUTF();
                    String[] v = AuthClient.verify(token);   // server-side license check
                    boolean licensed = v != null;
                    if (licensed) { c.name = v[0]; c.premium = Boolean.parseBoolean(v[1]); }
                    else { c.name = provided.isEmpty() ? "Player" : provided; c.premium = false; }
                    enqueueName(c.id, c.name, c.premium, licensed);
                    // returning player: send them back where they logged out
                    double[] p = playerPos.get(c.name.toLowerCase());
                    if (p != null) c.sendSpawn(p[0], p[1], p[2]);
                    for (Conn o : clients) if (o != c) o.sendName(c.id, c.name, c.premium);
                    // tell the newcomer about everyone already here (host + other clients)
                    if (hasHost) c.sendName(0, hostName, hostPremium);
                    for (Conn o : clients) if (o != c && o.name != null) c.sendName(o.id, o.name, o.premium);
                    // ...and about the NPCs
                    for (java.util.Map.Entry<Integer, Object[]> en : npcs.entrySet()) {
                        double[] np = (double[]) en.getValue()[1];
                        c.sendName(en.getKey(), (String) en.getValue()[0], false);
                        c.sendMove(en.getKey(), np[0], np[1], np[2], (float) np[3], 0);
                    }
                }
            }
        } catch (IOException e) {
            // client gone
        } finally {
            clients.remove(c);
            c.close();
            enqueueLeave(c.id);
            for (Conn o : clients) o.sendLeave(c.id);
        }
    }

    // ---- NPCs: server-side "players" that clients render like anyone else ----
    private final java.util.Map<Integer, Object[]> npcs = new java.util.concurrent.ConcurrentHashMap<>(); // id -> {name, double[x,y,z,yaw]}

    /** Spawn an NPC visible to all clients; returns its id. */
    public int spawnNpc(String name, double x, double y, double z) {
        int id = nextId.getAndIncrement();
        npcs.put(id, new Object[]{name, new double[]{x, y, z, 0}});
        for (Conn c : clients) { c.sendName(id, name, false); c.sendMove(id, x, y, z, 0, 0); }
        return id;
    }

    /** Move an NPC (broadcast to all clients). */
    public boolean moveNpc(int id, double x, double y, double z, float yaw) {
        Object[] n = npcs.get(id);
        if (n == null) return false;
        double[] p = (double[]) n[1];
        p[0] = x; p[1] = y; p[2] = z; p[3] = yaw;
        for (Conn c : clients) c.sendMove(id, x, y, z, yaw, 0);
        return true;
    }

    /** Remove an NPC. */
    public boolean removeNpc(int id) {
        if (npcs.remove(id) == null) return false;
        for (Conn c : clients) c.sendLeave(id);
        return true;
    }

    /** Last known position of a player by name, or null. */
    public double[] positionOf(String name) {
        return name == null ? null : playerPos.get(name.toLowerCase());
    }

    // ---- called from server-side code (console, plugins) ----
    /** Apply a block change server-side and broadcast it to every client. */
    public void applyBlock(int x, int y, int z, byte b) {
        world.set(x, y, z, b);
        if (world.infinite) editedChunks.add(World.chunkKeyFor(x, z));
        for (Conn c : clients) c.sendBlock(x, y, z, b);
    }

    /** Names of connected, identified players. */
    public java.util.List<String> onlineNames() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Conn c : clients) if (c.name != null) out.add(c.name);
        return out;
    }

    /** Disconnect a player by name (case-insensitive). Returns true if found. */
    public boolean kick(String name) {
        for (Conn c : clients)
            if (c.name != null && c.name.equalsIgnoreCase(name)) { c.close(); return true; }
        return false;
    }

    // ---- called from the host (main thread) ----
    public void hostMoved(double x, double y, double z, float yaw, float pitch) {
        for (Conn o : clients) o.sendMove(0, x, y, z, yaw, pitch);
    }
    public void hostBlock(int x, int y, int z, byte b) {
        for (Conn o : clients) o.sendBlock(x, y, z, b);
    }
    /** Chat line from the host / server / plugins, sent to every client. */
    public void hostChat(String from, String text) {
        for (Conn o : clients) o.sendChat(from, text);
    }

    public void hostName(String name, boolean premium) {
        hostName = name; hostPremium = premium;
        for (Conn o : clients) o.sendName(0, name, premium);
    }

    private void enqueueMove(int id, double x, double y, double z, float yaw, float pitch) {
        NetEvent e = new NetEvent(); e.type = NetEvent.MOVE; e.id = id;
        e.px = x; e.py = y; e.pz = z; e.yaw = yaw; e.pitch = pitch; hostQueue.add(e);
    }
    private void enqueueBlock(int x, int y, int z, byte b) {
        NetEvent e = new NetEvent(); e.type = NetEvent.BLOCK; e.x = x; e.y = y; e.z = z; e.b = b; hostQueue.add(e);
    }
    private void enqueueLeave(int id) {
        NetEvent e = new NetEvent(); e.type = NetEvent.LEAVE; e.id = id; hostQueue.add(e);
    }
    private void enqueueChat(String from, String text) {
        NetEvent e = new NetEvent(); e.type = NetEvent.CHAT; e.name = from; e.text = text; hostQueue.add(e);
    }
    private void enqueueName(int id, String name, boolean premium, boolean licensed) {
        NetEvent e = new NetEvent(); e.type = NetEvent.NAME; e.id = id; e.name = name;
        e.premium = premium; e.licensed = licensed; hostQueue.add(e);
    }

    /** One connected client. */
    private class Conn {
        final Socket sock; final int id;
        final DataInputStream in; final DataOutputStream out;
        volatile String name; volatile boolean premium;
        Conn(Socket s, int id) throws IOException {
            this.sock = s; this.id = id;
            s.setTcpNoDelay(true);
            in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
        }
        void sendAssignAndWorld() {
            try {
                synchronized (out) {
                    out.writeByte(T_ASSIGN); out.writeInt(id);
                    if (world.infinite) {
                        // seed + edited chunks only; the client regenerates the rest
                        out.writeByte(T_WORLD_INF);
                        out.writeLong(world.seed());
                        out.writeBoolean(protection);
                        java.util.Map<Long, byte[]> all = world.exportChunks();
                        java.util.List<Long> keys = new java.util.ArrayList<>(editedChunks);
                        out.writeInt(keys.size());
                        for (long k : keys) {
                            byte[] ch = all.get(k);
                            out.writeLong(k);
                            out.writeInt(ch.length);
                            out.write(ch);
                        }
                    } else {
                        byte[] data = world.data();
                        out.writeByte(T_WORLD); out.writeInt(world.cx); out.writeBoolean(protection);
                        out.writeInt(data.length); out.write(data);
                    }
                    out.flush();
                }
            } catch (IOException e) { close(); }
        }
        void sendMove(int pid, double x, double y, double z, float yaw, float pitch) {
            try { synchronized (out) {
                out.writeByte(T_MOVE); out.writeInt(pid);
                out.writeDouble(x); out.writeDouble(y); out.writeDouble(z);
                out.writeFloat(yaw); out.writeFloat(pitch); out.flush();
            }} catch (IOException e) { close(); }
        }
        void sendBlock(int x, int y, int z, byte b) {
            try { synchronized (out) {
                out.writeByte(T_BLOCK); out.writeInt(x); out.writeInt(y); out.writeInt(z); out.writeByte(b); out.flush();
            }} catch (IOException e) { close(); }
        }
        void sendChat(String from, String text) {
            try { synchronized (out) {
                out.writeByte(T_CHAT); out.writeUTF(from == null ? "" : from); out.writeUTF(text); out.flush();
            }} catch (IOException e) { close(); }
        }
        void sendSpawn(double x, double y, double z) {
            try { synchronized (out) {
                out.writeByte(T_SPAWN);
                out.writeDouble(x); out.writeDouble(y); out.writeDouble(z); out.flush();
            }} catch (IOException e) { close(); }
        }
        void sendLeave(int pid) {
            try { synchronized (out) { out.writeByte(T_LEAVE); out.writeInt(pid); out.flush(); } }
            catch (IOException e) { close(); }
        }
        void sendName(int pid, String nm, boolean prem) {
            try { synchronized (out) { out.writeByte(T_NAME); out.writeInt(pid); out.writeUTF(nm == null ? "" : nm); out.writeBoolean(prem); out.flush(); } }
            catch (IOException e) { close(); }
        }
        void close() { try { sock.close(); } catch (IOException ignored) {} }
    }
}
