package com.voxel;

/**
 * One placed furnace: three slots (input, fuel, output) that smelt over time.
 * Burning fuel is consumed one unit at a time; each unit is worth several
 * items (see Recipes.fuelValue). State is saved with the world.
 */
public class Furnace {
    public static final double SMELT_TIME = 6.0;   // seconds per item

    public byte inType = World.AIR;  public int inCount;
    public byte fuelType = World.AIR; public int fuelCount;
    public byte outType = World.AIR; public int outCount;

    public double progress;      // 0..SMELT_TIME for the item being smelted
    public int burnLeft;         // items the currently burning fuel can still smelt

    public boolean burning() { return burnLeft > 0; }

    /** Advance smelting. Returns true if anything changed (UI/save dirty). */
    public boolean tick(double dt) {
        byte result = Recipes.smeltResult(inType);
        boolean canSmelt = inCount > 0 && result != World.AIR
                && (outCount == 0 || outType == result);
        if (!canSmelt) {
            if (progress > 0) { progress = 0; return true; }
            return false;
        }
        if (burnLeft <= 0) {                       // light the next fuel unit
            int value = Recipes.fuelValue(fuelType);
            if (value == 0 || fuelCount <= 0) {
                if (progress > 0) { progress = 0; return true; }
                return false;
            }
            fuelCount--;
            if (fuelCount == 0) fuelType = World.AIR;
            burnLeft = value;
        }
        progress += dt;
        if (progress < SMELT_TIME) return true;
        progress = 0;
        burnLeft--;
        inCount--;
        if (inCount == 0) inType = World.AIR;
        outType = result;
        outCount++;
        return true;
    }
}
