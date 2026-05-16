package net.juniknytt.createrailgrinding.rail;

import com.simibubi.create.Create;
import com.simibubi.create.api.contraption.train.PortalTrackProvider;
import com.simibubi.create.content.kinetics.chainConveyor.ServerChainConveyorHandler;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackGraphHelper;
import com.simibubi.create.content.trains.graph.TrackGraphLocation;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.BezierTrackPointLocation;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackMaterial;
import net.createmod.catnip.data.Couple;
import net.createmod.catnip.math.BlockFace;
import net.juniknytt.createrailgrinding.Config;
import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.client.BalancingPoseTracker;
import net.juniknytt.createrailgrinding.compat.Mods;
import net.juniknytt.createrailgrinding.compat.SableSubLevels;
import net.juniknytt.createrailgrinding.effect.ModEffects;
import net.juniknytt.createrailgrinding.network.GrindAccelInputPayload;
import net.juniknytt.createrailgrinding.network.RailGrindDebugSyncPayload;
import net.juniknytt.createrailgrinding.network.RailGrindLeanSyncPayload;
import net.juniknytt.createrailgrinding.network.RailGrindSyncPayload;
import net.juniknytt.createrailgrinding.network.RailGrindTargetPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RailGrindHandler {
    private static final Map<UUID, GrindState> ACTIVE = new ConcurrentHashMap<>();
    /**
     * Client-side mirror of the local grinding player's {@code GrindState.experiencedSlope},
     * shipped every server tick via {@link net.juniknytt.createrailgrinding.network.RailGrindTargetPayload}.
     * {@link net.juniknytt.createrailgrinding.mixin.PlayerNoPhysicsTickMixin} reads this on
     * the client and {@code ACTIVE.get(uuid).experiencedSlope} on the server, so the
     * conditional noPhysics-bypass uses the same slope value on both sides.
     *
     * <p>Only the local player's slope is synced (the target payload is sent point-to-point
     * to the grinding player, not broadcast). Remote grinding players visible to this client
     * therefore see this field as the local player's slope, which is wrong for them — but
     * their position is lerp-driven from {@code ClientboundMoveEntityPacket}, so vanilla
     * Entity.move-snag doesn't manifest visibly for them the way it does for the local
     * player whose motion is set by {@link net.juniknytt.createrailgrinding.client.RailGrindClientMotion}.
     * Accept the cross-talk; the visible bug it could cause (remote player snags on a
     * slope-support while the local player is on a flat) is hypothetical and minor.
     */
    public static volatile double clientLocalSlope = 0.0;
    // Game-tick at which post-dismount fall-damage immunity expires, per player UUID. Populated
    // by stop(), drained lazily by hasFallImmunity(). Server-side only — every stop() caller
    // (PlayerTickEvent.Post, network packet handlers, login/logout/respawn) runs on the server.
    private static final Map<UUID, Long> FALL_DAMAGE_IMMUNITY_TIME = new ConcurrentHashMap<>();
    private static final int FALL_IMMUNITY_TICKS = 25;                    // 1.25 s — short window covering the landing arc after a stopWithLaunch dismount and the immediate drop after stepping off an elevated rail. Kept brief so a player who deliberately keeps falling after the window won't escape fall damage.
    // Remaining cooldown ticks before another grind can begin, per player UUID. Set to
    // START_COOLDOWN_TICKS by stop() (every grind exit), decremented once per server tick
    // by tickCooldown() (called from ModEvents.onPlayerTick), evicted when it hits 0.
    // Read by isPlayerOnRailGrindCooldown() — returns true while > 0, blocking both
    // Networking.handleTeleport (teleport-to-rail) and railgrinding() (start the ride).
    // Server-side only.
    private static final Map<UUID, Integer> START_COOLDOWN_REMAINING = new ConcurrentHashMap<>();
    private static final int START_COOLDOWN_TICKS = 10;                   // 0.5 s — gap after grind exit before the next grind can begin. Long enough that the polling jump+sneak trigger can't re-fire the instant after a manual dismount; short enough that a deliberate re-grind feels responsive.
    // Per-player counter of consecutive ticks the player's hitbox has been overlapping any
    // CarriageContraptionEntity's bounding box. Ticked every server tick by tickTrainOverlap();
    // increments while overlapping, hard-resets to 0 (entry removed) the first tick the
    // intersection clears — so a series of momentary brush-bys can never accumulate into a
    // kick. Once the count reaches TRAIN_OVERLAP_KICK_TICKS, the player counts as "crushed":
    // any active grind is dropped from inside tickTrainOverlap, and new grind starts are
    // refused by isPlayerCrushedByTrain (gates Networking.handleTeleport, handleStartFromNearest,
    // and railgrinding(loc, ...)). The counter keeps incrementing past the threshold while
    // overlap persists so the HUD can keep displaying the live "how long stuck" value, and
    // the absence of an entry (count == 0) doubles as the canonical "no overlap this tick"
    // signal — the HUD's intersect bool is just count > 0, no separate boolean needed.
    private static final Map<UUID, Integer> TRAIN_OVERLAP_TICKS = new ConcurrentHashMap<>();
    private static final int TRAIN_OVERLAP_KICK_TICKS = 5;                // 0.25 s — kick threshold. Brief enough that getting run over by a train terminates the grind well before drag/desync sets in, while still tolerating the 1–2 ticks of overlap the AABB lookup can pick up at the start or tail of a fast pass-through.
    // Top grind speed (shift held — ~3× vanilla sprint at the default 0.84). Sourced from
    // Config.TOP_GRIND_SPEED so server admins can retune without recompiling. Wrapped in a
    // method (instead of a static final) so the speedometer overlay and sound controller pick
    // up live config changes via the same accessor. SERVER spec syncs from a dedicated server
    // during the configuration phase; client-side callers (HUD overlays) can hit this before
    // the sync packet lands, so fall back to the spec default until it's loaded.
    public static double topSpeed() {
        return Config.SERVER_SPEC.isLoaded()
                ? Config.TOP_GRIND_SPEED.get()
                : Config.TOP_GRIND_SPEED.getDefault();
    }
    private static final double CRUISE_SPEED = 0.20;
    private static final double ACCELERATION = 0.005;                     // ~0.005/tick — momentum feel: 0→CRUISE ≈ 1.3 s, 0→TOP (unboosted) ≈ 8.4 s
    // Fraction of the remaining target-gap that currentSpeed closes per tick. The old
    // linear-ramp + Math.min/max clamp put a sharp kink in the velocity curve at the
    // moment current met target — visually a snap and audibly a sudden pitch/volume
    // plateau, especially obvious at low speeds where the percentage change is large.
    // Easing exponentially toward target gives a smooth derivative throughout the
    // approach. The per-tick step is still capped at ACCELERATION (× boosts) so large
    // gaps decay at the same legacy pace; only the final converge-to-target portion
    // changes shape. 0.08 → ~50% gap closed in 9 ticks (~0.45 s), 90% in ~28 ticks.
    private static final double SPEED_EASE_RATE = 0.08;
    // Low-pass filter on the *raw* per-tick target. experiencedSlope is derived from
    // last tick's motion vector, which flickers on curves and at edge transitions; that
    // flicker translates directly to target swings between e.g. cruise and walking
    // pace, which the speed-ease term then chases — perceived as jitter at low speeds.
    // 0.18/tick is fast enough that releasing/holding sneak still feels responsive
    // (~50% in 3-4 ticks) while smoothing single-tick noise spikes.
    private static final double TARGET_SMOOTH_RATE = 0.18;
    private static final double DOWNHILL_FACTOR = 0.9;                    // top speed × (1 + |slope| · 0.9) on descents
    private static final double UPHILL_FACTOR = 1.5;                      // top speed × (1 − slope · 1.5) on ascents — steep up gets noticeably slow
    private static final double DOWNHILL_CRUISE_MIN_FRACTION = 0.75;      // un-sneaked descent target floor: at a gentle downward slope, target = 75% topSpeed. Lifts the no-shift coast well above CRUISE_SPEED so gravity carries the player meaningfully even without holding shift.
    private static final double DOWNHILL_CRUISE_MAX_FRACTION = 1.00;      // un-sneaked descent target ceiling: at a max downward slope, target = 100% topSpeed. Sneaked top can still exceed this via DOWNHILL_FACTOR, so shift remains the way to push past cruise.
    private static final double DOWNHILL_ACCEL_BOOST = 5.0;               // accel up to 5× on the steepest descents — gives downhill grinding a sneak-tier kick. The slope-based bonus is further scaled by Config.DOWNWARD_MOMENTUM_GAIN (0.1–2.0) for server-side tuning.
    private static final double CURVE_FACTOR = 0.75;                      // bezier turns trim 25%
    // Asymmetric EMA rates the per-edge curve factor (1.0 on straights, CURVE_FACTOR on
    // turns) converges toward each tick. A single symmetric rate was the obvious choice
    // first, but it bounced badly through S-bend geometry: a 5-tick straight between two
    // turns is enough at rate 0.08 to pull the smoothed factor halfway back to 1.0
    // before the next turn yanks it down again, producing an audible spike train in
    // the grind loop and a visible bounce in the HUD speedometer.
    //
    // Split rates fix it: ENTER (smoother heading toward CURVE_FACTOR, factor decreasing)
    // runs at a moderate rate so long-straight → turn deceleration still feels responsive
    // (~50% in 14 ticks, ~0.7 s); EXIT (smoother heading toward 1.0, factor increasing)
    // runs much slower (~50% in 34 ticks, ~1.7 s) so short straights inside a chained
    // turn section can't escape CURVE_FACTOR before the next turn drops the smoother
    // back. Steady-state through an S-bend hovers ~0.01 of CURVE_FACTOR with almost no
    // perceptible oscillation. Long curve → long straight recovery is intentionally
    // gradual — reads as coasting back up to full speed rather than snapping.
    //
    // Compounds with TARGET_SMOOTH_RATE downstream for a critically-damped, kink-free
    // ease; other speed inputs (slope, shift) don't go through this smoother so their
    // response stays snappy.
    private static final double CURVE_FACTOR_ENTER_RATE = 0.05;
    private static final double CURVE_FACTOR_EXIT_RATE  = 0.02;
    // Half-width (in t-space) of the spline-tangent finite difference used to derive the
    // signed curve direction stored in {@link GrindState#experiencedCurve}. 0.05 means the
    // sample brackets ±5% of the current edge's parameter range. Larger window = stronger
    // signal on gentle curves but blurs the sign through edge-internal direction reversals
    // (rare on Create bezier rails); smaller window = noisier, more representative of the
    // instantaneous tangent change.
    private static final double CURVE_SAMPLE_EPSILON = 0.05;
    // Gain on the raw cross-product of the two sampled tangents (sin of angle between
    // them) before clamping to ±1. At ±5% t-window, a Create 90° bezier produces a per-
    // sample sin ≈ 0.16; with gain 5.0 that becomes ~0.78 — gentle curves register, tight
    // 180° stacks saturate at 1.0. See computeRawExperiencedCurve for the geometry.
    private static final double CURVE_SIGNAL_GAIN = 5.0;
    // EMA rate the smoothed {@link GrindState#experiencedCurve} converges toward the raw
    // per-tick signal. Fast enough that the value tracks visible curves within ~7 ticks,
    // slow enough that single-tick spikes at edge transitions don't flip the sign briefly.
    private static final double CURVE_SMOOTH_RATE = 0.15;
    private static final double MIN_SPEED = 0.10;                         // floor at walking pace (~2 m/s) on steep climbs
    // Fluid drag. Water is the user-visible 50% reference; lava is steeper since vanilla
    // swimming through it is markedly worse than water. OTHER covers any custom mod fluid —
    // FluidType has no canonical "swim speed" property to derive from, so we treat unknowns
    // as water-like rather than guessing from motionScale. When the player straddles
    // multiple fluid types the slowest factor wins (see computeFluidMultiplier).
    private static final double WATER_FLUID_FACTOR = 0.5;
    private static final double LAVA_FLUID_FACTOR = 0.25;
    private static final double OTHER_FLUID_FACTOR = 0.5;
    // Depth strider eases the slowdown linearly from the fluid factor up to this floor at
    // _FULL_LEVEL. At level 0 the slowdown is the unmodified factor; at level 3 it caps at
    // FLOOR (only 20% slower than air). Levels above 3 don't push the floor any further.
    private static final double DEPTH_STRIDER_FLUID_FLOOR = 0.8;
    private static final int DEPTH_STRIDER_FLUID_FULL_LEVEL = 3;
    private static final double Y_OFFSET = 0.5;                           // vertical hover above rail line (collision bypassed by noPhysics)
    private static final double LATERAL_OFFSET = 1.0;                  // standard-gauge rail-bar offset from spline centerline. Side (+1/-1) is picked once at grind init from the player's pre-teleport position (GrindState.lateralSign). For Steam'n'Rails narrow/wide gauge the per-track-type offset lives in GrindState.lateralOffset; this constant is the default seed value and the standard-rail case.
    // Steam 'n' Rails Narrow / Wide gauge ResourceLocations — compared via material.trackType.id
    // so the mod can be absent without breaking class-load (the IDs simply never match anything
    // registered on the local instance, falling back to standard handling). See [[railways-gauge-tracktype-ids]].
    private static final ResourceLocation RAILWAYS_NARROW_GAUGE = ResourceLocation.fromNamespaceAndPath("railways", "narrow_gauge");
    private static final ResourceLocation RAILWAYS_WIDE_GAUGE   = ResourceLocation.fromNamespaceAndPath("railways", "wide_gauge");
    // Steam'n'Rails wildcard gauge. Universal track is intentionally compatible with every
    // bogey (see Railways' MixinCarriage#railways$isIncompatible and MixinNavigation pathfinding
    // filter), so we mirror that semantics in advanceJunction: a Universal edge always passes
    // the gauge gate regardless of the current gauge, and a player already on Universal can
    // continue onto any grindable gauge. Same soft-compat rule as the narrow/wide constants —
    // when Railways isn't installed, no edge will ever match this id.
    private static final ResourceLocation RAILWAYS_UNIVERSAL    = ResourceLocation.fromNamespaceAndPath("railways", "universal");
    // Half-block deltas off LATERAL_OFFSET: narrow rides closer to the centerline, wide rides
    // further out. Matches the visual rail-bar geometry of the narrow/wide track blocks.
    private static final double LATERAL_OFFSET_NARROW = LATERAL_OFFSET - 0.5;
    private static final double LATERAL_OFFSET_WIDE   = LATERAL_OFFSET + 0.5;
    // Extra reach added to {@link #findNearestRailLocation}'s per-candidate range cap for
    // wide-gauge rails, matching the half-block the wide-gauge rail bar sits further from
    // the spline centerline. Without this, the jump+sneak start trigger feels stingy on
    // wide gauge — the player is standing on the rail bar but the bar is +0.5 outboard of
    // where the proximity scan measures distance to.
    private static final double WIDE_GAUGE_START_RADIUS_BONUS = 0.5;
    private static final double MAX_STEP = 2.0;                           // entry-velocity carry cap (~40 m/s) — the per-tick motion cap proper now lives client-side in RailGrindClientMotion. Keep here so railgrinding()'s entry-boost still saturates at the same value the client's predict-correct chase can reach to.
    // Half-extents of the rendered snap-target box (local frame: +Z = travel-aligned tangent,
    // +X = horizontal right-of-travel, +Y = perpendicular up). Visualization-only —
    // RailGrindDebugRenderer reads these to draw the cubes. The client's predict-correct
    // chase is a continuous tracker, so these don't gate any logic.
    public static final double SNAP_BOX_HALF_W = 0.15;
    public static final double SNAP_BOX_HALF_H = 0.15;
    public static final double SNAP_BOX_HALF_L = 0.40;
    private static final double MAX_DRIFT = 20.0;                         // ANTI-CHEAT TRIPWIRE (not the primary obstacle detector). Distance from snap target above which we hard-drop — only fires on catastrophic desync or a client that's silently ignoring our motion packets. Primary obstacle detection lives in {@link net.juniknytt.createrailgrinding.client.RailGrindClientMotion#runBlockedDetection}, which sends a {@link net.juniknytt.createrailgrinding.network.BlockedByObstaclePayload} after 3 ticks of vanilla-collision clipping (or a forward bb-probe overlap on slopes). This server-side threshold is intentionally LOOSE (was 5.0 before the client detector landed, with latency-scaling up to 20.0; we now use the old cap unconditionally) so it doesn't fire on legitimate transients — high-latency MP players, EMA-settle windows, post-reattach handoff gaps — that we used to band-aid with the suppression gates below.
    /**
     * Absolute slope threshold at/above which {@link net.juniknytt.createrailgrinding.mixin.PlayerNoPhysicsTickMixin}
     * re-asserts {@code noPhysics = true} for the grinding player. Below the threshold the
     * player goes through vanilla collision: walls, ceilings, and any block in the rail's
     * path stop them — meaning grind on flat sections terminates on geometry impact instead
     * of phasing through.
     *
     * <p>The trade-off: support pillars / decorative bases under the rail only cause the
     * snag-and-stop slope bug when the player is actually moving up or down. On flat
     * sections the player is above the rail bar (Y_OFFSET) and clears those supports
     * naturally. So the bypass is gated on slope-active conditions and the rest of the
     * time the rail behaves like a normal navigable surface.
     *
     * <p>{@code 0.25} ≈ sin(14.5°) — moderate slopes and above. Adjust if tightening the
     * envelope causes false-negatives on shallow inclines that still have support snag.
     */
    public static final double NO_PHYSICS_SLOPE_THRESHOLD = 0.25;
    /** {@code |experiencedSlope|} above which a tick counts toward the Sable-only EXTREME_SLOPE drop. 0.85 ≈ sin(58°) — past anything vanilla Create tracks can produce. */
    private static final double EXTREME_SLOPE_THRESHOLD = 0.85;
    /** Consecutive over-threshold ticks needed to fire EXTREME_SLOPE. A real near-vertical ship pitch persists; entry-velocity carry and sublevel-translation spikes release within ~2 ticks. */
    private static final int EXTREME_SLOPE_DROP_TICKS = 10;
    private static final double STUCK_VELOCITY_THRESHOLD = 0.05;          // per-tick displacement (blocks) below which the player counts as "not moving" — well under MIN_SPEED so legitimate steep-climb grinding never trips it
    private static final int STUCK_DROP_TICKS = 30;                       // ANTI-CHEAT TRIPWIRE — 1.5 s of zero-velocity ticks before drop. Primary obstacle detection now runs on the client (RailGrindClientMotion#runBlockedDetection → BlockedByObstaclePayload), which fires within 3 ticks. This server-side threshold was 3 before, but at that strictness any anchor-release / MovePlayer-rejection gap during cross-dim handoff produced a false-positive (sp.position frozen → absVelocity ≈ 0 for several ticks). 30 ticks is generous enough that those transients can't trip it, while still catching a genuinely-stuck server-side player (modded yank, physics override) before they become a permanent zombie grind.
    private static final int STUCK_GRACE_TICKS = 3;                       // first 0.15 s of grind: ignore stuck (let noPhysics sync to client). Superseded for new grinds by START_GRACE_TICKS below; kept for the post-reattach settling window (tick() resets gs.totalTicks = 0 when reattach grace ends, so this gate becomes active again for the next STUCK_GRACE_TICKS).
    // Base startup grace window (in ticks) for fresh grind starts, used as the floor in
    // the latency-scaled seed: railgrinding() computes
    //   gs.startGraceTicks = START_GRACE_TICKS + (player_latency_seconds × 20)
    // so a zero-latency single-player start gets 10 ticks (0.5 s) of grace and a
    // multiplayer client at e.g. 200 ms ping gets 10 + 4 = 14 ticks. Mirrors the
    // formula the cross-dim re-attach work uses for its post-transit settling window;
    // the +20×latency term ensures the suppression covers the actual server↔client
    // round trip rather than a fixed wall-clock window that's too short for high-ping
    // clients and too long for local play.
    //
    // While > 0, suppresses two early-cancel paths that fire from latency-induced state
    // divergence at grind start:
    //   - MAX_DRIFT in applyTickMotion (server's player.position lags the client's by
    //     2 × one-way-latency ticks; gs.position advances at currentSpeed/tick from
    //     spawn; their distance crosses MAX_DRIFT within a few ticks at MAX_STEP=2.0)
    //   - stuck detection (server-side absVelocity stays ~0 until the first post-sync
    //     client position update arrives — looks identical to "actually stuck")
    // Train-overlap is intentionally NOT suppressed: starting a grind from inside a
    // carriage is a real "drop the grind" condition, not a latency artifact, so the
    // kick fires normally during startup grace. See the tickTrainOverlap comment.
    // Also defers the railgrinding() noPhysics=true server-side flag until grace ends,
    // so vanilla collision applies through the grace — keeps server-side player.position
    // physically constrained while the client EMA-converges, which bounds the drift
    // window MAX_DRIFT would otherwise see. The PlayerNoPhysicsTickMixin's slope-gated
    // re-assertion is unchanged — at init slope is 0 (no prev tick), so the mixin
    // doesn't re-assert anyway; once motion starts and a real slope is derived, the
    // mixin re-asserts independently of this grace.
    // Cross-dim re-attach uses its own grace mechanism (reattachGraceTicks, trigger-
    // based with client ack) so this latency-scaled window is NOT seeded for re-attach
    // paths — those starts ride exclusively on reattachGraceTicks.
    private static final int START_GRACE_TICKS = 10;
    // Cross-dim re-attach grace — TRIGGER-BASED, LATENCY-SCALED. The counter is seeded
    // when the re-grind starts (paths 1/2/3 + path 4 in tickPendingRegrind) to
    // REATTACH_GRACE_BASE_TICKS + (latency_ms * 30 / 50), so the timeout-max scales with
    // the player's actual ping rather than being fixed. The grace ticks down every server
    // tick like before, BUT the canonical end-of-grace event is a client→server
    // CrossDimGraceReleasePayload ack: as soon as the client confirms mc.player + mc.level
    // + chunks-at-player-position are all live, it sends the ack and the server collapses
    // the counter to REATTACH_GRACE_AFTER_ACK_TICKS (a short fixed tail for final EMA
    // settling). The MAX-base value is just the timeout failsafe in case the ack never
    // arrives (client disconnect, packet loss, chunk-load stuck) — 100 ticks (5 s) base is
    // long enough to cover the worst-case nether chunk-load on a slow client but short
    // enough that a truly stuck transit drops the grind cleanly instead of leaving the
    // player frozen in mid-air forever. The latency multiplier adds 30 extra ticks per
    // tick of one-way latency on top of the base — i.e., 100 ms ping adds 60 ticks (3 s),
    // 500 ms adds 300 ticks (15 s) — generous headroom for slow clients without affecting
    // fast clients (their ack still ends grace early).
    //
    // Why trigger-based + latency-scaled: a fixed-duration grace couldn't simultaneously
    // serve fast and slow clients — fast clients felt the unnecessary motion-pause; slow
    // clients (nether chunks still streaming) ran out of grace before they were ready and
    // were dropped by MAX_DRIFT / stuck on the first post-pause tick. The ack lets each
    // client release the grace at its own pace, and the latency-scaled timeout keeps the
    // failsafe proportional to the round-trip the ack has to travel.
    //
    // tick()'s grace branch also resets gs.totalTicks=0 when the counter hits 0 so the
    // standard STUCK_GRACE_TICKS gate covers the post-pause settling period as a second
    // layer, AND reseeds the portal-transit cooldown so a player whose snap landed inside
    // / against a portal block (e.g., nether-side rails right at the portal frame) doesn't
    // get instantly re-teleported back through the portal on the first post-grace tick.
    // Normal grind starts (right-click teleport, jump+sneak nearest-rail trigger) do NOT
    // seed this counter — they want instant motion and the standard STUCK_GRACE_TICKS
    // gate already covers their brief noPhysics-sync window.
    private static final int REATTACH_GRACE_BASE_TICKS = 100;
    private static final int REATTACH_GRACE_AFTER_ACK_TICKS = 2;
    // Post-reattach "kick suppression + soft-snap recovery" — seeded at reattach grace end
    // (NOT during grace, NOT for normal start grace). Bridges the latency-bounded gap between
    // the server-side setPos anchor releasing and the first post-grace MovePlayer C2S packet
    // arriving. Two distinct kick-paths fire false-positives in that gap:
    //   - STUCK: sp.position is frozen at the last setPos target → absVelocity ≈ 0 →
    //     STUCK_DROP_TICKS=3 fires inside the gap on any connection slower than ~150 ms RTT.
    //   - MAX_DRIFT: client's actual position has run ahead of gs.position-derived target
    //     while the client EMA-converged out of the hard-snap window → drift magnitude
    //     spikes above the dynamic threshold for a few ticks until convergence completes.
    // While the counter > 0 STUCK is short-circuited and gs.position is proactively capped
    // against running more than {@link #POST_REATTACH_DRIFT_CAP} blocks ahead of where
    // sp.position projects onto the rail edge — see field doc on
    // {@link GrindState#postReattachKickSuppressTicks} for the full mechanism. The reactive
    // MAX_DRIFT → snapGsToPlayer recovery path stays as a safety net for degenerate cases
    // (sub-1e-6 tangent length etc.) where the cap can't engage.
    // 60-tick base (3 s) covers most data-loading delays; 10-tick-per-latency-tick scaling
    // generously covers MovePlayer round-trip + chunk-streaming. At 100 ms ping: 60 + 20 =
    // 80 ticks (4 s); at 300 ms: 60 + 60 = 120 ticks (6 s).
    private static final int POST_REATTACH_KICK_SUPPRESS_BASE_TICKS = 60;
    private static final int POST_REATTACH_KICK_SUPPRESS_LATENCY_MULT = 10;
    // Maximum blocks gs.position is allowed to lead sp.position (projected onto the rail
    // edge tangent) during the post-reattach kick-suppress window. If normal motion advance
    // would push gs.position further ahead than this, the cap rolls gs.position back so the
    // target is exactly {@code POST_REATTACH_DRIFT_CAP} blocks ahead of the player. Bounds
    // drift to a value well under {@link #MAX_DRIFT} so the bailout below can never fire
    // during suppress, regardless of how long the client takes to come online after grace
    // ends. 5 blocks is enough lead for the client's predict-correct to have a meaningful
    // target to chase (the player still moves forward at currentSpeed when the client is
    // keeping up) and small enough that a fully-stalled client visibly halts on the rail
    // rather than coasting off-target. Reused as the rubber-band distance: under heavy load
    // the player effectively waits at cap-distance for sp.position to catch up.
    private static final double POST_REATTACH_DRIFT_CAP = 5.0;
    // Multiplier on the latency-extension term — extra_ticks = latency_ms * REATTACH_GRACE_LATENCY_MULT / 50.
    // 30 means "30 ticks of grace per tick of one-way latency" (50 ms = 1 tick). Picked to
    // give substantial headroom on high-latency connections: 100 ms ping ≈ 60 extra ticks,
    // 500 ms ≈ 300 extra ticks. Trigger ack will usually end grace long before timeout, so
    // this only affects the worst-case fallback duration.
    private static final int REATTACH_GRACE_LATENCY_MULT = 30;
    private static final double LAUNCH_HORIZONTAL_MULT = 2.0;             // jump-off horizontal velocity = currentSpeed × this. >1 so dismount feels like a launch rather than coasting.
    private static final double LAUNCH_VERTICAL_BASE = 0.42;              // vanilla jump strength — minimum upward kick on jump-off, even at MIN_SPEED.
    private static final double LAUNCH_VERTICAL_SCALE = 0.6;              // extra vertical velocity per unit of currentSpeed. At TOP_SPEED this stacks ~0.5 on top of the base for ≈ 9-block launches.
    // Jump-trick charge window. The client tracks how long the jump key was held while
    // grinding before release and sends the count in StopGrindPayload; the server clamps
    // here and turns it into a 0..1 ratio via computeChargeRatio. _MIN at 0 means even an
    // instant tap still produces the existing speed-based launch (ratio 0 = no charge boost),
    // _MAX at 40 (~2 s) is the cap past which holding longer doesn't add anything.
    public  static final int JUMP_TRICK_CHARGE_INPUT_TIME_MIN = 0;
    public  static final int JUMP_TRICK_CHARGE_INPUT_TIME_MAX = 20;
    // Charge multipliers stack on top of the speed-based launch. At ratio 1.0 (full charge)
    // each component is doubled — so a TOP_SPEED full-charge dismount horizontally launches at
    // ≈ 3.36 (vs. 1.68 uncharged) and adds ≈ 1.85 vertical (vs. 0.92 uncharged). Pure additive
    // bonus on the existing formula keeps the speed-only behavior identical at ratio 0.
    private static final double LAUNCH_CHARGE_HORIZONTAL_BONUS_MULT = 1.0;
    private static final double LAUNCH_CHARGE_VERTICAL_BONUS_MULT   = 1.0;
    // Rail-grind sparks: base crit particle (spark-shaped) plus a red dust tint layered on at high speed.
    private static final DustParticleOptions RED_SPARK = new DustParticleOptions(new Vector3f(1.0f, 0.0f, 0.0f), 1.0f);
    // Search radius used to find a rail at a portal exit (cross-dimension transit). Bigger
    // than the default near-rail snap (1.75) because users are free to place the matching rail
    // a few blocks off from the exit portal frame, and the "force re-grind after a through-
    // portal" guarantee should tolerate that slack. 8 blocks comfortably covers a portal frame
    // plus a couple blocks of approach on the other side; beyond that the chosen rail starts
    // to feel arbitrary, so the no-rail branch in finishCrossDimRegrind kicks in instead.
    private static final double PORTAL_REGRIND_SEARCH_RANGE = 8.0;
    // Post-transit portal cooldown (ticks) — applied after Path 1 cross-dim teleport so vanilla
    // doesn't try to send the player back through the exit portal if they're still overlapping
    // it at land time. 80 ticks (~4 s) is conservatively long but still expires before any
    // realistic re-grind back through the same portal.
    private static final int PORTAL_REGRIND_COOLDOWN_TICKS = 80;
    // Per-player post-transit gate that blocks our own instant-portal paths (mixin, Path 1,
    // graph-hop, end-node adjacency scan) from re-firing immediately after a successful
    // transit. Vanilla's portalCooldown gets refreshed to Player.getDimensionChangingDelay()
    // (10 ticks in 1.21.1) by setAsInsidePortal whenever the player keeps overlapping a portal
    // block — too short to prevent ping-pong when the exit lands the player on or beside the
    // matching portal frame. This separate window is OUR cooldown and isn't reset by vanilla.
    private static final Map<UUID, Integer> PORTAL_TRANSIT_COOLDOWN = new ConcurrentHashMap<>();
    private static final int PORTAL_TRANSIT_COOLDOWN_TICKS = 40;

    // Per-player deferred re-grind queue. Populated by enqueuePending() after any cross-dim
    // teleport; drained by tickPendingRegrind() once the player's new-dimension chunks are
    // loaded server-side AND the minimum wait has elapsed (gives the client time to process
    // the respawn packet, instantiate its new LocalPlayer, and fire ClientPlayerNetworkEvent
    // .Clone). Without the wait, the silent grind-start sync raced the respawn — half the
    // time the client cleared noPhysics in Clone *after* our sync had applied it, leaving the
    // player physics-falling through the rail bar. Both gates together guarantee the client
    // is settled before we tell it to grind again, so the EMA chase starts cleanly.
    private static final Map<UUID, PendingRegrind> PENDING_REGRIND = new ConcurrentHashMap<>();
    // Minimum ticks to hold the player in noPhysics/noGravity at the exit before checking
    // the chunk gate. 5 ticks (~0.25 s) covers the typical respawn-packet → Clone-event
    // round-trip without making the cross-dim hop feel laggy.
    private static final int PENDING_REGRIND_MIN_WAIT_TICKS = 5;
    // Hard timeout. If the chunk gate never opens (player crossed into an unloaded chunk,
    // server's chunk loader is starved, mod interference, etc.) we give up after 5 s and
    // release the physics flags so the player isn't permanently frozen in mid-air.
    private static final int PENDING_REGRIND_TIMEOUT_TICKS = 100;

    /**
     * Deferred re-grind record. {@code preserved} is non-null only for graph-hop transits
     * (path 4) where the destination edge is already chosen by Create's track graph and we
     * want to bypass the nearest-rail proximity scan to keep the original edge.
     *
     * <p>{@code anchorPos} captures the player's server-side position at the moment of
     * enqueue (i.e. immediately after the cross-dim {@code teleportTo}). {@link
     * #tickPendingRegrind} pins {@code sp.position} back to this anchor every tick while
     * waiting for the chunk gate, so the client's new LocalPlayer (which can briefly fall
     * under vanilla gravity if the noGravity SynchedEntityData entry didn't re-sync after
     * the dim hop) cannot drag server-side position downward via its MovePlayer reports.
     * Path 1/2/3's nearest-rail scan also depends on this — without the anchor, a few
     * ticks of fall can move {@code sp.position} out of {@link #PORTAL_REGRIND_SEARCH_RANGE}.
     */
    private record PendingRegrind(
            double carryVelocity,
            int ticksWaited,
            @Nullable GrindState preserved,
            Vec3 anchorPos) {}

    /**
     * Why a {@link #stop} call fired. Every cancel path declares its reason at the call site,
     * so the debug HUD can surface a human-readable last-cancel label after the fact instead of
     * forcing the user to interpret raw stuckTicks / driftMargin values.
     *
     * <p>Wire-encoded by ordinal in {@link net.juniknytt.createrailgrinding.network.RailGrindDebugSyncPayload}.
     * Adding new values is wire-compatible (older clients clamp unknown ordinals to
     * {@link #UNKNOWN}); reordering or removing values is NOT — append only.
     */
    public enum StopReason {
        USER_DISMOUNT("canceled by user"),
        END_OF_TRACK("end of track"),
        STUCK("stuck ticks too high"),
        MAX_DRIFT("drift too high"),
        DAMAGE("incoming damage"),
        BOOTS_SWAP("boots changed"),
        TELEPORT_REQUEST("teleport request"),
        CHAIN_MOUNTED("mounted chain conveyor"),
        CHAIN_HANDOFF("chain conveyor handoff"),
        AUTO_SPIN("auto-spin attack"),
        SUBLEVEL_REMOVED("sublevel removed"),
        EXTREME_SLOPE("slope too extreme"),
        TRAIN_OVERLAP("intersecting train"),
        SESSION_BOUNDARY("session boundary"),
        BLOCKED("blocked by obstacle"),
        EXTERNAL_TELEPORT("external teleport"),
        TRACK_REMOVED("track destroyed"),
        UNKNOWN("unknown");

        public final String displayName;
        StopReason(String displayName) { this.displayName = displayName; }

        /** Bounds-safe wire decode — out-of-range ordinals (newer server, older client) clamp to UNKNOWN. */
        public static StopReason fromOrdinal(int ord) {
            StopReason[] values = values();
            return (ord >= 0 && ord < values.length) ? values[ord] : UNKNOWN;
        }
    }

    /**
     * Snapshot of the last cancel: which {@link StopReason} fired and how many ticks past grace
     * end it happened. Surfaced via {@link #getLastDropHudState} and the debug HUD so the user
     * can confirm post-mortem WHICH cancel path triggered a drop without having to interpret
     * raw counters. {@code ticksSinceGraceEnded} is captured from {@link GrindState}: -1 means
     * grace was still active at drop, 0 = first post-grace tick, &gt;0 = N ticks after grace.
     */
    public record LastDropHudState(
            StopReason reason,
            int ticksSinceGraceEnded) {}

    /** Per-player snapshot map. Overwritten by every {@link #stop} call; never cleared. */
    private static final Map<UUID, LastDropHudState> LAST_DROP_HUD_STATE = new ConcurrentHashMap<>();

    private RailGrindHandler() {}

    private static final class GrindState {
        final TrackGraph graph;
        TrackNode fromNode;
        TrackNode toNode;
        TrackEdge edge;
        double position;
        double currentSpeed;
        // Low-pass-filtered targetSpeed. NaN until the first tick() seeds it from the
        // raw target — initializing here would lock in CRUISE_SPEED before the slope
        // and edge geometry of the actual grind are known, costing a few ticks of
        // perceived lag at grind start. Seeded-on-first-tick means tick 1's behavior
        // is identical to the legacy path; smoothing only kicks in tick 2 onward.
        double smoothedTarget = Double.NaN;
        // Low-pass-filtered curve factor (1.0 on straights, CURVE_FACTOR on turns).
        // Replaces the binary {@code isTurn() ? CURVE_FACTOR : 1.0} step that
        // computeTargetSpeed used to apply, which produced an audible/visible jitter
        // in the grind-loop sound and the HUD speedometer every time the player
        // crossed a straight↔turn edge boundary. Ticked once per tick() before
        // computeTargetSpeed is called (kept out of computeTargetSpeed itself so
        // the debug-HUD's read-only call doesn't double-step the smoother). NaN-
        // seeded on the first tick from the current edge so grind start doesn't
        // ease from an unrelated value.
        double smoothedCurveFactor = Double.NaN;
        int stuckTicks;      // consecutive ticks the player's per-tick displacement was below STUCK_VELOCITY_THRESHOLD
        int extremeSlopeTicks;  // consecutive ticks of |experiencedSlope| > EXTREME_SLOPE_THRESHOLD (Sable-only); resets each tick the slope is below the threshold OR start/reattach grace is active
        int totalTicks;      // ticks since grind began — used for the noPhysics-sync grace window
        int startGraceTicks; // remaining ticks of the fresh-start grace window. Seeded in railgrinding() for non-reattach starts to START_GRACE_TICKS + (player_latency_ms / 50) so the window scales with the actual server↔client round-trip; decremented every tick() until 0. While > 0 suppresses MAX_DRIFT / stuck kicks AND defers the noPhysics=true assertion (server keeps vanilla collision active so latency-driven server↔client position divergence stays bounded). Train-overlap kick is NOT suppressed — see tickTrainOverlap. NOT seeded by the constructor — cross-dim reattach paths (which use reattachGraceTicks) deliberately skip this window; only railgrinding()'s normal-start branch sets it. See START_GRACE_TICKS for the full design.
        int reattachGraceTicks;  // remaining ticks of the cross-dim server-authoritative window. CROSS-DIM ONLY: seeded by tickPendingRegrind (paths 1/2/3 + preserved-gs path 4) to REATTACH_GRACE_BASE_TICKS + latency-based extra. NOT seeded by the GrindState ctor — normal grind starts (right-click teleport, jump+sneak nearest-rail trigger) want predict-correct from the start, not server-authoritative pose updates. Decremented inside applyTickMotion every tick until 0, OR collapsed to REATTACH_GRACE_AFTER_ACK_TICKS by releaseReattachGrace() when the client confirms it's ready (CrossDimGraceReleasePayload). While > 0, applyTickMotion ships the target with serverAuthoritative=true (client hard-snaps), pins sp.position to target on the server (zero drift), and suppresses MAX_DRIFT + stuck kicks. When the counter hits 0, totalTicks is reset to 0 (gives STUCK_GRACE_TICKS=3 a fresh window for the post-snap settling tail) and the portal-transit cooldown is reseeded. NOTE: motion advance (gs.position) is gated SEPARATELY by {@link #frozenAtReattachStart}: while that flag is true the position stays static and only ACK-arrival (or grace-counter timeout) can unfreeze it. Once unfrozen, motion resumes with the pre-cross-dim carry speed for the remaining post-ACK grace tail.
        // Cross-dim re-attach: while true, gs.position is HELD STATIC (no advance loop, no
        // currentSpeed math). Server keeps pinning sp.position to that static target and
        // client keeps hard-snapping to match — the player is visibly frozen at the re-grind
        // entry point. Cleared by either: (a) CrossDimGraceReleasePayload arrival (the client
        // confirms loaded + in-control + rendered), at which point releaseReattachGrace also
        // collapses reattachGraceTicks to REATTACH_GRACE_AFTER_ACK_TICKS for the predict-
        // correct transition tail; (b) reattachGraceTicks hitting 0 as a timeout failsafe so
        // a never-acking client doesn't get stuck. Set true ONLY in tickPendingRegrind for
        // both nearest-rail and preserved-gs paths — normal grind starts skip this entirely
        // and want instant motion.
        boolean frozenAtReattachStart;
        Vec3 prevPos;        // player position at the start of the previous tick — used to derive experienced slope
        // Previous tick's outgoing target world-position. Null on the first tick after
        // start (or after a graph-hop / pending-regrind reseed). Used to compute the
        // per-tick velocity vector shipped with RailGrindTargetPayload so the client
        // can extrapolate smoothly between packet arrivals — see the velocity-hint
        // rationale on the payload class.
        Vec3 prevTarget;
        double experiencedSlope;  // motion.y / motion.length() from last tick (sin of pitch); +up / -down
        // Signed curve direction of the rail at the player's current spline position.
        // +1 = full right turn, -1 = full left turn, 0 = straight. Derived in tick() from the
        // 2D cross product of the spline tangent sampled at gs.position ± CURVE_SAMPLE_EPSILON
        // (chord-flipped to travel direction), gained by CURVE_SIGNAL_GAIN, clamped to ±1, and
        // low-pass-filtered toward that target at CURVE_SMOOTH_RATE. Currently only surfaced
        // via the debug HUD as a preparatory signal for future balancing mechanics; no
        // gameplay consumer reads it yet.
        double experiencedCurve;
        double lateralSign;  // +1 or -1, fixed for the grind: which rail bar the player is riding on. Picked at init from prePos.
        // Distance from spline centerline to the rail bar. Standard rails use LATERAL_OFFSET (1.0);
        // Steam'n'Rails narrow gauge rides 0.5 closer, wide gauge rides 0.5 further out. Resolved
        // at grind init and refreshed in advanceJunction so a player crossing between gauges
        // tracks the correct bar instead of staying locked to the entry gauge's geometry.
        double lateralOffset = LATERAL_OFFSET;
        // Track type of the edge the player is currently riding. Seeded from the entry edge in
        // railgrinding() and refreshed by advanceJunction on every successful crossing. Read by
        // advanceJunction's gauge gate to reject candidate edges whose gauge differs from this —
        // crossing onto a different gauge (narrow → standard, wide → narrow, etc.) is treated as
        // end-of-track instead of a smooth transition, matching Steam'n'Rails' own train
        // pathfinding (MixinNavigation line 310) and per-tick compatibility check
        // (MixinCarriage#railways$isIncompatible). Null means "unknown/not yet seeded" → gate
        // is bypassed; only the success path of edge.getTrackMaterial() ever sets it non-null,
        // so an unexpected null can't trap a player mid-grind.
        @Nullable TrackMaterial.TrackType railTrackType;
        int steerSign;       // -1 = left, 0 = none, +1 = right. Synced from the local player via SteerInputPayload (sent only when the value flips). advanceJunction reads this as targetDot for the same lateral-projection algorithm Create's TravellingPoint.steer uses on player-controlled trains.
        byte accelInputMode = GrindAccelInputPayload.VANILLA;  // VANILLA (server polls isShiftKeyDown) / OVERRIDE_OFF / OVERRIDE_ON. Synced from the local player via GrindAccelInputPayload (sent only when the value flips). The OVERRIDE_* states make the override-key path independent of shift, so a player accelerating via the override key gets it even though Minecraft sees no sneak input.
        boolean collidingWithTrain;  // set by tickTrainOverlap each server tick: true iff the player's bounding box intersects any CarriageContraptionEntity's bounding box this tick. Surfaced via GrindDebugInfo for the debug HUD; the same per-tick overlap also feeds the TRAIN_OVERLAP_TICKS counter that drives the kick / start-prevention gates.
        // Sable sub-level the rail belongs to, or null when grinding on a parent-world rail.
        // The SubLevelHandle record itself has no Sable-typed fields (it wraps a plain Object
        // and accesses Sable through cached reflection in compat/SableSubLevels), so loading
        // GrindState is safe even when Sable isn't installed. The field is only ever set non-
        // null inside a Mods.SABLE.runIfInstalled(...) branch, so without Sable it stays null
        // and the worldPos / tangent / launch code paths fall through to the original logic.
        @Nullable SableSubLevels.SubLevelHandle subLevel;
        // Diagnostic counter: -1 while either grace counter is still > 0 (grace active), 0 on the
        // first tick BOTH counters are 0, then increments by 1 per tick. Surfaced via
        // GrindDebugInfo + LAST_DROP_HUD_STATE so the user can correlate a drop with how long
        // ago grace expired ("drop at ticksSinceGraceEnded=0" → cancel fired the moment grace
        // released; "drop at ticksSinceGraceEnded=N" → unrelated to grace boundary).
        int ticksSinceGraceEnded = -1;
        // Latency-scaled "kick suppression + proactive drift cap" seeded the moment reattach
        // grace fully ends. Bridges the gap between the server unsetting its setPos anchor
        // and the first MovePlayer C2S packet flowing back from the (post-grace, no-longer-
        // rejected) client. While > 0:
        //   - STUCK detection is short-circuited (sp.position is frozen in the gap → absVelocity
        //     reads ~0 → false-positive STUCK fires within 3 ticks).
        //   - gs.position is proactively capped against running more than POST_REATTACH_DRIFT_CAP
        //     blocks ahead of where sp.position projects onto the rail edge — when the gap
        //     keeps sp.position frozen, the cap rubber-bands the rail target to wait until
        //     the client's MovePlayer reports start flowing. Drift can't reach MAX_DRIFT so
        //     the catastrophic-desync bailout structurally cannot fire during suppress.
        //   - As a safety net for the degenerate-tangent fallthrough (sub-1e-6 tangent length
        //     on rotated-sublevel edges that collapse when projected), the reactive
        //     snapGsToPlayer recovery still runs in the MAX_DRIFT branch — projects player
        //     onto the rail, advances gs.position to match, setPos onto the rail bar.
        // Decremented per tick at the top of applyTickMotion so all gates see consistent
        // state. Normal grind starts never seed this — they ride MovePlayer packets the whole
        // way through, no handoff gap exists.
        int postReattachKickSuppressTicks = 0;

        GrindState(TrackGraph graph, TrackNode fromNode, TrackNode toNode, TrackEdge edge, double position) {
            this.graph = graph;
            this.fromNode = fromNode;
            this.toNode = toNode;
            this.edge = edge;
            this.position = position;
            this.currentSpeed = CRUISE_SPEED * Config.CRUISE_GRIND_SPEED.get();  // launch at sprint pace, not from a dead stop
            this.lateralSign = 1.0;            // overwritten by railgrinding once prePos is known
            // reattachGraceTicks intentionally NOT seeded here. Normal grind starts (right-
            // click teleport, jump+sneak nearest-rail trigger) build a GrindState via
            // railgrinding() and want instant motion — pausing them feels broken, and the
            // STUCK_GRACE_TICKS gate already covers the brief noPhysics-sync window on
            // those paths. Only cross-dim re-attach (tickPendingRegrind paths 1/2/3 and
            // the preserved-gs branch) sets this counter; see REATTACH_GRACE_BASE_TICKS.
        }
    }

    /**
     * Updates the player's steer input (-1 left, 0 none, +1 right). No-op when the player
     * isn't grinding. Called from the SteerInputPayload handler on the server.
     *
     * <p>When the value actually changes, also broadcasts a {@link RailGrindLeanSyncPayload}
     * to all observers (tracking entities + self) so remote clients can drive the lean visual
     * — model tilt and optional camera roll — for any grinding player they can see. Edge-
     * triggered to match the {@link net.juniknytt.createrailgrinding.network.SteerInputPayload}
     * cadence; straight-line grinding produces zero broadcast traffic.
     */
    public static void setSteerInput(Player player, int steerSign) {
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs == null) return;
        int clamped = Math.max(-1, Math.min(1, steerSign));
        if (clamped == gs.steerSign) return;
        gs.steerSign = clamped;
        if (player instanceof ServerPlayer sp) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                sp, new RailGrindLeanSyncPayload(sp.getUUID(), (byte) clamped));
        }
    }

    /**
     * Updates the player's accelerate-input source. No-op when the player isn't grinding.
     * Mode comes from {@link GrindAccelInputPayload}; values outside the known constants are
     * coerced to {@link GrindAccelInputPayload#VANILLA} so a malicious or stale client can't
     * leave the state in an undefined-accelerating mode.
     */
    public static void setAccelInputMode(Player player, byte mode) {
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs == null) return;
        if (mode == GrindAccelInputPayload.VANILLA
                || mode == GrindAccelInputPayload.OVERRIDE_OFF
                || mode == GrindAccelInputPayload.OVERRIDE_ON) {
            gs.accelInputMode = mode;
        } else {
            gs.accelInputMode = GrindAccelInputPayload.VANILLA;
        }
    }

    /**
     * Latency-scaled re-attach grace duration. Returns {@link #REATTACH_GRACE_BASE_TICKS}
     * plus {@code (latency_ms × REATTACH_GRACE_LATENCY_MULT / 50)} extra ticks — so each
     * tick of one-way client latency adds {@link #REATTACH_GRACE_LATENCY_MULT} ticks of
     * grace timeout. For non-{@link ServerPlayer} or unknown-latency callers (latency &lt; 0),
     * the latency term is treated as zero and the base alone applies. Bounds-safe: a
     * disconnected client reporting negative latency can't shrink the window below the base.
     */
    private static int latencyScaledReattachGrace(Player player) {
        int latencyMs = 0;
        if (player instanceof ServerPlayer sp && sp.connection != null) {
            int reported = sp.connection.latency();
            if (reported > 0) latencyMs = reported;
        }
        return REATTACH_GRACE_BASE_TICKS + (latencyMs * REATTACH_GRACE_LATENCY_MULT) / 50;
    }

    /**
     * Latency-scaled post-reattach kick-suppression duration (covers both STUCK suppression
     * and MAX_DRIFT soft-snap recovery). Returns
     * {@link #POST_REATTACH_KICK_SUPPRESS_BASE_TICKS} plus
     * {@code (latency_ms × POST_REATTACH_KICK_SUPPRESS_LATENCY_MULT / 50)} extra ticks —
     * the multiplier scales the suppression with round-trip latency since the gap being
     * bridged is exactly the time for a post-grace MovePlayer C2S packet to make it back.
     * Bounds-safe in the same way as {@link #latencyScaledReattachGrace}.
     */
    private static int latencyScaledPostReattachKickSuppress(Player player) {
        int latencyMs = 0;
        if (player instanceof ServerPlayer sp && sp.connection != null) {
            int reported = sp.connection.latency();
            if (reported > 0) latencyMs = reported;
        }
        return POST_REATTACH_KICK_SUPPRESS_BASE_TICKS + (latencyMs * POST_REATTACH_KICK_SUPPRESS_LATENCY_MULT) / 50;
    }

    /**
     * Client → server ack: the client has finished settling into the destination dimension
     * (mc.player + mc.level + chunks-at-player-position all live, loading screen dismissed)
     * and is ready for motion to resume. Clears {@code gs.frozenAtReattachStart} so the next
     * {@link #tick} runs the position-advance loop, and collapses {@code gs.reattachGraceTicks}
     * to {@link #REATTACH_GRACE_AFTER_ACK_TICKS} so the trigger-based grace ends in a small
     * final hard-snap tail rather than waiting out the full latency-scaled timeout.
     *
     * <p>Idempotent and tamper-safe: only ever LOWERS the counter, never raises it. A spurious
     * ack from a malicious or buggy client can't extend grace beyond the natural countdown,
     * and a repeated ack after the grace has already collapsed is a no-op (the {@code >} check
     * doesn't re-trigger). No-op when the player isn't grinding or has no active grace window
     * — covers acks that arrive after the grind ended for any reason.
     */
    public static void releaseReattachGrace(Player player) {
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs == null) return;
        gs.frozenAtReattachStart = false;
        if (gs.reattachGraceTicks > REATTACH_GRACE_AFTER_ACK_TICKS) {
            gs.reattachGraceTicks = REATTACH_GRACE_AFTER_ACK_TICKS;
        }
    }

    /**
     * True while the player is in the cross-dim re-attach grace window — gs has been
     * re-attached on the destination side, the silent grind-start sync has shipped, and
     * the server is holding gs.position still until the client confirms readiness (or
     * the MAX timeout fires). Acts as the canonical "this player is mid-transit" gate:
     * damage suppression in {@code ModEvents.onIncomingDamage}, the post-transit cooldown
     * union in {@link #isOnPostPortalTransitCooldown}, and the train-overlap kick all
     * consult this. Returns false for non-grinding players and for grinding players whose
     * grace counter has already expired.
     */
    public static boolean isInReattachGrace(Player player) {
        GrindState gs = ACTIVE.get(player.getUUID());
        return gs != null && gs.reattachGraceTicks > 0;
    }

    /**
     * True while the fresh-start grace window is open — i.e. {@code gs.startGraceTicks &gt; 0}
     * for a grinding player. Returns false for non-grinding players and for grinding players
     * past their grace window. Exposed for external kick-suppression gates that want to
     * respect the audit-driven startup window; the train-overlap path deliberately does NOT
     * consult this (see its comment) because a player spawning inside a carriage is a real
     * crush condition, not a latency artifact. See {@link #START_GRACE_TICKS} for the rationale.
     */
    public static boolean isInStartGrace(Player player) {
        GrindState gs = ACTIVE.get(player.getUUID());
        return gs != null && gs.startGraceTicks > 0;
    }

    /**
     * Whether the player is currently asking the grind logic to accelerate (= the historical
     * "shift held" path). When the client has sent an OVERRIDE_* mode, the override key state
     * wins regardless of the actual shift state — required so a player using the override
     * doesn't accidentally accelerate by sneaking, and conversely so the override key alone
     * triggers acceleration even though no sneak is registered.
     */
    private static boolean isAcceleratingForGrind(Player player, GrindState gs) {
        return switch (gs.accelInputMode) {
            case GrindAccelInputPayload.OVERRIDE_ON -> true;
            case GrindAccelInputPayload.OVERRIDE_OFF -> false;
            default -> player.isShiftKeyDown();
        };
    }

    public static boolean railgrinding(Player player, BlockPos trackPos, Vec3 prePos, double entryVelocity) {
        Level level = player.level();
        BlockState state = level.getBlockState(trackPos);
        if (!(state.getBlock() instanceof ITrackBlock track)) return false;
        if (!isGrindableMaterial(track.getMaterial())) return false;

        List<Vec3> axes = track.getTrackAxes(level, trackPos, state);
        if (axes.isEmpty()) return false;

        TrackGraphLocation loc = null;
        outer:
        for (Vec3 axis : axes) {
            for (Direction.AxisDirection dir : Direction.AxisDirection.values()) {
                loc = TrackGraphHelper.getGraphLocationAt(level, trackPos, dir, axis);
                if (loc != null) break outer;
            }
        }
        if (loc == null) return false;
        return railgrinding(player, loc, prePos, entryVelocity);
    }

    /**
     * Init the grind from a precomputed graph location. Lets bezier-curve clicks reuse the same
     * setup path as plain track-block clicks — Networking#handleTeleport resolves curve hits via
     * TrackGraphHelper.getBezierGraphLocationAt and hands the result here, so the grind starts
     * at the exact spot on the curve the player clicked instead of snapping to an endpoint.
     *
     * <p>Convenience overload for parent-world rails (the common path). Delegates to the
     * sublevel-aware overload with a null handle.
     */
    public static boolean railgrinding(Player player, TrackGraphLocation loc, Vec3 prePos, double entryVelocity) {
        return railgrinding(player, loc, prePos, entryVelocity, null);
    }

    /**
     * Sub-level-aware version of {@link #railgrinding(Player, TrackGraphLocation, Vec3, double)}.
     * When {@code subLevel} is non-null, the spline data {@code loc} references is in the
     * sublevel's local frame: {@code prePos} is transformed to that frame before the
     * lateral-side dot product, and {@link #worldPos(GrindState)} (called inside this method
     * to position the player) routes the spawn position back through the pose so the player
     * lands at the rail's current world position rather than its local one.
     */
    public static boolean railgrinding(Player player, TrackGraphLocation loc, Vec3 prePos, double entryVelocity,
                                       @Nullable SableSubLevels.SubLevelHandle subLevel) {
        // Both railgrinding() overloads converge here, so this is the single defensive gate
        // for the post-dismount cooldown. Networking.handleTeleport also checks earlier, so
        // the teleport itself is suppressed during cooldown, not just the grind init.
        if (isPlayerOnRailGrindCooldown(player)) return false;
        // Same convergence-gate logic for the train-crush check: refuse to start a grind
        // while the player has been overlapping a carriage long enough to count as crushed.
        // The network handlers also check earlier (so we don't waste a teleport/state-setup
        // pass for a request that would fail here), but this is the canonical block.
        if (isPlayerCrushedByTrain(player)) return false;
        // Riding a mob, boat, minecart, or Create seat is mutually exclusive with grinding —
        // the grind drives the player's position via setDeltaMovement on a noPhysics body,
        // and that fights the vehicle's own pose update. Refuse here so neither the polling
        // jump+sneak trigger nor the right-click teleport can spin up a grind on a passenger.
        if (player.isPassenger()) return false;

        TrackGraph graph = loc.graph;
        Couple<TrackNode> nodes = loc.edge.map(graph::locateNode);
        TrackNode first = nodes.getFirst();
        TrackNode second = nodes.getSecond();
        if (first == null || second == null) return false;

        TrackEdge forwardEdge = graph.getConnectionsFrom(first).get(second);
        if (forwardEdge == null) return false;

        // Defense-in-depth material gate. Three caller paths converge on this overload:
        //   1. Plain-block right-click via the BlockPos overload above (already gated).
        //   2. Bezier-curve right-click via Networking.handleTeleport calling
        //      TrackGraphHelper.getBezierGraphLocationAt — no upstream filter.
        //   3. Polling jump+sneak via handleStartFromNearest → findNearestRailLocation —
        //      the scanLevelForRails internals filter, but the convergence point is here.
        // A non-grindable material slipping through path 2 (or any future caller that
        // builds a TrackGraphLocation outside the proximity scan) would start a grind on
        // e.g. a wide-gauge phantom rail. Re-checking the resolved edge's material here
        // guarantees the grindable-rail rule holds at the single canonical entry point
        // rather than relying on every caller to remember.
        if (!isGrindableMaterial(forwardEdge.getTrackMaterial())) return false;

        Vec3 chord = second.getLocation().getLocation().subtract(first.getLocation().getLocation());
        // Node locations are stored in the sublevel's local frame for sublevel grinds, but
        // player.getLookAngle() is always world-space. Rotate the chord into world space so the
        // dot product picks the correct edge end regardless of the sublevel's pose — without
        // this a sublevel rotated relative to the parent world (e.g., a ship facing the opposite
        // direction) inverts the sign and the player starts grinding the wrong way. Parent-world
        // grinds skip the transform since chord is already in world space.
        Vec3 chordForFacing = subLevel == null ? chord : subLevel.rotateNormalToWorld(chord);
        boolean forward = player.getLookAngle().dot(chordForFacing) >= 0;

        TrackEdge edge;
        if (forward) {
            edge = forwardEdge;
        } else {
            edge = graph.getConnectionsFrom(second).get(first);
            if (edge == null) {
                // Graph doesn't store the reverse direction for this edge — fall back to forward
                // so the player can still grind even when looking against the rail's natural axis.
                edge = forwardEdge;
                forward = true;
            }
        }

        TrackNode fromNode = forward ? first : second;
        TrackNode toNode = forward ? second : first;
        double position = forward ? loc.position : edge.getLength() - loc.position;

        GrindState gs = new GrindState(graph, fromNode, toNode, edge, position);
        // Bind the sublevel handle (null for parent-world grinds) before any worldPos() call
        // so every position computed during init goes through the pose transform.
        gs.subLevel = subLevel;
        // Sonic Wind boosts the cruise-speed seed before the entry-velocity carry, so a
        // boosted player snapping onto a rail starts above the unboosted cruise floor.
        gs.currentSpeed *= ModEffects.sonicWindMultiplier(player);
        // Carry the player's pre-grind momentum into the grind, capped at MAX_STEP rather
        // than TOP_SPEED. The cap is what bounds how aggressively the client's EMA chase
        // has to ramp up on tick 1 — letting currentSpeed start above TOP_SPEED makes the
        // entry boost *visible* (decays smoothly down to TOP_SPEED via the accel/decel
        // logic in tick()), giving a speed-bleed feel after a sprint or fall onto the rail.
        gs.currentSpeed = Math.min(gs.currentSpeed + entryVelocity, MAX_STEP);

        // Pick which rail bar the player rides on. Dot the player's pre-teleport position
        // (relative to the spawn spline point) with the right-of-travel perpendicular: positive
        // → right side (+1), negative → left side (-1). Locked for the rest of the grind so
        // they don't snap from one rail bar to the other when the spline tangent rotates.
        // For sublevel grinds, splineSpawn and dirSpawn are sublevel-local — so prePos must
        // be transformed into the same frame for the dot product to pick the correct side.
        double edgeLenForSpawn = edge.getLength();
        double tSpawn = edgeLenForSpawn <= 0 ? 0 : Math.min(1.0, Math.max(0.0, position / edgeLenForSpawn));
        Vec3 splineSpawn = edge.getPosition(graph, tSpawn);
        Vec3 dirSpawn = sampleTangent(graph, edge, tSpawn);
        Vec3 spawnChord = toNode.getLocation().getLocation().subtract(fromNode.getLocation().getLocation());
        if (dirSpawn.x * spawnChord.x + dirSpawn.z * spawnChord.z < 0) dirSpawn = dirSpawn.scale(-1);
        double horizLenSpawn = Math.sqrt(dirSpawn.x * dirSpawn.x + dirSpawn.z * dirSpawn.z);
        if (horizLenSpawn > 1e-6) {
            Vec3 prePosForSide = subLevel == null ? prePos : subLevel.toLocal(prePos);
            double rxSpawn = -dirSpawn.z / horizLenSpawn;
            double rzSpawn =  dirSpawn.x / horizLenSpawn;
            double sideDot = rxSpawn * (prePosForSide.x - splineSpawn.x) + rzSpawn * (prePosForSide.z - splineSpawn.z);
            gs.lateralSign = sideDot >= 0 ? +1.0 : -1.0;
        }

        // Pick the gauge-appropriate rail-bar offset. Hands the resolver several candidate
        // probe positions (splineSpawn first — the player's actual centerline position on the
        // rail — falling back to edge midpoint and both endpoints) so the gauge detection holds
        // up at 1-block rails and chain endpoints, where a node-only probe lands past the rail.
        // The junction crossing in advanceJunction re-resolves on each new edge so mid-grind
        // gauge transitions adjust the bar offset to match.
        gs.lateralOffset = resolveLateralOffset(player.level(), graph, edge, splineSpawn, fromNode, toNode, subLevel);
        // Seed the gauge identity from the entry edge for advanceJunction's gauge gate. We pull
        // the type directly off the edge (vs. block-probing like the lateral-offset resolver
        // does) because TrackEdge#getTrackMaterial is the authoritative source — same API
        // Steam'n'Rails uses for handcart placement and train compatibility checks.
        gs.railTrackType = trackTypeOf(edge);

        ACTIVE.put(player.getUUID(), gs);
        // Arm the startup grace window. Cross-dim re-attach paths (paths 1/2/3/4 in
        // tickPendingRegrind) clear this back to 0 after railgrinding() returns so they
        // ride exclusively on reattachGraceTicks — see START_GRACE_TICKS for the gate
        // semantics and audit rationale.
        // Latency-scaled startup grace: base START_GRACE_TICKS + one tick per 50 ms of
        // server↔client round-trip latency, so the suppression actually covers the
        // round trip rather than a fixed wall-clock window that's too short for high-
        // ping clients. Mirrors the formula the cross-dim re-attach work uses for its
        // post-transit settling window. ServerPlayer.connection.latency() returns ms;
        // divide by 50 (ms per tick at 20 TPS) for the tick count. Non-ServerPlayer
        // grinders (rare — fake-player wrappers) get the base value only.
        int latencyTicks = 0;
        if (player instanceof ServerPlayer sp) {
            latencyTicks = sp.connection.latency() / 50;
        }
        gs.startGraceTicks = START_GRACE_TICKS + latencyTicks;
        // Creative flight + grind state collide and leave the player stuck flying after stop.
        if (player.getAbilities().flying) {
            player.getAbilities().flying = false;
            if (player instanceof ServerPlayer sp) sp.onUpdateAbilities();
        }
        // Elytra (fall-flying) overlaps the grind controller — gliding velocity fights the
        // per-tick snap and the player ends up doing the elytra pose on the rail. Kick them
        // out before we start driving motion ourselves. stopFallFlying() toggles flag 7
        // true→false to force the entity-data sync; only call it when actually fall-flying so
        // we don't spam shared-flag packets.
        if (player.isFallFlying()) player.stopFallFlying();
        player.setNoGravity(true);
        // noPhysics deliberately left as-is during the startup grace window. Vanilla
        // Player.tick() resets noPhysics = isSpectator() each tick, and the
        // PlayerNoPhysicsTickMixin only re-asserts when slope > NO_PHYSICS_SLOPE_THRESHOLD
        // (which is 0 on tick 0 since prevPos is null). So collision stays active server-
        // side through the grace, keeping player.position physically constrained while
        // the client catches up — see the comment block on START_GRACE_TICKS. After the
        // grace expires tick() sets noPhysics = true once so slope-based bypass behaves
        // identically to the pre-grace design.

        Vec3 spawn = worldPos(gs).add(0, Y_OFFSET, 0);
        player.setPos(spawn.x, spawn.y, spawn.z);
        // setPos only moves the server-side player. Sync the client too so it doesn't
        // start the grind from wherever Networking.handleTeleport's teleportTo landed.
        if (player instanceof ServerPlayer sp) {
            sp.connection.teleport(spawn.x, spawn.y, spawn.z, sp.getYRot(), sp.getXRot());
        }
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;

        syncPose(player, true);
        // Seed the client's EMA target so the very first tick after init has something to
        // chase. Without this the client's RailGrindClientMotion handler runs for up to
        // one tick with no target, leaving the player stationary at the spawn position
        // until the next server tick's payload arrives. Slope is 0 at init — no previous
        // motion to derive it from. The PlayerNoPhysicsTickMixin therefore won't re-assert
        // noPhysics on tick 0, but the spawn position is above the rail bar so the rail
        // block's own collision shape clears the player; only adjacent geometry can snag,
        // which is the rail-meets-wall mount failure we want to surface (not suppress).
        // Velocity = ZERO on the very first packet: the client's predict-correct chase
        // seeds smoothedTarget directly from this position with no extrapolation step,
        // so an inaccurate velocity here would just be ignored anyway. Normal grind starts
        // don't seed reattachGraceTicks so the payload ships with authoritative=false.
        sendTargetToPlayer(player, spawn, Vec3.ZERO, 0.0, false);
        return true;
    }

    /**
     * End the player's active grind. {@code reason} is recorded in {@link #LAST_DROP_HUD_STATE}
     * so the debug HUD can show what triggered the cancel after the fact — every call site must
     * pass a specific reason rather than defaulting to {@link StopReason#UNKNOWN}. No-op when
     * the player isn't grinding and has no pending regrind queued.
     */
    public static void stop(Player player, StopReason reason) {
        // Snapshot the cancel reason + post-grace tick count BEFORE removing the GrindState
        // so the debug HUD can render a "Last cancel:" line after the drop.
        GrindState gsForSnapshot = ACTIVE.get(player.getUUID());
        int ticksSinceGraceEnded = gsForSnapshot != null ? gsForSnapshot.ticksSinceGraceEnded : -1;
        LAST_DROP_HUD_STATE.put(player.getUUID(), new LastDropHudState(reason, ticksSinceGraceEnded));
        boolean wasActive = ACTIVE.remove(player.getUUID()) != null;
        // Also drain the pending-regrind queue: damage / logout / respawn / any explicit
        // dismount path should abort an in-flight cross-dim re-grind too, otherwise the
        // pending tick handler would still try to silently re-mount the player after the
        // event that asked us to stop.
        boolean wasPending = PENDING_REGRIND.remove(player.getUUID()) != null;
        if (!wasActive && !wasPending) return;
        player.setNoGravity(false);
        player.noPhysics = false;
        FALL_DAMAGE_IMMUNITY_TIME.put(player.getUUID(), player.level().getGameTime() + FALL_IMMUNITY_TICKS);
        // Stamp the post-grind cooldown. Decremented every server tick by tickCooldown();
        // blocks Networking.handleTeleport (no teleport-onto-rail) and railgrinding() (no
        // grind start) while the counter is > 0.
        START_COOLDOWN_REMAINING.put(player.getUUID(), START_COOLDOWN_TICKS);
        syncPose(player, false);
    }

    /**
     * Returns the most recent {@link LastDropHudState} snapshot for {@code player}, or null if
     * no drop has been recorded for them this session. Used by the debug HUD to render a
     * "Last cancel:" section that lets the user verify which cancel path triggered after a drop.
     */
    @Nullable
    public static LastDropHudState getLastDropHudState(Player player) {
        return LAST_DROP_HUD_STATE.get(player.getUUID());
    }

    /**
     * True while the player's post-transit cooldown is still active. Set to
     * {@link #PORTAL_TRANSIT_COOLDOWN_TICKS} by {@link #seedPortalTransitCooldown} after any
     * mod-driven portal transit; decremented each server tick in
     * {@link #tickPortalTransitCooldown}; read by the {@code PortalProcessorInstantMixin} to
     * skip the instant-teleport short-circuit, by {@link #tryPortalTransit} to skip Path-1
     * detection, and by the graph-hop / end-node-adjacency branches in {@link #tick} to skip
     * their own teleports. Effectively: once we transit, no further mod-driven transit fires
     * for the cooldown window — long enough for the player's rail motion to carry them past
     * the exit portal's AABB and break the ping-pong loop.
     */
    public static boolean isOnPostPortalTransitCooldown(Player player) {
        // Treat the cross-dim re-attach grace as part of the cooldown union. The PORTAL_TRANSIT_COOLDOWN
        // counter is fixed at 40 ticks while the grace can hold up to 60 (and waits for the client ack);
        // without this union, ticks 41-60 of grace would have an expired transit cooldown and the mixin
        // / tryPortalTransit / tryPortalTransitFromNode could re-fire if the player's bbox is still
        // anywhere near a portal block. Folding grace into the cooldown predicate gives every consumer
        // a single "this player is mid-transit, leave them alone" gate.
        if (isInReattachGrace(player)) return true;
        Integer remaining = PORTAL_TRANSIT_COOLDOWN.get(player.getUUID());
        return remaining != null && remaining > 0;
    }

    /** Seeds the post-transit cooldown to its full duration. Called from each transit path. */
    public static void seedPortalTransitCooldown(Player player) {
        PORTAL_TRANSIT_COOLDOWN.put(player.getUUID(), PORTAL_TRANSIT_COOLDOWN_TICKS);
    }

    /**
     * Stage a deferred cross-dim re-grind for {@code sp}. Sets the physics-disable flags so
     * the player hovers in place at the exit point, seeds the portal-transit cooldown so
     * we don't ping-pong back through, and stamps the queue entry that
     * {@link #tickPendingRegrind} drains once the chunks are loaded and the minimum wait
     * has elapsed.
     *
     * <p>Called from {@link #finishCrossDimRegrind} (paths 1/2/3) with {@code preserved =
     * null} so the deferred start uses nearest-rail proximity, and from
     * {@link #teleportThroughGraphHop} (path 4) with the original {@link GrindState} so the
     * deferred start re-attaches to the exact edge Create's graph picked rather than the
     * nearest-rail proximity scan.
     */
    public static void enqueuePending(ServerPlayer sp, double carryVelocity, @Nullable GrindState preserved) {
        seedPortalTransitCooldown(sp);
        // Force the noGravity SynchedEntityData entry to be flushed to the client. The
        // straightforward setNoGravity(true) is a no-op when the field was already true
        // (which it was — we were grinding on the source side), so SynchedEntityData
        // treats the write as clean and never queues a ClientboundSetEntityDataPacket.
        // After the cross-dim teleport the client's new LocalPlayer is built with default
        // noGravity=false; without a re-sync it falls under vanilla gravity for the pending
        // window, sends MovePlayer packets reflecting the fall, and drags server-side
        // sp.position down with it. Flipping false→true makes the diff fresh so the packet
        // ships and the new LocalPlayer inherits noGravity=true the moment it spawns.
        sp.setNoGravity(false);
        sp.setNoGravity(true);
        sp.noPhysics = true;
        sp.fallDistance = 0.0F;
        sp.setDeltaMovement(Vec3.ZERO);
        // Snapshot the post-teleport position. tickPendingRegrind force-anchors here every
        // tick while waiting, so server-side drift during the pending window is bounded to
        // a single tick (the one between vanilla physics and our re-anchor).
        Vec3 anchor = sp.position();
        PENDING_REGRIND.put(sp.getUUID(), new PendingRegrind(carryVelocity, 0, preserved, anchor));
    }

    /**
     * Drain the pending re-grind queue for {@code player}. Called every server tick from
     * {@link net.juniknytt.createrailgrinding.event.ModEvents#onPlayerTick}; cheap when the
     * queue entry is absent (single map lookup, early return).
     *
     * <p>Starts the actual grind once two gates pass:
     * <ul>
     *   <li><b>Min-wait</b>: at least {@link #PENDING_REGRIND_MIN_WAIT_TICKS} have elapsed
     *       since the enqueue. Covers the respawn-packet → Clone-event round-trip on the
     *       client so the new LocalPlayer exists when the sync packet lands.</li>
     *   <li><b>Chunk-loaded</b>: the player's block position has a fully-loaded chunk
     *       server-side. If the server doesn't have the chunk, the client doesn't either,
     *       and starting the grind would just race against ongoing chunk loading.</li>
     * </ul>
     * On {@link #PENDING_REGRIND_TIMEOUT_TICKS} elapsed without both gates passing, gives up
     * and releases the physics flags so the player can fall normally — better than freezing
     * them mid-air forever.
     */
    public static void tickPendingRegrind(Player player) {
        if (!(player instanceof ServerPlayer sp)) return;
        PendingRegrind pending = PENDING_REGRIND.get(sp.getUUID());
        if (pending == null) return;

        int waited = pending.ticksWaited() + 1;

        // Hard timeout. Release the flags and drop the pending entry — better than freezing
        // the player in mid-air indefinitely if the chunk gate never opens.
        if (waited >= PENDING_REGRIND_TIMEOUT_TICKS) {
            PENDING_REGRIND.remove(sp.getUUID());
            cleanupAbandonedPending(sp);
            return;
        }

        // Re-anchor the player to the teleport landing position every tick. Without this,
        // the client's new LocalPlayer (possibly with stale noGravity=false until its
        // SynchedEntityData entry re-syncs) sends MovePlayer reports reflecting a vanilla
        // gravity fall, and the server's handleMovePlayer faithfully updates sp.position
        // downward — which then becomes the search center for the nearest-rail scan in the
        // path 1/2/3 finishing branch, or the spawn input for any debug HUD. Re-anchoring
        // each tick bounds the drift to at most one tick of vanilla physics. Also re-asserts
        // noGravity / noPhysics flags defensively in case anything else flipped them.
        sp.setPos(pending.anchorPos().x, pending.anchorPos().y, pending.anchorPos().z);
        sp.setDeltaMovement(Vec3.ZERO);
        sp.fallDistance = 0.0F;
        if (!sp.isNoGravity()) sp.setNoGravity(true);
        sp.noPhysics = true;

        boolean minWaitReached = waited >= PENDING_REGRIND_MIN_WAIT_TICKS;
        boolean chunkReady = sp.level().isLoaded(sp.blockPosition());

        if (!(minWaitReached && chunkReady)) {
            // Both gates not yet satisfied — tick the counter and wait one more.
            PENDING_REGRIND.put(sp.getUUID(),
                    new PendingRegrind(pending.carryVelocity(), waited, pending.preserved(),
                            pending.anchorPos()));
            return;
        }

        PENDING_REGRIND.remove(sp.getUUID());

        // Graph-hop path: re-attach the preserved GrindState directly. The destination edge
        // / position / from-to nodes are already set correctly by advanceJunction before
        // teleportThroughGraphHop fired, so we don't need (and don't want) the nearest-rail
        // scan to second-guess that choice.
        if (pending.preserved() != null) {
            GrindState gs = pending.preserved();
            Vec3 spawn = worldPos(gs).add(0, Y_OFFSET, 0);
            ACTIVE.put(sp.getUUID(), gs);
            sp.setNoGravity(true);
            sp.noPhysics = true;
            sp.fallDistance = 0.0F;
            sp.setDeltaMovement(Vec3.ZERO);
            gs.prevPos = null;
            gs.stuckTicks = 0;
            // Re-seed the speed filter on the next tick: the destination edge can have
            // a very different slope/curve profile than the source, and carrying over
            // the pre-hop smoothed value would visibly mistrack the new target for the
            // ~10 ticks the filter takes to converge.
            gs.smoothedTarget = Double.NaN;
            // Same rationale for the curve-factor smoother: if the source edge is a
            // turn and the destination is a straight (or vice versa), keeping the
            // pre-hop factor would drag the target speed through a long visible ease
            // that doesn't match the edge the player is actually on. NaN re-seeds it
            // from the destination edge on the first post-hop tick.
            gs.smoothedCurveFactor = Double.NaN;
            // Same rationale for the signed curve indicator — destination edge's curve
            // direction is unrelated to the source's, so reset to 0 and let the EMA
            // converge to the new edge's value on the first few post-hop ticks.
            gs.experiencedCurve = 0.0;
            // Same rationale for the velocity hint: post-hop tangent is unrelated to the
            // pre-hop tangent. Drop prevTarget so the first post-hop tick ships velocity
            // = ZERO and the client re-seeds smoothedTarget cleanly.
            gs.prevTarget = null;
            // Arm the trigger-based, LATENCY-SCALED re-attach grace window. The counter
            // holds gs.position still while the client gets ready (nether chunks loading,
            // LocalPlayer re-spawn, etc.); when the client confirms readiness via
            // CrossDimGraceReleasePayload the server collapses this counter to
            // REATTACH_GRACE_AFTER_ACK_TICKS for a short final settling tail. If the ack
            // never arrives, the latency-scaled timeout fires and the grace ends naturally.
            // Belt-and-suspenders: totalTicks reset puts the existing STUCK_GRACE_TICKS
            // gate back in play as a second layer covering the post-grace ticks. See
            // REATTACH_GRACE_BASE_TICKS / latencyScaledReattachGrace() for the full design.
            gs.reattachGraceTicks = latencyScaledReattachGrace(sp);
            gs.totalTicks = 0;
            // Freeze motion until CrossDimGraceReleasePayload arrives. While frozen,
            // tick() skips its position-advance loop and currentSpeed math, so gs.position
            // stays at the value worldPos() computed for the spawn snap above. The hard-
            // snap target stays bit-identical from tick to tick, the client visually sits
            // still on the rail, and there's no per-tick teleport "repeatedly placed on
            // rail" symptom while the new dim loads. The preserved gs.currentSpeed (the
            // last pre-cross-dim value) is held intact for when releaseReattachGrace
            // finally unfreezes motion.
            gs.frozenAtReattachStart = true;
            // Hard-snap the client onto the destination spawn before motion resumes —
            // mirrors the init in railgrinding() so the player doesn't EMA-chase from
            // their teleport-landing pose for several ticks before catching up.
            sp.connection.teleport(spawn.x, spawn.y, spawn.z, sp.getYRot(), sp.getXRot());
            markNextStartSilent(sp.getUUID());
            syncPose(sp, true);
            // Use the preserved gs.experiencedSlope: it's the most recent server-side slope
            // before the cross-dim hop. If the player was on a slope at the exit portal,
            // we want the noPhysics-bypass to remain active for the first tick after the
            // re-grind so the landing spline tangent (likely similar pitch to the exit)
            // doesn't snag on adjacent geometry before the next tick re-derives the slope.
            // Velocity = ZERO: the destination edge's tangent isn't necessarily aligned with
            // the source edge's, so any carried-over velocity vector would point in the
            // wrong direction. Client seeds smoothedTarget on next packet from a fresh
            // post-hop velocity computed from prevTarget == null → 0.
            // serverAuthoritative=true: grace was just armed (reattachGraceTicks > 0), so
            // the client should hard-snap on receipt rather than EMA-chase the seed.
            sendTargetToPlayer(sp, spawn, Vec3.ZERO, gs.experiencedSlope, true);
            return;
        }

        // Nearest-rail re-grind path (1/2/3). If the proximity scan turns up empty, release
        // the flags and let the player fall normally with the standard fall-immunity window
        // applied so the landing arc after a portal exit doesn't kill them at speed.
        RailHit hit = findNearestRailLocation(sp.level(), sp.position(), PORTAL_REGRIND_SEARCH_RANGE);
        if (hit == null) {
            cleanupAbandonedPending(sp);
            return;
        }
        markNextStartSilent(sp.getUUID());
        railgrinding(sp, hit.loc(), sp.position(), pending.carryVelocity(), hit.subLevel());
        // Cross-dim re-attach only: arm the trigger-based grace window on the newly-built
        // GrindState. railgrinding() doesn't seed this in its ctor (normal grind starts
        // want instant motion — see the ctor comment), so paths 1/2/3 set it here after
        // the new state is in ACTIVE. Path 4's preserved-gs branch above sets the same
        // field directly on the carried-over GrindState. Both branches converge on the
        // same behavior — motion paused, stuck/drift kicks suppressed — until the client
        // sends CrossDimGraceReleasePayload confirming readiness, at which point
        // releaseReattachGrace collapses the counter to a short tail. The latency-scaled
        // timeout is the failsafe in case the ack never arrives.
        GrindState newGs = ACTIVE.get(sp.getUUID());
        if (newGs != null) {
            newGs.reattachGraceTicks = latencyScaledReattachGrace(sp);
            // Cross-dim re-attach uses reattachGraceTicks exclusively — its trigger-based
            // ack mechanism handles the latency-bound suppression that START_GRACE_TICKS
            // would otherwise duplicate. Clearing the start grace here keeps the kick
            // suppression semantics single-sourced from reattachGraceTicks for re-attaches,
            // matching the design in tickPendingRegrind's preserved-gs branch (path 4).
            newGs.startGraceTicks = 0;
            // Freeze motion until CrossDimGraceReleasePayload arrives — same rationale as
            // the path-4 branch above.
            newGs.frozenAtReattachStart = true;
            // Restore the player's pre-cross-dim grind speed verbatim. railgrinding()'s
            // ctor seeds currentSpeed = CRUISE_SPEED × cruise_cfg, then adds entryVelocity
            // (= the pre-cross-dim speed) and clamps to MAX_STEP. The user's expectation
            // is "the railgrind start speed should be the last speed they had before the
            // cross-dim routine" — so override with the carry value (clamped to MAX_STEP
            // for safety, since the spline-chase math can't track per-tick steps larger
            // than that without drifting off the bezier curve — see [[max_step_alignment_ceiling]]).
            newGs.currentSpeed = Math.min(pending.carryVelocity(), MAX_STEP);
        }
    }

    /**
     * Shared cleanup for pending re-grinds that don't produce an active grind — either
     * timed out before the chunk gate opened, or the nearest-rail scan came up empty.
     * Releases the no-physics flags so the player isn't permanently frozen, seeds the
     * fall-immunity window so the landing arc doesn't kill at speed, and broadcasts a
     * grinding=false sync so the client clears its pose / noPhysics state.
     */
    private static void cleanupAbandonedPending(ServerPlayer sp) {
        sp.setNoGravity(false);
        sp.noPhysics = false;
        FALL_DAMAGE_IMMUNITY_TIME.put(sp.getUUID(), sp.level().getGameTime() + FALL_IMMUNITY_TICKS);
        syncPose(sp, false);
    }

    /**
     * Decrement the player's post-transit cooldown counter by one and evict at zero. Called
     * every server tick from {@link net.juniknytt.createrailgrinding.event.ModEvents#onPlayerTick}
     * for every player (cheap when the entry is absent — single map lookup, early return).
     */
    public static void tickPortalTransitCooldown(Player player) {
        Integer remaining = PORTAL_TRANSIT_COOLDOWN.get(player.getUUID());
        if (remaining == null) return;
        int next = remaining - 1;
        if (next <= 0) PORTAL_TRANSIT_COOLDOWN.remove(player.getUUID());
        else PORTAL_TRANSIT_COOLDOWN.put(player.getUUID(), next);
    }

    /**
     * Try to instant-port a grinding player to the matching rail in another dimension via
     * Create's {@link PortalTrackProvider} registry. Fires from {@link #tick} when the player
     * overlaps a Create-registered portal block, before vanilla's {@code PortalProcessor}
     * counter has a chance to drive the standard transition. The {@link PortalProcessor}
     * instant-portal mixin handles the same trick for portal blocks Create doesn't know about
     * — that path lands the player at a vanilla-computed exit, and the dimension-change
     * listener handles the re-grind there. This path's advantage is that Create's provider
     * encodes the rail-to-rail mapping, so the exit is positioned where the matching rail
     * actually is.
     *
     * <p>Returns true iff a transit happened (caller should early-return from tick): the old
     * grind state is gone and {@code ACTIVE} either has a fresh entry for the new dimension
     * or no entry at all (no rail at the exit). Returns false if no Create-registered portal
     * block was under the player's feet — in that case the caller continues the grind tick
     * normally.
     */
    private static boolean tryPortalTransit(ServerPlayer sp, GrindState oldState, ServerLevel level) {
        if (isOnPostPortalTransitCooldown(sp)) return false;
        BlockPos here = sp.blockPosition();
        BlockState portalState = level.getBlockState(here);
        if (!PortalTrackProvider.isSupportedPortal(portalState)) return false;

        // Entry face = the nearest horizontal cardinal of the player's current rail tangent.
        // Create's provider uses this to disambiguate which side of the portal to map to on
        // the exit — same convention trains use when their TravellingPoint crosses a portal
        // edge. Y-component is zeroed because portal blocks (nether, end) are axis-aligned in
        // the horizontal plane; passing UP/DOWN here would land us with an unusable BlockFace.
        double edgeLen = oldState.edge.getLength();
        double t = edgeLen <= 0 ? 0 : Math.min(1.0, Math.max(0.0, oldState.position / edgeLen));
        Vec3 tangent = sampleTangent(oldState.graph, oldState.edge, t);
        Vec3 chord = oldState.toNode.getLocation().getLocation()
                .subtract(oldState.fromNode.getLocation().getLocation());
        if (tangent.x * chord.x + tangent.z * chord.z < 0) tangent = tangent.scale(-1);
        Direction entryDir = Direction.getNearest(tangent.x, 0, tangent.z);

        PortalTrackProvider.Exit exit = PortalTrackProvider.getOtherSide(
                level, new BlockFace(here, entryDir));
        if (exit == null) return false;

        // Drop the old grind state without seeding the START_COOLDOWN — the post-portal
        // re-grind in handleDimensionChange should fire on the next tick, not be gated by
        // the half-second dismount window meant to debounce a manual jump-off.
        double carryVelocity = oldState.currentSpeed;
        ACTIVE.remove(sp.getUUID());

        // Land one block past the exit portal in the face direction, with a half-block
        // vertical bias so the player isn't clipped inside the exit-portal block (which
        // would re-trigger vanilla portal handling on the very next tick). railgrinding()
        // re-snaps the player onto the rail bar itself, so this position is only used to
        // seed the rail-bar side pick and to give findNearestRailLocation a search center.
        BlockFace exitFace = exit.face();
        BlockPos exitTargetPos = exitFace.getConnectedPos();
        Vec3 targetPos = Vec3.atCenterOf(exitTargetPos);
        float yaw = exitFace.getFace().toYRot();
        sp.teleportTo(exit.level(), targetPos.x, targetPos.y, targetPos.z, yaw, sp.getXRot());
        sp.setPortalCooldown(PORTAL_REGRIND_COOLDOWN_TICKS);

        finishCrossDimRegrind(sp, carryVelocity);
        return true;
    }

    /**
     * Called after a cross-dimension teleport (path 1 — Create-supported portal in
     * {@link #tryPortalTransit}; path 2 — vanilla portal flow + {@code PlayerChangedDimensionEvent};
     * path 3 — adjacent-block portal scan in {@link #tryPortalTransitFromNode}) to defer the
     * re-grind on the other side until the player's new dimension is actually loaded on
     * their client. Hands off to {@link #enqueuePending} which holds the player in
     * noPhysics/noGravity at the exit point and starts the grind silently once
     * {@link #tickPendingRegrind} sees the chunks are ready.
     *
     * <p>Used to call {@code findNearestRailLocation} + {@code railgrinding} synchronously,
     * but the new EMA architecture surfaced a race: the client cleared noPhysics in its
     * ClientPlayerNetworkEvent.Clone handler *after* our sync packet had applied it, so the
     * brand-new LocalPlayer in the new dimension landed in vanilla physics and immediately
     * fell off the rail. Deferring gives the client time to settle.
     */
    private static void finishCrossDimRegrind(ServerPlayer sp, double carryVelocity) {
        enqueuePending(sp, carryVelocity, null);
    }

    /**
     * Called from {@code ModEvents.onPlayerChangedDimension} when a player who was grinding
     * just landed in a new dimension via vanilla's portal flow (or any other dimension change
     * — command teleport, /execute in, etc.). Tears down the old grind state (its TrackGraph
     * lives in the old dimension and is unreachable from the new level) and attempts to
     * resume the grind on the new side via {@link #finishCrossDimRegrind}.
     *
     * <p>Carries the player's pre-transit grind speed forward as the new grind's
     * entry-velocity so the cross-dim handoff doesn't feel like a hard stop.
     */
    public static void handleDimensionChange(ServerPlayer sp) {
        GrindState gs = ACTIVE.remove(sp.getUUID());
        if (gs == null) return;
        finishCrossDimRegrind(sp, gs.currentSpeed);
    }

    /**
     * Move the player across an inter-dimensional graph hop. Called from {@link #tick} after
     * {@link #advanceJunction} picks a next edge whose {@code fromNode} lives in a different
     * dimension than the player — Create's track-graph already encodes the rail-to-rail
     * crossing (see {@link TrackEdge#isInterDimensional}; the boolean is set in the edge ctor
     * from {@code node1.dimension != node2.dimension}), so the graph IS the source of truth
     * for which rail-end maps to which dimension. We don't go through {@link PortalTrackProvider}
     * or vanilla portal flow here — both would be redundant when the graph already says
     * "continue grinding at this node in that dimension."
     *
     * <p>Re-uses the existing {@link GrindState} on the new dimension (already pointing at
     * the right edge with {@code position = 0}) so the cross-dim handoff is just a player
     * teleport + flag re-assert + cooldown seed. Skipping {@link #finishCrossDimRegrind}'s
     * nearest-rail lookup matters: the graph has already chosen the canonical exit edge, and
     * a free-form proximity scan could grab an unrelated rail on the wrong side of the portal.
     */
    private static void teleportThroughGraphHop(ServerPlayer sp, GrindState gs, ResourceKey<Level> targetDim) {
        ServerLevel newLevel = sp.server.getLevel(targetDim);
        if (newLevel == null) {
            // Defensive: the node points at a dimension the server doesn't have loaded. Drop
            // the grind cleanly rather than leaving the player floating with stale flags.
            ACTIVE.remove(sp.getUUID());
            sp.setNoGravity(false);
            sp.noPhysics = false;
            syncPose(sp, false);
            return;
        }
        Vec3 targetPos = worldPos(gs).add(0, Y_OFFSET, 0);

        // Clear ACTIVE before teleport so the PlayerChangedDimensionEvent listener treats this
        // as "not grinding" and skips its own re-grind path — we hand the gs to the pending
        // queue and re-attach it once the client confirms the new dimension is loaded.
        ACTIVE.remove(sp.getUUID());
        sp.teleportTo(newLevel, targetPos.x, targetPos.y, targetPos.z, sp.getYRot(), sp.getXRot());
        sp.setPortalCooldown(PORTAL_REGRIND_COOLDOWN_TICKS);

        // Preserve the GrindState through the pending queue so the deferred re-grind picks
        // up exactly the destination edge Create's track graph chose, rather than falling
        // back to a free-form nearest-rail scan that could pick a different edge near the
        // exit node. Same silent-start behavior as paths 1/2/3 — the player was already
        // grinding before the hop, so the cross-dim handoff stays soundless.
        enqueuePending(sp, gs.currentSpeed, gs);
    }

    /**
     * Fallback portal-transit when the rail edge ends with no graph continuation. Scans the
     * six cardinal-adjacent blocks of the end node for a Create-supported portal block; if
     * found, treats the player as having entered that portal block and teleports via Create's
     * {@link PortalTrackProvider#getOtherSide}. Handles the "1 block of separation between
     * rail end and portal block" layout the user described — rails can't share a position
     * with a portal block, so the natural placement leaves a gap that the graph doesn't span.
     *
     * <p>Returns true iff a transit happened (caller should early-return).
     */
    private static boolean tryPortalTransitFromNode(ServerPlayer sp, GrindState gs, ServerLevel level) {
        if (isOnPostPortalTransitCooldown(sp)) return false;
        Vec3 nodeVec = gs.toNode.getLocation().getLocation();
        BlockPos nodeBlock = BlockPos.containing(nodeVec);
        // Prefer the direction the player is travelling — that's where a forward-facing portal
        // would be — but fall back to the other cardinals so a misaligned setup still teleports.
        Vec3 chord = gs.toNode.getLocation().getLocation()
                .subtract(gs.fromNode.getLocation().getLocation());
        Direction preferredDir = Direction.getNearest(chord.x, 0, chord.z);
        Direction[] order = orderedDirs(preferredDir);
        for (Direction dir : order) {
            BlockPos check = nodeBlock.relative(dir);
            BlockState state = level.getBlockState(check);
            if (!PortalTrackProvider.isSupportedPortal(state)) continue;
            PortalTrackProvider.Exit exit = PortalTrackProvider.getOtherSide(
                    level, new BlockFace(check, dir));
            if (exit == null) continue;

            double carryVelocity = gs.currentSpeed;
            ACTIVE.remove(sp.getUUID());
            BlockFace exitFace = exit.face();
            Vec3 targetPos = Vec3.atCenterOf(exitFace.getConnectedPos());
            float yaw = exitFace.getFace().toYRot();
            sp.teleportTo(exit.level(), targetPos.x, targetPos.y, targetPos.z, yaw, sp.getXRot());
            sp.setPortalCooldown(PORTAL_REGRIND_COOLDOWN_TICKS);
            finishCrossDimRegrind(sp, carryVelocity);
            return true;
        }
        return false;
    }

    /**
     * The four horizontal cardinals with {@code preferred} first, then the other three in
     * a stable order. Used by {@link #tryPortalTransitFromNode} so a portal directly along
     * the rail's travel direction wins over one that happens to sit perpendicular to a Y-fork
     * at the end node. If {@code preferred} isn't horizontal (e.g. nearly-zero chord), falls
     * back to a stable horizontal order so we still scan all four sides exactly once.
     */
    private static Direction[] orderedDirs(Direction preferred) {
        Direction[] horizontals = new Direction[] {
                Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST };
        if (preferred == null || !preferred.getAxis().isHorizontal()) {
            return horizontals;
        }
        Direction[] out = new Direction[4];
        out[0] = preferred;
        int i = 1;
        for (Direction d : horizontals) {
            if (d != preferred) out[i++] = d;
        }
        return out;
    }

    /**
     * True for {@link #FALL_IMMUNITY_TICKS} after the most recent grind exit. ModEvents reads
     * this to cancel fall damage during the window so a launch arc or a step off an elevated
     * rail doesn't kill the player. Map entries self-evict on the first read past the expiry
     * tick.
     *
     * This is more optimized for in-game use.
     */
    public static boolean hasFallImmunity(Player player) {
        Long time = FALL_DAMAGE_IMMUNITY_TIME.get(player.getUUID());
        if (time == null) return false;
        if (player.level().getGameTime() >= time) {
            FALL_DAMAGE_IMMUNITY_TIME.remove(player.getUUID());
            return false;
        }
        return true;
    }

    /**
     * Remaining ticks of post-dismount fall-damage immunity for this player, or 0 if the
     * window has already expired (or never started). Debug-only accessor used by the HUD;
     * gameplay code should use {@link #hasFallImmunity(Player)} instead so the boolean gate
     * stays the canonical contract.
     *
     * This exists to make debugging easier, shows up in debug hud.
     */
    public static int getFallImmunityRemaining(Player player) {
        Long time = FALL_DAMAGE_IMMUNITY_TIME.get(player.getUUID());
        if (time == null) return 0;
        long remaining = time - player.level().getGameTime();
        return remaining > 0 ? (int) remaining : 0;
    }

    /**
     * True while the player's post-grind cooldown counter is still above zero. The counter
     * is set to {@link #START_COOLDOWN_TICKS} when {@link #stop(Player, StopReason)} runs (any grind
     * exit) and decremented once per server tick by {@link #tickCooldown(Player)}. Both
     * grind-init routines read this to gate themselves: Networking.handleTeleport refuses
     * to teleport the player onto a rail while it returns true, and {@link #railgrinding}
     * refuses to start the ride. Returns false (allow grind) once the counter hits 0 and
     * the entry is evicted by tickCooldown.
     */
    public static boolean isPlayerOnRailGrindCooldown(Player player) {
        Integer remaining = START_COOLDOWN_REMAINING.get(player.getUUID());
        return remaining != null && remaining > 0;
    }

    /**
     * Remaining ticks of post-dismount start cooldown for this player, or 0 if the cooldown
     * has already lapsed. Debug-only accessor used by the HUD; gameplay code should use
     * {@link #isPlayerOnRailGrindCooldown(Player)} instead so the boolean gate stays the
     * canonical contract.
     *
     * Used for Debugging
     */
    public static int getStartCooldownRemaining(Player player) {
        Integer remaining = START_COOLDOWN_REMAINING.get(player.getUUID());
        return remaining == null ? 0 : remaining;
    }

    /**
     * Decrement the player's post-grind cooldown counter by one, evicting the map entry
     * when it hits zero. Called every server tick from {@code ModEvents.onPlayerTick}, so
     * the counter ticks down for grinding *and* non-grinding players alike — the cooldown
     * is only ever non-zero immediately after a {@link #stop(Player, StopReason)}, so a grinding
     * player's entry is already absent in steady state.
     */
    public static void tickCooldown(Player player) {
        Integer remaining = START_COOLDOWN_REMAINING.get(player.getUUID());
        if (remaining == null) return;
        int next = remaining - 1;
        if (next <= 0) {
            START_COOLDOWN_REMAINING.remove(player.getUUID());
        } else {
            START_COOLDOWN_REMAINING.put(player.getUUID(), next);
        }
    }

    /**
     * Tick the train-overlap crush counter: increment while the player's bounding box
     * intersects any {@link CarriageContraptionEntity}'s bounding box, reset to 0 the first
     * tick the intersection clears. The hard reset (rather than a slow decay) ensures a
     * series of momentary brush-bys can never accumulate into a kick — only one continuous
     * overlap counts toward the threshold.
     *
     * <p>Once the counter reaches {@link #TRAIN_OVERLAP_KICK_TICKS} and the player is
     * actively grinding, {@link #stop(Player, StopReason)} drops them. The same threshold gates new
     * grind starts via {@link #isPlayerCrushedByTrain}.
     *
     * <p>Runs every server tick for every player (from {@code ModEvents.onPlayerTick}),
     * not just grinders, because the crush gate also blocks non-grinding players from
     * starting a grind: standing inside a parked carriage and trying to grind should fail
     * outright rather than snap onto the rail and immediately re-drop.
     *
     * <p>Overlap detection is a plain entity-bbox vs player-bbox test via
     * {@code level.getEntitiesOfClass(CarriageContraptionEntity.class, player.getBoundingBox())}
     * — the AABB returned by Mojang's spatial lookup is the carriage entity's own bounding
     * box, no inflation, no per-bogey synthesis.
     */
    public static void tickTrainOverlap(Player player) {
        boolean overlapping = !player.level().getEntitiesOfClass(
                CarriageContraptionEntity.class, player.getBoundingBox()).isEmpty();

        if (overlapping) {
            int next = TRAIN_OVERLAP_TICKS.getOrDefault(player.getUUID(), 0) + 1;
            TRAIN_OVERLAP_TICKS.put(player.getUUID(), next);
            // Don't kick on overlap during the cross-dim re-attach grace — a parked train at
            // the exit rail would otherwise drop the grind on the player's 5th tick of mid-
            // transit "overlap" before they've moved an inch. Counter keeps ticking so the
            // moment grace ends, a legit train crush still fires within TRAIN_OVERLAP_KICK_TICKS.
            // Train-overlap is NOT suppressed during the fresh-start grace: a player
            // initiating a grind from inside a carriage's AABB is a real "stop the
            // grind" condition, not a latency artifact, and shielding it for ten ticks
            // would let the player visibly grind through the train before getting
            // dropped. Cross-dim re-attach grace IS still respected — that one parks
            // the player at a portal exit that may legitimately overlap a parked train
            // they're about to move past once motion resumes; the counter keeps ticking
            // so a sustained crush still fires within TRAIN_OVERLAP_KICK_TICKS of the
            // moment reattach grace ends.
            if (next >= TRAIN_OVERLAP_KICK_TICKS && isGrinding(player)
                    && !isInReattachGrace(player)) {
                stop(player, StopReason.TRAIN_OVERLAP);
            }
        } else {
            TRAIN_OVERLAP_TICKS.remove(player.getUUID());
        }

        // Surface the per-tick overlap result on the active GrindState (if any) so the debug
        // HUD's collidingWithTrain line reads the live bool. Non-grinders have no GrindState
        // to receive this; their overlap state is still visible via the always-on
        // intersectingTrainAABB / trainOverlapTicks lines below the grind block in the HUD.
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs != null) gs.collidingWithTrain = overlapping;
    }

    /**
     * True while the player has been overlapping a carriage's bounding box for at least
     * {@link #TRAIN_OVERLAP_KICK_TICKS} consecutive ticks. Read by both grind-init paths
     * (Networking.handleTeleport and handleStartFromNearest) and by {@link #railgrinding}
     * to refuse new grinds while the player is being crushed. Becomes false the instant
     * {@link #tickTrainOverlap} sees no overlap, since that path removes the counter entry.
     */
    public static boolean isPlayerCrushedByTrain(Player player) {
        Integer ticks = TRAIN_OVERLAP_TICKS.get(player.getUUID());
        return ticks != null && ticks >= TRAIN_OVERLAP_KICK_TICKS;
    }

    /**
     * Live count of consecutive ticks the player's hitbox has been overlapping a carriage
     * bounding box. 0 when there's no current overlap (counter reset). Debug-only accessor
     * used by the HUD; canonical readers use {@link #isPlayerCrushedByTrain} for the gate
     * semantics. The counter intentionally isn't capped at the kick threshold — leaving it
     * free-running surfaces "still stuck after the kick" as a visibly growing number on the
     * HUD instead of a silent saturation, and the HUD's "intersectingTrainAABB" bool is
     * just {@code count > 0}.
     */
    public static int getTrainOverlapTicks(Player player) {
        return TRAIN_OVERLAP_TICKS.getOrDefault(player.getUUID(), 0);
    }

    /**
     * Find the nearest rail spline point to {@code origin} within {@code maxDist} blocks,
     * using the same two resolvers the right-click empty-hand interaction uses:
     * <ul>
     *   <li>Plain {@link ITrackBlock} blocks → {@link TrackGraphHelper#getGraphLocationAt}
     *       (the call {@code railgrinding(BlockPos, …)} delegates to internally).</li>
     *   <li>Bezier curves on {@link TrackBlockEntity} → {@link TrackGraphHelper#getBezierGraphLocationAt}
     *       (the call {@code Networking.handleTeleport} makes for the right-click bezier branch).</li>
     * </ul>
     * Anything the right-click resolves, this resolves — the failure mode where some rails
     * worked and others didn't was a hand-rolled graph traversal that bypassed those helpers.
     *
     * <p>Plain blocks are picked up by a simple {@code BlockPos} scan in a small cube around
     * {@code origin}. Bezier curves are picked up by walking 3×3 chunks around the player and
     * iterating each loaded {@link TrackBlockEntity}'s {@link BezierConnection} segments —
     * curves whose endpoint TBE is in any of those chunks are covered, including curves that
     * bulge through the player's chunk while their endpoints sit one chunk over.
     */
    /**
     * Result of a nearest-rail scan: the graph location plus the (optional) Sable sub-level
     * the rail lives in. {@code subLevel} is null for parent-world rails. The
     * {@code SubLevelHandle} record carries no Sable types in its declaration (just an
     * {@code Object} reference), so consumers of this record stay loadable even when Sable
     * isn't installed.
     */
    public record RailHit(TrackGraphLocation loc, @Nullable SableSubLevels.SubLevelHandle subLevel) {}

    public static RailHit findNearestRailLocation(Level level, Vec3 origin, double maxDist) {
        // Mutable scan state — single-element arrays let scanLevelForRails update best-so-far
        // from inside its body without an explicit return tuple. Distance is always tracked
        // in world space so candidates from different sublevels (and from the parent world)
        // can be compared apples-to-apples.
        TrackGraphLocation[] bestLoc = { null };
        SableSubLevels.SubLevelHandle[] bestSub = { null };
        // Outer scan cap = base radius + wide-gauge bonus. Wide-gauge candidates are eligible
        // anywhere inside this; non-wide candidates are gated to the base cap via baseDistSq.
        double maxDistOuter = maxDist + WIDE_GAUGE_START_RADIUS_BONUS;
        double[] bestDistSq = { maxDistOuter * maxDistOuter };
        double baseDistSq = maxDist * maxDist;

        // Parent-world scan — origin and candidates are both in world coords, handle is null
        // (no pose transform applied to candidates).
        scanLevelForRails(level, origin, origin, null, bestLoc, bestSub, bestDistSq, baseDistSq);

        // Sub-level scans, only when Sable is loaded. Mods.SABLE.executeIfInstalled invokes
        // the inner Runnable only if the gate passes — so SableSubLevels.sublevelsNear is
        // never called when Sable is absent, and its Class.forName lookups never fire.
        Mods.SABLE.executeIfInstalled(() -> () -> {
            for (SableSubLevels.SubLevelHandle handle : SableSubLevels.sublevelsNear(level, origin, maxDistOuter)) {
                Level slLevel = handle.getLevel();
                if (slLevel == null) continue;  // sublevel was disposed between query and use
                // Origin moved into the sublevel's local frame — that's the coord system the
                // rail blocks live in, so the cursor scan addresses real positions.
                Vec3 originLocal = handle.toLocal(origin);
                scanLevelForRails(slLevel, originLocal, origin, handle, bestLoc, bestSub, bestDistSq, baseDistSq);
            }
        });

        return bestLoc[0] == null ? null : new RailHit(bestLoc[0], bestSub[0]);
    }

    /**
     * Parent-world-only variant of {@link #findNearestRailLocation}. Used by the right-click
     * teleport handler so the post-teleport scan can't accidentally pick a sublevel rail
     * adjacent to the teleport target (which has historically tripped Sable's chunk-cache
     * disconnect path — sublevel-targeted teleports go through StartGrindFromNearestPayload,
     * not TeleportToRailPacket, to keep this handler off that codepath entirely). Walks the
     * same {@link #scanLevelForRails} routine but only on the supplied level — no Sable
     * sweep.
     */
    public static @Nullable TrackGraphLocation findNearestRailInLevel(Level level, Vec3 origin, double maxDist) {
        TrackGraphLocation[] bestLoc = { null };
        SableSubLevels.SubLevelHandle[] bestSub = { null };
        double maxDistOuter = maxDist + WIDE_GAUGE_START_RADIUS_BONUS;
        double[] bestDistSq = { maxDistOuter * maxDistOuter };
        double baseDistSq = maxDist * maxDist;
        scanLevelForRails(level, origin, origin, null, bestLoc, bestSub, bestDistSq, baseDistSq);
        return bestLoc[0];
    }

    /**
     * Shared rail-proximity scan. {@code originInLevel} is the player's origin transformed
     * into {@code level}'s coordinate system (same as {@code originWorld} for the parent
     * world; sublevel-local for sublevels). {@code handle} is non-null only for sublevel
     * scans — when set, candidate spline points get transformed back to world via
     * {@link SableSubLevels.SubLevelHandle#toWorld} so {@code distanceToSqr(originWorld)}
     * compares world-space distances.
     *
     * <p>The block-cube range pads by 1 over the world-space radius so a rotated sublevel
     * can't push a local block slightly farther in world space than its local distance
     * suggests and miss it; the per-candidate world-distance check inside the loop is the
     * actual cap, so the small overscan never accepts a rail farther than {@code maxDist}.
     */
    private static void scanLevelForRails(
            Level level, Vec3 originInLevel, Vec3 originWorld,
            @Nullable SableSubLevels.SubLevelHandle handle,
            TrackGraphLocation[] bestLoc,
            SableSubLevels.SubLevelHandle[] bestSub,
            double[] bestDistSq,
            double baseDistSq) {
        BlockPos center = BlockPos.containing(originInLevel);
        int blockRange = (int) Math.ceil(Math.sqrt(bestDistSq[0])) + 1;

        // Plain ITrackBlock proximity scan — same resolution path as the right-click empty-hand
        // block hit (Networking.findRailBlockAt → railgrinding(BlockPos) → TrackGraphHelper.getGraphLocationAt).
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -blockRange; dx <= blockRange; dx++) {
            for (int dy = -blockRange; dy <= blockRange; dy++) {
                for (int dz = -blockRange; dz <= blockRange; dz++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (!(state.getBlock() instanceof ITrackBlock track)) continue;
                    TrackMaterial material = track.getMaterial();
                    if (!isGrindableMaterial(material)) continue;
                    TrackMaterial.TrackType trackType = material.trackType;
                    Vec3 cursorCenter = cursor.getCenter();
                    Vec3 cursorWorld = handle == null ? cursorCenter : handle.toWorld(cursorCenter);
                    double d2 = cursorWorld.distanceToSqr(originWorld);
                    // Per-type cap: wide gauge gets the outer (best-so-far) cap, everything
                    // else is held to the base cap. Wins over best-so-far in either case.
                    double candidateCap = isWideGaugeTrackType(trackType) ? bestDistSq[0] : Math.min(baseDistSq, bestDistSq[0]);
                    if (d2 < candidateCap) {
                        BlockPos pos = cursor.immutable();
                        TrackGraphLocation loc = resolvePlainTrack(level, pos, state, track);
                        if (loc != null) {
                            bestDistSq[0] = d2;
                            bestLoc[0] = loc;
                            bestSub[0] = handle;
                        }
                    }
                }
            }
        }

        // Bezier proximity scan — same resolution path as the right-click empty-hand bezier
        // hit (TrackGraphHelper.getBezierGraphLocationAt). Iterate 3×3 chunks around the player
        // for TrackBlockEntity instances; for each connection sample every segment of the curve
        // and keep the closest within range.
        int chunkX = SectionPos.blockToSectionCoord(center.getX());
        int chunkZ = SectionPos.blockToSectionCoord(center.getZ());
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                ChunkAccess chunk = level.getChunk(chunkX + cx, chunkZ + cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (BlockPos bePos : chunk.getBlockEntitiesPos()) {
                    BlockEntity be = level.getBlockEntity(bePos);
                    if (!(be instanceof TrackBlockEntity tbe)) continue;
                    // Material-gate the entire TBE before iterating its bezier connections.
                    // Both endpoints of a BezierConnection share the same material — Create
                    // won't let you link different gauges (or different rail varieties) with
                    // a curve — so the endpoint TBE's material stands in for the whole curve.
                    // Without this gate the plain-block scan above correctly rejects e.g. a
                    // wide-gauge phantom rail, but the bezier curves connected to that same
                    // rail still match here and the player teleports onto the curve. Mirrors
                    // the gate the plain scan applies at the top of this method so phantom
                    // (and any addon-mod gauge variant of phantom) gets the same treatment
                    // regardless of whether it's discovered as a straight rail or a curve.
                    if (!(level.getBlockState(bePos).getBlock() instanceof ITrackBlock endTrack)) continue;
                    TrackMaterial endMaterial = endTrack.getMaterial();
                    if (!isGrindableMaterial(endMaterial)) continue;
                    // Resolve the gauge of this endpoint for the per-type radius cap. Reuses
                    // the already-resolved endTrack reference so the block-state lookup runs
                    // once per TBE rather than twice.
                    boolean wideGauge = isWideGaugeTrackType(endMaterial.trackType);
                    double candidateCapBezier = wideGauge ? bestDistSq[0] : Math.min(baseDistSq, bestDistSq[0]);
                    for (Map.Entry<BlockPos, BezierConnection> entry : tbe.getConnections().entrySet()) {
                        BezierConnection conn = entry.getValue();
                        int segCount = conn.getSegmentCount();
                        for (int seg = 0; seg < segCount; seg++) {
                            float t = conn.getSegmentT(seg);
                            Vec3 p = conn.getPosition(t);
                            Vec3 pWorld = handle == null ? p : handle.toWorld(p);
                            double d2 = pWorld.distanceToSqr(originWorld);
                            if (d2 >= candidateCapBezier) continue;
                            BezierTrackPointLocation btpl = new BezierTrackPointLocation(entry.getKey(), seg);
                            TrackGraphLocation loc = TrackGraphHelper.getBezierGraphLocationAt(
                                    level, bePos, Direction.AxisDirection.POSITIVE, btpl);
                            if (loc == null) loc = TrackGraphHelper.getBezierGraphLocationAt(
                                    level, bePos, Direction.AxisDirection.NEGATIVE, btpl);
                            if (loc != null) {
                                bestDistSq[0] = d2;
                                bestLoc[0] = loc;
                                bestSub[0] = handle;
                                // best-so-far tightened; recompute per-type cap so later
                                // segments of this curve don't accept a worse hit.
                                candidateCapBezier = wideGauge ? bestDistSq[0] : Math.min(baseDistSq, bestDistSq[0]);
                            }
                        }
                    }
                }
            }
        }
    }

    /** Identical to the lookup loop in {@code railgrinding(BlockPos, …)} — try every track axis × axis-direction until {@link TrackGraphHelper#getGraphLocationAt} returns a non-null location. */
    private static TrackGraphLocation resolvePlainTrack(Level level, BlockPos pos, BlockState state, ITrackBlock track) {
        for (Vec3 axis : track.getTrackAxes(level, pos, state)) {
            for (Direction.AxisDirection dir : Direction.AxisDirection.values()) {
                TrackGraphLocation loc = TrackGraphHelper.getGraphLocationAt(level, pos, dir, axis);
                if (loc != null) return loc;
            }
        }
        return null;
    }

    /**
     * Stops the grind and gives the player a launch impulse along the rail's tangent at their
     * current position, plus a vertical boost. Both components scale with the player's grind
     * speed at the moment of dismount AND with the jump-charge ratio derived from
     * {@code chargeTicks} (how long the client held jump before release, clamped to
     * [{@link #JUMP_TRICK_CHARGE_INPUT_TIME_MIN}, {@link #JUMP_TRICK_CHARGE_INPUT_TIME_MAX}]).
     * Charge ratio is purely additive on top of the existing speed-based formula — at 0 charge
     * the launch is identical to the speed-only behavior, at full charge each component is
     * scaled by (1 + LAUNCH_CHARGE_*_BONUS_MULT).
     *
     * Tangent-based direction means uphill rails launch up-and-forward, downhill rails launch
     * forward-and-slightly-down (skater-off-a-ramp feel) — the vertical base is added on top so
     * even modest grinds always net some upward velocity unless the rail is steeply descending.
     */
    public static void stopWithLaunch(Player player, int chargeTicks, StopReason reason) {
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs == null) {
            stop(player, reason);
            return;
        }

        // Snapshot speed and travel-direction tangent before stop() removes the state.
        double speed = gs.currentSpeed;
        double edgeLen = gs.edge.getLength();
        double t = edgeLen <= 0 ? 0 : Math.min(1.0, Math.max(0.0, gs.position / edgeLen));
        Vec3 tangent = sampleTangent(gs.graph, gs.edge, t);
        Vec3 chord = gs.toNode.getLocation().getLocation().subtract(gs.fromNode.getLocation().getLocation());
        // Chord-flip first (both tangent and chord are in spline-local space), then rotate
        // into world space for sublevel grinds — the launch impulse drives world velocity,
        // so the direction has to be in world coords.
        if (tangent.x * chord.x + tangent.z * chord.z < 0) tangent = tangent.scale(-1);
        tangent = rotateTangentToWorld(gs, tangent);

        double chargeRatio = computeChargeRatio(chargeTicks);
        double speedMult  = Config.RAIL_JUMP_MOMENTUM.get();
        // Sonic Wind boosts the charge half of the launch only (railJumpMomentum is untouched).
        double chargeMult = Config.RAIL_JUMP_CHARGE.get() * ModEffects.sonicWindMultiplier(player);
        // railJumpMomentum scales both the horizontal speed-mult and the vertical speed-scale
        // term. railJumpCharge scales both the horizontal and vertical charge-bonus mults.
        // Vertical base launch (LAUNCH_VERTICAL_BASE) is intentionally not scaled — it's the
        // floor that guarantees an upward kick at any speed and stays constant.
        double horizMag = speed * LAUNCH_HORIZONTAL_MULT * speedMult
                * (1.0 + chargeRatio * LAUNCH_CHARGE_HORIZONTAL_BONUS_MULT * chargeMult);
        double vertBoost = (LAUNCH_VERTICAL_BASE + speed * LAUNCH_VERTICAL_SCALE * speedMult)
                * (1.0 + chargeRatio * LAUNCH_CHARGE_VERTICAL_BONUS_MULT * chargeMult);
        Vec3 launch = new Vec3(
            tangent.x * horizMag,
            tangent.y * horizMag + vertBoost,
            tangent.z * horizMag
        );

        // Inherit the sublevel's per-tick world velocity at the player's current position so
        // jumping off a moving Create Aeronautics ship feels like jumping off a moving train —
        // momentum carries through. Same units as the launch impulse (blocks/tick), so direct
        // vector addition is correct. Returns Vec3.ZERO for parent-world grinds.
        launch = launch.add(sublevelVelocityAt(gs, player.position()));

        stop(player, reason);
        player.setDeltaMovement(launch);
        player.hurtMarked = true;  // forces a velocity packet to the client so the launch isn't predicted away
        player.fallDistance = 0.0F;

        // Auto-deploy elytra if the player has a flight-capable chestplate equipped. Must run
        // AFTER stop() so the LivingEntityFallFlyingMixin's isGrinding gate has been cleared —
        // calling startFallFlying() directly here bypasses the tryToStartFallFlying path entirely,
        // so we just check canElytraFly (covers vanilla elytra + any modded equivalent) and toggle
        // shared flag 7. The launch impulse stays as starting velocity; vanilla elytra physics
        // takes over on the next tick.
        if (Config.AUTO_DEPLOY_ELYTRA.get()) {
            ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
            if (chest.canElytraFly(player)) {
                player.startFallFlying();
            }
        }
    }

    /**
     * Maps a held-tick count to a 0..1 charge ratio over the configured window. Clamps to the
     * window before normalizing so a malicious or out-of-range client-supplied value can't
     * exceed the cap. Shared between the server-side launch math and the client-side overlay
     * fill so the bar visually matches what the launch will compute.
     */
    public static double computeChargeRatio(int chargeTicks) {
        int range = JUMP_TRICK_CHARGE_INPUT_TIME_MAX - JUMP_TRICK_CHARGE_INPUT_TIME_MIN;
        if (range <= 0) return 0.0;
        int clamped = Math.max(JUMP_TRICK_CHARGE_INPUT_TIME_MIN,
                Math.min(JUMP_TRICK_CHARGE_INPUT_TIME_MAX, chargeTicks));
        return (clamped - JUMP_TRICK_CHARGE_INPUT_TIME_MIN) / (double) range;
    }

    public static boolean isGrinding(Player player) {
        return ACTIVE.containsKey(player.getUUID());
    }

    /** Server-authoritative grind speed in blocks/tick, or 0 if the player isn't grinding. */
    public static double getCurrentSpeed(Player player) {
        GrindState gs = ACTIVE.get(player.getUUID());
        return gs == null ? 0.0 : gs.currentSpeed;
    }

    /**
     * Returns the player's current experienced slope (motion.y / motion.length() from the
     * last tick — sin of pitch, +up / -down). Used by {@link net.juniknytt.createrailgrinding.mixin.PlayerNoPhysicsTickMixin}
     * to decide whether to re-assert {@code noPhysics = true} this tick.
     *
     * <p>Server: reads {@code GrindState.experiencedSlope} from {@code ACTIVE}. Authoritative.
     *
     * <p>Client: returns {@link #clientLocalSlope}, the synced mirror of the local grinding
     * player's slope from the most recent {@code RailGrindTargetPayload}. For remote grinding
     * players visible on this client, the returned value is the local player's slope, not
     * theirs — see the field's javadoc for why that's acceptable.
     */
    public static double getExperiencedSlope(Player player) {
        if (player.level().isClientSide()) {
            return clientLocalSlope;
        }
        GrindState gs = ACTIVE.get(player.getUUID());
        return gs == null ? 0.0 : gs.experiencedSlope;
    }

    /**
     * Debug-only frame for the local player's current grind, or null if not grinding.
     * - origin: world-space spline centerline point (rail block axis).
     * - tangent: travel-aligned tangent at that point.
     * - snapTarget: world-space position the player is being snapped to this tick — the same
     *   value applyTickMotion() drives the player toward, so the player's actual position lags
     *   by 1+ ticks and won't match it exactly.
     */
    public record GrindFrame(Vec3 origin, Vec3 tangent, Vec3 snapTarget) {}

    public static GrindFrame getGrindFrame(Player player) {
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs == null) return null;
        double edgeLen = gs.edge.getLength();
        double t = edgeLen <= 0 ? 0 : Math.min(1.0, Math.max(0.0, gs.position / edgeLen));
        Vec3 pos = gs.edge.getPosition(gs.graph, t);
        Vec3 dir = sampleTangent(gs.graph, gs.edge, t);
        // Defensive: if the edge's parameterization runs opposite to from→to, flip so the
        // tangent always points in the direction of travel.
        Vec3 chord = gs.toNode.getLocation().getLocation().subtract(gs.fromNode.getLocation().getLocation());
        if (dir.x * chord.x + dir.z * chord.z < 0) dir = dir.scale(-1);
        // Debug renderer draws origin/tangent in world space (overlays on the rail visible
        // to the player), so transform spline data out of the sublevel frame here.
        pos = localToWorld(gs, pos);
        dir = rotateTangentToWorld(gs, dir);
        Vec3 snap = worldPos(gs).add(0, Y_OFFSET, 0);
        return new GrindFrame(pos, dir, snap);
    }

    /** Live snapshot of a grind's per-tick non-constants. Debug only. Returns null if the player is not grinding. */
    public record GrindDebugInfo(
        double currentSpeed,
        double targetSpeed,
        double acceleration,
        double topSpeed,
        double experiencedSlope,
        // Signed curve direction at the player's current spline position. +1 right, -1 left,
        // 0 straight. Smoothed at CURVE_SMOOTH_RATE; see GrindState.experiencedCurve.
        double experiencedCurve,
        double position,
        double edgeLength,
        int stuckTicks,
        int totalTicks,
        double lateralSign,
        boolean edgeIsTurn,
        boolean crouchAccelerating,
        boolean collidingWithTrain,
        // Remaining blocks of drift the player can accumulate before the catastrophic-desync
        // bailout fires (MAX_DRIFT minus current playerPos↔target distance). Positive =
        // healthy margin; near-zero or negative = about to be / would-be kicked (negative
        // only appears when grace gates suppress the check — bailout would have fired this
        // tick if those gates weren't active). NaN when the gating predicates would
        // suppress the check entirely (reattachGraceTicks > 0 or startGraceTicks > 0), so
        // the HUD can show a "suppressed (in grace)" label rather than a misleading number.
        double driftMargin,
        // Remaining ticks of cross-dim re-attach grace (Fix C server-authoritative window).
        // Surfaced for HUD debug so the user can confirm grace state during cross-dim handoff;
        // not consumed by any gameplay logic.
        int reattachGraceTicks,
        // -1 while either grace counter is still > 0; 0 on the first tick BOTH are 0; then
        // increments by 1 per tick. Lets the user verify post-drop whether the cancel fired
        // at the grace boundary or much later. See GrindState.ticksSinceGraceEnded.
        int ticksSinceGraceEnded
    ) {}

    public static GrindDebugInfo getGrindDebugInfo(Player player) {
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs == null) return null;
        // Mirror the fluid scaling tick() applies, so the HUD's targetSpeed/accel lines
        // match the values actually driving motion this tick rather than the dry-air values.
        double fluidMult = computeFluidMultiplier(player);
        // Mirror applyTickMotion's exact MAX_DRIFT check: same target, same playerPos,
        // same threshold. Suppressed-in-tick gates (reattach/start grace) produce NaN
        // so the HUD can label "drift check off" rather than print a misleading margin.
        Vec3 target = worldPos(gs).add(0, Y_OFFSET, 0);
        double currentDrift = player.position().subtract(target).length();
        double threshold = computeDynamicMaxDrift(player, gs);
        double margin = (gs.reattachGraceTicks > 0 || gs.startGraceTicks > 0)
                ? Double.NaN
                : threshold - currentDrift;
        return new GrindDebugInfo(
            gs.currentSpeed,
            computeTargetSpeed(gs, player) * fluidMult,
            computeAcceleration(gs, player) * fluidMult,
            topSpeed(),
            gs.experiencedSlope,
            gs.experiencedCurve,
            gs.position,
            gs.edge.getLength(),
            gs.stuckTicks,
            gs.totalTicks,
            gs.lateralSign,
            gs.edge.isTurn(),
            isAcceleratingForGrind(player, gs),
            gs.collidingWithTrain,
            margin,
            gs.reattachGraceTicks,
            gs.ticksSinceGraceEnded
        );
    }

    /**
     * Local tangent at parameter t along an edge. TrackEdge.getDirectionAt(t) returns the
     * constant from→to chord on bezier edges (it's not the bezier derivative), which would
     * make any rotation derived from it freeze for the entire curve and snap at edge
     * boundaries. A centered finite difference of getPosition(t) tracks the curve smoothly
     * — same approach Create's TravellingPoint uses for bogey orientation.
     *
     * Sample points are clamped to [0, 1] so calls near the endpoints fall back to a one-
     * sided difference instead of reading off the edge.
     */
    private static Vec3 sampleTangent(TrackGraph graph, TrackEdge edge, double t) {
        final double eps = 0.001;
        double t0 = Math.max(0.0, t - eps);
        double t1 = Math.min(1.0, t + eps);
        if (t1 - t0 < 1e-9) return edge.getDirectionAt(t);
        Vec3 diff = edge.getPosition(graph, t1).subtract(edge.getPosition(graph, t0));
        double len = diff.length();
        if (len < 1e-9) return edge.getDirectionAt(t);
        return diff.scale(1.0 / len);
    }

    /**
     * Signed, normalized indicator of the rail's curve direction at the player's current spline
     * position. {@code +1} = right turn, {@code -1} = left turn, {@code 0} = straight (or edge
     * is straight per {@link TrackEdge#isTurn}). Magnitude scales with curve tightness via
     * {@link #CURVE_SIGNAL_GAIN}: gentle curves register weakly, tight (Create 180°-stack)
     * curves saturate at ±1.
     *
     * <p>Computed by sampling the spline tangent at {@code t ± CURVE_SAMPLE_EPSILON}, projecting
     * both samples into the xz plane (the curve direction is a top-down concept — pitch doesn't
     * affect "left vs right turn"), normalizing, and taking the 2D cross product. The 2D cross
     * {@code prev.x * next.z − prev.z * next.x} equals {@code sin(θ)} where θ is the angle from
     * the back-sampled tangent to the forward-sampled tangent.
     *
     * <p>Sign convention: in MC world coordinates, a player travelling along {@code +X} that
     * curves right reaches {@code +Z} → {@code cross = +1}. A left turn reaches {@code -Z} →
     * {@code cross = -1}. This matches {@link GrindState#steerSign}: {@code +1} right, {@code -1} left.
     *
     * <p>Returns 0 for straight edges (early-exit on {@code !edge.isTurn()}) to skip the 4
     * extra {@code getPosition} calls — the EMA smoothing in {@link #tick} still fades the
     * post-turn value back down to 0 over the straight section.
     */
    private static double computeRawExperiencedCurve(GrindState gs) {
        if (!gs.edge.isTurn()) return 0.0;
        double edgeLen = gs.edge.getLength();
        if (edgeLen <= 1e-6) return 0.0;
        double t = Math.min(1.0, Math.max(0.0, gs.position / edgeLen));
        double t0 = Math.max(0.0, t - CURVE_SAMPLE_EPSILON);
        double t1 = Math.min(1.0, t + CURVE_SAMPLE_EPSILON);
        if (t1 - t0 < 1e-6) return 0.0;
        Vec3 prev = sampleTangent(gs.graph, gs.edge, t0);
        Vec3 next = sampleTangent(gs.graph, gs.edge, t1);
        // Chord-flip both samples to travel direction. Same sign-flip is applied to both, so
        // the 2D cross result is invariant to whether the edge's parameterization runs from→to
        // or to→from; doing it explicitly here keeps the math consistent with getGrindFrame /
        // applyTickMotion's tangent handling elsewhere in this class.
        Vec3 chord = gs.toNode.getLocation().getLocation().subtract(gs.fromNode.getLocation().getLocation());
        double chordDot = prev.x * chord.x + prev.z * chord.z;
        if (chordDot < 0) {
            prev = prev.scale(-1);
            next = next.scale(-1);
        }
        double pl = Math.sqrt(prev.x * prev.x + prev.z * prev.z);
        double nl = Math.sqrt(next.x * next.x + next.z * next.z);
        if (pl < 1e-6 || nl < 1e-6) return 0.0;
        double px = prev.x / pl, pz = prev.z / pl;
        double nx = next.x / nl, nz = next.z / nl;
        // 2D cross in the xz plane — sin of the rotation angle from prev to next.
        double cross = px * nz - pz * nx;
        double scaled = cross * CURVE_SIGNAL_GAIN;
        return Math.max(-1.0, Math.min(1.0, scaled));
    }

    public static void tick(Player player) {
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs == null) return;

        // Player wrench-mounted a Create chain conveyor — both systems drive motion via
        // setDeltaMovement, so leaving the grind active fights the chain ride. Drop cleanly
        // so the wrench-mount hands off into chain riding. The fast path is the client-side
        // edge-trigger in ClientInputHandler#onChainMountTick → ChainMountedPayload, which
        // calls stop() within ~1 tick of the wrench click. This server-side check is the
        // fallback for paths that populate hangingPlayers without going through that payload
        // — Create's TTL packet fires only every 10 ticks from
        // ChainConveyorRidingHandler.clientTick, so without the client-driven payload the
        // handoff lags up to ~10 ticks.
        //
        // Skip during reattach grace: Create's hangingPlayers map is not cleared on cross-dim
        // by Create itself, so a stale source-dim entry can cause a false-positive stop() the
        // first tick after re-grind starts in the destination dim. The user reported this as
        // an "occasional drop from railgrind mode" within the grace window. The 10-tick TTL
        // ensures the stale entry expires naturally during grace; a legitimate new chain mount
        // mid-grace will trigger again the tick after grace ends.
        if (!isInReattachGrace(player)
                && ServerChainConveyorHandler.hangingPlayers.containsKey(player.getUUID())) {
            stop(player, StopReason.CHAIN_HANDOFF);
            return;
        }

        // Riptide-style auto spin attack fights our setDeltaMovement and yanks the player off
        // the rail at odd angles. Detect via the LivingEntity flag (set by startAutoSpinAttack)
        // rather than checking for the trident item — commands, potions, and other mods can
        // raise this flag without a trident in hand, and we want to drop the grind in all of
        // those cases too.
        //
        // Skip during reattach grace: isAutoSpinAttack is part of LivingEntity's transient
        // state and can survive cross-dim teleport in some flows (Create's portal handoff
        // doesn't clear it). False-positive risk during the grace window is non-zero and a
        // stop() here bypasses every grace protection. Real auto-spin attacks initiated mid-
        // grind will fire again the tick after grace expires.
        if (!isInReattachGrace(player) && player.isAutoSpinAttack()) {
            stop(player, StopReason.AUTO_SPIN);
            return;
        }

        // Sub-level disposal guard: if the sublevel was destroyed or its internal Level became
        // unavailable, drop the grind cleanly. Without this the worldPos() transform would
        // keep returning the last known position (or stale data through the cached pose) and
        // the player would freeze in mid-air on a non-existent rail. Only checked when
        // actually grinding on a sublevel; null subLevel (parent-world grind) skips entirely.
        if (gs.subLevel != null && (gs.subLevel.isRemoved() || gs.subLevel.getLevel() == null)) {
            stop(player, StopReason.SUBLEVEL_REMOVED);
            return;
        }

        // Track-removal guard: if the rail blocks (or the parent TrackBlockEntity of a bezier
        // curve) were broken mid-grind, gs.edge is now orphaned from its TrackGraph and every
        // downstream motion step would happily extrapolate along the dead spline — visible as
        // the player "ghost-grinding" through empty air along the old curve. Cheap O(1) map
        // lookups, fires the tick after the break. Skipped during reattach grace because that
        // window can briefly observe a graph mid-cross-dim handoff where the destination
        // dim's chunks aren't fully resolved yet; relying on grace expiry to re-check avoids
        // false positives at the cost of one extra tick of ghost-grind during cross-dim only.
        if (!isInReattachGrace(player) && !isCurrentEdgePresent(gs)) {
            stop(player, StopReason.TRACK_REMOVED);
            return;
        }

        // Rail-grind into a Create-supported portal block → instant cross-dimension teleport
        // (no vanilla 4-second wait) and immediate re-grind on the matching rail in the exit
        // dimension. Detection uses Create's own PortalTrackProvider registry, which already
        // covers vanilla nether/end and any portal a mod has registered via AllPortalTracks —
        // same list trains use, so the rail-grinder follows wherever Create rails can go.
        if (player.level() instanceof ServerLevel sl && player instanceof ServerPlayer sp
                && tryPortalTransit(sp, gs, sl)) {
            return;
        }

        // Decrement the startup grace window. On the tick the counter hits 0, set
        // noPhysics = true so the slope-gated mixin re-assertion can take over for the
        // rest of the grind. Through the grace itself, noPhysics stayed false (vanilla
        // collision active) — see START_GRACE_TICKS for the full design.
        if (gs.startGraceTicks > 0) {
            gs.startGraceTicks--;
            if (gs.startGraceTicks == 0) {
                player.noPhysics = true;
            }
        }

        gs.totalTicks++;

        if (player.level() instanceof ServerLevel sl) {
            double speedRatio = Math.min(2.0, gs.currentSpeed / topSpeed());
            // Below 25% max speed → no particles. 25–50% → plain crit, 50%+ → crit + red dust.
            // Cadence (interval) and count both scale with speed so a slow grind sheds a few
            // sparks at long intervals while a top-speed grind emits a continuous stream.
            if (speedRatio >= 0.25) {
                int interval = Math.max(1, (int) Math.round(1.0 / speedRatio));
                if (gs.totalTicks % interval == 0) {
                    // Travel-aligned tangent at the player's current edge position. Same chord-
                    // flip as stopWithLaunch / getGrindFrame so it always points forward.
                    // For sublevel grinds the tangent is in local space; particle offsets
                    // below combine it with player.position() (world), so it has to be rotated
                    // into world space first.
                    double edgeLen = gs.edge.getLength();
                    double t = edgeLen <= 0 ? 0 : Math.min(1.0, Math.max(0.0, gs.position / edgeLen));
                    Vec3 tangent = sampleTangent(gs.graph, gs.edge, t);
                    Vec3 chord = gs.toNode.getLocation().getLocation().subtract(gs.fromNode.getLocation().getLocation());
                    if (tangent.x * chord.x + tangent.z * chord.z < 0) tangent = tangent.scale(-1);
                    tangent = rotateTangentToWorld(gs, tangent);

                    Vec3 feet = player.position();
                    final double BACK_OFFSET = 0.4;
                    double sx = feet.x - tangent.x * BACK_OFFSET;
                    double sy = feet.y + 0.05 - tangent.y * BACK_OFFSET;
                    double sz = feet.z - tangent.z * BACK_OFFSET;

                    int count = 1 + (int) Math.round(speedRatio);
                    double spread = 0.10 + speedRatio * 0.12;
                    sl.sendParticles(ParticleTypes.CRIT, sx, sy, sz, count, spread, 0.05, spread, 0.0);
                    if (speedRatio >= 0.50) {
                        sl.sendParticles(RED_SPARK, sx, sy, sz, count, spread, 0.05, spread, 0.0);
                    }
                }
            }
        }

        // Re-assert no-collision flags every tick. If anything (pose change, dismount logic,
        // a vanilla branch, or a network desync) flips noPhysics back to false even briefly,
        // the player would clip into the dirt/stone under the rail on slopes — which is
        // exactly the Y-jitter symptom. Same for noGravity. Cheap to set; safe to repeat.
        if (!player.noPhysics)     player.noPhysics = true;
        if (!player.isNoGravity()) player.setNoGravity(true);
        // Same defensive re-assert for elytra: if the fall-flying flag flips back on mid-grind
        // (mod, network desync, vanilla auto-deploy on a future tick), the gliding controller
        // fights our snap and the player T-poses through the rail.
        if (player.isFallFlying()) player.stopFallFlying();
        // onGround left over from before the grind triggers vanilla step-up / sneak / fall
        // handling that nudges Y even with noPhysics on. Force-clear it.
        player.setOnGround(false);

        // Sample the slope the player actually traversed last tick (Δy / Δdistance, in 3D).
        // Falls back to 0 on the first tick or when the player didn't move. The same
        // displacement length doubles as the player's absolute velocity for stuck detection
        // in applyTickMotion — see the comment there.
        Vec3 currentPos = player.position();
        double absVelocity = 0.0;
        if (gs.prevPos != null) {
            Vec3 motion = currentPos.subtract(gs.prevPos);
            absVelocity = motion.length();
            if (absVelocity > 1e-4) gs.experiencedSlope = motion.y / absVelocity;
        }
        gs.prevPos = currentPos;

        // Signed curve indicator (+right / -left, magnitude ≤ 1, see computeRawExperiencedCurve).
        // Always smooth — even when raw is 0 (straight edge), so a turn-to-straight transition
        // fades the value down at CURVE_SMOOTH_RATE rather than snapping. Consumed by
        // computeTargetSpeed for the tilt-into-curve speed-penalty assist, debug HUD, and
        // shipped to the client via RailGrindTargetPayload.
        double rawCurve = computeRawExperiencedCurve(gs);
        gs.experiencedCurve += (rawCurve - gs.experiencedCurve) * CURVE_SMOOTH_RATE;

        // Extreme-slope guard (Sable-only). When Sable is loaded sublevels can rotate the
        // ride through near-vertical orientations — a rail that's horizontal in sublevel-
        // local frame can become a sheer 60°+ slope in world space if the ship pitches/rolls.
        // Beyond ±0.85 (≈ ±58° from horizontal) the grind controller's velocity / snap math
        // starts producing nonsense (the player overshoots the rail bar or trails behind it
        // by several blocks each tick) and the experience is worse than just dismounting.
        // The same threshold for both signs because a vertical climb and a vertical drop are
        // symmetrically bad. Gated on Mods.SABLE so standalone parent-world play — where
        // Create's tracks physically can't produce such steep slopes — is unaffected.
        //
        // Persistence-required (consecutive-tick) drop instead of single-tick: experiencedSlope
        // is derived from the world-space (currentPos - prevPos) delta, which captures three
        // categories of false-positive spikes that release within 1-2 ticks:
        //   - Entry-velocity carry: a fall onto the rail has Y-heavy motion until the grind
        //     controller's setDeltaMovement fully overrides it (couple of ticks).
        //   - Sublevel translation between samples: the ship's own up/down motion shows up in
        //     the player's world-position delta even though the rail is locally flat.
        //   - Floating-point noise on the absVelocity > 1e-4 boundary.
        // Requiring EXTREME_SLOPE_DROP_TICKS (10 ≈ 0.5 s) consecutive over-threshold samples
        // lets a genuine vertical orientation persist long enough to confirm while transient
        // spikes release the counter. Also short-circuited during start grace and reattach
        // grace, where the entry-snap and cross-dim-anchor dynamics that necessitate those
        // windows are exactly the same dynamics that produce the bogus single-tick spikes.
        if (Mods.SABLE.isLoaded() && Math.abs(gs.experiencedSlope) > EXTREME_SLOPE_THRESHOLD
                && gs.startGraceTicks <= 0 && !isInReattachGrace(player)) {
            if (++gs.extremeSlopeTicks >= EXTREME_SLOPE_DROP_TICKS) {
                stop(player, StopReason.EXTREME_SLOPE);
                return;
            }
        } else {
            gs.extremeSlopeTicks = 0;
        }

        // Re-attach grace window — applyTickMotion ships the target with
        // serverAuthoritative=true and force-anchors sp.position to target each tick. The
        // client hard-snaps to the target on receipt rather than running predict-correct, so
        // server↔client position stays bit-identical while the new LocalPlayer is still
        // settling — no RTT-scaled drift, no MAX_DRIFT bailout, no stuck false-positive.
        //
        // Motion advance during grace is gated SEPARATELY by gs.frozenAtReattachStart:
        // while that flag is true, this method short-circuits to applyTickMotion below
        // without touching gs.position or gs.currentSpeed. The player is visibly held still
        // at the re-grind entry point. ACK arrival (CrossDimGraceReleasePayload →
        // releaseReattachGrace) clears the flag and collapses reattachGraceTicks to
        // REATTACH_GRACE_AFTER_ACK_TICKS for the predict-correct transition tail; the
        // latency-scaled timeout in reattachGraceTicks itself is the failsafe in case the
        // ack never arrives. Pre-cross-dim currentSpeed is preserved verbatim (no cruise-
        // pace clamp): the user explicitly wants the grind to resume at the speed they had
        // before the cross-dim routine.
        if (gs.frozenAtReattachStart) {
            applyTickMotion(player, gs, absVelocity);
            return;
        }

        // Fluid drag (water/lava/custom fluids) scales target speed and per-tick accel
        // symmetrically. Multiplying outside the helpers keeps them fluid-unaware and lets
        // a single computeFluidMultiplier call cover both target and accel for one tick.
        // The MIN_SPEED floor inside computeTargetSpeed scales with the multiplier as a
        // side-effect (Math.max(MIN_SPEED, base) * mult == max(MIN_SPEED * mult, base * mult)
        // for positive mult), so the player can drop below the dry-air floor in fluid.
        //
        // Hard cap on fluid entry: without this, decel from TOP_SPEED to the fluid-scaled
        // target via the (also-fluid-scaled) accel takes ~75 ticks at half-mult — entering
        // water at full speed should feel like hitting a wall, not a 4-second taper. The cap
        // gates only when fluidMult < 1.0 so out-of-fluid descent overshoot still decays
        // gradually through the existing decel branch.
        // Ease the curve factor between straight (1.0) and turn (CURVE_FACTOR) before
        // computeTargetSpeed reads it. Done here, not inside computeTargetSpeed, so the
        // debug-HUD's read-only call doesn't tick the smoother a second time per tick.
        // Compounds with the smoothedTarget EMA below for a kink-free transition; slope
        // and shift changes don't share this smoother so their response stays snappy.
        //
        // Asymmetric: ENTER (heading toward CURVE_FACTOR) is faster than EXIT (heading
        // toward 1.0) so short straights inside an S-bend can't bounce the smoothed
        // factor back up between turns. See the ENTER/EXIT_RATE constant comments for
        // the full rationale and the S-bend modeling that picked the values.
        double rawCurveFactor = gs.edge.isTurn() ? CURVE_FACTOR : 1.0;
        if (Double.isNaN(gs.smoothedCurveFactor)) {
            gs.smoothedCurveFactor = rawCurveFactor;
        } else {
            double rate = (rawCurveFactor < gs.smoothedCurveFactor)
                    ? CURVE_FACTOR_ENTER_RATE
                    : CURVE_FACTOR_EXIT_RATE;
            gs.smoothedCurveFactor += (rawCurveFactor - gs.smoothedCurveFactor) * rate;
        }

        double fluidMult = computeFluidMultiplier(player);
        double targetSpeed = computeTargetSpeed(gs, player) * fluidMult;
        double accel = computeAcceleration(gs, player) * fluidMult;

        // Seed the smoothed target on tick 1 from the raw target (avoids a startup
        // lag spike); subsequent ticks low-pass the raw target so per-tick slope and
        // edge-transition flicker can't translate directly to currentSpeed jitter.
        if (Double.isNaN(gs.smoothedTarget)) {
            gs.smoothedTarget = targetSpeed;
        } else {
            gs.smoothedTarget += (targetSpeed - gs.smoothedTarget) * TARGET_SMOOTH_RATE;
        }

        if (fluidMult < 1.0 && gs.currentSpeed > targetSpeed) {
            gs.currentSpeed = targetSpeed;
            // Resync the filter to the snap value, otherwise the eased term below
            // would immediately pull currentSpeed back toward the pre-snap smoothed
            // target — undoing the hit-a-wall feel the snap is here to produce.
            gs.smoothedTarget = targetSpeed;
        }

        // Exponential ease toward the smoothed target, with the per-tick step capped
        // at the legacy `accel` value. For |diff| > accel / SPEED_EASE_RATE the cap
        // dominates and behavior matches the old linear ramp (so the 0→TOP and
        // 0→CRUISE timings are essentially unchanged). Inside that band the step
        // scales with the gap — currentSpeed asymptotes to the target instead of
        // hitting the old Math.min/max clamp, which is what produced the visible
        // "snap" at target arrival and the audible pitch plateau the user reported.
        double diff = gs.smoothedTarget - gs.currentSpeed;
        double step = diff * SPEED_EASE_RATE;
        if (step > accel) step = accel;
        else if (step < -accel) step = -accel;
        gs.currentSpeed += step;

        if (gs.currentSpeed <= 1e-6) {
            applyTickMotion(player, gs, absVelocity);
            return;
        }

        // Geometric compensation for the lateral rail-bar offset. gs.position advances
        // in centerline-arclength units (it maps to t via gs.position / edgeLen), but
        // the player actually rides on a bar offset by gs.lateralOffset from that
        // centerline. On a turn the outer bar covers more arclength per unit of
        // centerline arclength than the centerline does (and the inner bar less), so
        // without this scale the same gs.currentSpeed translates to a larger world
        // step on the outer rail — perceived by the player as a speed-up entering the
        // turn. Dividing the per-tick budget by the bar/center arclength ratio undoes
        // the asymmetry so world speed matches currentSpeed regardless of which bar
        // gs.lateralSign chose at grind start. See railBarSpeedFactor for the geometry.
        // Factor is sampled once per tick at the current position; edge crossings
        // mid-tick (rare at typical speeds) keep the same factor for the remainder of
        // the tick — at the small Δs involved the error is negligible compared to
        // recomputing inside the loop.
        double remaining = gs.currentSpeed / railBarSpeedFactor(gs);
        int safety = 256;
        while (remaining > 1e-6 && safety-- > 0) {
            double edgeLen = gs.edge.getLength();
            double room = edgeLen - gs.position;
            if (room > remaining) {
                gs.position += remaining;
                remaining = 0;
            } else {
                remaining -= Math.max(room, 0);
                gs.position = edgeLen;
                if (!advanceJunction(gs, player)) {
                    // No graph continuation. Before giving up, scan blocks adjacent to the
                    // end node for a Create-supported portal — handles the "1-block gap
                    // between rail end and portal block" placement that breaks the graph
                    // hop because rails and portal blocks can't share a position.
                    if (player.level() instanceof ServerLevel sl2 && player instanceof ServerPlayer sp2
                            && tryPortalTransitFromNode(sp2, gs, sl2)) {
                        return;
                    }
                    stop(player, StopReason.END_OF_TRACK);
                    return;
                }
                // advanceJunction picked a new edge. If its fromNode is in a different
                // dimension than the player, we've just stepped onto an inter-dim graph hop
                // (TrackEdge.interDimensional, set by Create whenever an edge's two nodes
                // live in different dimensions). Teleport the player across; the same gs
                // continues on the destination edge.
                //
                // No post-transit cooldown gate here on purpose: cooldown is for ping-pong
                // via overlap-based detection (mixin, Path 1, adjacency scan), which all key
                // off "player AABB intersects portal block." A graph hop is initiated by
                // graph traversal — the player would have to deliberately reverse direction
                // and re-cross the same inter-dim edge to "ping-pong," which is intentional
                // travel, not a bug. Blocking it would also leave gs pointing at an edge in a
                // dimension the player isn't in, dragging them through impossible geometry.
                if (player instanceof ServerPlayer spDim
                        && !gs.fromNode.getLocation().getDimension().equals(spDim.level().dimension())) {
                    teleportThroughGraphHop(spDim, gs, gs.fromNode.getLocation().getDimension());
                    // Bail out of this tick's advance loop: the player's level reference and
                    // gs.edge geometry now resolve against the new dimension; the rest of
                    // this tick's work (particles, applyTickMotion) re-runs cleanly next tick.
                    return;
                }
            }
        }

        applyTickMotion(player, gs, absVelocity);
    }

    private static double computeTargetSpeed(GrindState gs, Player player) {
        double slope = gs.experiencedSlope;  // +up / -down
        boolean crouchAccelerating = isAcceleratingForGrind(player, gs);
        // Sonic Wind boost (1.0× without the effect): user request only covers the sneak top
        // speed and the no-sneak cruise here, so the descending-coast branch deliberately
        // skips it.
        double sonicMult = ModEffects.sonicWindMultiplier(player);
        double base;
        if (crouchAccelerating) {
            // Sneak: ride at topSpeed with the asymmetric slope cap — descents lift it (DOWNHILL_FACTOR), ascents cut it (UPHILL_FACTOR).
            base = topSpeed() * sonicMult;
            double factor = slope < 0 ? DOWNHILL_FACTOR : UPHILL_FACTOR;
            base *= Math.max(0.0, 1.0 - slope * factor);
        } else if (slope < 0) {
            // No sneak, descending: gravity-pulled coast that ramps from DOWNHILL_CRUISE_MIN_FRACTION
            // (gentle slope) up to DOWNHILL_CRUISE_MAX_FRACTION (max slope) of topSpeed. Replaces the
            // old CRUISE_SPEED × (1 − slope·DOWNHILL_FACTOR) formula, which capped un-sneaked descents
            // at ~33% topSpeed even at maximum steepness — too gentle once the downhill accel boost
            // can actually push speed up faster.
            double steepness = Math.min(1.0, -slope);
            base = topSpeed() * (DOWNHILL_CRUISE_MIN_FRACTION
                    + (DOWNHILL_CRUISE_MAX_FRACTION - DOWNHILL_CRUISE_MIN_FRACTION) * steepness);
        } else {
            // No sneak, flat or ascending: cruise pace, with uphill cut.
            base = CRUISE_SPEED * Config.CRUISE_GRIND_SPEED.get() * sonicMult * Math.max(0.0, 1.0 - slope * UPHILL_FACTOR);
        }

        // Smoothed turn factor — ticked in tick() before this is called. Fall back to
        // the raw step when the smoother hasn't seeded yet (e.g. a debug-HUD read on
        // the very first frame of a grind) so the displayed value is never NaN.
        double curveFactor = Double.isNaN(gs.smoothedCurveFactor)
                ? (gs.edge.isTurn() ? CURVE_FACTOR : 1.0)
                : gs.smoothedCurveFactor;
        base *= curveFactor;

        return Math.max(MIN_SPEED, base);
    }

    private static double computeAcceleration(GrindState gs, Player player) {
        // Sonic Wind only multiplies the sneak-accel branch (per user request).
        double base = isAcceleratingForGrind(player, gs)
                ? ACCELERATION * Config.SNEAK_ACCELERATION.get() * ModEffects.sonicWindMultiplier(player)
                : ACCELERATION;
        double slope = gs.experiencedSlope;
        if (slope < 0) base *= 1.0 + (-slope) * (DOWNHILL_ACCEL_BOOST - 1.0) * Config.DOWNWARD_MOMENTUM_GAIN.get();
        return base;
    }

    /**
     * Returns the speed-scale factor in [WATER/LAVA/OTHER_FLUID_FACTOR..1.0] applied to
     * target speed, acceleration, and (via the cap in tick()) current speed when the player
     * is touching one or more fluids. 1.0 means out of fluid.
     *
     * <p>Iterates {@link NeoForgeRegistries#FLUID_TYPES} so any fluid the player overlaps
     * is considered, not just water/lava — multiple-fluid touches resolve to the slowest
     * factor (e.g., a custom fluid with factor 0.3 wins over water's 0.5 if both apply).
     * Each fluid maps to a hardcoded factor: vanilla water/lava get their own constants,
     * everything else falls back to OTHER_FLUID_FACTOR (water-like) since FluidType has no
     * canonical swim-speed property to derive from automatically.
     *
     * <p>Depth strider eases the slowdown by lerping the slowest-factor up toward
     * {@link #DEPTH_STRIDER_FLUID_FLOOR} based on the enchantment's level. The clamp at
     * {@link #DEPTH_STRIDER_FLUID_FULL_LEVEL} matches vanilla's depth strider cap — levels
     * above 3 don't reduce the slowdown further.
     */
    private static double computeFluidMultiplier(Player player) {
        if (!player.isInFluidType()) return 1.0;

        FluidType waterType = NeoForgeMod.WATER_TYPE.value();
        FluidType lavaType = NeoForgeMod.LAVA_TYPE.value();
        double slowest = 1.0;
        for (FluidType type : NeoForgeRegistries.FLUID_TYPES) {
            if (!player.isInFluidType(type)) continue;
            double factor;
            if (type == waterType) {
                factor = WATER_FLUID_FACTOR;
            } else if (type == lavaType) {
                factor = LAVA_FLUID_FACTOR;
            } else {
                factor = OTHER_FLUID_FACTOR;
            }
            if (factor < slowest) slowest = factor;
        }

        if (slowest >= 1.0) return 1.0;

        int dsLevel = Math.min(DEPTH_STRIDER_FLUID_FULL_LEVEL, getDepthStriderLevel(player));
        double dsRatio = dsLevel / (double) DEPTH_STRIDER_FLUID_FULL_LEVEL;
        return slowest + (DEPTH_STRIDER_FLUID_FLOOR - slowest) * dsRatio;
    }

    /**
     * Highest depth strider level across the player's equipped armor (0 if none equipped).
     * Uses the registry-holder lookup form because in 1.21+ {@link Enchantments#DEPTH_STRIDER}
     * is a {@link net.minecraft.resources.ResourceKey ResourceKey}, not a direct enchantment
     * instance — the level is read against the world's enchantment registry.
     */
    private static int getDepthStriderLevel(Player player) {
        Holder<Enchantment> ench = player.level().registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.DEPTH_STRIDER);
        return EnchantmentHelper.getEnchantmentLevel(ench, player);
    }

    /**
     * Legacy latency-aware MAX_DRIFT threshold. Used to widen the static {@link #MAX_DRIFT}
     * by {@code currentSpeed × 2.5 × RTT_ticks} to absorb client-EMA-vs-server-anchor drift
     * on high-ping MP, capped at {@code MAX_DRIFT × 4}.
     *
     * <p>Since the primary obstacle detector moved to the client
     * ({@link net.juniknytt.createrailgrinding.client.RailGrindClientMotion#runBlockedDetection}
     * → {@link net.juniknytt.createrailgrinding.network.BlockedByObstaclePayload}), this
     * server-side MAX_DRIFT is an anti-cheat tripwire only, and {@link #MAX_DRIFT} has been
     * raised from 5.0 to 20.0 (the old dynamic cap). Latency scaling on top of that is
     * unnecessary — 20 blocks is generous enough for any legitimate RTT. Returns the static
     * threshold unconditionally. Kept as a function (rather than inlining the constant) so
     * the existing call sites in {@code applyTickMotion} and the debug HUD ({@code applyTickHudSnapshot})
     * keep working with no signature churn, and so re-introducing latency scaling — if a
     * future regression demands it — is a one-method change.
     */
    private static double computeDynamicMaxDrift(Player player, GrindState gs) {
        return MAX_DRIFT;
    }

    /**
     * Predicate consumed by {@link net.juniknytt.createrailgrinding.mixin.ServerMovePacketMixin}
     * to suppress vanilla {@code handleMovePlayer} while a cross-dim handoff is in flight.
     * Returns true while the player is either waiting in the pending-regrind queue (Fix B's
     * anchored window before the destination grind starts) or inside the
     * {@link #reattachGraceTicks} server-authoritative window (Fix C). In both cases the
     * server has authoritative position via the per-tick anchor; accepting stale C2S move
     * reports from a still-loading or just-respawned LocalPlayer would clobber that anchor.
     */
    public static boolean shouldRejectMoveDuringCrossDim(Player player) {
        if (PENDING_REGRIND.containsKey(player.getUUID())) return true;
        return isInReattachGrace(player);
    }

    /**
     * "Player position priority" soft-snap: advance {@code gs.position} toward where the player
     * actually is by projecting {@code (player.position() − currentTarget)} onto the rail
     * tangent at {@code gs.position}, then setPos the player onto the rail bar at the updated
     * {@code gs.position}. Used by the post-reattach desync recovery path — instead of dropping
     * the player when their actual position has run ahead of the rail target, the rail target
     * "follows" the player along the edge and the player snaps onto the bar smoothly.
     *
     * <p>{@code gs.position} is clamped to {@code [0, edgeLength]}; if the projection would
     * push past the edge end the player snaps at the edge end and the next tick's normal
     * advance loop will handle the {@link #advanceJunction} hop. No edge crossing inside the
     * helper to keep recovery behavior predictable.
     *
     * <p>Tangent projection (linear approximation) rather than exact bezier arclength
     * projection — exact projection requires iterative t-search per call which is overkill
     * for a recovery path that runs only when drift > MAX_DRIFT inside a 1-2 s window. The
     * approximation is correct to first order in {@code drift / curvatureRadius}; on the
     * curvatures Create rails produce the residual error is well under one rail-bar-width.
     */
    private static void snapGsToPlayer(Player player, GrindState gs) {
        Vec3 currentTarget = worldPos(gs).add(0, Y_OFFSET, 0);
        double edgeLen = gs.edge.getLength();
        double t = edgeLen <= 0 ? 0 : Math.min(1.0, Math.max(0.0, gs.position / edgeLen));
        Vec3 tangent = sampleTangent(gs.graph, gs.edge, t);
        Vec3 chord = gs.toNode.getLocation().getLocation().subtract(gs.fromNode.getLocation().getLocation());
        if (tangent.x * chord.x + tangent.z * chord.z < 0) tangent = tangent.scale(-1);
        tangent = rotateTangentToWorld(gs, tangent);
        double tangentLen = tangent.length();
        if (tangentLen < 1e-6) {
            // Degenerate edge — fall back to a plain hard-snap, no projection.
            player.setPos(currentTarget.x, currentTarget.y, currentTarget.z);
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
            return;
        }
        Vec3 unitTangent = tangent.scale(1.0 / tangentLen);
        double projectionLength = player.position().subtract(currentTarget).dot(unitTangent);
        gs.position = Math.max(0.0, Math.min(edgeLen, gs.position + projectionLength));
        Vec3 newTarget = worldPos(gs).add(0, Y_OFFSET, 0);
        player.setPos(newTarget.x, newTarget.y, newTarget.z);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
    }

    private static void applyTickMotion(Player player, GrindState gs, double absVelocity) {
        Vec3 target = worldPos(gs).add(0, Y_OFFSET, 0);

        // Diagnostic counter for the debug HUD. Bumped at the TOP of applyTickMotion (before
        // any cancel checks) so a stop() snapshot in the same tick reflects the post-grace
        // tick number. -1 while either counter is still > 0; 0 on the first tick BOTH are 0
        // at the START of applyTickMotion; then +1 per tick. Lets the user verify that a
        // drop fired at ticksSinceGraceEnded=0 happened the first tick after grace fully
        // expired (likely grace-boundary cancel) vs ticksSinceGraceEnded=N (unrelated to grace).
        if (gs.startGraceTicks == 0 && gs.reattachGraceTicks == 0) {
            gs.ticksSinceGraceEnded = (gs.ticksSinceGraceEnded < 0) ? 0 : gs.ticksSinceGraceEnded + 1;
        }

        // Snapshot + decrement the post-reattach kick-suppress counter once per tick at the
        // TOP so all subsequent gates (drift cap, MAX_DRIFT bailout, stuck check) see the
        // same boolean state. While true:
        //   - gs.position is proactively capped against running more than
        //     POST_REATTACH_DRIFT_CAP blocks ahead of where sp.position projects onto the
        //     rail — drift can't reach MAX_DRIFT, so the reactive snap below is unreachable
        //     except for the degenerate-tangent fallthrough.
        //   - STUCK detection is short-circuited (stuckTicks held at 0).
        boolean inPostReattachKickSuppress = gs.postReattachKickSuppressTicks > 0;
        if (inPostReattachKickSuppress) gs.postReattachKickSuppressTicks--;

        // Proactive drift cap. Every tick of the post-reattach kick-suppress window, project
        // sp.position onto the rail edge tangent and constrain gs.position to be at most
        // POST_REATTACH_DRIFT_CAP blocks ahead of the player's projected arclength. When the
        // client's post-grace MovePlayer reports haven't started flowing yet (sp.position
        // frozen at the last grace-tick setPos anchor), this rubber-bands the rail target to
        // wait at cap-distance until the client catches up — drift stays bounded by the cap
        // even on a multi-second loading delay. After the gap closes, sp.position tracks
        // client.position via MovePlayer reports and the cap stops engaging (steady-state
        // drift = RTT × currentSpeed, comfortably below the cap on any reasonable connection).
        //
        // Forward-only: doesn't roll gs.position backward when sp is already at or ahead of
        // target. That case (player overshot or got pushed forward by something off-rail)
        // falls through to the reactive MAX_DRIFT snap below.
        //
        // No setPos here — sp.position drives the rubber-band so the client's predict-correct
        // chase keeps its smooth motion. setPos would teleport sp.position to the rail bar
        // every tick and zero deltaMovement, breaking the predict-correct loop and stalling
        // the player at the entry point.
        //
        // Gated on (startGraceTicks==0 && reattachGraceTicks==0) for the same reason as the
        // MAX_DRIFT check: during either grace window sp.position is anchored to target by
        // the inReattachGrace setPos block, so the projection would always be ~0 (no-op cap).
        if (inPostReattachKickSuppress && gs.startGraceTicks == 0 && gs.reattachGraceTicks == 0) {
            double edgeLen = gs.edge.getLength();
            double t = edgeLen <= 0 ? 0 : Math.min(1.0, Math.max(0.0, gs.position / edgeLen));
            Vec3 capTangent = sampleTangent(gs.graph, gs.edge, t);
            Vec3 capChord = gs.toNode.getLocation().getLocation().subtract(gs.fromNode.getLocation().getLocation());
            if (capTangent.x * capChord.x + capTangent.z * capChord.z < 0) capTangent = capTangent.scale(-1);
            capTangent = rotateTangentToWorld(gs, capTangent);
            double capTangentLen = capTangent.length();
            if (capTangentLen >= 1e-6) {
                Vec3 unitCapTangent = capTangent.scale(1.0 / capTangentLen);
                double capProjection = player.position().subtract(target).dot(unitCapTangent);
                // capProjection < 0 means sp.position is behind target along the tangent.
                // When that lag exceeds the cap, roll gs.position back so target is exactly
                // POST_REATTACH_DRIFT_CAP blocks ahead of where sp projects.
                if (capProjection < -POST_REATTACH_DRIFT_CAP) {
                    gs.position = Math.max(0.0, Math.min(edgeLen,
                            gs.position + capProjection + POST_REATTACH_DRIFT_CAP));
                    target = worldPos(gs).add(0, Y_OFFSET, 0);
                }
            }
        }

        // Catastrophic-desync bailout: at MAX_DRIFT blocks past target, the client's EMA
        // chaser can't recover in any reasonable time — gs.position has run far ahead of
        // where the player physically is. Drop unconditionally (skips STUCK_GRACE_TICKS),
        // no launch impulse — error recovery, not a deliberate dismount.
        //
        // Suppressed during the re-attach grace window AND the fresh-start grace window.
        // The server-side player.position lags client-reported positions by ~latency; on
        // the first ticks after either entry path the client may still be EMA-converging
        // to the snap target, and the drift between server's view and the target can
        // transiently exceed the threshold before convergence completes. reattachGraceTicks
        // ships server-authoritative target packets and pins sp.position to target each
        // tick (see the inReattachGrace anchor block below), so drift is structurally
        // zero while it's > 0; startGraceTicks doesn't anchor but keeps server-side
        // collision active so player.position stays physically constrained — see
        // START_GRACE_TICKS for the full design.
        //
        // The reactive inPostReattachKickSuppress soft-snap branch below is now a SAFETY NET
        // for the degenerate-tangent fallthrough in the proactive cap above. With a non-
        // degenerate edge, the cap clamps drift to POST_REATTACH_DRIFT_CAP < MAX_DRIFT, so
        // this branch never fires during suppress. If tangentLen<1e-6 skips the cap (zero-
        // length edge tangent — sublevel-rotated edges that collapse when projected), the
        // reactive snap catches the resulting drift here: project player onto rail, advance
        // gs.position to match, setPos the player onto the rail bar at the updated position.
        Vec3 playerPos = player.position();
        double dynamicMaxDrift = computeDynamicMaxDrift(player, gs);
        if (gs.reattachGraceTicks == 0 && gs.startGraceTicks == 0
                && playerPos.subtract(target).lengthSqr() > dynamicMaxDrift * dynamicMaxDrift) {
            if (inPostReattachKickSuppress) {
                snapGsToPlayer(player, gs);
                // Refresh target since snapGsToPlayer mutated gs.position.
                target = worldPos(gs).add(0, Y_OFFSET, 0);
                // Ship the post-snap target with serverAuthoritative=true and velocity=ZERO
                // so the client hard-snaps to the new position this tick rather than
                // predict-correct chasing it. Without this, the velocity hint computed by
                // the regular send path below would be (newTarget − oldPrevTarget) which
                // equals the snap distance — large enough for the client to over-extrapolate
                // the next tick and re-create the drift, ping-ponging between snap and
                // overshoot until the kick-suppress counter expires and MAX_DRIFT drops the
                // player for real. Setting prevTarget to the new target also keeps the
                // following tick's normal velocity hint clean.
                gs.prevTarget = target;
                sendTargetToPlayer(player, target, Vec3.ZERO, gs.experiencedSlope, true);
                player.fallDistance = 0.0F;
                // Skip the rest of applyTickMotion — sendTargetToPlayer just ran with the
                // post-snap target, the inReattachGrace anchor block is a no-op here
                // (reattachGraceTicks==0), and the stuck check would short-circuit anyway
                // (inPostReattachKickSuppress was true this tick).
                return;
            } else {
                stop(player, StopReason.MAX_DRIFT);
                return;
            }
        }

        // Ship the latest target + velocity hint to the local grinding client. The
        // client's RailGrindClientMotion runs a predict-correct chase: each client tick
        // it extrapolates a local smoothedTarget by velocity, then corrects toward the
        // most recently received target. That decouples the client's per-tick motion
        // from packet-arrival timing — under network jitter, gaps and bunches of
        // packets no longer translate to per-tick chase-magnitude variation.
        //
        // Velocity is target − prevTarget (last server tick's actual world-space
        // advance). On the very first tick after start / reseed, prevTarget is null
        // and we ship ZERO; the client treats the first packet as a seed rather than
        // an extrapolation step, so an inaccurate velocity here is harmless.
        //
        // The slope rides along so PlayerNoPhysicsTickMixin can gate the conditional
        // noPhysics-bypass on the same value the server reads from GrindState.
        Vec3 velocity = (gs.prevTarget == null) ? Vec3.ZERO : target.subtract(gs.prevTarget);
        // During the cross-dim re-attach window, mark the payload server-authoritative so
        // the client hard-snaps to {@code target} rather than predict-correct chasing.
        boolean inReattachGrace = gs.reattachGraceTicks > 0;
        sendTargetToPlayer(player, target, velocity, gs.experiencedSlope, inReattachGrace);
        gs.prevTarget = target;
        player.fallDistance = 0.0F;

        // Server-authoritative anchor — Create-train-style. While reattach grace is active,
        // pin sp.position to target every tick. The client is hard-snapping to the same
        // value via the serverAuthoritative payload flag, so the next MovePlayer report
        // it sends will match — no drift. setPos doesn't send a packet to the player
        // themselves (that's via the target payload above + LocalPlayer's own sendPosition),
        // so this is cheap. Without the anchor, the server's sp.position would track
        // whatever MovePlayer packets the client sent during the new-dim respawn window —
        // typically a few ticks of vanilla physics on a stale-noGravity LocalPlayer — and
        // the post-grace MAX_DRIFT check would kick on the first ticks of resumed motion.
        if (inReattachGrace) {
            player.setPos(target.x, target.y, target.z);
            player.setDeltaMovement(Vec3.ZERO);
            // Decrement here so every tick that calls applyTickMotion (including the
            // currentSpeed<=1e-6 early-return path above the main advance loop) progresses
            // grace exactly once. Paths that return without calling applyTickMotion (transit,
            // stop, etc.) end the grind anyway, so missing the decrement on them is fine.
            gs.reattachGraceTicks--;
            if (gs.reattachGraceTicks == 0) {
                if (gs.frozenAtReattachStart) {
                    // Timeout failsafe: the client never sent CrossDimGraceReleasePayload
                    // (loading screen lingered past the latency-scaled timeout, or the
                    // player relogged, or the packet was dropped). Force-unfreeze and re-
                    // arm a short post-ack transition tail so the position-advance ramps
                    // up under hard-snap rather than dumping into predict-correct from
                    // standstill. After this branch, the next applyTickMotion call sees
                    // inReattachGrace=true (counter back to AFTER_ACK_TICKS), motion has
                    // resumed (frozen=false in tick), and the client keeps hard-snapping
                    // through the tail.
                    gs.frozenAtReattachStart = false;
                    gs.reattachGraceTicks = REATTACH_GRACE_AFTER_ACK_TICKS;
                } else {
                    // Post-grace housekeeping. totalTicks=0 lets STUCK_GRACE_TICKS=3 cover the
                    // brief tail where the client transitions from hard-snap to predict-correct
                    // and server-side absVelocity is still settling. Portal-transit cooldown
                    // reseed prevents the mixin / tryPortalTransit / tryPortalTransitFromNode
                    // from re-firing instantly when the post-grace snap landed inside/against
                    // a portal block (common on nether-side rails right at the portal frame).
                    // See the REATTACH_GRACE_BASE_TICKS comment for the full rationale.
                    //
                    // postReattachKickSuppressTicks: latency-scaled extra suppression for BOTH
                    // STUCK detection and gs.position drift in the post-handoff window.
                    // STUCK_GRACE_TICKS=3 alone is too short to cover MP latency, and during
                    // the gap the server's setPos anchor has released but the post-grace
                    // MovePlayer C2S packet hasn't arrived → sp.position stale, absVelocity ≈ 0,
                    // and meanwhile gs.position is racing ahead at currentSpeed each tick.
                    // STUCK gets short-circuited; gs.position is proactively capped to at most
                    // POST_REATTACH_DRIFT_CAP blocks ahead of where sp.position projects onto
                    // the rail, so MAX_DRIFT structurally cannot fire. See the field doc on
                    // GrindState#postReattachKickSuppressTicks for the full mechanism.
                    gs.totalTicks = 0;
                    seedPortalTransitCooldown(player);
                    gs.postReattachKickSuppressTicks = latencyScaledPostReattachKickSuppress(player);
                }
            }
        }

        // Stuck detection: drop the grind if the player's actual per-tick displacement is
        // below STUCK_VELOCITY_THRESHOLD — i.e., they aren't moving despite the EMA push.
        // Velocity-based (not distance-based) detection only fires for actual stuck
        // conditions (mod yanking the player, physics overriding deltaMovement, etc.) and
        // doesn't false-trip on legitimate high-speed grinds where currentSpeed temporarily
        // outpaces the client's EMA settle rate.
        // Four suppression gates:
        //   - startGraceTicks > 0: fresh start, server's player.position is still catching
        //     up to the client's chase position; absVelocity reads ~0 here even when the
        //     grind is functioning correctly. See START_GRACE_TICKS for the design.
        //   - reattachGraceTicks > 0: cross-dim re-attach, the server is anchoring
        //     sp.position to target every tick (see the inReattachGrace block above), so
        //     absVelocity measures the anchor-to-anchor delta which equals the client's
        //     hard-snap delta — fine in steady state, but the very first tick after a
        //     cross-dim teleport could read whatever vanilla physics did before the anchor
        //     ran. Better to skip the check outright while the anchor is active.
        //   - totalTicks <= STUCK_GRACE_TICKS: post-reattach settling window (tick()
        //     resets totalTicks = 0 when reattach grace ends), and historical fallback
        //     for paths that don't seed startGraceTicks.
        //   - inPostReattachKickSuppress: latency-scaled extension of the previous gate,
        //     seeded specifically when reattach grace ends. STUCK_GRACE_TICKS=3 alone is too
        //     short to cover round-trip MovePlayer latency on MP connections — the server's
        //     setPos anchor has released but the first post-grace MovePlayer C2S packet hasn't
        //     arrived, so sp.position stays frozen at the last anchor target and absVelocity
        //     reads ~0 → false-positive STUCK firing 3 ticks past grace end. The same boolean
        //     also gates the MAX_DRIFT soft-snap recovery above; both are decremented once at
        //     the top of applyTickMotion so the two checks see consistent suppression state.
        if (gs.startGraceTicks > 0
                || gs.reattachGraceTicks > 0
                || gs.totalTicks <= STUCK_GRACE_TICKS
                || inPostReattachKickSuppress) {
            gs.stuckTicks = 0;
        } else if (absVelocity < STUCK_VELOCITY_THRESHOLD) {
            gs.stuckTicks++;
            if (gs.stuckTicks >= STUCK_DROP_TICKS) {
                stop(player, StopReason.STUCK);
            }
        } else {
            gs.stuckTicks = 0;
        }
    }

    /**
     * Send the per-tick target position + velocity hint to the grinding player. No-op for
     * non-ServerPlayer targets (e.g., fake-player wrappers used by some mods), since the
     * payload distributor needs a ServerPlayer connection to send through.
     *
     * <p>{@code velocity} is the target's per-tick advance in world space (currentTarget −
     * previousTarget); pass {@link Vec3#ZERO} for the first tick of a grind or right after
     * a teleport/reseed where there is no meaningful previous target.
     *
     * <p>{@code serverAuthoritative} marks the cross-dim re-attach window where the client
     * should hard-snap to {@code target} (rather than running predict-correct) so server↔
     * client position stays bit-identical while the new LocalPlayer is still settling.
     * See {@code RailGrindClientMotion.onClientTickPre}.
     */
    private static void sendTargetToPlayer(Player player, Vec3 target, Vec3 velocity, double slope,
                                           boolean serverAuthoritative) {
        if (player instanceof ServerPlayer sp) {
            PacketDistributor.sendToPlayer(sp, new RailGrindTargetPayload(
                target.x, target.y, target.z,
                velocity.x, velocity.y, velocity.z,
                slope,
                serverAuthoritative));
        }
    }

    /**
     * Null-aware sublevel-transform helpers. Each takes a GrindState and a value in
     * sublevel-local frame; if {@code gs.subLevel} is null (parent-world grind) the value is
     * returned unchanged, otherwise it routes through the sublevel handle. Centralizes the
     * "if (subLevel != null) apply pose, else pass through" branch that the worldPos / tangent
     * call sites all share.
     */
    private static Vec3 localToWorld(GrindState gs, Vec3 local) {
        return gs.subLevel == null ? local : gs.subLevel.toWorld(local);
    }

    private static Vec3 rotateTangentToWorld(GrindState gs, Vec3 localUnit) {
        return gs.subLevel == null ? localUnit : gs.subLevel.rotateNormalToWorld(localUnit);
    }

    private static Vec3 sublevelVelocityAt(GrindState gs, Vec3 worldPos) {
        return gs.subLevel == null ? Vec3.ZERO : gs.subLevel.sublevelVelocityAt(worldPos);
    }

    /**
     * Local-frame rail-bar position at parameter t along {@code gs.edge}. Mirrors the
     * lateral-offset geometry {@link #worldPos} applies internally; broken out so
     * {@link #railBarSpeedFactor} can sample it at neighboring t values. Skips the
     * sublevel pose because callers consume only relative-length ratios, which the
     * rigid local→world transform preserves — avoiding the matrix multiply where it
     * makes no difference.
     */
    private static Vec3 localBarPos(GrindState gs, double t) {
        Vec3 pos = gs.edge.getPosition(gs.graph, t);
        Vec3 dir = sampleTangent(gs.graph, gs.edge, t);
        Vec3 chord = gs.toNode.getLocation().getLocation().subtract(gs.fromNode.getLocation().getLocation());
        if (dir.x * chord.x + dir.z * chord.z < 0) dir = dir.scale(-1);
        double horizLen = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        if (horizLen < 1e-6) return pos;
        double off = gs.lateralOffset * gs.lateralSign;
        double rx = -dir.z / horizLen * off;
        double rz =  dir.x / horizLen * off;
        return pos.add(rx, 0, rz);
    }

    /**
     * Ratio of rail-bar arclength to spline-centerline arclength at the player's
     * current edge parameter. On a straight the perpendicular doesn't rotate, so bar
     * and center arclength match exactly (returns 1.0). On a turn the outer rail
     * traces a longer arc than the centerline (ratio &gt; 1) and the inner rail a
     * shorter one (ratio &lt; 1) — same effect that makes the outer wheel of a real
     * vehicle spin faster than the inner one through a corner.
     *
     * <p>Used in {@link #tick} to scale the per-tick gs.position advance budget.
     * {@code gs.currentSpeed} represents the player's intended world speed, but
     * {@code gs.position} advances in centerline-arclength units (mapped to t via
     * {@code gs.position / edgeLen}). Without this scale, the same currentSpeed
     * on an outer-rail grind produces a larger world step per tick than on the
     * centerline — visible to the player as a speed gain when they enter the
     * outer side of a turn (and a slowdown on the inner side). Dividing the
     * advance budget by this ratio undoes that geometric asymmetry so both bars
     * yield the same world speed.
     *
     * <p>Centered finite difference at ±{@code eps} around the current t.
     * eps = 0.005 is large enough to swamp per-sample numerical noise in
     * {@code edge.getPosition} while staying local enough to capture curvature
     * at the player's actual position. Sublevel pose is skipped because the rigid
     * local→world transform preserves lengths, so local-frame and world-frame
     * ratios are identical.
     */
    private static double railBarSpeedFactor(GrindState gs) {
        double edgeLen = gs.edge.getLength();
        if (edgeLen < 1e-6) return 1.0;
        double t = Math.min(1.0, Math.max(0.0, gs.position / edgeLen));
        final double eps = 0.005;
        double t0 = Math.max(0.0, t - eps);
        double t1 = Math.min(1.0, t + eps);
        if (t1 - t0 < 1e-9) return 1.0;

        Vec3 c0 = gs.edge.getPosition(gs.graph, t0);
        Vec3 c1 = gs.edge.getPosition(gs.graph, t1);
        double cLen = c1.subtract(c0).length();
        if (cLen < 1e-9) return 1.0;

        Vec3 b0 = localBarPos(gs, t0);
        Vec3 b1 = localBarPos(gs, t1);
        double bLen = b1.subtract(b0).length();

        return bLen / cLen;
    }

    /**
     * Is this track type one we know how to grind on? STANDARD covers vanilla Create rails;
     * the two Steam'n'Rails gauges (narrow/wide) are detected by ResourceLocation ID so the
     * compat is soft — the railways modid being absent just means the IDs never match.
     *
     * <p>This is the gauge-only gate. {@link #isGrindableMaterial(TrackMaterial)} layers the
     * additional rail-variety filter on top, and is what callers should use whenever they have
     * the {@link TrackMaterial} in hand. The gauge-only entry point stays for diagnostic paths
     * that only have a {@code TrackType} (sound/UI filtering on remote players, etc.).
     */
    public static boolean isGrindableTrackType(TrackMaterial.TrackType type) {
        if (type == TrackMaterial.TrackType.STANDARD) return true;
        if (type == null || type.id == null) return false;
        return type.id.equals(RAILWAYS_NARROW_GAUGE) || type.id.equals(RAILWAYS_WIDE_GAUGE);
    }

    /**
     * Full rail-variety + gauge gate. The user's mental model is "rail-grinding works on the
     * standard, narrow, and wide gauges of *regular* rails" — phantom (and any addon-mod gauge
     * variant of phantom) is a different rail variety even when its underlying TrackType is one
     * of the three gauges.
     *
     * <p>Why a separate id check is required: Steam'n'Rails builds {@code WIDE_GAUGE_<base>} /
     * {@code NARROW_GAUGE_<base>} variants by calling its {@code wideVariant(base)} /
     * {@code narrowVariant(base)} factories, which both overwrite the base material's TrackType
     * with {@code CRTrackType.WIDE_GAUGE} or {@code NARROW_GAUGE}. So the base PHANTOM material's
     * UNIVERSAL TrackType (correctly rejected by {@link #isGrindableTrackType}) does NOT
     * propagate to its narrow / wide variants — those variants pass the gauge gate alone and
     * have to be filtered by their material id instead.
     *
     * <p>The id-substring check on {@code "phantom"} catches both the canonical
     * {@code railways:narrow_phantom} / {@code railways:wide_phantom} ids and any addon-mod
     * variants that follow the same naming convention. Returning false on a null material is
     * the safe default — callers that genuinely don't know the material can't have a valid
     * grindable rail to work with.
     */
    public static boolean isGrindableMaterial(@Nullable TrackMaterial material) {
        if (material == null) return false;
        ResourceLocation id = material.id;
        if (id != null && id.getPath().contains("phantom")) return false;
        return isGrindableTrackType(material.trackType);
    }

    /**
     * True iff this track type is Steam'n'Rails wide gauge. Used by {@link #findNearestRailLocation}
     * to give wide-gauge candidates the {@link #WIDE_GAUGE_START_RADIUS_BONUS} extra reach
     * that matches their +0.5 outboard rail-bar offset.
     */
    private static boolean isWideGaugeTrackType(TrackMaterial.TrackType type) {
        return type != null && type.id != null && type.id.equals(RAILWAYS_WIDE_GAUGE);
    }

    /**
     * Rail-bar lateral offset for this track type. Narrow gauge rides 0.5 closer to the
     * centerline; wide gauge rides 0.5 further out. Anything we don't know about falls back
     * to the standard offset so future track types don't silently break the geometry.
     */
    private static double lateralOffsetForType(TrackMaterial.TrackType type) {
        if (type == null || type.id == null) return LATERAL_OFFSET;
        if (type.id.equals(RAILWAYS_NARROW_GAUGE)) return LATERAL_OFFSET_NARROW;
        if (type.id.equals(RAILWAYS_WIDE_GAUGE)) return LATERAL_OFFSET_WIDE;
        return LATERAL_OFFSET;
    }

    /**
     * Probe for an {@link ITrackBlock} near {@code probe} and return its trackType, or null if
     * nothing was found. Tries the containing BlockPos, one block below (node locations sit at
     * the rail's top surface so the containing block is often the air above), then a 3×3 column
     * sweep down two Y levels — this catches the corner-endpoint case where BlockPos.containing
     * lands on the air block beyond the rail chain rather than above the rail itself.
     *
     * <p>Returns null (not STANDARD) when no rail is found so callers can distinguish "the rail
     * is genuinely standard" from "we probed the wrong spot" and keep trying other candidate
     * positions before falling back to the default offset.
     */
    private static @Nullable TrackMaterial.TrackType resolveTrackTypeNear(Level level, Vec3 probe) {
        BlockPos bp = BlockPos.containing(probe);
        BlockState s = level.getBlockState(bp);
        if (s.getBlock() instanceof ITrackBlock t) return t.getMaterial().trackType;
        BlockState below = level.getBlockState(bp.below());
        if (below.getBlock() instanceof ITrackBlock t) return t.getMaterial().trackType;
        // Edge-endpoint fallback: a node corner can have BlockPos.containing land on the air
        // block diagonally adjacent to the rail (e.g. a 1-block rail's east-side node, where
        // the rail is at (x, y, z) but the node sits at (x+1, y+1, z+0.5) → containing → air at
        // (x+1, y+1, z); below() → air at (x+1, y, z) since the rail chain ends at x). Scan the
        // 3×3 horizontal neighborhood at the node's Y and one below to recover the actual rail.
        for (int dy = 0; dy >= -1; dy--) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0 && (dy == 0 || dy == -1)) continue;  // already probed
                    BlockState near = level.getBlockState(bp.offset(dx, dy, dz));
                    if (near.getBlock() instanceof ITrackBlock t) return t.getMaterial().trackType;
                }
            }
        }
        return null;
    }

    /**
     * Pick the lateral offset for the rail the player is currently riding. Samples several
     * candidate positions on the edge — the player's spawn point on the centerline first, then
     * the edge midpoint, then the two endpoints — and returns the first match. This redundancy
     * is what makes the detection robust at end-of-chain endpoints: a node-only probe misses
     * 1-block rails entirely, because BlockPos.containing on the far-side node corner lands
     * past the rail block (see {@link #resolveTrackTypeNear} for the corner-case detail).
     *
     * <p>For sublevel grinds the spline coords and tangent live in the sublevel's local frame,
     * so we sample the sublevel's level when {@code subLevel} is non-null — the probe Vec3s
     * are already in that frame because they come from the same graph the grind state uses.
     */
    private static double resolveLateralOffset(Level level, TrackGraph graph, TrackEdge edge,
                                               @Nullable Vec3 hint, TrackNode fromNode, TrackNode toNode,
                                               @Nullable SableSubLevels.SubLevelHandle subLevel) {
        Level sampleLevel = level;
        if (subLevel != null) {
            Level sl = subLevel.getLevel();
            if (sl != null) sampleLevel = sl;
        }
        Vec3[] candidates = new Vec3[] {
                hint,
                edge == null ? null : edge.getPosition(graph, 0.5),
                fromNode == null ? null : fromNode.getLocation().getLocation(),
                toNode == null ? null : toNode.getLocation().getLocation(),
        };
        for (Vec3 c : candidates) {
            if (c == null) continue;
            TrackMaterial.TrackType type = resolveTrackTypeNear(sampleLevel, c);
            if (type != null) return lateralOffsetForType(type);
        }
        return LATERAL_OFFSET;
    }

    private static Vec3 worldPos(GrindState gs) {
        double edgeLen = gs.edge.getLength();
        double t = edgeLen <= 0 ? 0 : Math.min(1.0, Math.max(0.0, gs.position / edgeLen));
        Vec3 pos = gs.edge.getPosition(gs.graph, t);

        // Lateral offset from spline centerline to the rail bar. Uses the smooth bezier-aware
        // tangent so the perpendicular rotates correctly through curves — using
        // getDirectionAt(t) here would lock the perpendicular to the chord on bezier edges,
        // making the player visually drift off the rail bar through any turn.
        //
        // For sublevel grinds the spline coords and tangent live in the sublevel's local
        // frame; the local→world pose transform is the final step. Doing the lateral offset
        // in local space keeps the rail-bar perpendicular correct even on rotated sublevels.
        Vec3 dir = sampleTangent(gs.graph, gs.edge, t);
        Vec3 chord = gs.toNode.getLocation().getLocation().subtract(gs.fromNode.getLocation().getLocation());
        if (dir.x * chord.x + dir.z * chord.z < 0) dir = dir.scale(-1);
        double horizLen = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        Vec3 local = pos;
        if (horizLen >= 1e-6) {
            double off = gs.lateralOffset * gs.lateralSign;
            double rx = -dir.z / horizLen * off;
            double rz =  dir.x / horizLen * off;
            local = pos.add(rx, 0, rz);
        }
        return localToWorld(gs, local);
    }

    /**
     * Pick the next edge at an intersection — same algorithm Create's
     * {@code TravellingPoint.steer(SteerDirection, Vec3)} uses for player-controlled trains,
     * and same candidate-list filtering Create's {@code TravellingPoint.travel} applies before
     * passing the list to the selector.
     *
     * <p>Two-stage selection:
     * <ol>
     *   <li><b>Filter</b>: skip the back-edge (neighbor we came from) and any candidate whose
     *       start tangent isn't continuous with the current end tangent — the latter via
     *       {@link TrackEdge#canTravelTo(TrackEdge)}, which returns true only when
     *       {@code currentDir · candidateDir > 0.875} (≈ cos 29°). This is the same gate Create's
     *       {@code TravellingPoint.travel} applies (see its inner loop calling
     *       {@code this.edge.canTravelTo(candidate)} before adding to the option list). It's
     *       what stops the player from picking a perpendicular exit at a 4-way crossing or
     *       jumping to the other branch at a Y-merge — both would require physically reversing,
     *       which trains can't do here either.</li>
     *   <li><b>Pick</b>: among the survivors, compute the lateral signed projection
     *       {@code dot = (currentDir × up) · candidateDir} (straight ≈ 0, left negative, right
     *       positive) and pick whichever is closest to {@code targetDot = steerSign}
     *       (−1 / 0 / +1). targetDot = 0 reproduces the "go straightest" behavior; ±1 lets the
     *       player pick the leftmost / rightmost branch at a Y-fork.</li>
     * </ol>
     *
     * <p>{@code currentDir} is the tangent at the END of the edge we're leaving
     * ({@code gs.edge.getDirection(false)}, where {@code false} means the node2 side =
     * {@code gs.toNode}). Each candidate's {@code candidateDir} is its tangent at the
     * START ({@code getDirection(true)} = node1 side = {@code atNode}).
     */
    private static boolean advanceJunction(GrindState gs, Player player) {
        TrackNode atNode = gs.toNode;
        TrackNode previousFrom = gs.fromNode;

        Map<TrackNode, TrackEdge> connections = gs.graph.getConnectionsFrom(atNode);
        if (connections == null || connections.isEmpty()) return false;

        Vec3 currentDir = gs.edge.getDirection(false);
        Vec3 cross = currentDir.cross(new Vec3(0, 1, 0));
        double targetDot = gs.steerSign;

        TrackNode bestNeighbor = null;
        TrackEdge bestEdge = null;
        double bestDiff = Double.MAX_VALUE;

        for (Map.Entry<TrackNode, TrackEdge> entry : connections.entrySet()) {
            TrackNode neighbor = entry.getKey();
            if (neighbor == previousFrom) continue;
            TrackEdge candidate = entry.getValue();
            // Forward-continuity filter (cos 29° threshold). See javadoc above.
            if (!gs.edge.canTravelTo(candidate)) continue;
            // Gauge gate. A player on standard rails must end here when the next edge is
            // narrow/wide (and vice versa) — different gauges are physically different rail
            // widths, and treating them as a continuous spline would let the player teleport
            // sideways onto the new rail bar. Same intent as Steam'n'Rails' own train
            // gauge filter in MixinNavigation (line 310) and MixinCarriage's per-tick
            // incompatibility check: UNIVERSAL is the wildcard, everything else must match
            // by TrackType id. {@code continue} (not return false) so a multi-way junction
            // where one branch happens to be a different gauge can still pick a same-gauge
            // branch instead of dropping unnecessarily.
            if (!gaugeCompatibleForGrind(gs.railTrackType, trackTypeOf(candidate))) continue;
            // Rail-variety gate. gaugeCompatibleForGrind only checks the TrackType id, so a
            // narrow→narrow_phantom transition (both have CRTrackType.NARROW_GAUGE) would
            // pass it. Re-check the candidate edge's material here so a player on a regular
            // narrow rail can't cross into a phantom-variant branch at an intersection.
            // {@code continue} rather than {@code return false} so a multi-way junction where
            // one branch happens to be a phantom variant can still pick the same-variety
            // branch instead of dropping the grind entirely.
            if (!isGrindableMaterial(candidate.getTrackMaterial())) continue;
            Vec3 candidateDir = candidate.getDirection(true);
            double dot = cross.dot(candidateDir);
            double diff = Math.abs(targetDot - dot);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestNeighbor = neighbor;
                bestEdge = candidate;
            }
        }

        // No forward continuation → end of track (or only perpendicular / reverse exits exist,
        // or every forward exit is a different gauge). Same dropout behavior as before:
        // returning false causes tick() to call stop(player, StopReason.END_OF_TRACK) cleanly
        // rather than reversing. Mirrors TravellingPoint setting blocked=true and Train.tick()
        // halting.
        if (bestEdge == null) return false;

        gs.fromNode = atNode;
        gs.toNode = bestNeighbor;
        gs.edge = bestEdge;
        gs.position = 0;
        // Crossing into a new edge — re-resolve in case we just walked from a standard rail
        // onto a narrow- or wide-gauge section (or vice versa). The resolver will probe the
        // edge midpoint and both endpoints, so passing a null hint just defers to that built-in
        // candidate list — and the midpoint sample is the most reliable single probe across
        // straights, slopes, and short chains where node corners can fall past the rail.
        gs.lateralOffset = resolveLateralOffset(player.level(), gs.graph, bestEdge, null, atNode, bestNeighbor, gs.subLevel);
        // Refresh the gauge identity. Normally a no-op (the gauge gate above only lets through
        // matching candidates), but the UNIVERSAL wildcard branch can legitimately step from
        // UNIVERSAL onto a typed edge (or the reverse) — re-seeding here pins the new edge's
        // actual type so the next junction filters against what we're really on.
        gs.railTrackType = trackTypeOf(bestEdge);
        return true;
    }

    /**
     * True iff the edge {@code gs} is currently riding is still wired into its TrackGraph.
     * When a player breaks a rail block or a bezier curve's parent {@link TrackBlockEntity},
     * Create removes the corresponding {@link TrackNode}s from {@link TrackGraph#connectionsByNode}
     * (and may split the graph via {@code findDisconnectedGraphs}). The {@code gs} reference
     * still points at the orphaned {@link TrackEdge} and the motion loop would otherwise keep
     * tracing its spline as if the rail were still there — most visibly on curves, where the
     * player floats along the bezier after the supporting blocks are gone.
     *
     * <p>Identity check ({@code == gs.edge}) rather than null-only: a graph rebuild that
     * reconnects the same two nodes with a fresh edge instance means the player is now riding
     * a rail they didn't choose; treat that as a removal and let the player re-grind.
     */
    private static boolean isCurrentEdgePresent(GrindState gs) {
        if (gs.graph == null || gs.fromNode == null || gs.toNode == null || gs.edge == null) return false;
        Map<TrackNode, TrackEdge> conns = gs.graph.getConnectionsFrom(gs.fromNode);
        if (conns == null) return false;
        return conns.get(gs.toNode) == gs.edge;
    }

    /**
     * Pull the {@link TrackMaterial.TrackType} from an edge. Null-safe so callers can use the
     * result in the gauge gate without explicit null checks (a null type means "unknown" and
     * the gauge gate is permissive in that case). Same API Steam'n'Rails uses for its own
     * gauge filtering (e.g. {@code MixinCarriage.railways$isIncompatible},
     * {@code MixinNavigation.railways$searchGeneral}).
     */
    @Nullable
    private static TrackMaterial.TrackType trackTypeOf(@Nullable TrackEdge edge) {
        if (edge == null) return null;
        TrackMaterial mat = edge.getTrackMaterial();
        return mat == null ? null : mat.trackType;
    }

    /**
     * True when a player riding a {@code current}-gauge rail is allowed to continue onto a
     * {@code candidate}-gauge rail. Same rule Steam'n'Rails applies to its own train
     * compatibility checks: matching ids are compatible, UNIVERSAL is a wildcard on either
     * side, and a null on either side is treated permissively so unknown / unresolved types
     * don't false-drop a grind.
     */
    private static boolean gaugeCompatibleForGrind(@Nullable TrackMaterial.TrackType current,
                                                   @Nullable TrackMaterial.TrackType candidate) {
        if (current == null || candidate == null) return true;
        if (current == candidate) return true;
        ResourceLocation curId = current.id;
        ResourceLocation candId = candidate.id;
        if (curId == null || candId == null) return true;
        if (curId.equals(RAILWAYS_UNIVERSAL) || candId.equals(RAILWAYS_UNIVERSAL)) return true;
        return curId.equals(candId);
    }

    private static void syncPose(Player player, boolean grinding) {
        if (player.level().isClientSide) {
            BalancingPoseTracker.setBalancing(player.getUUID(), grinding);
        } else if (player instanceof ServerPlayer serverPlayer) {
            // Single-shot per-UUID side-channel: portal-transit paths call markNextStartSilent
            // right before triggering a grinding=true sync, and we consume the marker here so
            // the outgoing payload tells the client to skip playCollide and the dismount prompt.
            // Only consumed when grinding=true (a dismount sync would never want the silent flag
            // and shouldn't strip a marker meant for the next real start).
            boolean silent = grinding && SILENT_NEXT_START.remove(player.getUUID());
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                serverPlayer, new RailGrindSyncPayload(player.getUUID(), grinding, silent));
        }
    }

    /**
     * Ship the current grind state of {@code target} to {@code observer}'s client. Called from
     * {@link net.juniknytt.createrailgrinding.event.ModEvents#onStartTrackingPlayer} so the new
     * observer's {@link BalancingPoseTracker} (and the sound / noPhysics state derived from it)
     * is seeded the moment the entity enters tracking range — chunk-load proximity or cross-dim
     * arrival. Without this seeding the observer never receives the one-shot start sync that
     * fired when {@code target} began grinding, so:
     *   <ul>
     *     <li>If {@code target} is grinding now → observer sees vanilla pose with no sound.</li>
     *     <li>If {@code target} stopped grinding while outside the observer's range → the
     *         observer's pose tracker may still hold a stale {@code grinding=true} entry from
     *         a prior tracking session, manifesting as a phantom grind pose + sounds.</li>
     *   </ul>
     * <p>{@code silent=true} suppresses {@code playCollide} and the dismount-prompt overlay on
     * the receiving side — a tracking-start is never a fresh grind start. When {@code grinding=false}
     * the silent flag is a no-op on the client (both gates require {@code grinding=true}), so
     * stale-clear syncs share the same payload shape.
     */
    public static void syncStateToObserver(ServerPlayer observer, ServerPlayer target) {
        PacketDistributor.sendToPlayer(observer,
            new RailGrindSyncPayload(target.getUUID(), isGrinding(target), true));
        // Seed lean state for the lean visual on the new observer. Edge-triggered broadcasts
        // from setSteerInput don't reach an observer who started tracking mid-grind, so
        // without this seed a remote player holding A/D would appear upright until the next
        // steer-sign flip. Only sent for non-zero leans: an observer's tracker initializes
        // to "no lean" already, so a steerSign==0 seed would be redundant traffic.
        GrindState gs = ACTIVE.get(target.getUUID());
        if (gs != null && gs.steerSign != 0) {
            PacketDistributor.sendToPlayer(observer,
                new RailGrindLeanSyncPayload(target.getUUID(), (byte) gs.steerSign));
        }
    }

    // See syncPose: portal-driven re-grind paths (finishCrossDimRegrind, teleportThroughGraphHop)
    // add the player's UUID right before invoking the code that emits the grinding=true sync.
    // The entry is consumed exactly once on the next such sync — never sticks around to silence
    // a later, unrelated grind start.
    private static final java.util.Set<UUID> SILENT_NEXT_START = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void markNextStartSilent(UUID uuid) {
        SILENT_NEXT_START.add(uuid);
    }

    /**
     * Build the per-tick debug snapshot for {@code player} and ship it to that player only,
     * so their client-side {@link net.juniknytt.createrailgrinding.client.RailGrindDebugHud}
     * and {@link net.juniknytt.createrailgrinding.client.RailGrindDebugRenderer} can render
     * without owning a server-authoritative ACTIVE map. Caller is responsible for the config
     * gate ({@code Config.SYNC_DEBUG_TO_CLIENTS}); this method only knows how to assemble and
     * send. Always sends, even for non-grinding players, so the always-on overlap/cooldown
     * lines stay current.
     */
    public static void broadcastDebugSnapshot(ServerPlayer player) {
        GrindDebugInfo info = getGrindDebugInfo(player);
        GrindFrame frame = getGrindFrame(player);
        int overlap = getTrainOverlapTicks(player);
        int fallImmunity = getFallImmunityRemaining(player);
        int startCooldown = getStartCooldownRemaining(player);
        LastDropHudState lastDrop = getLastDropHudState(player);
        boolean hasLastDrop = lastDrop != null;
        int lastDropReasonOrd = hasLastDrop ? lastDrop.reason().ordinal() : StopReason.UNKNOWN.ordinal();
        int lastDropTicksSinceGraceEnded = hasLastDrop ? lastDrop.ticksSinceGraceEnded() : -1;

        RailGrindDebugSyncPayload payload;
        if (info != null && frame != null) {
            payload = new RailGrindDebugSyncPayload(
                    true,
                    info.currentSpeed(), info.targetSpeed(), info.acceleration(), info.topSpeed(),
                    info.experiencedSlope(), info.experiencedCurve(), info.position(), info.edgeLength(),
                    info.stuckTicks(), info.totalTicks(),
                    info.lateralSign(), info.edgeIsTurn(), info.crouchAccelerating(), info.collidingWithTrain(),
                    frame.origin().x, frame.origin().y, frame.origin().z,
                    frame.tangent().x, frame.tangent().y, frame.tangent().z,
                    frame.snapTarget().x, frame.snapTarget().y, frame.snapTarget().z,
                    overlap, fallImmunity, startCooldown,
                    info.driftMargin(),
                    info.reattachGraceTicks(),
                    info.ticksSinceGraceEnded(),
                    hasLastDrop, lastDropReasonOrd, lastDropTicksSinceGraceEnded);
        } else {
            payload = new RailGrindDebugSyncPayload(
                    false,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, false, false,
                    0, 0, 0, 0, 0, 0, 0, 0, 0,
                    overlap, fallImmunity, startCooldown,
                    Double.NaN,
                    0,
                    -1,
                    hasLastDrop, lastDropReasonOrd, lastDropTicksSinceGraceEnded);
        }
        PacketDistributor.sendToPlayer(player, payload);
    }
}
