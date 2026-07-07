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
    private final Socket sock;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Queue<NetEvent> queue;
    public volatile int myId = -1;
    private volatile boolean running = true;

    public NetClient(String host, int port, Queue<NetEvent> queue) throws IOException {
        this.queue = queue;
        sock = new Socket(host, port);
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
                switch (type) {
                    case NetServer.T_ASSIGN:
                        myId = in.readInt();
                        break;
                    case NetServer.T_WORLD: {
                        NetEvent e = new NetEvent();
                        e.type = NetEvent.WORLD;
                        e.chunks = in.readInt();
                        e.protection = in.readBoolean();
                        int len = in.readInt();
                        e.blocks = new byte[len];
                        in.readFully(e.blocks);
                        queue.add(e);
                        break;
                    }
                    case NetServer.T_MOVE: {
                        NetEvent e = new NetEvent();
                        e.type = NetEvent.MOVE; e.id = in.readInt();
                        e.px = in.readDouble(); e.py = in.readDouble(); e.pz = in.readDouble();
                        e.yaw = in.readFloat(); e.pitch = in.readFloat();
                        queue.add(e);
                        break;
                    }
                    case NetServer.T_BLOCK: {
                        NetEvent e = new NetEvent();
                        e.type = NetEvent.BLOCK; e.x = in.readInt(); e.y = in.readInt(); e.z = in.readInt(); e.b = in.readByte();
                        queue.add(e);
                        break;
                    }
                    case NetServer.T_WORLD_INF: {
                        NetEvent e = new NetEvent();
                        e.type = NetEvent.WORLD;
                        e.infiniteWorld = true;
                        e.seed = in.readLong();
                        e.protection = in.readBoolean();
                        int n = in.readInt();
                        e.chunkMap = new java.util.HashMap<>();
                        for (int i = 0; i < n; i++) {
                            long k = in.readLong();
                            byte[] ch = new byte[in.readInt()];
                            in.readFully(ch);
                            e.chunkMap.put(k, ch);
                        }
                        queue.add(e);
                        break;
                    }
                    case NetServer.T_CHAT: {
                        NetEvent e = new NetEvent();
                        e.type = NetEvent.CHAT; e.name = in.readUTF(); e.text = in.readUTF();
                        queue.add(e);
                        break;
                    }
                    case NetServer.T_SPAWN: {
                        NetEvent e = new NetEvent();
                        e.type = NetEvent.SPAWN;
                        e.px = in.readDouble(); e.py = in.readDouble(); e.pz = in.readDouble();
                        queue.add(e);
                        break;
                    }
                    case NetServer.T_LEAVE: {
                        NetEvent e = new NetEvent();
                        e.type = NetEvent.LEAVE; e.id = in.readInt();
                        queue.add(e);
                        break;
                    }
                    case NetServer.T_NAME: {
                        NetEvent e = new NetEvent();
                        e.type = NetEvent.NAME; e.id = in.readInt(); e.name = in.readUTF(); e.premium = in.readBoolean();
                        queue.add(e);
                        break;
                    }
                }
            }
        } catch (IOException e) {
            // connection dropped: tell the game thread so it can leave the session
            if (running) {
                NetEvent ev = new NetEvent();
                ev.type = NetEvent.DISCONNECT;
                queue.add(ev);
            }
        }
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

    /** Send our chosen name and login token; the server verifies premium. */
    public void sendName(String name, String token) {
        try { synchronized (out) {
            out.writeByte(NetServer.T_NAME); out.writeInt(0);
            out.writeUTF(name == null ? "" : name); out.writeUTF(token == null ? "" : token); out.flush();
        }} catch (IOException ignored) {}
    }

    public void close() {
        running = false;
        try { sock.close(); } catch (IOException ignored) {}
    }
}
