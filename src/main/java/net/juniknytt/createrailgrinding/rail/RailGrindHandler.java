package net.juniknytt.createrailgrinding.rail;

import com.simibubi.create.Create;
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
import net.juniknytt.createrailgrinding.Config;
import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.client.BalancingPoseTracker;
import net.juniknytt.createrailgrinding.network.GrindAccelInputPayload;
import net.juniknytt.createrailgrinding.network.RailGrindSyncPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
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
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RailGrindHandler {
    private static final Map<UUID, GrindState> ACTIVE = new ConcurrentHashMap<>();
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
    private static final double DOWNHILL_FACTOR = 0.9;                    // top speed × (1 + |slope| · 0.9) on descents
    private static final double UPHILL_FACTOR = 1.5;                      // top speed × (1 − slope · 1.5) on ascents — steep up gets noticeably slow
    private static final double DOWNHILL_CRUISE_MIN_FRACTION = 0.75;      // un-sneaked descent target floor: at a gentle downward slope, target = 75% topSpeed. Lifts the no-shift coast well above CRUISE_SPEED so gravity carries the player meaningfully even without holding shift.
    private static final double DOWNHILL_CRUISE_MAX_FRACTION = 1.00;      // un-sneaked descent target ceiling: at a max downward slope, target = 100% topSpeed. Sneaked top can still exceed this via DOWNHILL_FACTOR, so shift remains the way to push past cruise.
    private static final double DOWNHILL_ACCEL_BOOST = 5.0;               // accel up to 5× on the steepest descents — gives downhill grinding a sneak-tier kick. The slope-based bonus is further scaled by Config.DOWNWARD_MOMENTUM_GAIN (0.1–2.0) for server-side tuning.
    private static final double CURVE_FACTOR = 0.75;                      // bezier turns trim 25%
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
    private static final double LATERAL_OFFSET = 1.0;                  // 5/16 — matches the rail bar's position within the track block, so the player rides on the bar instead of the block centerline. Side is picked once at grind init from the player's pre-teleport position (GrindState.lateralSign).
    private static final double MAX_STEP = 2.0;                           // smooth catch-up cap (~40 m/s) — must exceed TOP_SPEED · downhill boost
    private static final double REALIGN_GAIN = 0.15;                      // per-tick fraction of (playerPos − expectedPos) folded into expectedPos. Continuous soft drift correction: ≈0 when on track (playerPos ≈ expectedPos in steady state), proportional to drift when desync occurs. Replaces the prior binary box-threshold realign that toggled the velocity reference and produced visible jitter on bezier curves.
    // Half-extents of the rendered snap-target box (local frame: +Z = travel-aligned tangent,
    // +X = horizontal right-of-travel, +Y = perpendicular up). Visualization-only —
    // RailGrindDebugRenderer reads these to draw the cubes; the realignment behavior is
    // continuous (REALIGN_GAIN) and no longer threshold-driven, so these don't gate any logic.
    public static final double SNAP_BOX_HALF_W = 0.15;
    public static final double SNAP_BOX_HALF_H = 0.15;
    public static final double SNAP_BOX_HALF_L = 0.40;
    private static final double MAX_DRIFT = 10.0;                         // distance from snap target above which we hard-drop the grind (catastrophic desync — gs.position has advanced somewhere the player can't follow). Bypasses STUCK_GRACE_TICKS because at this scale the cause isn't sync lag.
    private static final double STUCK_VELOCITY_THRESHOLD = 0.05;          // per-tick displacement (blocks) below which the player counts as "not moving" — well under MIN_SPEED so legitimate steep-climb grinding never trips it
    private static final int STUCK_DROP_TICKS = 3;                        // 3 consecutive stuck ticks (after grace) → drop from grind
    private static final int STUCK_GRACE_TICKS = 8;                       // first 0.4 s of grind: ignore stuck (let noPhysics sync to client)
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

    private RailGrindHandler() {}

    private static final class GrindState {
        final TrackGraph graph;
        TrackNode fromNode;
        TrackNode toNode;
        TrackEdge edge;
        double position;
        double currentSpeed;
        int stuckTicks;      // consecutive ticks the player's per-tick displacement was below STUCK_VELOCITY_THRESHOLD
        int totalTicks;      // ticks since grind began — used for the noPhysics-sync grace window
        Vec3 prevPos;        // player position at the start of the previous tick — used to derive experienced slope
        double experiencedSlope;  // motion.y / motion.length() from last tick (sin of pitch); +up / -down
        double lateralSign;  // +1 or -1, fixed for the grind: which rail bar the player is riding on. Picked at init from prePos.
        Vec3 expectedPos;    // where the player *should* be after last tick's velocity was applied — used as the velocity reference instead of player.position(), which lags the client by 1+ ticks and accumulates chord-cut drift on parallel curves.
        int steerSign;       // -1 = left, 0 = none, +1 = right. Synced from the local player via SteerInputPayload (sent only when the value flips). advanceJunction reads this as targetDot for the same lateral-projection algorithm Create's TravellingPoint.steer uses on player-controlled trains.
        byte accelInputMode = GrindAccelInputPayload.VANILLA;  // VANILLA (server polls isShiftKeyDown) / OVERRIDE_OFF / OVERRIDE_ON. Synced from the local player via GrindAccelInputPayload (sent only when the value flips). The OVERRIDE_* states make the override-key path independent of shift, so a player accelerating via the override key gets it even though Minecraft sees no sneak input.
        boolean collidingWithTrain;  // set by tickTrainOverlap each server tick: true iff the player's bounding box intersects any CarriageContraptionEntity's bounding box this tick. Surfaced via GrindDebugInfo for the debug HUD; the same per-tick overlap also feeds the TRAIN_OVERLAP_TICKS counter that drives the kick / start-prevention gates.

        GrindState(TrackGraph graph, TrackNode fromNode, TrackNode toNode, TrackEdge edge, double position) {
            this.graph = graph;
            this.fromNode = fromNode;
            this.toNode = toNode;
            this.edge = edge;
            this.position = position;
            this.currentSpeed = CRUISE_SPEED * Config.CRUISE_GRIND_SPEED.get();  // launch at sprint pace, not from a dead stop
            this.lateralSign = 1.0;            // overwritten by railgrinding once prePos is known
        }
    }

    /**
     * Updates the player's steer input (-1 left, 0 none, +1 right). No-op when the player
     * isn't grinding. Called from the SteerInputPayload handler on the server.
     */
    public static void setSteerInput(Player player, int steerSign) {
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs == null) return;
        gs.steerSign = Math.max(-1, Math.min(1, steerSign));
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
        if (track.getMaterial().trackType != TrackMaterial.TrackType.STANDARD) return false;

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
     */
    public static boolean railgrinding(Player player, TrackGraphLocation loc, Vec3 prePos, double entryVelocity) {
        // Both railgrinding() overloads converge here, so this is the single defensive gate
        // for the post-dismount cooldown. Networking.handleTeleport also checks earlier, so
        // the teleport itself is suppressed during cooldown, not just the grind init.
        if (isPlayerOnRailGrindCooldown(player)) return false;
        // Same convergence-gate logic for the train-crush check: refuse to start a grind
        // while the player has been overlapping a carriage long enough to count as crushed.
        // The network handlers also check earlier (so we don't waste a teleport/state-setup
        // pass for a request that would fail here), but this is the canonical block.
        if (isPlayerCrushedByTrain(player)) return false;

        TrackGraph graph = loc.graph;
        Couple<TrackNode> nodes = loc.edge.map(graph::locateNode);
        TrackNode first = nodes.getFirst();
        TrackNode second = nodes.getSecond();
        if (first == null || second == null) return false;

        TrackEdge forwardEdge = graph.getConnectionsFrom(first).get(second);
        if (forwardEdge == null) return false;

        Vec3 chord = second.getLocation().getLocation().subtract(first.getLocation().getLocation());
        boolean forward = player.getLookAngle().dot(chord) >= 0;

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
        // Carry the player's pre-grind momentum into the grind, capped at MAX_STEP rather
        // than TOP_SPEED. Below MAX_STEP, applyTickMotion never scales down the per-tick
        // velocity, so expectedPos stays on the parallel-offset curve and the player tracks
        // the rail bar exactly. Above it, the velocity scaler there puts expectedPos on the
        // straight chord between reference and target, drifting the player off the rail
        // through bezier turns. Letting currentSpeed start above TOP_SPEED makes the entry
        // boost *visible*: it decays smoothly down to TOP_SPEED via the accel/decel logic
        // in tick(), giving a speed-bleed feel after a sprint or fall onto the rail.
        gs.currentSpeed = Math.min(gs.currentSpeed + entryVelocity, MAX_STEP);

        // Pick which rail bar the player rides on. Dot the player's pre-teleport position
        // (relative to the spawn spline point) with the right-of-travel perpendicular: positive
        // → right side (+1), negative → left side (-1). Locked for the rest of the grind so
        // they don't snap from one rail bar to the other when the spline tangent rotates.
        double edgeLenForSpawn = edge.getLength();
        double tSpawn = edgeLenForSpawn <= 0 ? 0 : Math.min(1.0, Math.max(0.0, position / edgeLenForSpawn));
        Vec3 splineSpawn = edge.getPosition(graph, tSpawn);
        Vec3 dirSpawn = sampleTangent(graph, edge, tSpawn);
        Vec3 spawnChord = toNode.getLocation().getLocation().subtract(fromNode.getLocation().getLocation());
        if (dirSpawn.x * spawnChord.x + dirSpawn.z * spawnChord.z < 0) dirSpawn = dirSpawn.scale(-1);
        double horizLenSpawn = Math.sqrt(dirSpawn.x * dirSpawn.x + dirSpawn.z * dirSpawn.z);
        if (horizLenSpawn > 1e-6) {
            double rxSpawn = -dirSpawn.z / horizLenSpawn;
            double rzSpawn =  dirSpawn.x / horizLenSpawn;
            double sideDot = rxSpawn * (prePos.x - splineSpawn.x) + rzSpawn * (prePos.z - splineSpawn.z);
            gs.lateralSign = sideDot >= 0 ? +1.0 : -1.0;
        }

        ACTIVE.put(player.getUUID(), gs);
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
        player.noPhysics = true;

        Vec3 spawn = worldPos(gs).add(0, Y_OFFSET, 0);
        player.setPos(spawn.x, spawn.y, spawn.z);
        // setPos only moves the server-side player. Sync the client too so it doesn't
        // start the grind from wherever Networking.handleTeleport's teleportTo landed.
        if (player instanceof ServerPlayer sp) {
            sp.connection.teleport(spawn.x, spawn.y, spawn.z, sp.getYRot(), sp.getXRot());
        }
        gs.expectedPos = spawn;  // seed for the per-tick velocity reference
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;

        syncPose(player, true);
        return true;
    }

    public static void stop(Player player) {
        if (ACTIVE.remove(player.getUUID()) == null) return;
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
     * is set to {@link #START_COOLDOWN_TICKS} when {@link #stop(Player)} runs (any grind
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
     * is only ever non-zero immediately after a {@link #stop(Player)}, so a grinding
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
     * actively grinding, {@link #stop(Player)} drops them. The same threshold gates new
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
            if (next >= TRAIN_OVERLAP_KICK_TICKS && isGrinding(player)) {
                stop(player);
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
    public static TrackGraphLocation findNearestRailLocation(Level level, Vec3 origin, double maxDist) {
        BlockPos center = BlockPos.containing(origin);
        double bestDistSq = maxDist * maxDist;
        TrackGraphLocation best = null;

        // Plain ITrackBlock proximity scan — same resolution path as the right-click empty-hand
        // block hit (Networking.findRailBlockAt → railgrinding(BlockPos) → TrackGraphHelper.getGraphLocationAt).
        int blockRange = (int) Math.ceil(maxDist);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -blockRange; dx <= blockRange; dx++) {
            for (int dy = -blockRange; dy <= blockRange; dy++) {
                for (int dz = -blockRange; dz <= blockRange; dz++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (!(state.getBlock() instanceof ITrackBlock track)) continue;
                    if (track.getMaterial().trackType != TrackMaterial.TrackType.STANDARD) continue;
                    double d2 = cursor.getCenter().distanceToSqr(origin);
                    if (d2 < bestDistSq) {
                        BlockPos pos = cursor.immutable();
                        TrackGraphLocation loc = resolvePlainTrack(level, pos, state, track);
                        if (loc != null) {
                            bestDistSq = d2;
                            best = loc;
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
                    for (Map.Entry<BlockPos, BezierConnection> entry : tbe.getConnections().entrySet()) {
                        BezierConnection conn = entry.getValue();
                        int segCount = conn.getSegmentCount();
                        for (int seg = 0; seg < segCount; seg++) {
                            float t = conn.getSegmentT(seg);
                            Vec3 p = conn.getPosition(t);
                            double d2 = p.distanceToSqr(origin);
                            if (d2 >= bestDistSq) continue;
                            BezierTrackPointLocation btpl = new BezierTrackPointLocation(entry.getKey(), seg);
                            TrackGraphLocation loc = TrackGraphHelper.getBezierGraphLocationAt(
                                    level, bePos, Direction.AxisDirection.POSITIVE, btpl);
                            if (loc == null) loc = TrackGraphHelper.getBezierGraphLocationAt(
                                    level, bePos, Direction.AxisDirection.NEGATIVE, btpl);
                            if (loc != null) {
                                bestDistSq = d2;
                                best = loc;
                            }
                        }
                    }
                }
            }
        }

        return best;
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
    public static void stopWithLaunch(Player player, int chargeTicks) {
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs == null) {
            stop(player);
            return;
        }

        // Snapshot speed and travel-direction tangent before stop() removes the state.
        double speed = gs.currentSpeed;
        double edgeLen = gs.edge.getLength();
        double t = edgeLen <= 0 ? 0 : Math.min(1.0, Math.max(0.0, gs.position / edgeLen));
        Vec3 tangent = sampleTangent(gs.graph, gs.edge, t);
        Vec3 chord = gs.toNode.getLocation().getLocation().subtract(gs.fromNode.getLocation().getLocation());
        if (tangent.x * chord.x + tangent.z * chord.z < 0) tangent = tangent.scale(-1);

        double chargeRatio = computeChargeRatio(chargeTicks);
        double speedMult  = Config.RAIL_JUMP_MOMENTUM.get();
        double chargeMult = Config.RAIL_JUMP_CHARGE.get();
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

        stop(player);
        player.setDeltaMovement(launch);
        player.hurtMarked = true;  // forces a velocity packet to the client so the launch isn't predicted away
        player.fallDistance = 0.0F;
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
        double position,
        double edgeLength,
        int stuckTicks,
        int totalTicks,
        double lateralSign,
        boolean edgeIsTurn,
        boolean crouchAccelerating,
        boolean collidingWithTrain
    ) {}

    public static GrindDebugInfo getGrindDebugInfo(Player player) {
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs == null) return null;
        // Mirror the fluid scaling tick() applies, so the HUD's targetSpeed/accel lines
        // match the values actually driving motion this tick rather than the dry-air values.
        double fluidMult = computeFluidMultiplier(player);
        return new GrindDebugInfo(
            gs.currentSpeed,
            computeTargetSpeed(gs, player) * fluidMult,
            computeAcceleration(gs, player) * fluidMult,
            topSpeed(),
            gs.experiencedSlope,
            gs.position,
            gs.edge.getLength(),
            gs.stuckTicks,
            gs.totalTicks,
            gs.lateralSign,
            gs.edge.isTurn(),
            isAcceleratingForGrind(player, gs),
            gs.collidingWithTrain
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

    public static void tick(Player player) {
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs == null) return;

        // Player wrench-mounted a Create chain conveyor — both systems drive motion via
        // setDeltaMovement, so leaving the grind active fights the chain ride. Drop cleanly
        // so the wrench-mount hands off into chain riding. ServerChainConveyorHandler
        // populates this map server-side from the wrench's ServerboundChainConveyorRidingPacket
        // and refreshes it every tick via TTL packets, so the entry is present from the
        // first tick of the ride; stop()'s ACTIVE.remove guards against the second-tick
        // re-entry being a no-op.
        if (ServerChainConveyorHandler.hangingPlayers.containsKey(player.getUUID())) {
            stop(player);
            return;
        }

        // Riptide-style auto spin attack fights our setDeltaMovement and yanks the player off
        // the rail at odd angles. Detect via the LivingEntity flag (set by startAutoSpinAttack)
        // rather than checking for the trident item — commands, potions, and other mods can
        // raise this flag without a trident in hand, and we want to drop the grind in all of
        // those cases too.
        if (player.isAutoSpinAttack()) {
            stop(player);
            return;
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
                    double edgeLen = gs.edge.getLength();
                    double t = edgeLen <= 0 ? 0 : Math.min(1.0, Math.max(0.0, gs.position / edgeLen));
                    Vec3 tangent = sampleTangent(gs.graph, gs.edge, t);
                    Vec3 chord = gs.toNode.getLocation().getLocation().subtract(gs.fromNode.getLocation().getLocation());
                    if (tangent.x * chord.x + tangent.z * chord.z < 0) tangent = tangent.scale(-1);

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
        double fluidMult = computeFluidMultiplier(player);
        double targetSpeed = computeTargetSpeed(gs, player) * fluidMult;
        double accel = computeAcceleration(gs, player) * fluidMult;

        if (fluidMult < 1.0 && gs.currentSpeed > targetSpeed) {
            gs.currentSpeed = targetSpeed;
        }

        if (gs.currentSpeed < targetSpeed)
            gs.currentSpeed = Math.min(gs.currentSpeed + accel, targetSpeed);
        else if (gs.currentSpeed > targetSpeed)
            gs.currentSpeed = Math.max(gs.currentSpeed - accel, targetSpeed);

        if (gs.currentSpeed <= 1e-6) {
            applyTickMotion(player, gs, absVelocity);
            return;
        }

        double remaining = gs.currentSpeed;
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
                    stop(player);
                    return;
                }
            }
        }

        applyTickMotion(player, gs, absVelocity);
    }

    private static double computeTargetSpeed(GrindState gs, Player player) {
        double slope = gs.experiencedSlope;  // +up / -down
        boolean crouchAccelerating = isAcceleratingForGrind(player, gs);
        double base;
        if (crouchAccelerating) {
            // Sneak: ride at topSpeed with the asymmetric slope cap — descents lift it (DOWNHILL_FACTOR), ascents cut it (UPHILL_FACTOR).
            base = topSpeed();
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
            base = CRUISE_SPEED * Config.CRUISE_GRIND_SPEED.get() * Math.max(0.0, 1.0 - slope * UPHILL_FACTOR);
        }

        if (gs.edge.isTurn()) base *= CURVE_FACTOR;

        return Math.max(MIN_SPEED, base);
    }

    private static double computeAcceleration(GrindState gs, Player player) {
        double base = isAcceleratingForGrind(player, gs) ? ACCELERATION * Config.SNEAK_ACCELERATION.get() : ACCELERATION;
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

    private static void applyTickMotion(Player player, GrindState gs, double absVelocity) {
        Vec3 target = worldPos(gs).add(0, Y_OFFSET, 0);

        // Velocity reference is gs.expectedPos (last tick's parallel-curve target), not
        // player.position(). Reason: target lives on the parallel offset curve, and any
        // residual sync lag in player.position() means (target - playerPos) is a chord that
        // crosses the curve rather than running along it. The lateral component of that chord
        // scales linearly with LATERAL_OFFSET, so the over-correction stays invisible at
        // small offsets (e.g. 0.3125) but turns into visible per-tick jitter once the offset
        // gets to a full block. Using expectedPos makes the per-tick velocity equal the
        // parallel-curve tangent itself — independent of LATERAL_OFFSET magnitude.
        //
        // Realignment (soft, continuous). Instead of a binary threshold that toggled the
        // velocity reference between expectedPos and playerPos — which produced violent
        // jitter on curves whenever drift hovered near the threshold — we lerp expectedPos a
        // small fraction toward playerPos every tick. In steady state playerPos ≈ expectedPos
        // (server's view of the player has no lag because the client report from the previous
        // tick already reflects what we told it to do), so the contribution is ≈0 and the
        // smooth-tangent path is preserved. When real drift occurs it's proportional and
        // ramps up smoothly, and the existing MAX_STEP cap on `velocity` keeps even a strong
        // pull bounded so a physical obstruction still throttles absVelocity below
        // STUCK_VELOCITY_THRESHOLD and the stuck-tick check drops the grind.
        Vec3 playerPos = player.position();
        // Catastrophic-desync bailout: at MAX_DRIFT blocks past target, the soft pull can't
        // recover in any reasonable time — gs.position has run far ahead of where the player
        // physically is. Drop unconditionally (skips STUCK_GRACE_TICKS), no launch impulse —
        // error recovery, not a deliberate dismount.
        if (playerPos.subtract(target).lengthSqr() > MAX_DRIFT * MAX_DRIFT) {
            stop(player);
            return;
        }
        Vec3 reference = gs.expectedPos != null
                ? gs.expectedPos.lerp(playerPos, REALIGN_GAIN)
                : playerPos;
        Vec3 velocity = target.subtract(reference);
        double speedSq = velocity.lengthSqr();

        // Cap per-tick velocity so the player smoothly catches up instead of snapping
        if (speedSq > MAX_STEP * MAX_STEP) {
            velocity = velocity.scale(MAX_STEP / Math.sqrt(speedSq));
        }
        player.setDeltaMovement(velocity.x, velocity.y, velocity.z);
        player.hurtMarked = true;
        player.fallDistance = 0.0F;

        // Advance the reference along the parallel curve. If velocity got capped, expectedPos
        // intentionally lags target rather than jumping ahead — next tick's chord picks up
        // from where the player actually went.
        gs.expectedPos = reference.add(velocity);

        // Stuck detection: drop the grind if the player's actual per-tick displacement is
        // below STUCK_VELOCITY_THRESHOLD — i.e., they aren't moving despite our setDeltaMovement.
        // The previous distance-based check (target − player.position) grew unboundedly any
        // time currentSpeed exceeded MAX_STEP: gs.position advanced by currentSpeed each tick
        // but the velocity cap held the player to MAX_STEP, so the gap widened by
        // (currentSpeed − MAX_STEP) per tick and falsely tripped stuck detection on legitimate
        // high-speed grinds. Velocity-based detection only fires for the actual stuck condition
        // (mod yanking the player, physics overriding setDeltaMovement, etc.).
        // Grace window at start: client noPhysics syncs ~1 tick after grind begins, and the
        // local player's collision pass clamps movement until the sync arrives. Skip stuck
        // detection during that window so the player isn't dropped before noPhysics lands.
        if (gs.totalTicks <= STUCK_GRACE_TICKS) {
            gs.stuckTicks = 0;
        } else if (absVelocity < STUCK_VELOCITY_THRESHOLD) {
            gs.stuckTicks++;
            if (gs.stuckTicks >= STUCK_DROP_TICKS) {
                stop(player);
            }
        } else {
            gs.stuckTicks = 0;
        }
    }

    private static Vec3 worldPos(GrindState gs) {
        double edgeLen = gs.edge.getLength();
        double t = edgeLen <= 0 ? 0 : Math.min(1.0, Math.max(0.0, gs.position / edgeLen));
        Vec3 pos = gs.edge.getPosition(gs.graph, t);

        // Lateral offset from spline centerline to the rail bar. Uses the smooth bezier-aware
        // tangent so the perpendicular rotates correctly through curves — using
        // getDirectionAt(t) here would lock the perpendicular to the chord on bezier edges,
        // making the player visually drift off the rail bar through any turn.
        Vec3 dir = sampleTangent(gs.graph, gs.edge, t);
        Vec3 chord = gs.toNode.getLocation().getLocation().subtract(gs.fromNode.getLocation().getLocation());
        if (dir.x * chord.x + dir.z * chord.z < 0) dir = dir.scale(-1);
        double horizLen = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        if (horizLen < 1e-6) return pos;
        double off = LATERAL_OFFSET * gs.lateralSign;
        double rx = -dir.z / horizLen * off;
        double rz =  dir.x / horizLen * off;
        return pos.add(rx, 0, rz);
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
            Vec3 candidateDir = candidate.getDirection(true);
            double dot = cross.dot(candidateDir);
            double diff = Math.abs(targetDot - dot);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestNeighbor = neighbor;
                bestEdge = candidate;
            }
        }

        // No forward continuation → end of track (or only perpendicular / reverse exits exist).
        // Same dropout behavior as before: returning false causes tick() to call stop(player)
        // cleanly rather than reversing. Mirrors TravellingPoint setting blocked=true and
        // Train.tick() halting.
        if (bestEdge == null) return false;

        gs.fromNode = atNode;
        gs.toNode = bestNeighbor;
        gs.edge = bestEdge;
        gs.position = 0;
        return true;
    }

    private static void syncPose(Player player, boolean grinding) {
        if (player.level().isClientSide) {
            BalancingPoseTracker.setBalancing(player.getUUID(), grinding);
        } else if (player instanceof ServerPlayer serverPlayer) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                serverPlayer, new RailGrindSyncPayload(player.getUUID(), grinding));
        }
    }
}
