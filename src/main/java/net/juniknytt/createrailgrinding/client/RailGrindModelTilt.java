package net.juniknytt.createrailgrinding.client;

import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player slope-normal tilt for the rail-grind body pose, smoothed across ticks and
 * frame-interpolated between tick boundaries. Aligns the model's local +Y (head-up) axis
 * with the upward-facing normal of the nodegraph spline at the player's current position
 * — the same vector the top face of the blue snap-target box in {@link RailGrindDebugRenderer}
 * points along after its yaw+pitch rotations.
 *
 * <h2>Tangent source</h2>
 * The spline geometry isn't accessible on a dedicated-server client without the
 * (off-by-default) debug sync, so the tangent is derived from each player's per-tick
 * observed displacement ({@code position - previousPosition}). That works for both
 * sources of grind motion this mod produces:
 * <ul>
 *   <li>Local grinding player: {@link RailGrindClientMotion} setPos's the player toward
 *       the target each client tick, and the per-tick advance equals the server's
 *       per-tick spline advance in steady state — so {@code x - xo} reproduces the
 *       rail tangent.</li>
 *   <li>Remote grinding players: vanilla {@code ClientboundMoveEntityPacket} updates
 *       {@code xo/yo/zo} each tick from the server's broadcast position, so the same
 *       per-tick delta is available without any custom payload.</li>
 * </ul>
 *
 * <h2>Smoothing</h2>
 * Two-stage so neither tick jitter nor frame rate leak into the rendered tilt:
 * <ol>
 *   <li>{@link #onClientTickPost} runs once per client tick: for each balancing player
 *       it snapshots the current smoothed quaternion into {@link #PREV} and slerps the
 *       {@link #CURR} quaternion {@link #TICK_SLERP} of the way toward the raw observed
 *       tilt. Tick-aligned so the smoothing rate is the same regardless of framerate.</li>
 *   <li>{@link #getRenderTilt} runs per render frame: slerps from PREV to CURR by the
 *       caller-supplied {@code partialTicks}, producing continuous motion across the
 *       tick boundary. Same idiom vanilla uses for {@code Entity.getX(partialTicks)}.</li>
 * </ol>
 *
 * <p>When raw observed motion is below {@link #HORIZ_EPSILON_SQ} the player is treated as
 * stationary: CURR is left untouched and PREV is collapsed to CURR, so the render lerp
 * produces a static (last-known) tilt instead of slingshotting back to identity. Means
 * brief pauses on a slope (e.g., low-speed creep) hold the tilted pose instead of
 * popping upright and back.
 */
@EventBusSubscriber(modid = RailGrind.MODID, value = Dist.CLIENT)
public final class RailGrindModelTilt {
    // Below this horizontal per-tick displacement the raw tilt axis is dominated by
    // floating-point noise (cross(up, tangent_horiz) flips direction frame to frame).
    // Squared so we can compare without a sqrt.
    private static final double HORIZ_EPSILON_SQ = 1.0e-6;

    /**
     * Fraction of the way CURR moves toward the raw observed tilt each client tick.
     * 0.20 → half-life ≈ 3.1 ticks ≈ 155 ms. Lower = smoother (more lag through curves
     * and slope transitions); higher = more responsive (less smoothing). Tuned to ride
     * out the per-tick noise from {@code RailGrindClientMotion}'s predict-correct chase
     * (per-tick advance fluctuates as the client converges on each server target)
     * without visible lag through normal bezier curves.
     */
    private static final float TICK_SLERP = 0.20f;

    // End-of-tick smoothed rotation per balancing player. Slerp target for renders.
    private static final Map<UUID, Quaternionf> CURR = new HashMap<>();
    // Snapshot of CURR at the start of the latest tick — lerp-from point for the
    // per-frame partialTicks interpolation. Always updated alongside CURR by
    // onClientTickPost, even when motion is below threshold, so a stationary player's
    // render lerp doesn't replay a stale prior tick's transition (would flicker between
    // a no-longer-current PREV and CURR every tick the player held still).
    private static final Map<UUID, Quaternionf> PREV = new HashMap<>();

    private RailGrindModelTilt() {}

    /**
     * @return Smoothed tilt rotation for {@code player} at the current render frame, or
     *         {@code null} if no smoothed value is tracked yet (player isn't balancing,
     *         level just loaded, or balancing started this tick and the tick handler
     *         hasn't initialised yet).
     */
    public static Quaternionf getRenderTilt(Player player, float partialTicks) {
        UUID id = player.getUUID();
        Quaternionf curr = CURR.get(id);
        if (curr == null) return null;
        Quaternionf prev = PREV.get(id);
        // Allocate a fresh Quaternionf for the return so the in-place slerp doesn't
        // mutate the cached PREV/CURR (which would compound the smoothing across frames
        // and pull CURR all the way to the latest render result).
        Quaternionf result = new Quaternionf(prev != null ? prev : curr);
        return result.slerp(curr, partialTicks);
    }

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            // Level unload — drop all state so a fresh grind on the next level starts
            // from raw (no stale interpolation from the previous world).
            CURR.clear();
            PREV.clear();
            return;
        }
        // Drop stale entries — players no longer balancing or no longer in this level
        // (chunk unload, dim change). Walk via iterator so we can remove during traversal.
        Iterator<UUID> iter = CURR.keySet().iterator();
        while (iter.hasNext()) {
            UUID id = iter.next();
            Player p = mc.level.getPlayerByUUID(id);
            if (p == null || !BalancingPoseTracker.isBalancing(p)) {
                iter.remove();
                PREV.remove(id);
            }
        }
        // Advance / initialise balancing players.
        for (Player p : mc.level.players()) {
            if (!BalancingPoseTracker.isBalancing(p)) continue;
            UUID id = p.getUUID();
            Quaternionf raw = computeRawTilt(p);
            Quaternionf curr = CURR.get(id);
            if (curr == null) {
                // First tick balancing. Skip if no horizontal motion yet — wait for the
                // first non-null raw so initial smoothing starts from a meaningful value
                // (avoids a brief upright→tilted ramp on grind start).
                if (raw == null) continue;
                CURR.put(id, new Quaternionf(raw));
                PREV.put(id, new Quaternionf(raw));
            } else {
                // Snapshot current as prev BEFORE advancing curr, so PREV→CURR is exactly
                // the last-tick-boundary → this-tick-boundary transition the render lerp
                // wants to interpolate across.
                PREV.put(id, new Quaternionf(curr));
                if (raw != null) {
                    curr.slerp(raw, TICK_SLERP);
                }
                // else: stationary tick — leave curr alone. PREV = old curr = current
                // curr, so getRenderTilt returns a static value (no spurious motion).
            }
        }
    }

    /**
     * Raw (unsmoothed) tilt rotation from the player's per-tick observed displacement.
     * Returns null if horizontal motion is below {@link #HORIZ_EPSILON_SQ} — the tilt
     * axis is ill-defined for a stationary or purely-vertical player.
     *
     * <p>Algebra: rotate world up (0,1,0) by -pitch about axis a = cross((0,1,0),
     * T_horiz_unit). For T_horiz_unit = (dx/horiz, 0, dz/horiz), a = (dz/horiz, 0,
     * -dx/horiz), and Rodrigues collapses to n = (-dx/horiz * sin, cos, -dz/horiz * sin).
     * Sign check: ascending (dy &gt; 0) tilts the normal opposite to horizontal travel
     * (snowboarder standing perpendicular to upslope leans back); descending (dy &lt; 0)
     * tilts it toward travel (forward lean).
     */
    private static Quaternionf computeRawTilt(Player player) {
        double dx = player.getX() - player.xo;
        double dy = player.getY() - player.yo;
        double dz = player.getZ() - player.zo;
        double horizSq = dx * dx + dz * dz;
        if (horizSq < HORIZ_EPSILON_SQ) return null;
        double horiz = Math.sqrt(horizSq);
        double mag = Math.sqrt(horizSq + dy * dy);
        double sinPitch = dy / mag;
        double cosPitch = horiz / mag;
        double invHoriz = 1.0 / horiz;
        float nx = (float) (-dx * invHoriz * sinPitch);
        float ny = (float) cosPitch;
        float nz = (float) (-dz * invHoriz * sinPitch);
        return new Quaternionf().rotationTo(0f, 1f, 0f, nx, ny, nz);
    }
}
