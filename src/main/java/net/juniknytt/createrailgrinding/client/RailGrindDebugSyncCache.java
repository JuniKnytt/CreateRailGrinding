package net.juniknytt.createrailgrinding.client;

import net.juniknytt.createrailgrinding.network.RailGrindDebugSyncPayload;
import net.minecraft.client.Minecraft;

/**
 * Client-side store for the most recent {@link RailGrindDebugSyncPayload} the server sent.
 * The debug HUD and debug renderer read from this when the server is broadcasting debug data
 * (server config {@code syncDebugToClients=true}). On an integrated server the HUD/renderer
 * still work via their direct reads of {@code RailGrindHandler.ACTIVE} because both sides
 * share the JVM, so this cache is only the dedicated-server path.
 *
 * <p>Freshness is gated by gameTime; an entry older than {@link #STALE_TICKS} is ignored so
 * the HUD doesn't keep displaying stale numbers if the server stops broadcasting (e.g., admin
 * flips the config off mid-session).
 */
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

    /** Latest payload if it arrived within the last few ticks, else null. */
    public static RailGrindDebugSyncPayload getFresh() {
        RailGrindDebugSyncPayload p = latest;
        if (p == null) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        long now = mc.level.getGameTime();
        return (now - latestGameTime) <= STALE_TICKS ? p : null;
    }
}
