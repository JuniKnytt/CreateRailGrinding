package net.juniknytt.createrailgrinding.client;

import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player smoothed steer-input lean state for the rail-grind lean visual. Output is a
 * scalar in {@code [-1, +1]} where {@code -1} = full left lean and {@code +1} = full right
 * lean; consumers multiply by their own peak angle (10° model tilt, 10° camera roll, ~0.15
 * blocks of camera lateral offset) to drive the actual visual.
 *
 * <h2>Source of raw values</h2>
 * <ul>
 *   <li><b>Remote players</b>: raw sign set by {@link net.juniknytt.createrailgrinding.network.RailGrindLeanSyncPayload}
 *       broadcasts from the server (edge-triggered on steer-flip + one-shot seed on
 *       entity-tracking start).</li>
 *   <li><b>Local player</b>: raw sign self-published every tick from
 *       {@link ClientInputHandler#onSteerTick} by reading {@code keyLeft.isDown()} /
 *       {@code keyRight.isDown()}. Local-input feed is independent of the server round-trip
 *       so the local player's lean visual responds instantly to their keys. The server's
 *       broadcast handler intentionally ignores its own UUID to prevent the round-tripped
 *       value from fighting local input.</li>
 * </ul>
 *
 * <h2>Smoothing</h2>
 * Two-stage to match {@link RailGrindModelTilt}'s slope-tilt smoothing:
 * <ol>
 *   <li>{@link #onClientTickPost} runs once per client tick: snapshots CURR into PREV, then
 *       moves CURR a fraction {@link #TICK_FACTOR} of the way from CURR toward RAW. Plain
 *       linear EMA (no slerp needed for a scalar). Tick-aligned so the smoothing rate is
 *       framerate-independent.</li>
 *   <li>{@link #getRenderLean} runs per render frame: linearly interpolates PREV → CURR by
 *       {@code partialTicks}, producing continuous motion across the tick boundary.</li>
 * </ol>
 */
@EventBusSubscriber(modid = RailGrind.MODID, value = Dist.CLIENT)
public final class RailGrindLeanTracker {
    /**
     * Fraction of the way CURR moves toward RAW each client tick. 0.18 → ~3.5-tick half-
     * life → ~175 ms to reach 50% lean on a fresh A/D press. Slightly slower than the
     * slope-tilt smoother (0.20) so the lean visual reads as a deliberate body shift rather
     * than reacting as fast as the slope normal. Tuned by feel; if it lags too long on key
     * presses, raise; if it judders on rapid alternating taps, lower.
     */
    private static final float TICK_FACTOR = 0.18f;

    // Raw target lean per balancing player (-1.0, 0.0, +1.0). ConcurrentHashMap because the
    // payload handler enqueues writes from the network thread via context.enqueueWork() which
    // dispatches to the client tick thread — but we still treat it as concurrent in case any
    // future caller writes off-thread. CURR/PREV are tick-thread-only and stay plain HashMaps.
    private static final Map<UUID, Float> RAW = new ConcurrentHashMap<>();
    // End-of-tick smoothed lean. Slerp-target for renders (well, lerp; scalar).
    private static final Map<UUID, Float> CURR = new HashMap<>();
    // Snapshot of CURR at the start of the latest tick. Lerp-from for partial-tick renders.
    private static final Map<UUID, Float> PREV = new HashMap<>();

    private RailGrindLeanTracker() {}

    /**
     * Set the raw lean sign for a player. Values outside {@code [-1, +1]} are clamped. Called
     * from network payload handler (remote players) and from local-input poller (local player).
     */
    public static void setRawSign(UUID id, int sign) {
        float clamped = sign > 0 ? 1.0f : (sign < 0 ? -1.0f : 0.0f);
        RAW.put(id, clamped);
    }

    /**
     * Drop all tracked state. Called on session boundaries (login/logout/respawn) so a stale
     * lean from a previous session can't bleed through.
     */
    public static void clearAll() {
        RAW.clear();
        CURR.clear();
        PREV.clear();
    }

    /**
     * @return Smoothed lean scalar in {@code [-1, +1]} at the current render frame, or
     *         {@code 0.0f} if no smoothed value is tracked yet for this player.
     */
    public static float getRenderLean(Player player, float partialTicks) {
        UUID id = player.getUUID();
        Float curr = CURR.get(id);
        if (curr == null) return 0.0f;
        Float prev = PREV.get(id);
        if (prev == null) prev = curr;
        return Mth.lerp(partialTicks, prev, curr);
    }

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            clearAll();
            return;
        }
        // Drop entries for players who are no longer balancing or no longer in this level
        // (chunk unload, dim change, dismount). Same walk-via-iterator pattern as
        // RailGrindModelTilt to remove safely during traversal.
        Iterator<Map.Entry<UUID, Float>> iter = RAW.entrySet().iterator();
        while (iter.hasNext()) {
            UUID id = iter.next().getKey();
            Player p = mc.level.getPlayerByUUID(id);
            if (p == null || !BalancingPoseTracker.isBalancing(p)) {
                iter.remove();
                CURR.remove(id);
                PREV.remove(id);
            }
        }
        // Advance smoothing for surviving entries.
        for (Map.Entry<UUID, Float> e : RAW.entrySet()) {
            UUID id = e.getKey();
            float raw = e.getValue();
            Float curr = CURR.get(id);
            if (curr == null) {
                // First tick balancing — seed both at raw so renders don't ramp through a
                // bogus zero start (especially when seeded mid-lean by syncStateToObserver).
                CURR.put(id, raw);
                PREV.put(id, raw);
            } else {
                PREV.put(id, curr);
                CURR.put(id, curr + (raw - curr) * TICK_FACTOR);
            }
        }
    }
}
