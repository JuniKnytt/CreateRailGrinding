package net.juniknytt.createrailgrinding.client;

import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.network.BlockedByObstaclePayload;
import net.juniknytt.createrailgrinding.rail.RailGrindHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * Client-side rail-grind motion controller. Predict-correct chase: each client tick the
 * locally-tracked {@code smoothedTarget} is extrapolated forward by the most recent
 * server-shipped velocity hint, then corrected toward the most recently received target
 * position. The player's deltaMovement is set to chase {@code smoothedTarget}.
 *
 * <p>Why predict-correct rather than rigid 100%-close-gap chase: with rigid chase, every
 * client tick moves the player by exactly {@code target - position}. Under network jitter
 * the inter-arrival time of {@link net.juniknytt.createrailgrinding.network.RailGrindTargetPayload}
 * varies, so between two 20Hz client ticks sometimes 0 / 1 / 2 packets arrive. The
 * per-tick gap therefore oscillates between 0× and 2× server-tick-worth even when the
 * server's true target velocity is constant, and the player visibly stutters. Predict-
 * correct extrapolates by the last known velocity each tick, so motion stays smooth
 * between packets; the correction term absorbs both burst arrivals and brief packet
 * stalls within ~3 ticks.
 *
 * <p>Why NOT velocity-memory EMA (the old {@code v = 0.75·v + 0.25·gap} formulation that
 * lived here previously): that's a 2nd-order underdamped system and produced visible
 * sine-wave bobbing on bezier curves. This scheme separates the prediction (velocity-
 * driven, no memory of the player's previous deltaMovement) from the correction (1st-
 * order EMA on position toward target). No spring/mass dynamics, no oscillation, and
 * zero steady-state lag when packets arrive at the expected rate.
 *
 * <p>Hosts the obstacle detector ({@link #runBlockedDetection}) — per-tick comparison of
 * actual motion to requested motion, mirroring Create's chain-conveyor approach. See the
 * field doc on {@link #BLOCKED_RATIO} and the audit doc / memory
 * {@code post_reattach_stuck_suppress.md} for the rationale.
 */
@EventBusSubscriber(modid = RailGrind.MODID, value = Dist.CLIENT)
public final class RailGrindClientMotion {
    /**
     * Per-tick chase-vector cap. Used as a safety net when smoothedTarget is far from the
     * player position — e.g. the very first tick after grind start (smoothedTarget was
     * just seeded but the player hasn't snapped to the rail yet) or after a hard
     * desync. Steady-state chase magnitude is bounded by the server's currentSpeed
     * which the server clamps separately, so this cap doesn't gate normal motion.
     */
    private static final double MAX_STEP = 2.0;

    /**
     * Fraction of the position gap (receivedTarget − smoothedTarget) closed per tick by
     * the correction term. With perfect packet timing (one packet per client tick) and a
     * matching velocity hint, the extrapolation alone tracks the target exactly and this
     * term contributes nothing. Under jitter (bunched / dropped packets) it pulls the
     * smoothedTarget back to authoritative position over ~{@code 1/CORRECTION_RATE}
     * ticks. 0.3 → 50% closure in ~2 ticks, 90% in ~7 ticks; high enough to recover from
     * a 2-tick packet stall fast, low enough that single-tick noise doesn't propagate
     * into chase magnitude.
     */
    private static final double CORRECTION_RATE = 0.3;

    /**
     * Δ_actual / Δ_intent ratio below which a flat-track tick counts as "blocked." 0.5
     * means the player moved less than half the requested distance — vanilla collision
     * clipped the requested deltaMovement. Lower (stricter) → fires faster but more
     * false positives from EMA-settle transients; higher (looser) → tolerates partial
     * blockage but takes longer to confirm a full wall. Pair with
     * {@link #BLOCKED_DROP_TICKS} so a single tick of jitter can't dismount.
     */
    private static final double BLOCKED_RATIO = 0.5;
    /** Consecutive blocked ticks before sending a dismount packet. 3 = 150 ms. */
    private static final int BLOCKED_DROP_TICKS = 3;
    /**
     * Minimum {@code Δ_intent} magnitude that arms the detector. Below this the rail is
     * essentially stationary (initial spawn, server-authoritative anchor releasing,
     * end-of-edge slow-down) and a zero-actual-motion frame isn't an obstacle signal.
     * 0.1 blocks ≈ MIN_SPEED-ish; tune up if start-of-grind transients false-fire.
     */
    private static final double MIN_INTENT = 0.1;
    /**
     * Bottom slice of the player bb that the slope-branch forward probe ignores. The
     * grind rides ~0.5 blocks above the rail bar, so anything within the first 0.5
     * blocks of the bb (i.e., at-or-below rail height) is either the rail itself or
     * decorative support geometry the player is meant to clear. 0.5 was tuned in the
     * earlier {@code rail-grind-partial-collision-design} sketch; revisit if the slope
     * branch fails to detect obstacles whose collision is exclusively at the bb's lower
     * half.
     */
    private static final double BB_BOTTOM_TRIM = 0.5;
    /** Lower clamp on the forward bb-probe distance: don't peek closer than half a block. */
    private static final double FORWARD_PROBE_MIN = 0.5;
    /** Upper clamp on the forward bb-probe distance: don't peek farther than one block. */
    private static final double FORWARD_PROBE_MAX = 1.0;

    private static volatile @Nullable Vec3 target = null;
    private static volatile @Nullable Vec3 velocity = null;
    private static volatile boolean serverAuthoritative = false;
    private static @Nullable Vec3 smoothedTarget = null;

    /** Player position at the end of the previous client tick (i.e., what vanilla physics resolved). Null on the first tick after grind start / clearTarget. */
    private static @Nullable Vec3 lastPlayerPos = null;
    /** Diff vector we set as {@code deltaMovement} at the start of the previous client tick. Compared to {@code (player.position() − lastPlayerPos)} to detect collision-clipping. Null until the first full tick has run. */
    private static @Nullable Vec3 lastIntent = null;
    /** Running count of consecutive blocked ticks; sends the dismount packet when it reaches {@link #BLOCKED_DROP_TICKS}. */
    private static int blockedTicks = 0;
    /** Single-shot latch: prevents re-sending the dismount packet within the RTT window before the server's sync-stop reaches us. Cleared by {@link #clearTarget}. */
    private static boolean blockedDispatched = false;
    /**
     * Per-tick signal: true iff the slope-branch forward bb-probe overlapped solid
     * geometry this tick. Read by {@link net.juniknytt.createrailgrinding.mixin.PlayerNoPhysicsTickMixin}
     * to suspend the {@code noPhysics} bypass for the same tick — i.e., as soon as the
     * client sees a wall ahead on a slope, vanilla collision is restored, even though
     * the 3-tick dismount confirm hasn't fired yet and the server hasn't issued a stop.
     *
     * <p>Without this signal, the bypass keeps firing for the {@code BLOCKED_DROP_TICKS +
     * RTT} window between the first probe-hit and the server's sync-back, and the player
     * phases through walls during that window — the end-of-track-on-slope clipping bug.
     * The 3-tick confirm stays in force for the dismount packet to retain false-positive
     * resistance there; for the bypass itself we want to react on tick 1.
     */
    private static volatile boolean obstacleAheadOnSlope = false;

    private RailGrindClientMotion() {}

    /**
     * Updates the latest received target, the server's last-tick velocity hint
     * (current target − previous target in world space), and the cross-dim
     * server-authoritative flag. Called from the payload handler. {@link #smoothedTarget}
     * is not touched here — it lives on the client tick and integrates these inputs
     * there, so multiple packets between client ticks collapse to the latest values.
     *
     * <p>When {@code authoritative} is true, the client tick will hard-snap to
     * {@code nextTarget} instead of running predict-correct — see {@link #onClientTickPre}.
     */
    public static void setTargetAndVelocity(Vec3 nextTarget, Vec3 nextVelocity, boolean authoritative) {
        target = nextTarget;
        velocity = nextVelocity;
        serverAuthoritative = authoritative;
    }

    /** Drop all motion state — used on dismount, relog, and session boundaries. */
    public static void clearTarget() {
        target = null;
        velocity = null;
        serverAuthoritative = false;
        smoothedTarget = null;
        resetBlockedDetectionState();
        blockedDispatched = false;
        obstacleAheadOnSlope = false;
    }

    /**
     * Runs once per client tick BEFORE vanilla {@code Minecraft.tick()} processes input
     * or runs the player tick, so the deltaMovement we set here is the value the
     * player's physics integration consumes this tick.
     *
     * <p>The handler is intentionally a no-op outside of an active grind: target is
     * cleared on dismount via {@link #clearTarget}, and {@link BalancingPoseTracker} is
     * the canonical "this player is grinding" gate. Both checks together prevent a
     * stale target from driving motion after the server has issued a stop.
     */
    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (!BalancingPoseTracker.isBalancing(player)) {
            // Defensive: if any state survived the sync packet, make sure it can't drive
            // motion the tick after the grind ended. Mostly redundant with clearTarget()
            // firing from the sync handler / session boundary, but cheap.
            if (target != null || smoothedTarget != null) clearTarget();
            return;
        }
        Vec3 currentTarget = target;
        if (currentTarget == null) return;
        Vec3 currentVelocity = velocity;
        if (currentVelocity == null) currentVelocity = Vec3.ZERO;

        // Run the obstacle detector BEFORE we update lastPlayerPos / lastIntent — the
        // detector compares last tick's saved {intent, position} against this tick's
        // post-physics player.position(), so it has to read those before we overwrite.
        runBlockedDetection(player);

        // Cross-dim re-attach window: hard-snap to the server target every tick. The
        // server is sending the same target it is anchoring sp.position to on its side,
        // so this keeps both ends bit-identical while the new LocalPlayer is still
        // settling (no predict-correct convergence delay, no per-tick drift to lag
        // behind by RTT). Once the server collapses reattachGraceTicks to 0, subsequent
        // packets ship with serverAuthoritative=false and the predict-correct chase
        // resumes from a known-synced state — smoothedTarget = currentTarget below
        // is the seed for that.
        if (serverAuthoritative) {
            player.setPos(currentTarget.x, currentTarget.y, currentTarget.z);
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
            smoothedTarget = currentTarget;
            // Reset detector state while server is force-positioning the player. The
            // hard-snap delta is intent ≈ 0 and actual ≈ 0, which the MIN_INTENT gate
            // already short-circuits — but clearing lastPlayerPos / lastIntent here
            // forces the post-grace re-arm to begin with a fresh baseline rather than
            // a snapshot from before the cross-dim handoff started. Also drop the
            // dispatched-latch: the server's {@code handleBlockedByObstacle} gates on
            // {@code isInReattachGrace} and ignores incoming dismount packets during
            // the window, so any packet we sent right before grace started never made
            // it to {@code stop(...)} and the matching sync-back-to-client never
            // clears {@code blockedDispatched} via {@link #clearTarget}. Clear it
            // here so the detector can fire fresh post-grace if the obstacle is real.
            resetBlockedDetectionState();
            blockedDispatched = false;
            return;
        }

        if (smoothedTarget == null) {
            // First packet after grind start / reseed: seed directly so we don't ease
            // from the player's pre-grind position. The server ships velocity = ZERO on
            // the first tick, so even if we ran the extrapolate/correct loop the result
            // would be the same — but explicit seeding documents the intent.
            smoothedTarget = currentTarget;
        } else {
            // Predict: advance the local smoothed target by the server's last-known
            // per-tick velocity. In steady state with one-packet-per-tick delivery
            // this exactly matches the next target the server will ship, giving zero
            // lag.
            Vec3 predicted = smoothedTarget.add(currentVelocity);
            // Correct: pull the predicted position toward the most recently received
            // target. This is a 1st-order EMA on position only (no velocity memory),
            // so it cannot overshoot or oscillate — under sustained jitter it produces
            // a small constant offset that the next clean packet re-zeroes.
            Vec3 gap = currentTarget.subtract(predicted);
            smoothedTarget = predicted.add(gap.scale(CORRECTION_RATE));
        }

        Vec3 diff = smoothedTarget.subtract(player.position());
        double mag = diff.length();
        if (mag > MAX_STEP) {
            diff = diff.scale(MAX_STEP / mag);
        }

        // Save THIS tick's intent + pre-motion player position so the detector can read
        // them next tick. The detector then asks "did vanilla physics deliver the
        // motion we just requested?" by comparing (current player.position() −
        // lastPlayerPos) to lastIntent.
        lastIntent = diff;
        lastPlayerPos = player.position();

        player.setDeltaMovement(diff);
        // Per-tick zero-out so the post-grind fall-damage immunity window doesn't have
        // to absorb height accumulated while chasing the rail.
        player.fallDistance = 0.0F;
    }

    /**
     * Per-tick obstacle detector. Runs at the start of the client tick (before the
     * existing motion code overwrites {@code lastIntent} / {@code lastPlayerPos}). On
     * 3 consecutive blocked ticks, sends a {@link BlockedByObstaclePayload} and latches
     * {@code blockedDispatched} so we don't spam the server within the RTT window before
     * the dismount sync packet returns.
     *
     * <p>Two regimes, slope-gated to mirror {@link net.juniknytt.createrailgrinding.mixin.PlayerNoPhysicsTickMixin}:
     * <ul>
     *   <li><b>Flat ({@code |slope| ≤ NO_PHYSICS_SLOPE_THRESHOLD}):</b> {@code noPhysics}
     *       is off, vanilla collision applies, so a wall clips the requested
     *       deltaMovement. Compare last tick's intent magnitude to actual motion
     *       magnitude — if the player moved &lt; {@link #BLOCKED_RATIO} of what we
     *       asked, vanilla just clipped us against something.</li>
     *   <li><b>Slope ({@code |slope| > NO_PHYSICS_SLOPE_THRESHOLD}):</b> {@code noPhysics}
     *       is on, vanilla collision is bypassed, so the Δ-ratio check is blind to
     *       walls. Forward bb-probe instead — peek a half-to-full block ahead with the
     *       bottom of the bb trimmed (to ignore decorative supports under the rail).
     *       If the probed region overlaps solid geometry, count this tick as blocked.</li>
     * </ul>
     *
     * <p>Slope is sourced from local {@code deltaMovement} (matching the noPhysics-mixin's
     * latency-free read) rather than the L+1-stale synced value from
     * {@code RailGrindHandler.clientLocalSlope}.
     */
    private static void runBlockedDetection(LocalPlayer player) {
        // Default to "no obstacle this tick" before any early return. The slope branch
        // below reassigns to the probe result; the flat branch / early-return paths leave
        // it cleared so the mixin's bypass-suspend gate doesn't latch on stale state.
        obstacleAheadOnSlope = false;

        if (lastPlayerPos == null || lastIntent == null) return;
        double intentLen = lastIntent.length();
        if (intentLen < MIN_INTENT) {
            blockedTicks = 0;
            return;
        }

        // Slope from local deltaMovement — matches PlayerNoPhysicsTickMixin's source so
        // the detector picks the regime the noPhysics-bypass is actually in this tick.
        Vec3 dm = player.getDeltaMovement();
        double dmLen = dm.length();
        double slope = dmLen > 1e-3 ? dm.y / dmLen : 0.0;
        boolean noPhysicsRegime = Math.abs(slope) > RailGrindHandler.NO_PHYSICS_SLOPE_THRESHOLD;

        boolean tickBlocked;
        if (noPhysicsRegime) {
            // Forward bb-probe with the bottom trimmed off so rail supports don't trip
            // the check. probeDist scales with the requested motion — at full grind speed
            // (intent ≈ 2.0) we look a full block ahead; at slow speeds we still look at
            // least half a block so a stationary wall right in front of us trips on the
            // first tick we'd actually hit it.
            Vec3 forward = lastIntent.scale(1.0 / intentLen);
            double probeDist = Math.max(FORWARD_PROBE_MIN, Math.min(FORWARD_PROBE_MAX, intentLen));
            AABB pbb = player.getBoundingBox();
            AABB trimmed = new AABB(
                    pbb.minX, pbb.minY + BB_BOTTOM_TRIM, pbb.minZ,
                    pbb.maxX, pbb.maxY,                  pbb.maxZ);
            AABB probe = trimmed.move(forward.x * probeDist, forward.y * probeDist, forward.z * probeDist);
            tickBlocked = !player.level().noCollision(probe);
            // Bypass-suspend signal updated EVERY tick the probe runs, including ticks
            // after blockedDispatched has latched. Keeps vanilla collision active during
            // the dispatch-to-sync window so the player can't continue phasing while
            // we wait for the server's stop-and-sync round trip.
            obstacleAheadOnSlope = tickBlocked;
        } else {
            double actualLen = player.position().subtract(lastPlayerPos).length();
            tickBlocked = actualLen < intentLen * BLOCKED_RATIO;
        }

        if (blockedDispatched) return;

        if (tickBlocked) {
            blockedTicks++;
            if (blockedTicks >= BLOCKED_DROP_TICKS) {
                PacketDistributor.sendToServer(BlockedByObstaclePayload.INSTANCE);
                blockedDispatched = true;
                blockedTicks = 0;
            }
        } else {
            blockedTicks = 0;
        }
    }

    /**
     * True iff the slope-branch forward bb-probe overlapped solid geometry on this
     * client tick. Read by {@link net.juniknytt.createrailgrinding.mixin.PlayerNoPhysicsTickMixin}
     * to suspend the {@code noPhysics} bypass for the same tick — instant reaction to
     * a wall ahead, no waiting for the 3-tick dismount confirm or the server's sync.
     */
    public static boolean isObstacleAheadOnSlope() {
        return obstacleAheadOnSlope;
    }

    /**
     * Clears detector state but NOT {@code blockedDispatched}. Called from the
     * server-authoritative branch (cross-dim grace) where last tick's intent / position
     * baselines came from a different motion regime and shouldn't drive next tick's
     * comparison. The dispatched-latch persists so a packet in flight can't be paired
     * with a new dispatch from the same dismount intent.
     */
    private static void resetBlockedDetectionState() {
        lastPlayerPos = null;
        lastIntent = null;
        blockedTicks = 0;
        obstacleAheadOnSlope = false;
    }
}
