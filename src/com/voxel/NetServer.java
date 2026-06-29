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
    static final byte T_ASSIGN = 0, T_WORLD = 1, T_MOVE = 2, T_BLOCK = 3, T_LEAVE = 4;
    public static final int PORT = 25565;

    private final World world;
    private final boolean protection;
    private final Queue<NetEvent> hostQueue;       // events delivered to the host thread
    private final List<Conn> clients = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextId = new AtomicInteger(1); // host is 0
    private ServerSocket ss;
    private volatile boolean running;

    public NetServer(World world, boolean protection, Queue<NetEvent> hostQueue) {
        this.world = world; this.protection = protection; this.hostQueue = hostQueue;
    }

    public void start() throws IOException {
        ss = new ServerSocket(PORT);
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
                    enqueueMove(c.id, x, y, z, yaw, pitch);
                    for (Conn o : clients) if (o != c) o.sendMove(c.id, x, y, z, yaw, pitch);
                } else if (type == T_BLOCK) {
                    int x = c.in.readInt(), y = c.in.readInt(), z = c.in.readInt();
                    byte b = c.in.readByte();
                    world.set(x, y, z, b);
                    enqueueBlock(x, y, z, b);
                    for (Conn o : clients) if (o != c) o.sendBlock(x, y, z, b);
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

    // ---- called from the host (main thread) ----
    public void hostMoved(double x, double y, double z, float yaw, float pitch) {
        for (Conn o : clients) o.sendMove(0, x, y, z, yaw, pitch);
    }
    public void hostBlock(int x, int y, int z, byte b) {
        for (Conn o : clients) o.sendBlock(x, y, z, b);
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

    /** One connected client. */
    private class Conn {
        final Socket sock; final int id;
        final DataInputStream in; final DataOutputStream out;
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
                    byte[] data = world.data();
                    out.writeByte(T_WORLD); out.writeInt(world.cx); out.writeBoolean(protection);
                    out.writeInt(data.length); out.write(data);
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
        void sendLeave(int pid) {
            try { synchronized (out) { out.writeByte(T_LEAVE); out.writeInt(pid); out.flush(); } }
            catch (IOException e) { close(); }
        }
        void close() { try { sock.close(); } catch (IOException ignored) {} }
    }
}
