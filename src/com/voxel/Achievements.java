package com.voxel;

import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;

/**
 * Global achievements shared by all worlds, stored in the .pma format
 * (PAC Mine Achievements): saves/achievements.pma — magic "PMA1" followed
 * by the unlocked ids. Each entry: {id, title, description}.
 */
public class Achievements {
    public static final String[][] ALL = {
        {"FIRST_BLOCK", "FIRST STEPS",   "BREAK YOUR FIRST BLOCK"},
        {"BUILDER",     "BUILDER",       "PLACE YOUR FIRST BLOCK"},
        {"WOOD",        "TIMBER",        "PICK UP WOOD"},
        {"SWORD",       "ARMED",         "FORGE THE SWORD"},
        {"ZOMBIE",      "ZOMBIE SLAYER", "KILL A ZOMBIE"},
        {"BRUTE",       "GIANT DOWN",    "KILL A BRUTE"},
        {"HUNTER",      "HUNTER",        "KILL AN ANIMAL"},
        {"HEAL",        "TASTY HEART",   "HEAL WITH A HEART"},
        {"MINER",       "IRON MAN",      "MINE IRON ORE"},
        {"NIGHT",       "SURVIVOR",      "SURVIVE A NIGHT"},
        {"WAVE5",       "WAVE MASTER",   "REACH WAVE 5 IN SUPER"},
    };

    private static final int MAGIC_PMA = 0x504D4131;   // "PMA1"
    private static final Path FILE = Paths.get("saves", "achievements.pma");
    private static final Path LEGACY = Paths.get("saves", "achievements.txt");
    private static final Set<String> unlocked = new HashSet<>();
    private static boolean loaded = false;

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (Files.exists(FILE)) {
                byte[] raw = PMCrypt.decrypt(Files.readAllBytes(FILE));
                try (java.io.DataInputStream in = new java.io.DataInputStream(
                        new java.io.ByteArrayInputStream(raw))) {
                    if (in.readInt() != MAGIC_PMA) throw new java.io.IOException("bad pma file");
                    int n = in.readInt();
                    for (int i = 0; i < n; i++) unlocked.add(in.readUTF());
                }
            } else if (Files.exists(LEGACY)) {          // migrate the old .txt once
                for (String line : Files.readAllLines(LEGACY))
                    if (!line.isBlank()) unlocked.add(line.trim());
                save();
                Files.deleteIfExists(LEGACY);
            }
        } catch (Exception e) { System.err.println("achievements load: " + e); }
    }

    private static synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(buf)) {
                out.writeInt(MAGIC_PMA);
                out.writeInt(unlocked.size());
                for (String id : unlocked) out.writeUTF(id);
            }
            Files.write(FILE, PMCrypt.encrypt(buf.toByteArray()));
        } catch (Exception e) { System.err.println("achievements save: " + e); }
    }

    /** Unlock once; returns true only the first time (=> show the toast). */
    public static synchronized boolean unlock(String id) {
        load();
        if (!unlocked.add(id)) return false;
        save();
        return true;
    }

    public static synchronized boolean has(String id) { load(); return unlocked.contains(id); }

    public static String titleOf(String id) {
        for (String[] a : ALL) if (a[0].equals(id)) return a[1];
        return id;
    }

    public static synchronized int unlockedCount() {
        load();
        int n = 0;
        for (String[] a : ALL) if (unlocked.contains(a[0])) n++;
        return n;
    }
}
