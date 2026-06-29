package com.voxel;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Serialises a whole game (world blocks + settings + player state) to a
 * gzip-compressed file under the saves/ directory.
 */
public class SaveGame {
    private static final int MAGIC = 0x50414331; // "PAC1"
    public static final File DIR = new File("saves");

    public int chunks;
    public boolean hostileMobs, creative, protection;
    public double px, py, pz;
    public float yaw, pitch;
    public double health;
    public boolean hasSword;
    public int logsBroken;
    public byte[] blocks;

    public static List<String> list() {
        List<String> names = new ArrayList<>();
        if (DIR.isDirectory()) {
            File[] fs = DIR.listFiles((d, n) -> n.endsWith(".pmw"));
            if (fs != null) {
                java.util.Arrays.sort(fs, (a, b) -> a.getName().compareTo(b.getName()));
                for (File f : fs) names.add(f.getName().substring(0, f.getName().length() - 4));
            }
        }
        return names;
    }

    /** Next free world name like WORLD1, WORLD2, ... */
    public static String nextName() {
        int i = 1;
        while (new File(DIR, "WORLD" + i + ".pmw").exists()) i++;
        return "WORLD" + i;
    }

    public static void delete(String name) {
        new File(DIR, name + ".pmw").delete();
    }

    public void save(String name) throws IOException {
        DIR.mkdirs();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(
                new BufferedOutputStream(new FileOutputStream(new File(DIR, name + ".pmw")))))) {
            out.writeInt(MAGIC);
            out.writeInt(chunks);
            out.writeBoolean(hostileMobs);
            out.writeBoolean(creative);
            out.writeBoolean(protection);
            out.writeDouble(px); out.writeDouble(py); out.writeDouble(pz);
            out.writeFloat(yaw); out.writeFloat(pitch);
            out.writeDouble(health);
            out.writeBoolean(hasSword);
            out.writeInt(logsBroken);
            out.writeInt(blocks.length);
            out.write(blocks);
        }
    }

    public static SaveGame load(String name) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(
                new BufferedInputStream(new FileInputStream(new File(DIR, name + ".pmw")))))) {
            if (in.readInt() != MAGIC) throw new IOException("bad save file");
            SaveGame s = new SaveGame();
            s.chunks = in.readInt();
            s.hostileMobs = in.readBoolean();
            s.creative = in.readBoolean();
            s.protection = in.readBoolean();
            s.px = in.readDouble(); s.py = in.readDouble(); s.pz = in.readDouble();
            s.yaw = in.readFloat(); s.pitch = in.readFloat();
            s.health = in.readDouble();
            s.hasSword = in.readBoolean();
            s.logsBroken = in.readInt();
            int len = in.readInt();
            s.blocks = new byte[len];
            in.readFully(s.blocks);
            return s;
        }
    }
}
