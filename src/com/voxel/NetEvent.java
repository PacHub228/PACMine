package com.voxel;

/**
 * A network event handed from the I/O threads to the main game thread via a
 * thread-safe queue. Only the fields relevant to {@link #type} are set.
 */
public class NetEvent {
    public static final int WORLD = 1, BLOCK = 2, MOVE = 3, LEAVE = 4, NAME = 5;

    public int type;
    public int id;                 // player id (MOVE/LEAVE/NAME)
    public int x, y, z;            // block coords (BLOCK)
    public byte b;                 // block id (BLOCK)
    public double px, py, pz;      // player pos (MOVE)
    public float yaw, pitch;       // player look (MOVE)
    public int chunks;             // world size (WORLD)
    public boolean protection;     // world flag (WORLD)
    public byte[] blocks;          // world data (WORLD)
    public String name;            // player name (NAME)
    public boolean premium;        // premium flag (NAME)
}
