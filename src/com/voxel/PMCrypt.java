package com.voxel;

/**
 * Encryption for all PAC Mine files (.pms, .pma, ...). An encrypted file
 * starts with the "PMC1" magic followed by the payload XORed with a rolling
 * keystream. Files without the magic are treated as legacy plain data, so
 * old saves keep loading (and get encrypted on the next save).
 *
 * This keeps players from casually editing saves in a text/hex editor; it is
 * not meant to resist a determined reverse engineer.
 */
public final class PMCrypt {
    private static final byte[] MAGIC = {'P', 'M', 'C', '1'};
    private static final byte[] KEY = "PACMINE-PACHUB-PACPERLAR-2026".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private PMCrypt() {}

    private static void xor(byte[] data, int off) {
        for (int i = off; i < data.length; i++) {
            int p = i - off;
            data[i] ^= (byte) (KEY[p % KEY.length] ^ (p * 31) ^ (p >> 7));
        }
    }

    /** Wrap plain payload bytes into an encrypted PM file image. */
    public static byte[] encrypt(byte[] plain) {
        byte[] out = new byte[MAGIC.length + plain.length];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        System.arraycopy(plain, 0, out, MAGIC.length, plain.length);
        xor(out, MAGIC.length);
        return out;
    }

    /** Decode a PM file image: decrypts if marked, returns as-is when legacy plain. */
    public static byte[] decrypt(byte[] file) {
        if (file.length >= MAGIC.length
                && file[0] == MAGIC[0] && file[1] == MAGIC[1]
                && file[2] == MAGIC[2] && file[3] == MAGIC[3]) {
            xor(file, MAGIC.length);
            return java.util.Arrays.copyOfRange(file, MAGIC.length, file.length);
        }
        return file;   // legacy unencrypted file
    }
}
