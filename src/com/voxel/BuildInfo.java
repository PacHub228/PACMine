package com.voxel;

/** Single source of truth for the version string and the network protocol number. */
public final class BuildInfo {
    public static final String VERSION = "0.1.17-dev";
    /** Bump whenever the packet layout changes; shown in the F3 overlay. */
    public static final int PROTOCOL = 8;

    private BuildInfo() {}
}
