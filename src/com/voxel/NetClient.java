package com.voxel;

import java.io.*;
import java.net.Socket;
import java.util.Queue;

/**
 * Client side of multiplayer: connects to a host, reads the world and ongoing
 * updates into the shared event queue, and sends the local player's moves and
 * block edits.
 */
public class NetClient {
    private static final int MAX_PACKETS_PER_SECOND = 500;
    private static final int MAX_QUEUED_EVENTS = 4096;
    private static final int MAX_NETWORK_CHUNKS = 1024;
    private static final int MAX_CHAT_LENGTH = 100;
    private final Socket sock;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Queue<NetEvent> queue;
    public volatile int myId = -1;
    private volatile boolean running = true;
    private long packetWindow = System.nanoTime();
    private int packetsInWindow;

    public NetClient(String host, int port, Queue<NetEvent> queue) throws IOException {
        this.queue = queue;
        sock = new Socket();
        sock.connect(new java.net.InetSocketAddress(host, port), 8000);
        sock.setTcpNoDelay(true);
        in = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
        out = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream()));
        Thread t = new Thread(this::readLoop, "net-reader");
        t.setDaemon(true);
        t.start();
    }

    private void readLoop() {
        try {
            while (running) {
                byte type = in.readByte();
                if (!allowPacket()) throw new IOException("server packet rate exceeded");
                switch (type) {
                    case NetServer.T_ASSIGN:
                        myId = in.readInt();
                        if (myId <= 0) throw new IOException("invalid player id");
                        break;
                    case NetServer.T_WORLD: {
                        NetEvent e = new NetEvent();
                        e.type = NetEvent.WORLD;
                        e.chunks = in.readInt();
                        e.protection = in.readBoolean();
                        int len = in.readInt();
                        int expected = e.chunks * World.CHUNK * World.SY * e.chunks * World.CHUNK;
                        if (e.chunks < 2 || e.chunks > 24 || len != expected) throw new IOException("invalid world size");
                        e.blocks = new byte[len];
                        in.readFully(e.blocks);
                        enqueue(e);
                        break;
                    }
                    case NetServer.T_MOVE: {
                        NetEvent e = new NetEvent();
                        e.type = NetEvent.MOVE; e.id = in.readInt();
                        e.px = in.readDouble(); e.py = in.readDouble(); e.pz = in.readDouble();
                        e.yaw = in.readFloat(); e.pitch = in.readFloat();
                        if (e.id < 0 || !validPosition(e.px, e.py, e.pz, e.yaw, e.pitch))
                            throw new IOException("invalid move packet");
                        enqueue(e);
                        break;
                    }
                    case NetServer.T_BLOCK: {
                        NetEvent e = new NetEvent();
                        e.type = NetEvent.BLOCK; e.x = in.readInt(); e.y = in.readInt(); e.z = in.readInt(); e.b = in.readByte();
                        if (e.y < 0 || e.y >= World.SY || (e.b != World.AIR && (e.b < World.GRASS || e.b > World.GLASS)))
                            throw new IOException("invalid block packet");
                        enqueue(e);
                        break;
                    }
                    case NetServer.T_WORLD_INF: {
                        NetEvent e = new NetEvent();
                        e.type = NetEvent.WORLD;
                        e.infiniteWorld = true;
                        e.seed = in.readLong();
                        e.protection = in.readBoolean();
                        int n = in.readInt();
                        if (n < 0 || n > MAX_NETWORK_CHUNKS) throw new IOException("invalid chunk count");
                        e.chunkMap = new java.util.HashMap<>();
                        for (int i = 0; i < n; i++) {
                            long k = in.readLong();
                            int len = in.readInt();
                            if (len != World.CHUNK * World.SY * World.CHUNK) throw new IOException("invalid chunk size");
                            byte[] ch = new byte[len];
                            in.readFully(ch);
                            e.chunkMap.put(k, ch);
                        }
                        enqueue(e);
                        break;
                    }
                    case NetServer.T_CHAT: {
                        NetEvent e = new NetEvent();
                        e.type = NetEvent.CHAT; e.name = in.readUTF(); e.text = in.readUTF();
                        if (!validChatName(e.name) || e.text.length() > MAX_CHAT_LENGTH) throw new IOException("invalid chat packet");
                        enqueue(e);
                        break;
                    }
                    case NetServer.T_SPAWN: {
                        NetEvent e = new NetEvent();
                        e.type = NetEvent.SPAWN;
                        e.px = in.readDouble(); e.py = in.readDouble(); e.pz = in.readDouble();
                        if (!validPosition(e.px, e.py, e.pz, 0, 0)) throw new IOException("invalid spawn packet");
                        enqueue(e);
                        break;
                    }
                    case NetServer.T_LEAVE: {
                        NetEvent e = new NetEvent();
                        e.type = NetEvent.LEAVE; e.id = in.readInt();
                        if (e.id < 0) throw new IOException("invalid leave packet");
                        enqueue(e);
                        break;
                    }
                    case NetServer.T_NAME: {
                        NetEvent e = new NetEvent();
                        e.type = NetEvent.NAME; e.id = in.readInt(); e.name = in.readUTF();
                        if (e.id < 0 || !validName(e.name)) throw new IOException("invalid name packet");
                        enqueue(e);
                        break;
                    }
                    default: throw new IOException("unknown packet type: " + type);
                }
            }
        } catch (IOException e) {
            // connection dropped: tell the game thread so it can leave the session
            if (running) {
                NetEvent ev = new NetEvent();
                ev.type = NetEvent.DISCONNECT;
                if (queue.size() < MAX_QUEUED_EVENTS) queue.add(ev);
            }
        }
    }

    private boolean allowPacket() {
        long now = System.nanoTime();
        if (now - packetWindow >= 1_000_000_000L) { packetWindow = now; packetsInWindow = 0; }
        return ++packetsInWindow <= MAX_PACKETS_PER_SECOND;
    }

    private void enqueue(NetEvent event) throws IOException {
        if (queue.size() >= MAX_QUEUED_EVENTS) throw new IOException("server event queue exceeded");
        queue.add(event);
    }

    private static boolean validName(String name) {
        return name != null && name.matches("[\\p{L}\\p{N}_-]{1,20}");
    }

    private static boolean validChatName(String name) {
        return validName(name) || "[Server]".equals(name);
    }

    private static boolean validPosition(double x, double y, double z, float yaw, float pitch) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
            && Float.isFinite(yaw) && Float.isFinite(pitch)
            && y >= -16 && y <= World.SY + 16 && Math.abs(pitch) <= 90.1f;
    }

    public void sendMove(double x, double y, double z, float yaw, float pitch) {
        try { synchronized (out) {
            out.writeByte(NetServer.T_MOVE);
            out.writeDouble(x); out.writeDouble(y); out.writeDouble(z);
            out.writeFloat(yaw); out.writeFloat(pitch); out.flush();
        }} catch (IOException ignored) {}
    }

    public void sendBlock(int x, int y, int z, byte b) {
        try { synchronized (out) {
            out.writeByte(NetServer.T_BLOCK);
            out.writeInt(x); out.writeInt(y); out.writeInt(z); out.writeByte(b); out.flush();
        }} catch (IOException ignored) {}
    }

    public void sendChat(String msg) {
        try { synchronized (out) {
            out.writeByte(NetServer.T_CHAT); out.writeUTF(msg); out.flush();
        }} catch (IOException ignored) {}
    }

    /** Send the nickname stored in the local profile. */
    public void sendName(String name) {
        try { synchronized (out) {
            out.writeByte(NetServer.T_NAME); out.writeInt(0);
            out.writeUTF(name == null ? "" : name); out.flush();
        }} catch (IOException ignored) {}
    }

    public void close() {
        running = false;
        try { sock.close(); } catch (IOException ignored) {}
    }
}
