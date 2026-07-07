package com.voxel;

/**
 * A network event handed from the I/O threads to the main game thread via a
 * thread-safe queue. Only the fields relevant to {@link #type} are set.
 */
public class NetEvent {
    public static final int WORLD = 1, BLOCK = 2, MOVE = 3, LEAVE = 4, NAME = 5, DISCONNECT = 6, SPAWN = 7, CHAT = 8;

    public int type;
    public int id;                 // player id (MOVE/LEAVE/NAME)
    public int x, y, z;            // block coords (BLOCK)
    public byte b;                 // block id (BLOCK)
    public double px, py, pz;      // player pos (MOVE)
    public float yaw, pitch;       // player look (MOVE)
    public int chunks;             // world size (WORLD)
    public boolean protection;     // world flag (WORLD)
    public byte[] blocks;          // world data (WORLD)
    public boolean infiniteWorld;  // infinite world join (WORLD)
    public long seed;              // world seed (infinite WORLD)
    public java.util.Map<Long, byte[]> chunkMap;  // edited chunks (infinite WORLD)
    public String name;            // player name (NAME/CHAT)
    public String text;            // chat message (CHAT)
    public boolean premium;        // premium flag (NAME)
    public boolean licensed;       // token verified against the backend (NAME)
}
