package com.voxel;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Game save in the .pms format (PAC Mine Saves): stores EVERYTHING —
 * world blocks, settings, player state, inventory, zombies, item drops,
 * time of day and Super-mode wave progress.
 *
 * Legacy .pmw worlds are still readable; saving always writes .pms and
 * removes the old .pmw so a world never exists in both formats.
 */
public class SaveGame {
    private static final int MAGIC_PMS  = 0x504D5331;  // "PMS1" full save
    private static final int MAGIC_PMS2 = 0x504D5332;  // "PMS2" adds zombie type + hp
    private static final int MAGIC_PMS3 = 0x504D5333;  // "PMS3" adds animals
    private static final int MAGIC_PMS4 = 0x504D5334;  // "PMS4" slot-based inventory
    private static final int MAGIC     = 0x50414331;   // "PAC1" legacy finite
    private static final int MAGIC_INF = 0x50414332;   // "PAC2" legacy infinite
    public static final File DIR = new File("saves");

    public int chunks;
    public boolean hostileMobs, creative, superMode, protection;
    public double px, py, pz;
    public float yaw, pitch;
    public double health;
    public boolean hasSword;
    public int logsBroken;
    public byte[] blocks;

    // full state (.pms only; legacy loads keep the defaults)
    public double timeOfDay = 0.25;
    public int wave; public double waveTimer;
    public int selectedSlot = 2;
    public int[] inv = new int[16];          // legacy (PMS1-3) per-type counts
    public byte[] slotType;                  // PMS4 slot inventory (null on legacy)
    public int[] slotCount;
    public List<double[]> zombies = new ArrayList<>();  // {x, y, z, yaw, combat, type, hp}
    public List<double[]> drops = new ArrayList<>();    // {type, x, y, z}
    public List<double[]> animals = new ArrayList<>();  // {type, x, y, z, yaw}

    // infinite worlds
    public boolean infinite;
    public long seed;
    public java.util.Map<Long, byte[]> chunkMap;

    public static List<String> list() {
        List<String> names = new ArrayList<>();
        if (DIR.isDirectory()) {
            File[] fs = DIR.listFiles((d, n) -> n.endsWith(".pms") || n.endsWith(".pmw"));
            if (fs != null) {
                java.util.Arrays.sort(fs, (a, b) -> a.getName().compareTo(b.getName()));
                for (File f : fs) {
                    String n = f.getName().substring(0, f.getName().lastIndexOf('.'));
                    if (!names.contains(n)) names.add(n);
                }
            }
        }
        return names;
    }

    /** Next free world name like WORLD1, WORLD2, ... */
    public static String nextName() {
        int i = 1;
        while (new File(DIR, "WORLD" + i + ".pms").exists()
            || new File(DIR, "WORLD" + i + ".pmw").exists()) i++;
        return "WORLD" + i;
    }

    public static void delete(String name) {
        new File(DIR, name + ".pms").delete();
        new File(DIR, name + ".pmw").delete();
    }

    public void save(String name) throws IOException {
        DIR.mkdirs();
        ByteArrayOutputStream buf = new ByteArrayOutputStream(1 << 16);
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(buf))) {
            out.writeInt(MAGIC_PMS4);
            out.writeBoolean(infinite);
            if (!infinite) out.writeInt(chunks);
            out.writeBoolean(hostileMobs);
            out.writeBoolean(creative);
            out.writeBoolean(superMode);
            out.writeBoolean(protection);
            out.writeDouble(px); out.writeDouble(py); out.writeDouble(pz);
            out.writeFloat(yaw); out.writeFloat(pitch);
            out.writeDouble(health);
            out.writeBoolean(hasSword);
            out.writeInt(logsBroken);
            out.writeDouble(timeOfDay);
            out.writeInt(wave); out.writeDouble(waveTimer);
            out.writeInt(selectedSlot);
            out.writeInt(slotType == null ? 0 : slotType.length);
            if (slotType != null)
                for (int i = 0; i < slotType.length; i++) {
                    out.writeByte(slotType[i]);
                    out.writeInt(slotCount[i]);
                }
            out.writeInt(zombies.size());
            for (double[] a : zombies) {
                out.writeDouble(a[0]); out.writeDouble(a[1]); out.writeDouble(a[2]);
                out.writeFloat((float) a[3]); out.writeBoolean(a[4] != 0);
                out.writeInt(a.length > 5 ? (int) a[5] : 0);
                out.writeDouble(a.length > 6 ? a[6] : 2);
            }
            out.writeInt(drops.size());
            for (double[] a : drops) {
                out.writeByte((byte) a[0]);
                out.writeDouble(a[1]); out.writeDouble(a[2]); out.writeDouble(a[3]);
            }
            out.writeInt(animals.size());
            for (double[] a : animals) {
                out.writeInt((int) a[0]);
                out.writeDouble(a[1]); out.writeDouble(a[2]); out.writeDouble(a[3]);
                out.writeFloat((float) a[4]);
            }
            if (infinite) {
                out.writeLong(seed);
                out.writeInt(chunkMap.size());
                for (java.util.Map.Entry<Long, byte[]> e : chunkMap.entrySet()) {
                    out.writeLong(e.getKey());
                    out.writeInt(e.getValue().length);
                    out.write(e.getValue());
                }
            } else {
                out.writeInt(blocks.length);
                out.write(blocks);
            }
        }
        java.nio.file.Files.write(new File(DIR, name + ".pms").toPath(), PMCrypt.encrypt(buf.toByteArray()));
        new File(DIR, name + ".pmw").delete();   // superseded by the .pms
    }

    public static SaveGame load(String name) throws IOException {
        File pms = new File(DIR, name + ".pms");
        File f = pms.exists() ? pms : new File(DIR, name + ".pmw");
        byte[] raw = PMCrypt.decrypt(java.nio.file.Files.readAllBytes(f.toPath()));
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(
                new ByteArrayInputStream(raw)))) {
            int magic = in.readInt();
            if (magic >= MAGIC_PMS && magic <= MAGIC_PMS4)
                return loadPms(in, magic - MAGIC_PMS + 1);
            if (magic == MAGIC || magic == MAGIC_INF) return loadLegacy(in, magic);
            throw new IOException("bad save file");
        }
    }

    private static SaveGame loadPms(DataInputStream in, int ver) throws IOException {
        boolean v2 = ver >= 2, v3 = ver >= 3, v4 = ver >= 4;
        SaveGame s = new SaveGame();
        s.infinite = in.readBoolean();
        if (!s.infinite) s.chunks = in.readInt();
        s.hostileMobs = in.readBoolean();
        s.creative = in.readBoolean();
        s.superMode = in.readBoolean();
        s.protection = in.readBoolean();
        s.px = in.readDouble(); s.py = in.readDouble(); s.pz = in.readDouble();
        s.yaw = in.readFloat(); s.pitch = in.readFloat();
        s.health = in.readDouble();
        s.hasSword = in.readBoolean();
        s.logsBroken = in.readInt();
        s.timeOfDay = in.readDouble();
        s.wave = in.readInt(); s.waveTimer = in.readDouble();
        s.selectedSlot = in.readInt();
        if (v4) {
            int ns = in.readInt();
            s.slotType = new byte[ns];
            s.slotCount = new int[ns];
            for (int i = 0; i < ns; i++) { s.slotType[i] = in.readByte(); s.slotCount[i] = in.readInt(); }
        } else {
            int ni = in.readInt();
            s.inv = new int[ni];
            for (int i = 0; i < ni; i++) s.inv[i] = in.readInt();
        }
        int nz = in.readInt();
        for (int i = 0; i < nz; i++) {
            double zx = in.readDouble(), zy = in.readDouble(), zz = in.readDouble();
            float zyaw = in.readFloat();
            double combat = in.readBoolean() ? 1 : 0;
            int type = v2 ? in.readInt() : 0;
            double hp = v2 ? in.readDouble() : 2;
            s.zombies.add(new double[]{zx, zy, zz, zyaw, combat, type, hp});
        }
        int nd = in.readInt();
        for (int i = 0; i < nd; i++)
            s.drops.add(new double[]{in.readByte(), in.readDouble(), in.readDouble(), in.readDouble()});
        if (v3) {
            int na = in.readInt();
            for (int i = 0; i < na; i++)
                s.animals.add(new double[]{in.readInt(), in.readDouble(), in.readDouble(),
                                           in.readDouble(), in.readFloat()});
        }
        readWorld(in, s);
        return s;
    }

    private static SaveGame loadLegacy(DataInputStream in, int magic) throws IOException {
        SaveGame s = new SaveGame();
        s.infinite = magic == MAGIC_INF;
        if (!s.infinite) s.chunks = in.readInt();
        s.hostileMobs = in.readBoolean();
        s.creative = in.readBoolean();
        s.protection = in.readBoolean();
        s.px = in.readDouble(); s.py = in.readDouble(); s.pz = in.readDouble();
        s.yaw = in.readFloat(); s.pitch = in.readFloat();
        s.health = in.readDouble();
        s.hasSword = in.readBoolean();
        s.logsBroken = in.readInt();
        readWorld(in, s);
        return s;
    }

    private static void readWorld(DataInputStream in, SaveGame s) throws IOException {
        if (s.infinite) {
            s.seed = in.readLong();
            int n = in.readInt();
            s.chunkMap = new java.util.HashMap<>();
            for (int i = 0; i < n; i++) {
                long k = in.readLong();
                byte[] data = new byte[in.readInt()];
                in.readFully(data);
                s.chunkMap.put(k, data);
            }
        } else {
            s.blocks = new byte[in.readInt()];
            in.readFully(s.blocks);
        }
    }
}
