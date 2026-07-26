package com.voxel;

/**
 * Crafting and smelting tables. Pure data + lookup helpers, no game state,
 * so both the client UI and (later) plugins can ask the same questions.
 *
 * A recipe either yields a block/item stack or unlocks a tool (tools live as
 * flags on the player, not as inventory items).
 */
public class Recipes {
    public static final int TOOL_NONE = 0, TOOL_SWORD = 1, TOOL_PICK = 2,
                            TOOL_IRON_SWORD = 3, TOOL_IRON_PICK = 4;

    public static class Recipe {
        public final String name;
        public final byte[] inputs;
        public final int[] counts;
        public final byte output;      // World.AIR when the result is a tool
        public final int outCount;
        public final int tool;         // TOOL_* or TOOL_NONE

        Recipe(String name, byte[] inputs, int[] counts, byte output, int outCount, int tool) {
            this.name = name; this.inputs = inputs; this.counts = counts;
            this.output = output; this.outCount = outCount; this.tool = tool;
        }
    }

    public static final Recipe[] ALL = {
        new Recipe("ДОСКИ",          new byte[]{World.WOOD},                     new int[]{1},    World.PLANKS,  4, TOOL_NONE),
        new Recipe("ПЕЧКА",          new byte[]{World.STONE},                    new int[]{8},    World.FURNACE, 1, TOOL_NONE),
        new Recipe("МЕЧ",            new byte[]{World.PLANKS},                   new int[]{2},    World.AIR,     0, TOOL_SWORD),
        new Recipe("КИРКА",          new byte[]{World.STONE, World.PLANKS},      new int[]{3, 2}, World.AIR,     0, TOOL_PICK),
        new Recipe("ЖЕЛЕЗНЫЙ МЕЧ",   new byte[]{World.INGOT_IRON, World.PLANKS}, new int[]{2, 1}, World.AIR,     0, TOOL_IRON_SWORD),
        new Recipe("ЖЕЛЕЗНАЯ КИРКА", new byte[]{World.INGOT_IRON, World.PLANKS}, new int[]{3, 2}, World.AIR,     0, TOOL_IRON_PICK),
    };

    /** Smelting: what this input turns into, or AIR if it can't be smelted. */
    public static byte smeltResult(byte in) {
        switch (in) {
            case World.SAND: return World.GLASS;
            case World.IRON: return World.INGOT_IRON;
            case World.WOOD: return World.COAL;      // charcoal
            default: return World.AIR;
        }
    }

    /** How many items one unit of this fuel can smelt (0 = not fuel). */
    public static int fuelValue(byte b) {
        switch (b) {
            case World.COAL:   return 8;
            case World.PLANKS: return 2;
            case World.WOOD:   return 2;
            default: return 0;
        }
    }

    public static String toolName(int tool) {
        switch (tool) {
            case TOOL_SWORD:      return "МЕЧ";
            case TOOL_PICK:       return "КИРКА";
            case TOOL_IRON_SWORD: return "ЖЕЛЕЗНЫЙ МЕЧ";
            case TOOL_IRON_PICK:  return "ЖЕЛЕЗНАЯ КИРКА";
            default: return "";
        }
    }
}
