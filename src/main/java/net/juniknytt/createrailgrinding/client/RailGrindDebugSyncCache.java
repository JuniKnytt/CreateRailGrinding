package net.juniknytt.createrailgrinding.client;

import net.juniknytt.createrailgrinding.network.RailGrindDebugSyncPayload;
import net.minecraft.client.Minecraft;

public final class RailGrindDebugSyncCache {
    private static final int STALE_TICKS = 5;

    private static volatile RailGrindDebugSyncPayload latest;
    private static volatile long latestGameTime;

    private RailGrindDebugSyncCache() {}

    public static void store(RailGrindDebugSyncPayload payload) {
        latest = payload;
        Minecraft mc = Minecraft.getInstance();
        latestGameTime = mc.level == null ? 0L : mc.level.getGameTime();
    }

    public static RailGrindDebugSyncPayload getFresh() {
        RailGrindDebugSyncPayload p = latest;
        if (p == null) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        long now = mc.level.getGameTime();
        return (now - latestGameTime) <= STALE_TICKS ? p : null;
    }
}
